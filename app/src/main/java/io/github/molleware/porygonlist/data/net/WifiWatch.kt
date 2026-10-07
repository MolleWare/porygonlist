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

/** What the background sync should do, given where the phone is and who it knows. */
enum class BackgroundPlan {
  /** On an approved wifi with somebody to sync with: keep the quarter-hour windows coming. */
  RUN,

  /** On a wifi nobody approved, or with nobody to sync with: stop waking at all. */
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
 * the foreground would stay quiet.
 */
fun backgroundPlan(snapshot: NetworkSnapshot, state: AppState): BackgroundPlan {
  if (state.peers.isEmpty()) return BackgroundPlan.STOP
  return when (val decision = discoveryDecision(snapshot, state.approvedFingerprints)) {
    is DiscoveryDecision.Discover -> BackgroundPlan.RUN
    is DiscoveryDecision.Hold ->
      if (decision.reason == HoldReason.NOT_APPROVED) BackgroundPlan.STOP else BackgroundPlan.LEAVE
  }
}

/**
 * Hears about wifi being joined, with the app closed, and turns the background windows on or off.
 *
 * This is what keeps the quarter-hour windows to known wifi only. The job scheduler can wait for
 * "unmetered", but it cannot tell home from a café, so left to itself the job wakes every quarter
 * hour on any wifi just to find out it should not be there. Here the question is asked once, on
 * arrival: an approved network starts the windows — with one straight away, because getting home is
 * exactly when there is something to hand over — and any other network stops them.
 *
 * Android delivers the join through a [PendingIntent] it holds on the app's behalf, so nothing has
 * to be running. Those registrations do not survive a reboot, which is why this also listens for
 * the boot and re-registers. The job is persisted across the reboot anyway, and stops itself if it
 * wakes somewhere it should not be.
 */
class WifiWatch : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      register(app)
      return
    }

    // A network was joined. Reading the state is a file read, so it is done off the main thread,
    // inside the time a receiver is given.
    val network: Network? =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
      else @Suppress("DEPRECATION") intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
    val pending = goAsync()
    scope.launch {
      try {
        val repo = Graph.listRepository(app)
        repo.load()
        val state = repo.state.filterNotNull().first()
        when (backgroundPlan(AndroidNetworkMonitor(app).current(network), state)) {
          BackgroundPlan.RUN -> {
            SyncJob.ensureScheduled(app)
            SyncJob.runSoon(app)
          }
          BackgroundPlan.STOP -> SyncJob.cancel(app)
          BackgroundPlan.LEAVE -> Unit
        }
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Asks Android to tell [WifiWatch] whenever a wifi is joined. Safe to call repeatedly: the same
     * PendingIntent replaces the earlier registration rather than adding a second.
     */
    fun register(context: Context) {
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
      runCatching { connectivity.registerNetworkCallback(request, intent) }
    }
  }
}
