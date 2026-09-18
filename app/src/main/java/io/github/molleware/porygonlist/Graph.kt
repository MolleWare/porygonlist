package io.github.molleware.porygonlist

import android.content.Context
import io.github.molleware.porygonlist.data.FileListRepository
import io.github.molleware.porygonlist.data.crypto.AndroidKeystoreIdentityStore
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.net.AndroidNetworkMonitor
import io.github.molleware.porygonlist.data.net.NetworkMonitor
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

  private val identityStore = AndroidKeystoreIdentityStore()

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
      identityStore.forget()
      identity = null
    }
  }

  fun networkMonitor(context: Context): NetworkMonitor =
    monitor ?: synchronized(this) { monitor ?: AndroidNetworkMonitor(context).also { monitor = it } }

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
