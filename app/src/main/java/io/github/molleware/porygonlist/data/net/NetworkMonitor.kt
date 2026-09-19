package io.github.molleware.porygonlist.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reports the link this phone is on, as it changes. */
interface NetworkMonitor {
  val snapshots: Flow<NetworkSnapshot>

  /**
   * The name the wifi gives itself, or null when the phone will not say.
   *
   * Pull rather than push, and deliberately so. The value depends on a permission that can be
   * granted while the app is in the foreground, and granting one does not make a network callback
   * fire again — so a name pushed through [snapshots] would stay stale until the network changed,
   * which is precisely the moment someone is watching for it to appear. Callers read it when they
   * need it and re-read it after asking for the permission.
   *
   * Never an identity. See [WifiName].
   */
  fun currentWifiName(): String?
}

/**
 * Watches the default network through [ConnectivityManager].
 *
 * Needs only `ACCESS_NETWORK_STATE`, which is a normal permission: granted at install, no prompt,
 * nothing for F-Droid to flag. Everything the fingerprint is built from comes out of
 * [LinkProperties], which is readable without asking for location.
 *
 * Not unit-tested — it is a thin adapter onto framework types, and the decision it feeds is pure
 * and tested on its own.
 */
class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {

  private val connectivity =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  override val snapshots: Flow<NetworkSnapshot> =
    callbackFlow {
        // Capabilities and link properties arrive in separate callbacks, so the latest of each is
        // held and a snapshot is emitted whenever either moves.
        var capabilities: NetworkCapabilities? = null
        var link: LinkProperties? = null

        fun emit() {
          trySend(snapshotOf(capabilities, link))
        }

        val callback =
          object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
              capabilities = caps
              emit()
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
              link = properties
              emit()
            }

            override fun onLost(network: Network) {
              capabilities = null
              link = null
              emit()
            }
          }

        // Registering can throw if the process is being torn down; a monitor that cannot start
        // simply reports being offline, which holds discovery — the safe direction.
        val registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
        if (!registered) trySend(NetworkSnapshot.Offline)

        awaitClose { if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) } }
      }
      .distinctUntilChanged()

  /**
   * Reads the SSID off the wifi network, whatever the default network happens to be.
   *
   * Asked of the *wifi* network rather than the default one because of VPNs: with a tunnel up the
   * default network is the tunnel, and its capabilities carry no `WifiInfo` at all — so the name
   * would come back empty exactly for the people running a VPN. `NOT_VPN` keeps this to the real
   * link.
   *
   * `transportInfo` carries the `WifiInfo` from Android 10; below that the only route is
   * `WifiManager`, and this app's floor is API 26, so the older path stays. Either way the value is
   * redacted to `<unknown ssid>` unless `ACCESS_FINE_LOCATION` is granted **and** location services
   * are switched on — [WifiName.clean] turns both into null.
   */
  @Suppress("DEPRECATION")
  override fun currentWifiName(): String? =
    runCatching {
        val raw =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifi =
              connectivity.allNetworks.firstOrNull { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps != null &&
                  caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                  caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
              }
            (wifi?.let { connectivity.getNetworkCapabilities(it) }?.transportInfo as? WifiInfo)?.ssid
          } else {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            manager?.connectionInfo?.ssid
          }
        WifiName.clean(raw)
      }
      .getOrNull()

  private fun snapshotOf(capabilities: NetworkCapabilities?, link: LinkProperties?): NetworkSnapshot {
    if (capabilities == null) return NetworkSnapshot.Offline

    return NetworkSnapshot(
      fingerprint = link?.let { fingerprintOf(it) },
      isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
      // VALIDATED means the link actually works, rather than being mid-handshake or held by a
      // captive portal. Discovering behind a portal would be packets into a wall.
      isUsable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
      // NOT_VPN is absent exactly when this *is* a VPN. Free of any permission — it comes off the
      // same capabilities the wifi check already reads.
      isVpn = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
    )
  }

  /**
   * Builds the fingerprint from the routing table.
   *
   * IPv6 is skipped: privacy-extension addresses rotate by design, so including them would make the
   * fingerprint change under its own feet. The IPv4 gateway and subnet on a home network stay put.
   */
  private fun fingerprintOf(link: LinkProperties): NetworkFingerprint? {
    val gateways =
      link.routes.mapNotNull { route ->
        route.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddressOrNull()
      }

    val subnets =
      link.linkAddresses.mapNotNull { address ->
        address.address.takeIf { it is Inet4Address }?.let { "${maskOf(it, address.prefixLength)}/${address.prefixLength}" }
      }

    val dns = link.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddressOrNull() }

    return NetworkFingerprint.of(gateways, subnets, dns)
  }

  /** The network part of an address, so a new DHCP lease does not change the fingerprint. */
  private fun maskOf(address: InetAddress, prefixLength: Int): String {
    val bytes = address.address
    for (i in bytes.indices) {
      val bitsHere = prefixLength - i * 8
      bytes[i] =
        when {
          bitsHere >= 8 -> bytes[i]
          bitsHere <= 0 -> 0
          else -> (bytes[i].toInt() and (0xFF shl (8 - bitsHere))).toByte()
        }
    }
    return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
  }

  private fun InetAddress.hostAddressOrNull(): String? = hostAddress
}
