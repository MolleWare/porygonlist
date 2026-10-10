package io.github.molleware.porygonlist.data.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.molleware.porygonlist.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The quarter-hour window, held open as a foreground service for its thirty seconds.
 *
 * An exact alarm on its own wakes the phone, but in Doze it only lifts the network restriction for a
 * few seconds — not long enough to find a peer, resolve it and exchange. A foreground service keeps
 * it for as long as the service runs, and the alarm firing is what allows starting one from closed.
 *
 * A short service (Android 14 and later) because that is what this is: it asks for no further
 * permission and Android stops it after three minutes regardless. The price is a notification while
 * it runs, kept as quiet as Android allows — minimum importance, no sound, no status-bar icon. If the
 * owner never allowed notifications it is not shown at all, and the window runs just the same.
 */
class SyncWindowService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var running: Job? = null
  private var window: SyncWindow? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Foreground first, unconditionally: Android expects it within seconds of the start, even when
    // this start turns out to be a second alarm arriving during a window already running.
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)

    if (running?.isActive != true) {
      val opened = SyncWindow(applicationContext).also { window = it }
      running =
        scope.launch {
          try {
            opened.run()
          } finally {
            stopSelf()
          }
        }
    }
    // Not restarted if killed: the next quarter hour is the retry.
    return START_NOT_STICKY
  }

  /** A short service past its time. Never expected after thirty seconds, but it must end promptly. */
  override fun onTimeout(startId: Int) = stopSelf()

  override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

  override fun onDestroy() {
    scope.cancel()
    window?.close()
    super.onDestroy()
  }

  private fun notification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager?.getNotificationChannel(CHANNEL) == null) {
      manager?.createNotificationChannel(
        NotificationChannel(CHANNEL, "Background sync", NotificationManager.IMPORTANCE_MIN).apply {
          description = "Shown for the few seconds a closed app spends handing changes to your paired phones."
          setShowBadge(false)
        }
      )
    }
    return NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_launcher_monochrome)
      .setContentTitle("Syncing lists")
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setSilent(true)
      .setOngoing(true)
      .build()
  }

  private companion object {
    const val CHANNEL = "sync"
    const val NOTIFICATION_ID = 0x5061
  }
}
