package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.IdFactory
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * This phone's sync machinery for the life of the session: who it is, the ids it hands out, and its
 * clock.
 *
 * These are stateful in a way [AppState] is not, so they live here and their positions are written
 * back into the state on every save. Restoring them is what stops a restart minting an id twice or
 * resuming the clock behind a stamp already on disk.
 */
class LocalNode(val device: DeviceId, val ids: IdFactory, val clock: HybridClock)

interface ListRepository {
  /** Null until the stored state has been read. The UI shows nothing rather than flashing a seed. */
  val state: StateFlow<AppState?>

  /** Reads the file, or seeds the design's content on first run. Safe to call more than once. */
  suspend fun load()

  /** Applies [transform] in memory straight away and persists it in the background. */
  fun update(transform: (AppState, LocalNode) -> AppState)
}

/**
 * File-backed store for the whole app state.
 *
 * Nothing is read at construction: [load] is called from the ViewModel's scope once the first frame
 * is on screen, so opening the app never waits on disk. Writes are coalesced — a burst of taps
 * while ticking items off becomes one save.
 */
class FileListRepository(
  private val file: File,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val writeDelayMs: Long = 300,
  /** This phone's key-derived identity. Not a value the repository is free to invent. */
  private val identity: LocalIdentity,
) : ListRepository {

  private val _state = MutableStateFlow<AppState?>(null)
  override val state: StateFlow<AppState?> = _state.asStateFlow()

  private val writeLock = Mutex()
  private var pendingWrite: Job? = null

  @Volatile private var node: LocalNode? = null

  override suspend fun load() {
    if (_state.value != null) return
    val restored =
      withContext(io) { runCatching { if (file.exists()) StateCodec.decode(file.readText()) else null }.getOrNull() }

    // A file authored under a different identity is not this phone's state. That happens if the
    // keystore entry was cleared — the old items are unattributable, so start clean rather than
    // claim authorship of them.
    if (restored != null && restored.localDevice != identity.deviceId) {
      val fresh = AppState.seed(localDevice = identity.deviceId)
      node = fresh.toNode()
      _state.value = fresh
      schedulePersist(fresh)
      return
    }

    if (restored != null) {
      // Receipts may have arrived after the last save; settle anything they now make collectable.
      val settled = restored.pruneDeliveredTombstones()
      node = settled.toNode()
      _state.value = settled
      if (settled != restored) schedulePersist(settled)
      return
    }

    // A missing or unreadable file is a first run, not an error worth surfacing. The identity is
    // not minted here any more: it comes from the keystore, which is what keeps it stable across
    // reinstalls of this file and makes it something a peer can verify rather than merely believe.
    val seeded = AppState.seed(localDevice = identity.deviceId)
    node = seeded.toNode()
    _state.value = seeded
    schedulePersist(seeded)
  }

  override fun update(transform: (AppState, LocalNode) -> AppState) {
    val current = _state.value ?: return
    val localNode = node ?: return

    // The node advances as the transform mints ids and stamps; its new positions are folded back in
    // so that what gets written to disk can always be resumed from.
    val next = transform(current, localNode).copy(idCounter = localNode.ids.peek(), clockHead = localNode.clock.head())
    if (next == current) return
    _state.value = next
    schedulePersist(next)
  }

  private fun AppState.toNode() =
    LocalNode(
      device = localDevice,
      ids = IdFactory(localDevice, start = idCounter),
      clock = HybridClock(localDevice, start = clockHead),
    )

  private fun schedulePersist(snapshot: AppState) {
    pendingWrite?.cancel()
    pendingWrite =
      scope.launch(io) {
        delay(writeDelayMs)
        writeLock.withLock { runCatching { writeAtomically(StateCodec.encode(snapshot)) } }
      }
  }

  /**
   * Writes beside the real file and renames over it, so a kill mid-write leaves the previous state
   * intact rather than a half-written one.
   */
  private fun writeAtomically(text: String) {
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
      file.writeText(text)
      tmp.delete()
    }
  }
}
