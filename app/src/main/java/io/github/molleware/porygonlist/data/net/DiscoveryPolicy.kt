package io.github.molleware.porygonlist.data.net

/**
 * What the system currently says about the link this phone is on.
 *
 * Pure data, with no Android types in it, so the decision that follows can be tested without a
 * device. [AndroidNetworkMonitor] is the only thing that knows how to fill it in.
 */
data class NetworkSnapshot(
  /** Null when the network cannot be told apart from any other — treat as unidentifiable. */
  val fingerprint: NetworkFingerprint?,
  val isWifi: Boolean,
  /** The link is up and usable, as opposed to connecting or sitting behind a captive portal. */
  val isUsable: Boolean,
  /**
   * A VPN is carrying this phone's traffic.
   *
   * Read straight off the link's capabilities, so it costs no permission beyond the one already
   * held. It matters more than it looks: with a tunnel up, [fingerprint] describes the tunnel and
   * not the wifi, so it is the same in every café in the world.
   */
  val isVpn: Boolean = false,
) {
  companion object {
    /** No network at all. */
    val Offline = NetworkSnapshot(fingerprint = null, isWifi = false, isUsable = false)
  }
}

/** Whether to run peer discovery here, and if not, why not. */
sealed interface DiscoveryDecision {
  /** Advertise and look for peers. */
  data object Discover : DiscoveryDecision

  data class Hold(val reason: HoldReason) : DiscoveryDecision
}

enum class HoldReason {
  /** Nothing to join. */
  OFFLINE,

  /**
   * Mobile data, or anything that is not wifi.
   *
   * Peers are not reachable across carrier NAT, so discovery would be packets sent for nothing.
   */
  NOT_WIFI,

  /**
   * A VPN is carrying this phone's traffic.
   *
   * Two separate reasons to stop, either of which would be enough.
   *
   * The identification breaks first. The fingerprint is built from the default network, which *is*
   * the tunnel while a VPN is up — so it describes the VPN and not the wifi. That fingerprint is
   * identical in every café in the world, which means matching it against the approved list would
   * eventually say "this is your home network" somewhere that is not. Holding here is what closes
   * that, and it closes it without needing the fingerprint itself to change.
   *
   * The reachability is the other half. An app cannot send around a VPN unless the VPN itself
   * allows it, so unless the tunnel is configured to let local traffic past, a peer one metre away
   * on the same wifi is not reachable. Some VPNs do allow it and some do not, which is exactly the
   * kind of thing that must not be guessed at: the honest answer is to say a VPN is on and let
   * somebody who wants to sync turn it off.
   */
  VPN,

  /** No gateway to hash, so this network cannot be matched against the approved list. */
  UNIDENTIFIABLE,

  /** A real network, correctly identified, that the owner has not opted into. */
  NOT_APPROVED,
}

/**
 * Decides whether to look for peers on the current link.
 *
 * This is the whole point of the fingerprint: on a work or public network the app should be silent,
 * rather than announcing "a PorygonList device is here" to everyone on the LAN, every few seconds,
 * all day. Approval is opt-in and the unknown case holds — a network nobody has vouched for gets
 * nothing, including an unidentifiable one.
 *
 * A [Discover] answer says only that it is worth looking. It says nothing about whether this is a
 * safe place to be, and grants no trust to anything found here.
 */
fun discoveryDecision(snapshot: NetworkSnapshot, approved: Set<NetworkFingerprint>): DiscoveryDecision =
  when {
    !snapshot.isUsable -> DiscoveryDecision.Hold(HoldReason.OFFLINE)
    // Before the wifi check, not after. A VPN reports the transports of whatever it runs over, so
    // this can be reached with isWifi either way — and "a VPN is on" is the useful thing to be told
    // in both, where "not wifi" would send somebody looking at their router.
    snapshot.isVpn -> DiscoveryDecision.Hold(HoldReason.VPN)
    !snapshot.isWifi -> DiscoveryDecision.Hold(HoldReason.NOT_WIFI)
    snapshot.fingerprint == null -> DiscoveryDecision.Hold(HoldReason.UNIDENTIFIABLE)
    snapshot.fingerprint !in approved -> DiscoveryDecision.Hold(HoldReason.NOT_APPROVED)
    else -> DiscoveryDecision.Discover
  }
