package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.IdFactory
import java.io.File
import java.io.FileOutputStream
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

  /**
   * Throws everything away and starts again as whoever the identity now says this phone is.
   *
   * Called after the key has been destroyed, so the state that comes back is authored by a device
   * that has never existed before. Nothing is kept: the old lists were authored by an id this phone
   * can no longer sign for, and keeping them would mean showing items nobody can account for.
   */
  suspend fun reset()
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
  /**
   * This phone's key-derived identity. Not a value the repository is free to invent, and read
   * through a function rather than held, because deleting the identity replaces it underneath.
   */
  private val identity: () -> LocalIdentity,
) : ListRepository {

  private val _state = MutableStateFlow<AppState?>(null)
  override val state: StateFlow<AppState?> = _state.asStateFlow()

  private val writeLock = Mutex()
  private var pendingWrite: Job? = null

  @Volatile private var node: LocalNode? = null

  /**
   * The saves before the current one, newest first. Each save moves them down a place, so a file
   * that cannot be read — cut short by a crash or a dead battery just after a save — costs only the
   * changes since the save before it.
   */
  private val previous = (1..KEPT_SAVES).map { File(file.parentFile, "${file.name}.$it") }

  /**
   * Set aside when no save at all can be read, and never rotated away. Every save was written by
   * the same build, so a reader that fails on one fails on all of them: that is a fault in an
   * update, not in the files, and the update that fixes it gets the lists back from here.
   */
  private val unreadable = File(file.parentFile, "${file.name}.unreadable")

  /** Set once [unreadable] has been brought back: it goes after the save holding its lists lands. */
  @Volatile private var unreadableRecovered = false

  override suspend fun load() {
    if (_state.value != null) return
    val me = identity().deviceId
    val (restored, anySave, skipped) = withContext(io) { newestReadable() }

    // A file authored under a different identity is not this phone's state. That happens if the
    // keystore entry was cleared — the old items are unattributable, so start clean rather than
    // claim authorship of them.
    if (restored != null && restored.localDevice != me) {
      val fresh = AppState.empty(localDevice = me)
      node = fresh.toNode()
      _state.value = fresh
      schedulePersist(fresh)
      return
    }

    if (restored != null) {
      // Receipts may have arrived after the last save; settle anything they now make collectable.
      val pruned = restored.pruneDeliveredTombstones()
      // A newer save was passed over. It is damage, not a fault in the reader, since this build
      // reads an older one: it goes, rather than taking a backup's place at the next save. The ids
      // it handed out are unknown, and a peer may already hold items under them, so jump past them
      // as the blank start below does; the margin covers a clock set back since, for a save holds
      // at most one burst of edits.
      val settled =
        if (skipped.isEmpty()) {
          pruned
        } else {
          withContext(io) { writeLock.withLock { skipped.forEach { runCatching { it.delete() } } } }
          pruned.copy(idCounter = maxOf(System.currentTimeMillis(), pruned.idCounter + LOST_SAVE_IDS))
        }
      val recovered = withContext(io) { retryUnreadable(settled) }
      val loaded = recovered ?: settled
      node = loaded.toNode()
      _state.value = loaded
      if (loaded != restored) schedulePersist(loaded)
      return
    }

    // The identity is not minted here any more: it comes from the keystore, which is what keeps it
    // stable across reinstalls of this file and makes it something a peer can verify rather than
    // merely believe.
    val blank =
      if (!anySave) {
        AppState.empty(localDevice = me)
      } else {
        // Saves exist and none of them reads. They are kept for a later build to try again, and
        // ids start above any the lost state can have handed out — counters only ever reach the
        // thousands — so nothing made from here can share an id with an item a peer still holds.
        withContext(io) { setAsideUnreadable() }
        AppState.empty(localDevice = me).let { it.copy(idCounter = it.clockHead.wall) }
      }
    node = blank.toNode()
    _state.value = blank
    schedulePersist(blank)
  }

  /**
   * The newest save that decodes, whether there was any save at all, and the newer saves passed
   * over to reach it.
   */
  private fun newestReadable(): Triple<AppState?, Boolean, List<File>> {
    val saves = (listOf(file) + previous).filter { it.exists() }
    val readable = saves.withIndex().firstNotNullOfOrNull { (i, save) ->
      runCatching { StateCodec.decode(save.readText()) }.getOrNull()?.let { i to it }
    }
    return Triple(readable?.second, saves.isNotEmpty(), saves.take(readable?.first ?: 0))
  }

  /** Copies the newest save aside, unless an earlier failure already did: that one holds the lists. */
  private fun setAsideUnreadable() {
    if (unreadable.exists()) return
    val newest = (listOf(file) + previous).firstOrNull { it.exists() } ?: return
    runCatching { newest.copyTo(unreadable) }
  }

  /**
   * Tries the set-aside file again: after an update with a fixed reader, its lists come back into
   * [current], and the file goes. Null when there is nothing to bring back.
   */
  private fun retryUnreadable(current: AppState): AppState? {
    if (!unreadable.exists()) return null
    val kept = runCatching { StateCodec.decode(unreadable.readText()) }.getOrNull() ?: return null
    // Readable now, but written under an identity this phone no longer has: unattributable, as
    // above, so it goes rather than coming back.
    if (kept.localDevice != current.localDevice) {
      unreadable.delete()
      return null
    }
    unreadableRecovered = true
    return current.withRecovered(kept)
  }

  override suspend fun reset() {
    // Any save still in flight belongs to the phone that has just ceased to exist; letting it land
    // would put the old state back on disk moments after it was deleted. The earlier saves and any
    // set-aside file go with it: they were authored by the same vanished device.
    pendingWrite?.cancel()
    unreadableRecovered = false
    withContext(io) { writeLock.withLock { (listOf(file, unreadable) + previous).forEach { runCatching { it.delete() } } } }

    val fresh = AppState.empty(localDevice = identity().deviceId)
    node = fresh.toNode()
    _state.value = fresh
    schedulePersist(fresh)
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
        writeLock.withLock { runCatching { writeRotating(StateCodec.encode(snapshot)) } }
      }
  }

  /**
   * Writes beside the real file, forces it to storage, moves the earlier saves down a place, and
   * renames the new one in.
   *
   * The sync is what makes the rename safe: without it, a phone that dies just after a save can
   * come back with the new name pointing at a file whose contents never reached storage. Any step
   * failing leaves the newest readable save where [load] looks for it; the state stays in memory
   * and the next save tries again. There is no fallback that writes over the real file in place.
   */
  private fun writeRotating(text: String) {
    val tmp = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(tmp).use { out ->
      out.write(text.toByteArray(Charsets.UTF_8))
      out.fd.sync()
    }
    // Oldest slot first, so each file moves into a place just vacated.
    for (i in previous.indices.reversed()) {
      val from = if (i == 0) file else previous[i - 1]
      if (from.exists()) from.renameTo(previous[i])
    }
    check(tmp.renameTo(file)) { "could not move the new save into place" }
    if (unreadableRecovered) {
      unreadable.delete()
      unreadableRecovered = false
    }
  }

  private companion object {
    /** Saves kept besides the current one. Each is the whole state: a few KB. */
    const val KEPT_SAVES = 2

    /** More ids than one save's worth of edits can mint, even a long paste. */
    const val LOST_SAVE_IDS = 1_000_000L
  }
}

