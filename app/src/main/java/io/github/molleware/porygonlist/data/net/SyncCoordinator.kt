package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.ListRepository
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PinnedTls
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncExchange
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.afterExchange
import io.github.molleware.porygonlist.data.sync.payloadFor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Decides when two paired phones talk, and what each believes afterwards.
 *
 * Both directions live here. As a caller it watches for paired phones on an approved network and
 * pushes to them; as a listener it answers whatever [SyncEndpoint] has authenticated. Either way
 * one exchange carries both phones' changes, so it does not matter which end starts it.
 *
 * **When to push is the part worth understanding.** The obvious rule — push whenever the state
 * changes — loops for ever, because applying what a peer sent is itself a state change. So a peer
 * is pushed to only when the payload this phone owes it differs from the last one it acknowledged.
 * Content rather than stamps, because two of the things that need to travel carry no stamp at all:
 * adding someone to a list, and renaming one. After an exchange the side that learned something
 * pushes once more, the other finds nothing new, and it settles.
 *
 * Nothing here starts on its own. [start] is called after the state has loaded, and even then the
 * caller side does nothing until discovery — which sits behind the approved-network gate — reports
 * somebody. The listener side is reached only through an endpoint that is itself gated.
 */
class SyncCoordinator(
  private val repo: ListRepository,
  private val identity: () -> LocalIdentity,
  private val found: Flow<Set<ServiceRecord>>,
  private val scope: CoroutineScope,
  /** How a call is placed. Swapped out in tests, which have no sockets. */
  private val dial: ((ReachablePeer, SyncPayload) -> SyncExchange.Exchange?)? = null,
  private val now: () -> Long = System::currentTimeMillis,
) : SyncAnswerer {

  /** What each peer last acknowledged, by content. In memory only: a restart simply pushes again. */
  private val acknowledged = ConcurrentHashMap<DeviceId, String>()

  /** Peers on the network as of the last look, and when each absent one was first missed. */
  private val present = ConcurrentHashMap.newKeySet<DeviceId>()
  private val goneSince = ConcurrentHashMap<DeviceId, Long>()

  @Volatile private var job: Job? = null

  /** Begins watching for peers to push to. Idempotent, so it can be called from anywhere that loads. */
  @OptIn(FlowPreview::class)
  fun start() {
    if (job != null) return
    synchronized(this) {
      if (job != null) return
      job =
        scope.launch {
          combine(
              found,
              // A burst of taps becomes one push, the way a burst of taps becomes one save.
              repo.state.filterNotNull().debounce(SETTLE_MS),
              ticks(),
            ) { records, state, _ ->
              state to reachablePeers(records, state.peers, state.localDevice)
            }
            .collect { (state, reachable) ->
              noteReachable(reachable)
              reachable.forEach { peer -> pushIfOwed(peer, state) }
            }
        }
    }
  }

  fun stop() {
    synchronized(this) {
      job?.cancel()
      job = null
    }
  }

  /**
   * A peer that has been away a while is pushed to again, whatever it last acked.
   *
   * It may have been reinstalled, restored, or simply lost a save in between, and an ack from before
   * it went away says nothing reliable about what it holds now. One extra exchange is the price.
   *
   * **A while, not a blip.** Seen on hardware: mDNS on wifi loses and finds a phone again within
   * seconds — two address families, a resolve that times out, a screen going off — and forgetting
   * at the first gap turned each of those into a redundant exchange. Nothing about a phone that was
   * gone for five seconds has changed, so only an absence longer than [FORGET_AFTER_MS] counts.
   *
   * What is measured is the absence itself: from the first look that misses a peer to the look that
   * finds it again. Not time since it was last *seen* — looks can be a minute apart when nothing is
   * happening, and that would read a phone that never left as one that had been gone a minute.
   */
  internal fun noteReachable(reachable: List<ReachablePeer>) {
    val t = now()
    val here = reachable.mapTo(mutableSetOf()) { it.peer.deviceId }

    present.filterNot { it in here }.forEach { gone ->
      present.remove(gone)
      goneSince.putIfAbsent(gone, t)
    }

    here.forEach { id ->
      val since = goneSince.remove(id)
      if (since != null && t - since > FORGET_AFTER_MS) acknowledged.remove(id)
      present.add(id)
    }
  }

  /** Pushes to [peer] if it is owed something. Internal so the tests can drive it without timing. */
  internal fun pushIfOwed(peer: ReachablePeer, state: AppState) {
    val id = peer.peer.deviceId
    val payload = state.payloadFor(id, readOnlyClock(state)) ?: return
    val content = contentOf(payload)
    if (acknowledged[id] == content) return

    val exchange = (dial ?: ::callOverTls)(peer, payload) ?: return
    repo.update { s, node -> s.afterExchange(id, exchange.theirs, exchange.ackOfMine, payload, node.clock, node.ids) }
    if (exchange.ackOfMine == payload.at) acknowledged[id] = content
  }

  override fun answer(peerKey: ByteArray, socket: SSLSocket) {
    socket.soTimeout = TIMEOUT_MS
    answerOn(peerKey, socket.inputStream, socket.outputStream)
  }

  /** The listener's half over plain streams, so it can be exercised without TLS. */
  internal fun answerOn(peerKey: ByteArray, inp: InputStream, out: OutputStream) {
    val state = repo.state.value ?: return
    // A key the endpoint accepted is only a key. Whether it belongs to a phone this one has paired
    // with is decided here, against the current state, and a stranger with a certificate gets
    // nothing — not even the courtesy of the conversation.
    val peer = state.peers.firstOrNull { it.publicKey.contentEquals(peerKey) } ?: return

    var sent: SyncPayload? = null
    val exchange =
      SyncExchange.answer(inp, out) { claimed ->
        // Nothing for a caller claiming to be someone it did not authenticate as.
        if (claimed != peer.deviceId) null
        else state.payloadFor(claimed, readOnlyClock(state)).also { sent = it }
      } ?: return

    repo.update { s, node -> s.afterExchange(peer.deviceId, exchange.theirs, exchange.ackOfMine, sent, node.clock, node.ids) }
    sent?.let { if (exchange.ackOfMine == it.at) acknowledged[peer.deviceId] = contentOf(it) }
  }

  private fun callOverTls(peer: ReachablePeer, payload: SyncPayload): SyncExchange.Exchange? {
    val factory = PinnedTls.syncClientFactory(identity(), peer.peer.publicKey) ?: return null
    return try {
      factory.createSocket().use { plain ->
        val socket = plain as SSLSocket
        PinnedTls.harden(socket)
        socket.soTimeout = TIMEOUT_MS
        socket.connect(InetSocketAddress(peer.host, peer.port), TIMEOUT_MS)
        socket.startHandshake()
        SyncExchange.call(payload, socket.outputStream, socket.inputStream)
      }
    } catch (e: IOException) {
      // Not reaching a peer is an ordinary thing on a phone. The next change, tick or reappearance
      // tries again, and nothing was applied in the meantime.
      null
    }
  }

  /**
   * A clock that can only be read.
   *
   * Assembling a payload reads the clock head and nothing else, and the state already carries it.
   * Using the repository's live clock would need an update, which would save an unchanged state.
   */
  private fun readOnlyClock(state: AppState) = HybridClock(state.localDevice, start = state.clockHead)

  /** What a payload says, with the stamp it was assembled at taken out — that changes every time. */
  private fun contentOf(payload: SyncPayload): String {
    val normalised = payload.copy(at = Hlc(0, 0, payload.from))
    val digest = MessageDigest.getInstance("SHA-256").digest(SyncCodec.encode(normalised).toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }

  /**
   * A slow heartbeat, for the push that failed.
   *
   * A call that does not connect leaves nothing changed, so nothing else would ever prompt a retry
   * while both phones sit there. This does, and costs one comparison a minute when all is quiet.
   */
  private fun ticks(): Flow<Unit> = flow {
    while (true) {
      emit(Unit)
      delay(RETRY_MS)
    }
  }

  private companion object {
    const val TIMEOUT_MS = 8_000
    const val SETTLE_MS = 1_500L
    const val RETRY_MS = 60_000L
    const val FORGET_AFTER_MS = 30_000L
  }
}
