package io.github.molleware.porygonlist.data.net

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Syncs for a short while around every quarter hour, with the app out of sight — the fallback for a
 * phone that has not allowed exact alarms. See [BackgroundSync] for the schedule it falls back from,
 * and [SyncWindow] for what a window does.
 *
 * With the app open, the phone listens and looks for its peers the whole time. Closed, Android
 * freezes it soon after and Doze takes it off the network, so on its own it would only hand over
 * changes while somebody was looking at it. This gives it a window on Android's schedule instead.
 *
 * **Aimed at the quarter hour, not held to it.** A change moves only when both phones are reachable
 * at once, so every window waits until the next :00, :15, :30 or :45 — the same moments the alarm
 * uses, and the same moments the other phone is aiming at. Android treats the wait as "not before"
 * rather than "at": awake or on a charger the window lands within seconds of it, but in Doze it is
 * held to the phone's next maintenance window, which is the phone's own. Each window aims at the
 * quarter after the one it actually ran in, so a late one costs one meeting, not every one after.
 *
 * That is why it is a chain of one-off jobs rather than a periodic one: a periodic job starts its
 * period wherever Android likes and drifts from there, and nothing about it can be aimed.
 *
 * **Two ids, alternating.** Scheduling a job under the id of the one running stops the running one,
 * so a window cannot book its successor under its own id. It books it under the other.
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

  /** One per job id: a quarter-hour window and [runSoon]'s can both be live in this one service. */
  private val running = mutableMapOf<Int, Pair<Job, SyncWindow>>()

  override fun onStartJob(params: JobParameters): Boolean {
    // The next one is booked before this one does anything, so a window that fails or is stopped
    // never breaks the chain. A window that finds it should not be running cancels both anyway.
    if (params.jobId in QUARTER_IDS) book(applicationContext, QUARTER_IDS.first { it != params.jobId })

    val window = SyncWindow(applicationContext)
    val job =
      scope.launch {
        try {
          window.run()
        } finally {
          synchronized(running) { running.remove(params.jobId) }
          jobFinished(params, false)
        }
      }
    synchronized(running) { running[params.jobId] = job to window }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    // Android wants the time back: the wifi went, or the window ran long. Stop cleanly; the next
    // quarter hour is already booked.
    val (job, window) = synchronized(running) { running.remove(params.jobId) } ?: return false
    job.cancel()
    window.close()
    return false
  }

  companion object {
    /** The first is the id the old periodic job had, so an update replaces it rather than adding to it. */
    private val QUARTER_IDS = listOf(0x5059, 0x5062)

    /** A single window as soon as possible, on joining an approved wifi. See [WifiWatch]. */
    private const val NOW_ID = 0x5060

    /**
     * Books the next quarter-hour window if none is, and leaves it alone if one is.
     *
     * A booked window already aims at a fixed moment, so leaving it is never wrong. The exception is
     * the periodic job an earlier version scheduled under the same id, which is replaced.
     */
    fun ensureScheduled(context: Context) {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      val pending = QUARTER_IDS.mapNotNull { scheduler.getPendingJob(it) }
      if (pending.any { !it.isPeriodic }) return
      book(context, QUARTER_IDS.first())
    }

    /**
     * Unmetered rather than any network: the gate wants an approved wifi, and there is no reason to
     * wake on mobile data only to find that out. No deadline either, for the same reason — a
     * deadline runs the job with its network requirement unmet.
     */
    private fun book(context: Context, id: Int) {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      val now = System.currentTimeMillis()
      scheduler.schedule(
        JobInfo.Builder(id, ComponentName(context, SyncJob::class.java))
          .setMinimumLatency(nextQuarterHour(now) - now)
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
          // Survives a reboot, so a phone that restarts overnight goes back to syncing without
          // waiting for somebody to open the app.
          .setPersisted(true)
          .build()
      )
    }

    /** True while the quarter-hour windows are on — that is, while the phone is on an approved wifi. */
    fun isScheduled(context: Context): Boolean {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
      return QUARTER_IDS.any { scheduler.getPendingJob(it) != null }
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

    /** Stops the quarter-hour windows. [runSoon]'s one-off is left to finish or to [cancelAll]. */
    fun cancelQuarterHours(context: Context) {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      QUARTER_IDS.forEach(scheduler::cancel)
    }

    /** Stops the windows until an approved wifi is joined again. */
    fun cancelAll(context: Context) {
      cancelQuarterHours(context)
      context.getSystemService(JobScheduler::class.java)?.cancel(NOW_ID)
    }
  }
}
