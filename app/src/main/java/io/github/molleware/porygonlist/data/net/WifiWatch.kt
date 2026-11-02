package io.github.molleware.porygonlist.data.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.github.molleware.porygonlist.Graph
import io.github.molleware.porygonlist.data.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What the background sync should do, given where the phone is, who it knows, and the switch. */
enum class BackgroundPlan {
  /** On an approved wifi with somebody to sync with, and the switch on: keep the windows coming. */
  RUN,

  /** On a wifi nobody approved, with nobody to sync with, or with the switch off: stop waking at all. */
  STOP,

  /**
   * Not enough to go on — offline, a link still settling, a VPN in the way. Leave the schedule as
   * it is: stopping here would mean the next window never comes, because the event that would
   * restart it, joining the wifi, has already happened.
   */
  LEAVE,
}

/**
 * Decides [BackgroundPlan] from a network and the state. Pure, so it is tested off the device.
 *
 * Built on the same [discoveryDecision] the open app uses, so the background never runs anywhere
 * the foreground would stay quiet. The switch is read with [alarmsAllowed] as Android says now, so a
 * revoke nobody was told about stops the schedule rather than leaving it to a job.
 */
fun backgroundPlan(snapshot: NetworkSnapshot, state: AppState, alarmsAllowed: Boolean): BackgroundPlan {
  if (state.peers.isEmpty()) return BackgroundPlan.STOP
  if (backgroundSwitch(state.backgroundSync, alarmsAllowed) != BackgroundSwitch.ON) return BackgroundPlan.STOP
  return when (val decision = discoveryDecision(snapshot, state.approvedFingerprints)) {
    is DiscoveryDecision.Discover -> BackgroundPlan.RUN
    is DiscoveryDecision.Hold ->
      if (decision.reason == HoldReason.NOT_APPROVED) BackgroundPlan.STOP else BackgroundPlan.LEAVE
  }
}

/**
 * Hears about wifi being joined, with the app closed, and turns the background windows on or off.
 *
 * This is what keeps the quarter-hour windows to known wifi only. An alarm cannot tell home from a
 * café, so left to itself it wakes the phone every quarter hour on any wifi just to find out it
 * should not be there. Here the question is asked once, on arrival: an approved network starts the
 * windows — with one straight away, because getting home is exactly when there is something to hand
 * over — and any other network stops them. With the switch off, every network stops them.
 *
 * Android delivers the join through a [PendingIntent] it holds on the app's behalf, so nothing has
 * to be running. Those registrations do not survive a reboot, which is why this also listens for
 * the boot and re-registers. Alarms do not survive one either, so the alarm is re-armed by the join
 * that follows, since a phone boots before its wifi is up.
 */
class WifiWatch : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      register(app)
      return
    }

    // A network was joined.
    val network: Network? =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
      else @Suppress("DEPRECATION") intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
    follow(this, app, { AndroidNetworkMonitor(app).current(network) }, soon = true)
  }

  companion object {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Does what [backgroundPlan] says for the network [snapshot] reads, from [receiver]'s
     * [BroadcastReceiver.onReceive]: the schedule on or off and, with [soon], one window straight away.
     *
     * Reading the state is a file read, so it is done off the main thread, inside the time a
     * receiver is given.
     */
    fun follow(receiver: BroadcastReceiver, app: Context, snapshot: () -> NetworkSnapshot, soon: Boolean) {
      val pending = receiver.goAsync()
      scope.launch {
        try {
          val repo = Graph.listRepository(app)
          repo.load()
          val state = repo.state.filterNotNull().first()
          when (backgroundPlan(snapshot(), state, BackgroundSync.alarmsAllowed(app))) {
            BackgroundPlan.RUN -> {
              BackgroundSync.ensureScheduled(app, state.backgroundSync)
              // Not while a window is under way: it is doing what one soon would, and this is very
              // likely its own registration firing for the wifi already joined. Booked anyway, Doze
              // held it and ran it as a second window at the next maintenance slot (Pixel, 22:16,
              // 2026-10-24) — after every window that started a fresh process.
              if (soon && !SyncWindow.isRunning) BackgroundSync.runSoon(app)
            }
            BackgroundPlan.STOP -> BackgroundSync.cancel(app)
            BackgroundPlan.LEAVE -> Unit
          }
        } finally {
          pending.finish()
        }
      }
    }

    /** Whether this process has registered already. */
    private val registered = AtomicBoolean(false)

    /**
     * Asks Android to tell [WifiWatch] whenever a wifi is joined. Once per process: the same
     * PendingIntent would replace the earlier registration rather than add a second, but every
     * registration fires at once for the wifi already joined, and that asks for a window. With each
     * window registering again, that was a loop — 2,600 broadcasts in two minutes on the Pixel,
     * 2026-10-24, each "sync now" cancelling the window it replaced. A new process, after a reboot
     * or a kill, still registers on the first call.
     */
    fun register(context: Context) {
      if (!registered.compareAndSet(false, true)) return
      val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
      val request =
        NetworkRequest.Builder()
          .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
          .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
          // Told once the link works, not the moment it associates: before validation it reads as
          // offline, which would leave the plan undecided on exactly the event meant to decide it.
          .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
          .build()
      // Mutable because the system writes the joined network into the intent as an extra.
      val flags =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
      val intent = PendingIntent.getBroadcast(context, 0, Intent(context, WifiWatch::class.java), flags)
      // A refusal leaves the next call free to try again.
      runCatching { connectivity.registerNetworkCallback(request, intent) }.onFailure { registered.set(false) }
    }
  }
}
