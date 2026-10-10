package io.github.molleware.porygonlist.data.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/** A quarter of an hour: how far apart the background windows are, in either mode. */
const val QUARTER_HOUR_MS = 15 * 60_000L

/**
 * The next quarter hour strictly after [nowMs] — :00, :15, :30 or :45.
 *
 * Counted from the epoch, which every phone agrees on to within a second or so of network time, and
 * which lands on the local quarter hours too: every time zone in use is offset by a whole number of
 * quarter hours.
 */
fun nextQuarterHour(nowMs: Long): Long = (Math.floorDiv(nowMs, QUARTER_HOUR_MS) + 1) * QUARTER_HOUR_MS

/**
 * When the background windows run, and which of two ways.
 *
 * **On the quarter hour, both ways.** A window only lands a change when both phones are listening
 * at once, so every window aims at :00, :15, :30 or :45, the moments every phone on the list agrees
 * on. What differs is how firmly.
 *
 * - An exact alarm wakes the phone at that moment even in Doze, so windows overlap by construction.
 *   That needs "Alarms & reminders", which Android 14 and later leave off until the owner turns it
 *   on (Settings offers the way there).
 * - Without it, [SyncJob] waits until the same moment, but Android only promises "not before". Awake
 *   or charging — overnight on a charger, say — it lands on time; in Doze it waits for the phone's
 *   own maintenance window, and that meeting is missed.
 *
 * It is one or the other, never both, so there is no doubled wake-up.
 *
 * The decision is remade every time the schedule is touched, so granting or revoking the permission
 * moves the phone between the two without anything else having to notice.
 */
object BackgroundSync {

  /** Turns the windows on, the aligned way if this phone allows it. Safe to call repeatedly. */
  fun ensureScheduled(context: Context) {
    if (canAlign(context)) {
      armAlarm(context)
      SyncJob.cancelQuarterHours(context)
    } else {
      cancelAlarm(context)
      SyncJob.ensureScheduled(context)
    }
  }

  /** One window straight away, on joining an approved wifi. Outside the rhythm in either mode. */
  fun runSoon(context: Context) = SyncJob.runSoon(context)

  /** Stops the windows until an approved wifi is joined again. */
  fun cancel(context: Context) {
    cancelAlarm(context)
    SyncJob.cancelAll(context)
  }

  /** True while the windows are on at all, whichever way — the phone is on an approved wifi. */
  fun isOn(context: Context): Boolean = alarmArmed(context) || SyncJob.isScheduled(context)

  /** Whether this phone may wake on the exact quarter hour. Always, before Android 12. */
  fun canAlign(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

  private fun armAlarm(context: Context) {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    // Re-arming replaces the earlier alarm with one for the same moment, so this can be called on
    // every launch without pushing anything back.
    runCatching {
      alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextQuarterHour(System.currentTimeMillis()), alarmIntent(context))
    }
  }

  /**
   * Disarms the alarm *and* cancels its PendingIntent. The second part is what makes [alarmArmed]
   * honest: a PendingIntent outlives the alarm that fired it, so its existence only means "on" if
   * turning off always destroys it.
   */
  private fun cancelAlarm(context: Context) {
    val pending = existingAlarmIntent(context) ?: return
    context.getSystemService(AlarmManager::class.java)?.cancel(pending)
    pending.cancel()
  }

  private fun alarmArmed(context: Context) = existingAlarmIntent(context) != null

  private fun alarmIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(context, 0, Intent(context, SyncAlarm::class.java), PendingIntent.FLAG_IMMUTABLE)

  private fun existingAlarmIntent(context: Context): PendingIntent? =
    PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, SyncAlarm::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    )
}

/**
 * The quarter-hour alarm going off, and the owner granting exact alarms.
 *
 * An alarm arms the next one first, so a window that fails or is refused never breaks the chain,
 * then opens this window in [SyncWindowService]. Firing is also the one moment Android lets a closed
 * app start a foreground service, which is what keeps the network through Doze for the whole window.
 *
 * The grant arrives with the app possibly closed, from Settings. If the windows were on, they move
 * across to the aligned schedule there and then; if not, joining an approved wifi will pick it up.
 */
class SyncAlarm : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext
    if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
      if (BackgroundSync.isOn(app)) BackgroundSync.ensureScheduled(app)
      return
    }

    BackgroundSync.ensureScheduled(app)
    val started = runCatching { ContextCompat.startForegroundService(app, Intent(app, SyncWindowService::class.java)) }
    // Refused — a vendor's battery manager, say. A job is the next best thing: later and unaligned,
    // but the change still gets its chance.
    if (started.isFailure) BackgroundSync.runSoon(app)
  }
}
