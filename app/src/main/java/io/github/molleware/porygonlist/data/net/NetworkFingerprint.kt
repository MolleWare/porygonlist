package io.github.molleware.porygonlist.data.net

import java.security.MessageDigest

/**
 * A stable-ish handle for "the network this phone is on", derived from what the system will tell us
 * without asking for a single permission.
 *
 * **This is not a security boundary and must never be used as one.** It is spoofable, it collides,
 * and a network that matches is still a hostile place. Its only job is to answer "is it worth
 * looking for peers here", so that the app is not broadcasting its presence all day on an office or
 * café network. Whether the peer it then finds is really Ava is settled by keys, not by this.
 *
 * Built from the gateway, the subnet and the DNS servers, because those are readable from
 * `LinkProperties` with no permission at all. The SSID and BSSID would be better identifiers and
 * cost `ACCESS_FINE_LOCATION` on Android 10 and up, which is not a trade worth making for a value
 * that is only a hint.
 *
 * Collisions are real and expected: two homes both running the stock `192.168.1.1` with the router
 * as DNS will fingerprint the same. The consequence is bounded — discovery starts somewhere it was
 * not meant to, leaking presence but no list data, and the connection still fails authentication.
 */
@JvmInline
value class NetworkFingerprint(val value: String) {

  /** Short form for a label when the user has not named the network — "a3f91c". */
  val short: String
    get() = value.take(6)

  override fun toString() = value

  companion object {
    /**
     * Hashes the parts of a link that stay put across reconnections.
     *
     * The phone's own address is deliberately excluded: DHCP hands out a different one each lease,
     * and a fingerprint that changes when you rejoin your own wifi is useless. The subnet prefix
     * that address sits in does stay put, so that is what goes in.
     *
     * Returns null when there is nothing to go on — no gateway means no way to tell this network
     * from any other, and callers must treat that as "cannot identify", never as a match.
     */
    fun of(gateways: List<String>, subnets: List<String>, dnsServers: List<String>): NetworkFingerprint? {
      if (gateways.isEmpty() && subnets.isEmpty()) return null

      // Sorted so that the order the system happens to report routes in cannot change the answer.
      val material =
        buildString {
          append(gateways.sorted().joinToString(","))
          append('|')
          append(subnets.sorted().joinToString(","))
          append('|')
          append(dnsServers.sorted().joinToString(","))
        }

      val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
      return NetworkFingerprint(digest.take(6).joinToString("") { "%02x".format(it) })
    }
  }
}