/**
 * [kept] — a state set aside when it could not be read — brought back into this one.
 *
 * What this phone has now wins wherever the two hold the same thing: a list that has come back from
 * a peer since, or a phone paired again. Everything else in [kept] is added: the lists only it has,
 * with their open questions; the phones and networks; the name, staples, recipes and background sync
 * switch if none were set again.
 * Receipts are not merged — losing them is safe, tombstones only stay longer.
 */
internal fun AppState.withRecovered(kept: AppState): AppState {
  val here = lists.map { it.id }.toSet()
  val back = kept.lists.filterNot { it.id in here }
  val backIds = back.map { it.id }.toSet()
  val pairedHere = peers.map { it.deviceId }.toSet()
  val networksHere = networks.map { it.fingerprint }.toSet()
  return copy(
    displayName = displayName.ifBlank { kept.displayName },
    // The name comes back with its stamp, so it does not go out as never renamed.
    displayNameAt = if (displayName.isBlank()) kept.displayNameAt else displayNameAt,
    idCounter = maxOf(idCounter, kept.idCounter),
    clockHead = maxOf(clockHead, kept.clockHead),
    lists = lists + back,
    activeListId = if (lists.any { it.id == activeListId }) activeListId else kept.activeListId,
    peers = peers + kept.peers.filterNot { it.deviceId in pairedHere },
    networks = networks + kept.networks.filterNot { it.fingerprint in networksHere },
    staples = staples.ifEmpty { kept.staples },
    recipes = recipes.ifEmpty { kept.recipes },
    backgroundSync = backgroundSync ?: kept.backgroundSync,
    conflicts = conflicts + kept.conflicts.filter { it.listId in backIds },
  )
}
