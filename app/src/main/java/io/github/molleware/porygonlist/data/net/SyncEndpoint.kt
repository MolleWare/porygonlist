package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PairingInvite
import io.github.molleware.porygonlist.data.crypto.PairingToken
import io.github.molleware.porygonlist.data.crypto.PinnedTls
import java.net.ServerSocket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Why the socket is open. It stays open while any of these hold. */
enum class ListenReason {
  /** Discovery is running on an approved network, so peers may call. */
  DISCOVERY,

  /**
   * A pairing code is on screen, so whoever scans it may call straight back.
   *
   * Deliberately independent of [DISCOVERY]. The first time two people pair they are very likely
   * standing on a wifi neither has approved yet — hers, his, a kitchen — and requiring approval
   * first would mean the handshake never works on the one occasion it is most wanted. It is not a
   * hole in the gate: the gate exists to stop this phone *announcing itself* in the background,
   * and nothing is announced here. The address goes out in a code held up to one person, the
   * socket is open only while that screen is, and what arrives on it still has to be agreed to.
   */
  PAIRING,
}

/**
 * The port this phone answers on, and the TLS behind it.
 *
 * Discovery has to advertise a port, and a port nothing is listening on is a lie that costs the
 * other phone a connection timeout to find out. So the socket is bound first and the number the
 * system gave is what goes out — never a constant. A fixed port would also be the one thing on
 * this network announcing "this phone runs PorygonList" to anyone scanning, and would collide
 * with a second copy of the app on the same device.
 *
 * Every connection is TLS presenting this phone's keystore key. On this listening end the peer is
 * *not* authenticated, because the only thing it is currently able to do is introduce itself —
 * see [PairingHandshake]. When list exchange lands it will pin the peer's key before a byte of
 * anyone's groceries moves, and that check belongs on the connection, not here.
 */
class SyncEndpoint(
  private val scope: CoroutineScope,
  /** Read late, for the same reasons discovery does: the keystore is slow and the key can change. */
  private val identity: () -> LocalIdentity,
) {

  private val lock = Any()

  private val reasons = mutableSetOf<ListenReason>()
  private var socket: ServerSocket? = null
  private var loop: Job? = null

  /**
   * Invites arriving from phones that have scanned this one's code.
   *
   * A hot flow with a small buffer rather than a state: two people pairing is an event, and an
   * unread one should not be replayed onto a later screen as though it had just happened.
   */
  private val _incoming = MutableSharedFlow<PairingInvite>(extraBufferCapacity = 4)
  val incoming: SharedFlow<PairingInvite> = _incoming.asSharedFlow()

  /**
   * The token from the code currently on screen, or null when there is no code up.
   *
   * Doubles as the "accepting introductions" flag, which is the point: there is no state where
   * this phone takes a pairing handshake without a token to check it against. Read per connection
   * rather than at accept time, so putting the screen away stops introductions immediately even
   * though the socket stays open for discovery.
   */
  @Volatile private var pairingToken: PairingToken? = null

  /** The port in use, or null when not listening. */
  val port: Int?
    get() = synchronized(lock) { socket?.localPort?.takeIf { it > 0 && socket?.isClosed == false } }

  /**
   * Opens the socket for [reason], returning the port everything should be told about.
   *
   * Null when TLS or the bind failed. The honest response to that is silence: advertising an
   * address that refuses just costs the other phone a timeout to discover.
   */
  fun start(reason: ListenReason, token: PairingToken? = null): Int? =
    synchronized(lock) {
      reasons += reason
      if (reason == ListenReason.PAIRING) pairingToken = token

      socket?.let { open -> if (!open.isClosed) return open.localPort }
      openLocked()
    }

  /**
   * Drops [reason], closing the socket if nothing else needs it.
   *
   * Counting reasons rather than having one owner matters here: the pairing screen and the
   * discovery gate both open this, on their own schedules, and whichever finishes first must not
   * take the socket out from under the other.
   */
  fun stop(reason: ListenReason) =
    synchronized(lock) {
      reasons -= reason
      if (reason == ListenReason.PAIRING) pairingToken = null
      if (reasons.isEmpty()) closeLocked()
    }

  /** Closes regardless of reasons. For teardown, not for ordinary use. */
  fun stopAll() =
    synchronized(lock) {
      reasons.clear()
      pairingToken = null
      closeLocked()
    }

  private fun openLocked(): Int? {
    val factory = PinnedTls.serverFactory(identity()) ?: return null

    // Port 0 asks the system for a free one, on every interface: the peer arrives over wifi and
    // which local address that is depends on the link.
    val bound = runCatching { factory.createServerSocket(0) as SSLServerSocket }.getOrNull() ?: return null
    runCatching { bound.needClientAuth = false }
    socket = bound

    loop =
      scope.launch(Dispatchers.IO) {
        while (isActive && !bound.isClosed) {
          // accept() throws when the socket is closed under it, which is how close ends this loop
          // rather than by a flag an accept would never look at.
          val client = runCatching { bound.accept() }.getOrNull() ?: break
          launch { handle(client as SSLSocket) }
        }
      }

    return bound.localPort
  }

  /**
   * One connection.
   *
   * Handled on its own coroutine so a peer that connects and then says nothing holds up nobody
   * else — it has a read timeout, and the accept loop is already back round.
   */
  private suspend fun handle(client: SSLSocket) {
    try {
      PinnedTls.harden(client)
      val expecting = pairingToken ?: return

      val invite = PairingHandshake.serve(client, expecting) ?: return

      // Spent. A token is good for one introduction, so a photograph of the screen cannot be used
      // again after the person it was meant for has already arrived — and the second caller gets
      // the same refusal as any stranger. Showing the code again mints a new one.
      synchronized(lock) { if (pairingToken == expecting) pairingToken = null }

      _incoming.emit(invite)
    } finally {
      runCatching { client.close() }
    }
  }

  private fun closeLocked() {
    loop?.cancel()
    loop = null
    socket?.let { runCatching { it.close() } }
    socket = null
  }
}
