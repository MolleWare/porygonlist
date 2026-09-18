package io.github.molleware.porygonlist.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reports the link this phone is on, as it changes. */
interface NetworkMonitor {
  val snapshots: Flow<NetworkSnapshot>
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
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

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

  private fun snapshotOf(capabilities: NetworkCapabilities?, link: LinkProperties?): NetworkSnapshot {
    if (capabilities == null) return NetworkSnapshot.Offline

    return NetworkSnapshot(
      fingerprint = link?.let { fingerprintOf(it) },
      isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
      // VALIDATED means the link actually works, rather than being mid-handshake or held by a
      // captive portal. Discovering behind a portal would be packets into a wall.
      isUsable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
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
