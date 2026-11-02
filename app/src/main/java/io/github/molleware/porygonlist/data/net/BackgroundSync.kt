package io.github.molleware.porygonlist.data.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/** A quarter of an hour: how far apart the background windows are. */
const val QUARTER_HOUR_MS = 15 * 60_000L

/**
 * The next quarter hour strictly after [nowMs] — :00, :15, :30 or :45.
 *
 * Counted from the epoch, which every phone agrees on to within a second or so of network time, and
 * which lands on the local quarter hours too: every time zone in use is offset by a whole number of
 * quarter hours.
 */
fun nextQuarterHour(nowMs: Long): Long = (Math.floorDiv(nowMs, QUARTER_HOUR_MS) + 1) * QUARTER_HOUR_MS

/** What the "Background sync" switch in Settings reads as. See [backgroundSwitch]. */
enum class BackgroundSwitch {
  /** A window on every quarter hour, woken by an exact alarm, while on an approved wifi. */
  ON,

  /** No window at all with the app closed: turned off, or never on, on a phone without alarms. */
  OFF,

  /**
   * Turned on, and then alarms were taken away in system settings. As off as [OFF] — there is no
   * slower schedule to fall back to — but it says why, and the switch is the way back.
   */
  ALARMS_TURNED_OFF,
}

/**
 * The switch, from what the owner chose — [chosen], null until they first touch it — and whether
 * this phone allows the app's alarms.
 *
 * **On needs alarms.** A window only lands a change when both phones are listening at once, and only
 * an exact alarm gets a closed phone to the quarter hour through Doze. Without one, Android left a
 * ready window unstarted for 35 minutes on an awake phone on its charger (Pixel, 2026-10-24). So it
 * is on and on time, or off (Hugo, for 0.2.0).
 *
 * **Never chosen, it follows the alarms**: on where they are allowed, off where they are not, for a
 * new install and for a phone coming from 0.1.0 alike.
 *
 * **Revoking alarms turns it off.** Android kills the app and cancels its alarm, and tells it
 * nothing (Pixel, 2026-10-21), so there is no moment to turn the switch off in. It reads as off
 * instead, wherever it is next read: the next start, or a window.
 */
fun backgroundSwitch(chosen: Boolean?, alarmsAllowed: Boolean): BackgroundSwitch =
  when {
    chosen == false -> BackgroundSwitch.OFF
    alarmsAllowed -> BackgroundSwitch.ON
    chosen == true -> BackgroundSwitch.ALARMS_TURNED_OFF
    else -> BackgroundSwitch.OFF
  }

/**
 * [chosen] as it is written down at a start: a choice never made becomes on while alarms are
 * allowed, and stays unmade while they are not.
 *
 * Nothing reads any differently for it — never chosen with alarms allowed is already on. It is what
 * lets that phone say "alarms were turned off" once they are, rather than look as if it had never
 * been on. Left unmade without alarms, so allowing them later still turns it on, as the default says.
 */
fun settledChoice(chosen: Boolean?, alarmsAllowed: Boolean): Boolean? = chosen ?: true.takeIf { alarmsAllowed }

/** What a tap on the switch does. */
enum class SwitchTap {
  TURN_OFF,
  TURN_ON,

  /** On is asked for without alarms: off to the system page, and on if they come back allowed. */
  ASK_FOR_ALARMS,
}

/** What tapping the switch does while it reads [reading]. */
fun switchTap(reading: BackgroundSwitch, alarmsAllowed: Boolean): SwitchTap =
  when {
    reading == BackgroundSwitch.ON -> SwitchTap.TURN_OFF
    alarmsAllowed -> SwitchTap.TURN_ON
    else -> SwitchTap.ASK_FOR_ALARMS
  }

/**
 * Whether the Lists screen asks for background sync to be turned on: while it is off, unless the
 * owner turned it off themselves.
 *
 * The card exists because Settings already offered alarms and nobody went looking there. Somebody
 * who switched it off in Settings has been, and asking them again at every start would only nag.
 * Off by default for want of alarms, or turned off by revoking them, it asks.
 */
