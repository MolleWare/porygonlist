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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The quarter-hour jobs 0.1.0 booked: a chain of one-off windows aimed at :00, :15, :30 and :45,
 * alternating between these two ids, for a phone without alarms and as a backstop beside the alarm
 * for one with them. The first is also the id of the periodic job before that. Nothing books them
 * any more — see [BackgroundSync] — but a phone updating from 0.1.0 still has one, persisted.
 */
private val RETIRED_JOB_IDS = listOf(0x5059, 0x5062)

/** Whether [id] is one of the jobs this build no longer runs, and only cancels. */
internal fun isRetiredJob(id: Int): Boolean = id in RETIRED_JOB_IDS

/**
 * One sync window straight away, with the app out of sight: on joining an approved wifi with the
 * switch on, or when Android would not let the quarter-hour alarm start its foreground service. See
 * [BackgroundSync] for the schedule, and [SyncWindow] for what a window does.
 *
 * Getting home is exactly when there is something to hand over, and the next quarter hour may be
 * fourteen minutes away. This is not a schedule of its own: one window, then nothing until the
 * alarm. Without alarms the switch is off, and nothing books it.
 *
 * **Why JobScheduler and not WorkManager.** This is the framework scheduler, which WorkManager wraps.
 * It adds no dependency and installs no startup initialiser, so the cold-start path does not grow.
 * Nothing about it runs before the first frame.
 *
 * The gate is still checked in the window, just as the open app checks it: a window that finds
 * itself on a café's wifi, or the switch off, announces nothing, opens nothing, and stops the schedule.
 */
class SyncJob : JobService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** The window under way, by job id, so Android's "stop now" can close it. */
  private val running = mutableMapOf<Int, Pair<Job, SyncWindow>>()

  override fun onStartJob(params: JobParameters): Boolean {
    // Booked by 0.1.0, before this build replaced it: the old chain stops here, and its successor,
    // if it has one, goes with it.
    if (isRetiredJob(params.jobId)) {
      retireOldJobs(applicationContext, always = true)
      return false
    }

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
    // quarter hour's alarm is already armed.
    val (job, window) = synchronized(running) { running.remove(params.jobId) } ?: return false
    job.cancel()
    window.close()
    return false
  }

  companion object {
    /** A single window as soon as possible, on joining an approved wifi. See [WifiWatch]. */
    private const val NOW_ID = 0x5060

    /** Whether this process has cancelled the retired jobs already. */
    private val retired = AtomicBoolean(false)

    /**
     * Cancels the quarter-hour jobs 0.1.0 booked ([RETIRED_JOB_IDS]), so nothing old keeps waking
     * the phone. Called wherever the schedule is touched; the first call in a process does it and
     * the rest return, since nothing in this build books them again. [always] for a retired job
     * found running, which says one is still there whatever this process did before.
     */
    fun retireOldJobs(context: Context, always: Boolean = false) {
      if (!retired.compareAndSet(false, true) && !always) return
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      RETIRED_JOB_IDS.forEach(scheduler::cancel)
    }

    /** One window soon, outside the quarter-hour rhythm. Leaves one that is still waiting. */
    fun runSoon(context: Context) {
      val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
      // Already booked, or running: booking it again would cancel the window under way and start
      // another, which is how a burst of wifi broadcasts became a loop of windows (2026-10-24).
      if (scheduler.getPendingJob(NOW_ID) != null) return
      scheduler.schedule(
        JobInfo.Builder(NOW_ID, ComponentName(context, SyncJob::class.java))
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
          .build()
      )
    }

    /** Takes back a [runSoon] that has yet to run. One under way finishes its half minute. */
    fun cancelSoon(context: Context) {
      context.getSystemService(JobScheduler::class.java)?.cancel(NOW_ID)
    }
  }
}
