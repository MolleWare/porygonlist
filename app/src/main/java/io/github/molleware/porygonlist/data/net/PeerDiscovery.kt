package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.sync.DeviceId
import kotlinx.coroutines.flow.StateFlow

/**
 * The service type advertised and browsed for.
 *
 * Not registered with IANA, so it belongs under the unofficial convention rather than pretending to
 * a reserved name. The trailing dot Android wants is added by the adapter, not carried here.
 */
const val SERVICE_TYPE = "_porygonlist._tcp"

/**
 * The TXT key carrying the advertiser's device id.
 *
 * In TXT rather than only in the service name because Android renames a service on collision — two
 * phones that somehow advertised the same name would come back as `name (2)`, and an id parsed out
 * of that is wrong exactly when it matters. The name still carries the id for anyone reading a
 * packet capture; this is the copy that is believed.
 */
const val TXT_DEVICE_ID = "id"

/**
 * A service seen on the network, before anything about it has been believed.
 *
 * [deviceId] is a **claim**. Anything on the LAN can advertise any id it likes, so nothing here
 * grants trust: it is an address to try and a guess at who will answer. What settles identity is
 * the key exchanged on the connection, which is why this type has no key in it and no way to get
 * one.
 */
data class ServiceRecord(val deviceId: DeviceId, val host: String, val port: Int)

/**
 * A peer this phone has paired with, found at an address on this network.
 *
 * Pairing it with the [TrustedPeer] is what makes the pinned [TrustedPeer.publicKey] available to
 * the connection that follows — the address came off the wire, the key did not.
 */
data class ReachablePeer(val peer: TrustedPeer, val host: String, val port: Int)

/** Advertises this phone and watches for others, for as long as it is running. */
interface PeerDiscovery {

  /** Everything currently being advertised on this network, self included until it is filtered. */
  val found: StateFlow<Set<ServiceRecord>>

  /**
   * Begins advertising on [port] and browsing for others.
   *
   * Idempotent: calling it while already running on the same port does nothing, which is what lets
   * it be driven straight off a flow of decisions that re-emits.
   */
  fun start(port: Int)

  /** Stops both halves and empties [found]. Safe to call when not running. */
  fun stop()
}

/**
 * Which of the phones on this network are ones we have actually paired with.
 *
 * The filtering is the whole of the logic worth testing, so it lives here as a function over data
 * rather than inside the Android adapter.
 *
 * Self is dropped first. A phone browsing for a service it is itself advertising will see its own
 * record, and connecting to it would have this device waiting on its own receipts.
 *
 * Strangers are dropped next, and silently. An unpaired phone advertising on the same wifi is not
 * an error and not worth a line on a screen — it is a flatmate with the app, and the correct
 * response is to ignore it. Only [SyncSession.receive]'s second check would catch it after that,
 * and neither check should ever be the only one.
 *
 * A peer advertising twice — two interfaces, or a stale record the responder has not aged out —
 * keeps whichever comes first rather than being reported twice. One connection per peer is what
 * the delivery log is written for.
 */
fun reachablePeers(
  found: Collection<ServiceRecord>,
  peers: Collection<TrustedPeer>,
  localDevice: DeviceId,
): List<ReachablePeer> {
  val byId = peers.associateBy { it.deviceId }
  return found
    .asSequence()
    .filter { it.deviceId != localDevice }
    .mapNotNull { record -> byId[record.deviceId]?.let { ReachablePeer(it, record.host, record.port) } }
    .distinctBy { it.peer.deviceId }
    .toList()
}
