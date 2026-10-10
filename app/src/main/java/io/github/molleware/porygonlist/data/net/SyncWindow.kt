package io.github.molleware.porygonlist.data.net

import android.content.Context
import io.github.molleware.porygonlist.Graph
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * One background sync window: do what the open app does for [WINDOW_MS], then stop.
 *
 * Shared by the two things that open one — [SyncJob] on Android's own schedule, and
 * [SyncWindowService] on the quarter hour — so both check the same gate and undo the same things.
 *
 * **One at a time, per process.** The socket counts *reasons* to listen, not callers, so two windows
 * overlapping — the quarter-hour alarm landing during the window that joining the wifi asked for —
 * would each hold [ListenReason.BACKGROUND], and the first to finish would close the other's socket
 * under it. The second simply steps aside; the first is already doing everything it would.
 */
class SyncWindow(private val context: Context) {

  @Volatile private var opened: Opened? = null

  /** What this window started itself, so it stops exactly that and nothing the open app is using. */
  private class Opened(val socket: Boolean, val discovery: Boolean)

  suspend fun run() {
    if (!busy.compareAndSet(false, true)) return
    try {
      open()
    } finally {
      close()
      busy.set(false)
    }
  }

  private suspend fun open() {
    val repo = Graph.listRepository(context)
    repo.load()
    val state = repo.state.filterNotNull().first()

    // Read in one go, not off the monitor's flow — whose first value has no fingerprint yet, which
    // made every window on hardware give up as "unidentifiable" before announcing anything.
    val snapshot = AndroidNetworkMonitor(context).current()
    when (backgroundPlan(snapshot, state)) {
      BackgroundPlan.RUN -> Unit
      // Woke somewhere it should not be — a reboot while away, a network since un-approved, nobody
      // left to sync with. Stop the schedule; joining an approved wifi starts it again.
      BackgroundPlan.STOP -> {
        BackgroundSync.cancel(context)
        return
      }
      BackgroundPlan.LEAVE -> return
    }

    val endpoint = Graph.syncEndpoint()
    val discovery = Graph.peerDiscovery(context)
    Graph.syncCoordinator(context).start()

    // The open app may already be listening — a frozen process wakes up into this window with its
    // discovery still set up. Then the window only has to keep the process awake; starting a second
    // advertisement would be the open app's to undo, and it would not know to.
    val appListening = endpoint.holds(ListenReason.DISCOVERY)
    val port = endpoint.start(ListenReason.BACKGROUND) ?: return
    opened = Opened(socket = true, discovery = !appListening)
    if (!appListening) discovery.start(port)

    delay(WINDOW_MS)
  }

  /** Safe to call more than once, and from Android's "stop now" as well as from the end of [run]. */
  fun close() {
    val was = opened ?: return
    opened = null
    val endpoint = Graph.syncEndpoint()
    // Checked again at the end, not trusted from the start: the owner may have opened the app during
    // the window, and its discovery is now the one running.
    if (was.discovery && !endpoint.holds(ListenReason.DISCOVERY)) Graph.peerDiscovery(context).stop()
    if (was.socket) endpoint.stop(ListenReason.BACKGROUND)
  }

  companion object {
    /** Long enough to find a peer, resolve it and exchange; short enough to cost next to nothing. */
    const val WINDOW_MS = 30_000L

    private val busy = AtomicBoolean(false)
  }
}
