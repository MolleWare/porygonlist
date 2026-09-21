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
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reports the link this phone is on, as it changes. */
interface NetworkMonitor {
  val snapshots: Flow<NetworkSnapshot>

  /**
   * The name the wifi gives itself, or null when the phone will not say.
   *
   * A flow rather than a getter because of how Android hands this over. From API 31 a
   * `WifiInfo` read through `getNetworkCapabilities` comes back with its location-sensitive
   * fields **redacted regardless of permission** — an unredacted one arrives only through a
   * registered `NetworkCallback`. So there is nothing to return synchronously, and the value
   * turns up shortly after [refreshWifiName] asks for it.
   *
   * Never an identity. See [WifiName].
   */
  val wifiName: StateFlow<String?>

  /**
   * Starts watching for the name, or looks again.
   *
   * Called once when something wants the name, and again after the location permission is granted:
   * granting does not make an existing callback re-fire, so the registration is torn down and
   * remade to force a fresh, unredacted one.
   */
  fun refreshWifiName()
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

  private val _wifiName = MutableStateFlow<String?>(null)
  override val wifiName: StateFlow<String?> = _wifiName.asStateFlow()

  private var registered: ConnectivityManager.NetworkCallback? = null

  /**
   * Registers — or re-registers — the callback that carries the name.
   *
   * The re-registration is the point. `getNetworkCapabilities` redacts the `WifiInfo` from API 31
   * whatever permission is held, so the name can only arrive through a callback; and granting a
   * permission does not make an already-registered callback fire again. Tearing the registration
   * down and remaking it is what produces a fresh, unredacted one the moment somebody says yes.
   *
   * Asked of the *wifi* network rather than the default one because of VPNs: with a tunnel up the
   * default network is the tunnel, whose capabilities carry no `WifiInfo` at all, so the name would
   * go missing for exactly the people running one. `NOT_VPN` keeps this on the real link.
   */
  override fun refreshWifiName() {
    registered?.let { existing -> runCatching { connectivity.unregisterNetworkCallback(existing) } }
    registered = null

    val callback = wifiCallback()
    val request =
      NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    // A name is a nicety, so failing to register is silent: the fingerprint's short form still
    // names the network and nothing else depends on this.
    if (runCatching { connectivity.registerNetworkCallback(request, callback) }.isSuccess) {
      registered = callback
    }
  }

  /**
   * The callback, asking for location-sensitive fields where that has to be asked for.
   *
   * From API 31 holding `ACCESS_FINE_LOCATION` is **not enough on its own**: a `WifiInfo` delivered
   * to a callback built the ordinary way comes back with the SSID as `<unknown ssid>` and the BSSID
   * as `02:00:00:00:00:00`, while every other field — address, signal, link speed — is real. The
   * callback has to be *constructed* with [FLAG_INCLUDE_LOCATION_INFO] to opt into them.
   *
   * That flag arrived in API 31, so below it the plain constructor is the only one there is, and
   * nothing is redacted there anyway.
   */
  private fun wifiCallback(): ConnectivityManager.NetworkCallback =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocatedWifiCallback() else PlainWifiCallback()

  private fun nameChanged(caps: NetworkCapabilities) {
    _wifiName.value = nameFrom(caps)
  }

  private fun nameLost() {
    _wifiName.value = null
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private inner class LocatedWifiCallback :
    ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = nameChanged(caps)

    override fun onLost(network: Network) = nameLost()
  }

  private inner class PlainWifiCallback : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = nameChanged(caps)

    override fun onLost(network: Network) = nameLost()
  }

  /**
   * The SSID out of a wifi network's capabilities.
   *
   * `transportInfo` carries the `WifiInfo` from Android 10; below that the only route is
   * `WifiManager`, and this app's floor is API 26, so the older path stays. Either way the value is
   * `<unknown ssid>` unless `ACCESS_FINE_LOCATION` is granted **and** location services are on —
   * [WifiName.clean] turns both into null.
   */
  @Suppress("DEPRECATION")
  private fun nameFrom(caps: NetworkCapabilities): String? =
    runCatching {
        val raw =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps.transportInfo as? WifiInfo)?.ssid
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
      address = link?.let { addressOn(it) },
    )
  }

  /**
   * This phone's own address on the link, for a pairing invite to name.
   *
   * IPv4 only, and for the same reason the fingerprint skips IPv6: a privacy-extension address
   * rotates by design, so an invite drawn with one could be stale before the other phone reads it.
   * Loopback is excluded because the address is for somebody else to dial.
   */
  private fun addressOn(link: LinkProperties): String? =
    link.linkAddresses
      .map { it.address }
      .filterIsInstance<Inet4Address>()
      .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
      ?.hostAddressOrNull()

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
