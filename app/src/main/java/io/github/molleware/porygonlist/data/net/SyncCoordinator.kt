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
import kotlinx.coroutines.flow.MutableStateFlow
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
 *
 * **Discovery alone is not enough to know who can be called.** Seen on hardware: Android reports
 * every service found and expired in the same millisecond, and the Samsung never saw the Pixel at
 * all, so its edits waited until the Pixel happened to call. So a phone's address is remembered for
 * [REMEMBER_MS] after it was last found, and a caller says which port to call it back on, which the
 * phone it called remembers too. Remembered addresses are used only while [callbackPort] says sync
 * is allowed on this network, so they never walk round the gate.
 */
class SyncCoordinator(
  private val repo: ListRepository,
  private val identity: () -> LocalIdentity,
  private val found: Flow<Set<ServiceRecord>>,
  private val scope: CoroutineScope,
  /** How a call is placed. Swapped out in tests, which have no sockets. */
  private val dial: ((ReachablePeer, SyncPayload) -> SyncExchange.Exchange?)? = null,
  private val now: () -> Long = System::currentTimeMillis,
  /** The port peers may call this phone back on; null while sync is not allowed on this network. */
  private val callbackPort: () -> Int? = { null },
) : SyncAnswerer {

  /** What each peer last acknowledged, by content. In memory only: a restart simply pushes again. */
  private val acknowledged = ConcurrentHashMap<DeviceId, String>()

  /** Where each phone last told us to call it back, from its own authenticated call, and when. */
  private val heard = ConcurrentHashMap<DeviceId, Known>()

  /** Where discovery last found each phone, and when, kept through Android's found-and-lost flicker. */
  private val seen = ConcurrentHashMap<DeviceId, Known>()

  private class Known(val record: ServiceRecord, val at: Long)

  /** Bumped when a caller teaches us its address, so the loop looks again straight away. */
  private val learned = MutableStateFlow(0)

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
              learned,
            ) { records, state, _, _ ->
              state to records
            }
            .collect { (state, records) ->
              // Presence, for re-pushing a peer that was away, is what discovery says. Who to call
              // also includes where peers were recently reachable.
              noteReachable(reachablePeers(records, state.peers, state.localDevice))
              pushAll(records, state)
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

  /**
   * Every address worth trying, best first: where a peer last told us to call it, then where
   * discovery finds it now, then where discovery found it within [REMEMBER_MS].
   *
   * Any of them can be stale — a phone reopens its socket on a new port whenever it comes back onto
   * the wifi — so a caller tries a peer's addresses in turn, and one that fails is forgotten. Seen on
   * hardware: a stale discovery record taking the place of the address the peer had just given.
   * Only discovery's current answer is used while sync is not allowed on this network.
   */
  internal fun callable(records: Collection<ServiceRecord>, state: AppState): List<ReachablePeer> {
    val t = now()
    records.forEach { seen[it.deviceId] = Known(it, t) }
    val candidates = if (callbackPort() == null) records.toList() else heard.fresh(t) + records + seen.fresh(t)
    val byId = state.peers.associateBy { it.deviceId }
    return candidates
      .filter { it.deviceId != state.localDevice }
      .distinct()
      .mapNotNull { record -> byId[record.deviceId]?.let { ReachablePeer(it, record.host, record.port) } }
  }

  private fun Map<DeviceId, Known>.fresh(t: Long) = values.filter { t - it.at <= REMEMBER_MS }.map { it.record }

  /** Pushes to every peer worth calling, trying each one's addresses in turn until one answers. */
  internal fun pushAll(records: Collection<ServiceRecord>, state: AppState) =
    callable(records, state).groupBy { it.peer.deviceId }.values.forEach { addresses ->
      addresses.firstOrNull { pushIfOwed(it, state) }
    }

  /**
   * Pushes to [peer] if it is owed something. Internal so the tests can drive it without timing.
   *
   * False only when the call did not go through, and that address is then forgotten: the peer's
   * next call, or discovery, brings back a good one.
   */
  internal fun pushIfOwed(peer: ReachablePeer, state: AppState): Boolean {
    val id = peer.peer.deviceId
    val payload = state.payloadFor(id, readOnlyClock(state))?.copy(callback = callbackPort()) ?: return true
    val content = contentOf(payload)
    if (acknowledged[id] == content) return true

    val exchange = (dial ?: ::callOverTls)(peer, payload) ?: return false.also { forget(peer) }
    repo.update { s, node -> s.afterExchange(id, exchange.theirs, exchange.ackOfMine, payload, node.clock, node.ids) }
    if (exchange.ackOfMine == payload.at) acknowledged[id] = content
    return true
  }

  private fun forget(peer: ReachablePeer) {
    val failed = ServiceRecord(peer.peer.deviceId, peer.host, peer.port)
    heard.computeIfPresent(failed.deviceId) { _, known -> known.takeUnless { it.record == failed } }
    seen.computeIfPresent(failed.deviceId) { _, known -> known.takeUnless { it.record == failed } }
  }

  override fun answer(peerKey: ByteArray, socket: SSLSocket) {
    socket.soTimeout = TIMEOUT_MS
    answerOn(peerKey, socket.inputStream, socket.outputStream, from = socket.inetAddress?.hostAddress)
  }

  /**
   * The listener's half over plain streams, so it can be exercised without TLS.
   *
   * [from] is the address the call came from. With the port the caller says it listens on, that is
   * where to call it back.
   */
  internal fun answerOn(peerKey: ByteArray, inp: InputStream, out: OutputStream, from: String? = null) {
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
        else state.payloadFor(claimed, readOnlyClock(state))?.copy(callback = callbackPort()).also { sent = it }
      } ?: return

    repo.update { s, node -> s.afterExchange(peer.deviceId, exchange.theirs, exchange.ackOfMine, sent, node.clock, node.ids) }
    sent?.let { if (exchange.ackOfMine == it.at) acknowledged[peer.deviceId] = contentOf(it) }

    val port = exchange.theirs.callback
    if (from != null && port != null) {
      heard[peer.deviceId] = Known(ServiceRecord(peer.deviceId, from, port), now())
      learned.value++
    }
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

  /** What a payload says, without the stamp and callback port, which say nothing about the lists. */
  private fun contentOf(payload: SyncPayload): String {
    val normalised = payload.copy(at = Hlc(0, 0, payload.from), callback = null)
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
    /** Long, because an address that stops answering is dropped on the first failed call anyway. */
    const val REMEMBER_MS = 30 * 60_000L
  }
}
