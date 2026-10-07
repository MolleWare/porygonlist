package io.github.molleware.porygonlist.data.net

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.molleware.porygonlist.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Syncs for a short while every quarter of an hour or so, with the app out of sight.
 *
 * With the app open, the phone listens and looks for its peers the whole time. Closed, Android
 * freezes it soon after and Doze takes it off the network, so on its own it would only hand over
 * changes while somebody was looking at it. This gives it a window on Android's schedule instead:
 * it wakes, does what the open app does for [WINDOW_MS], and stops.
 *
 * **What it can and cannot do.** Without a server, a change moves only when both phones are
 * reachable at once. A window lands whatever is waiting with a phone that has the app open, or
 * that is in its own window at the same moment. Two phones in pockets rarely line up, and Doze
 * stretches the gaps further the longer a phone sits still. That is the trade the owner chose over
 * listening all day, which keeps the wifi chip awake to stay discoverable.
 *
 * **Why JobScheduler and not WorkManager.** This is the framework scheduler, which WorkManager wraps.
 * It adds no dependency and installs no startup initialiser, so the cold-start path does not grow.
 * Nothing about it runs before the first frame: [ensureScheduled] is called after it.
 *
 * **Only after joining a known wifi.** [WifiWatch] starts the schedule on arriving at an approved
 * network and stops it on arriving anywhere else, so away from home the phone does not wake every
 * quarter hour to find that out. The gate is still checked in every window, just as the open app
 * checks it: a window that finds itself on a café's wifi announces nothing, opens nothing, and
 * stops the schedule.
 */
class SyncJob : JobService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var running: Job? = null
  @Volatile private var opened: Opened? = null

  /** What this window started itself, so it stops exactly that and nothing the open app is using. */
  private class Opened(val socket: Boolean, val discovery: Boolean)

  override fun onStartJob(params: JobParameters): Boolean {
    running =
      scope.launch {
        try {
          window()
        } finally {
          close()
          jobFinished(params, false)
        }
      }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    // Android wants the time back: the wifi went, or the window ran long. Stop cleanly and let the
    // next period try again rather than asking to be rescheduled straight away.
    running?.cancel()
    close()
    return false
  }

  private suspend fun window() {
    val repo = Graph.listRepository(applicationContext)
    repo.load()
    val state = repo.state.filterNotNull().first()

    // Read in one go, not off the monitor's flow — whose first value has no fingerprint yet, which
    // made every window on hardware give up as "unidentifiable" before announcing anything.
    val snapshot = AndroidNetworkMonitor(applicationContext).current()
    when (backgroundPlan(snapshot, state)) {
      BackgroundPlan.RUN -> Unit
      // Woke somewhere it should not be — a reboot while away, a network since un-approved, nobody
      // left to sync with. Stop the schedule; joining an approved wifi starts it again.
      BackgroundPlan.STOP -> {
        cancel(applicationContext)
        return
      }
      BackgroundPlan.LEAVE -> return
    }

    val endpoint = Graph.syncEndpoint()
    val discovery = Graph.peerDiscovery(applicationContext)
    Graph.syncCoordinator(applicationContext).start()

    // The open app may already be listening — a frozen process wakes up into this job with its
    // discovery still set up. Then the window only has to keep the process awake; starting a second
    // advertisement would be the open app's to undo, and it would not know to.
    val appListening = endpoint.holds(ListenReason.DISCOVERY)
    val port = endpoint.start(ListenReason.BACKGROUND) ?: return
    opened = Opened(socket = true, discovery = !appListening)
    if (!appListening) discovery.start(port)

    delay(WINDOW_MS)
  }

  private fun close() {
    val was = opened ?: return
    opened = null
    val endpoint = Graph.syncEndpoint()
    // Checked again at the end, not trusted from the start: the owner may have opened the app during
    // the window, and its discovery is now the one running.
    if (was.discovery && !endpoint.holds(ListenReason.DISCOVERY)) Graph.peerDiscovery(applicationContext).stop()
    if (was.socket) endpoint.stop(ListenReason.BACKGROUND)
  }

  companion object {
    private const val JOB_ID = 0x5059 // "PY"

    /** A single window as soon as possible, on joining an approved wifi. See [WifiWatch]. */
    private const val NOW_ID = 0x5060

    /** Android will not run a periodic job more often than this. */
    private const val PERIOD_MS = 15 * 60_000L

    /** Long enough to find a peer, resolve it and exchange; short enough to cost next to nothing. */
    private const val WINDOW_MS = 30_000L

    /**
     * Schedules the window if it is not already, and leaves it alone if it is.
     *
     * Re-scheduling an existing periodic job restarts its period, so calling this on every launch
     * without the check would push the next window back each time the app is opened.
     *
     * Unmetered rather than any network: the gate wants an approved wifi, and there is no reason to
     * wake on mobile data only to find that out.
     */
    fun ensureScheduled(context: Context) {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      if (scheduler.getPendingJob(JOB_ID) != null) return
      scheduler.schedule(
        JobInfo.Builder(JOB_ID, ComponentName(context, SyncJob::class.java))
          .setPeriodic(PERIOD_MS)
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
          // Survives a reboot, so a phone that restarts overnight goes back to syncing without
          // waiting for somebody to open the app.
          .setPersisted(true)
          .build()
      )
    }

    /** One window soon, outside the quarter-hour rhythm. Replaces any that is still waiting. */
    fun runSoon(context: Context) {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      scheduler.schedule(
        JobInfo.Builder(NOW_ID, ComponentName(context, SyncJob::class.java))
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
          .build()
      )
    }

    /** Stops the windows until an approved wifi is joined again. */
    fun cancel(context: Context) {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      scheduler.cancel(JOB_ID)
      scheduler.cancel(NOW_ID)
    }
  }
}
