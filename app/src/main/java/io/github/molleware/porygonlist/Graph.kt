package io.github.molleware.porygonlist

import android.content.Context
import io.github.molleware.porygonlist.data.FileListRepository
import io.github.molleware.porygonlist.data.crypto.AndroidKeystoreIdentityStore
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.net.AndroidNetworkMonitor
import io.github.molleware.porygonlist.data.net.NetworkMonitor
import io.github.molleware.porygonlist.data.net.NsdPeerDiscovery
import io.github.molleware.porygonlist.data.net.PeerDiscovery
import io.github.molleware.porygonlist.data.net.SyncCoordinator
import io.github.molleware.porygonlist.data.net.SyncEndpoint
import io.github.molleware.porygonlist.data.ListRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency wiring.
 *
 * Built on first use rather than in `Application.onCreate`, so nothing here is on the cold-start
 * path — the repository is only constructed once a screen asks for it, and it still does not touch
 * disk until [ListRepository.load] runs.
 */
object Graph {
  private const val STATE_FILE = "porygonlist.state"

  @Volatile private var repository: ListRepository? = null

  @Volatile private var monitor: NetworkMonitor? = null

  @Volatile private var identity: LocalIdentity? = null

  @Volatile private var discovery: PeerDiscovery? = null

  @Volatile private var endpoint: SyncEndpoint? = null

  @Volatile private var coordinator: SyncCoordinator? = null

  private val identityStore = AndroidKeystoreIdentityStore()

  /**
   * The scope the network side runs on.
   *
   * Process-lived rather than tied to a screen: a resolve in flight or an accept loop outlives the
   * composable that caused it, and cancelling one mid-handover is how a peer ends up holding half
   * a payload. Nothing is started here — see [SyncEndpoint] and [NsdPeerDiscovery], which begin
   * only when the discovery gate opens.
   */
  private val networkScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

  /** This phone's key-derived identity, created in the keystore on first use. */
  fun identity(): LocalIdentity =
    identity ?: synchronized(this) { identity ?: identityStore.identity().also { identity = it } }

  /**
   * Destroys this phone's identity, so the next [identity] call is a different device.
   *
   * Only the key is dealt with here. The state that was authored under it is the repository's to
   * throw away, and it has to happen in this order: a state file left behind would be read back as
   * somebody else's and discarded anyway, but not before a save in flight had rewritten it.
   */
  fun deleteIdentity() {
    synchronized(this) {
      // Discovery goes first, and is discarded rather than stopped and reused: it was built around
      // the old device id and would keep advertising an identity this phone no longer holds. The
      // next caller gets one built around the new key.
      discovery?.stop()
      discovery = null
      // The coordinator was listening to that discovery and answering as that key, so it goes too.
      coordinator?.stop()
      coordinator = null
      endpoint?.stopAll()
      endpoint = null

      identityStore.forget()
      identity = null
    }
  }

  fun networkMonitor(context: Context): NetworkMonitor =
    monitor ?: synchronized(this) { monitor ?: AndroidNetworkMonitor(context).also { monitor = it } }

  /**
   * Peer discovery, built against this phone's current identity.
   *
   * Constructing it advertises nothing. It sits idle until something calls `start`, which is what
   * keeps the presence leak behind the approval gate rather than behind this object's lifetime.
   */
  fun peerDiscovery(context: Context): PeerDiscovery =
    discovery
      ?: synchronized(this) {
        discovery ?: NsdPeerDiscovery(context, { identity().deviceId }, networkScope).also { discovery = it }
      }

  /** The socket peers connect back on. Binds nothing until `start`. */
  fun syncEndpoint(): SyncEndpoint =
    endpoint ?: synchronized(this) { endpoint ?: SyncEndpoint(networkScope, ::identity).also { endpoint = it } }

  /**
   * What decides when paired phones exchange lists, and answers when they call.
   *
   * Building it does no I/O and starts nothing — it only connects the pieces, including telling the
   * endpoint who answers a sync call. [SyncCoordinator.start] is called once the state has loaded,
   * which keeps all of this behind the first frame.
   */
  fun syncCoordinator(context: Context): SyncCoordinator =
    coordinator
      ?: synchronized(this) {
        coordinator
          ?: SyncCoordinator(
              repo = listRepository(context),
              identity = ::identity,
              found = peerDiscovery(context).found,
              scope = networkScope,
            )
            .also {
              syncEndpoint().syncHandler = it
              coordinator = it
            }
      }

  fun listRepository(context: Context): ListRepository =
    repository
      ?: synchronized(this) {
        repository
          ?: FileListRepository(
              file = File(context.applicationContext.filesDir, STATE_FILE),
              scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
              identity = ::identity,
            )
            .also { repository = it }
      }
}