fun asksForBackgroundSync(chosen: Boolean?, alarmsAllowed: Boolean): Boolean =
  chosen != false && backgroundSwitch(chosen, alarmsAllowed) != BackgroundSwitch.ON

/**
 * When the background windows run: on the quarter hour, by exact alarm, while the switch is on.
 *
 * **On the quarter hour.** A window only lands a change when both phones are listening at once, so
 * every window is at :00, :15, :30 or :45, the moments every phone on the list agrees on. An exact
 * alarm wakes the phone at that moment even in Doze, so windows overlap by construction. That needs
 * "Alarms & reminders", which Android 14 and later leave off until the owner turns it on; the switch
 * takes them there.
 *
 * **Nothing else.** Until 0.2.0 a phone without alarms ran its windows as jobs aimed at the same
 * moments, and a phone with them kept one booked beside the alarm in case they were revoked. Android
 * only promises a job "not before", so the first missed its meetings, and the second only existed to
 * fall back to the first. Both are gone: off means no window, and revoked means off. [SyncJob]
 * remains for the one window straight away on joining an approved wifi.
 *
 * The decision is remade every time the schedule is touched, from the switch and from Android, so a
 * revoke that nobody was told about is noticed at the next start or window, and stops everything.
 */
object BackgroundSync {

  /**
   * Arms the next quarter hour if the switch reads on, and stops every window if not. Safe to call
   * repeatedly. [chosen] is the owner's choice, [io.github.molleware.porygonlist.data.AppState.backgroundSync].
   */
  fun ensureScheduled(context: Context, chosen: Boolean?) {
    SyncJob.retireOldJobs(context)
    if (backgroundSwitch(chosen, alarmsAllowed(context)) == BackgroundSwitch.ON) armAlarm(context) else cancel(context)
  }

  /**
   * From an alarm going off: the next one, without reading the state. An alarm is only ever armed
   * while the switch is on, and the window that follows reads it, and stops the schedule if it has
   * been turned off since. Revoked, Android cancels the alarm, so it never gets here.
   */
  fun armNext(context: Context) {
    SyncJob.retireOldJobs(context)
    if (alarmsAllowed(context)) armAlarm(context)
  }

  /** One window straight away, on joining an approved wifi while the switch is on. */
  fun runSoon(context: Context) = SyncJob.runSoon(context)

  /** Stops the windows: until an approved wifi is joined again, or until the switch is on again. */
  fun cancel(context: Context) {
    SyncJob.retireOldJobs(context)
    cancelAlarm(context)
    SyncJob.cancelSoon(context)
  }

  /** Whether this phone allows the app's exact alarms. Always, before Android 12. */
  fun alarmsAllowed(context: Context): Boolean =
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
   * Disarms the alarm *and* cancels its PendingIntent, which outlives the alarm that fired it, so
   * nothing of the schedule is left behind. Looked up rather than made, so with no alarm ever armed
   * this costs nothing.
   */
  private fun cancelAlarm(context: Context) {
    val pending = existingAlarmIntent(context) ?: return
    context.getSystemService(AlarmManager::class.java)?.cancel(pending)
    pending.cancel()
  }

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
 * The grant arrives with the app possibly closed, from system settings. If the switch now reads on
 * and the phone is on an approved wifi, the next quarter hour is armed there and then; if not,
 * joining one, or opening the app, picks it up. There is no broadcast for the revoke.
 */
class SyncAlarm : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext
    if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
      WifiWatch.follow(this, app, { AndroidNetworkMonitor(app).current() }, soon = false)
      return
    }

    BackgroundSync.armNext(app)
    val started = runCatching { ContextCompat.startForegroundService(app, Intent(app, SyncWindowService::class.java)) }
    // Refused — a vendor's battery manager, say. A job is the next best thing: later and unaligned,
    // but the change still gets its chance.
    if (started.isFailure) BackgroundSync.runSoon(app)
  }
}
