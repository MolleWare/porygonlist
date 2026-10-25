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
  private class Opened(val socket: Boolean)

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

    // The wifi listener, in case nothing in this process has registered it yet. The boot path does,
    // but on the Pixel after the reboots of 2026-10-13 and 2026-10-20 it was missing until the app
    // was opened, while this job — persisted by the system — survived and ran. Once per process:
    // see WifiWatch.register for the loop that repeating it made. Before the plan, so a window that
    // stops the schedule on a strange wifi still leaves the listener that restarts it at home.
    if (state.peers.isNotEmpty()) WifiWatch.register(context)

    // Read in one go, not off the monitor's flow — whose first value has no fingerprint yet, which
    // made every window on hardware give up as "unidentifiable" before announcing anything.
    val monitor = AndroidNetworkMonitor(context)
    val snapshot = settledSnapshot { monitor.current() }
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
    Graph.syncCoordinator(context).apply {
      start()
      // A window exists to exchange, so it calls everyone once: owing nothing is no reason to miss
      // what a peer has been holding for this phone while it could not be reached.
      callEveryoneOnce()
    }

    // The open app may already be listening — a frozen process wakes up into this window with its
    // discovery still set up. Then the window only has to keep the process awake; starting a second
    // advertisement would be the open app's to undo, and it would not know to.
    val appListening = endpoint.holds(ListenReason.DISCOVERY)
    val port = endpoint.start(ListenReason.BACKGROUND) ?: return
    opened = Opened(socket = true)
    if (!appListening) discovery.start(port)

    delay(WINDOW_MS)
  }

  /** Safe to call more than once, and from Android's "stop now" as well as from the end of [run]. */
  fun close() {
    val was = opened ?: return
    opened = null
    val endpoint = Graph.syncEndpoint()
    // Decided at the end, not at the start: the owner may have opened the app during the window, and
    // its discovery is now the one running; or sent it away, leaving its discovery to this window.
    if (!endpoint.holds(ListenReason.DISCOVERY)) Graph.peerDiscovery(context).stop()
    if (was.socket) endpoint.stop(ListenReason.BACKGROUND)
  }

  companion object {
    /** Long enough to find a peer, resolve it and exchange; short enough to cost next to nothing. */
    const val WINDOW_MS = 30_000L

    /**
     * The network, once the system lets this process see it, or offline after [SETTLE_MS].
     *
     * A window started from the background begins firewalled: Android blocks a background app's
     * network, and lifts that for a job or a foreground service a moment after it starts, not as it
     * starts. Until then the active network reads as none. Seen on the Pixel, 2026-10-24: the 19:30
     * alarm's window read "offline" at 19:30:00 and stood aside in 25 ms, while the firewall allowed
     * the app only later — so a phone on its home wifi missed its quarter hour.
     */
    internal suspend fun settledSnapshot(read: () -> NetworkSnapshot): NetworkSnapshot {
      var snapshot = read()
      var waited = 0L
      while (!snapshot.isUsable && waited < SETTLE_MS) {
        delay(SETTLE_STEP_MS)
        waited += SETTLE_STEP_MS
        snapshot = read()
      }
      return snapshot
    }

    private const val SETTLE_MS = 5_000L
    private const val SETTLE_STEP_MS = 250L

    private val busy = AtomicBoolean(false)
  }
}
