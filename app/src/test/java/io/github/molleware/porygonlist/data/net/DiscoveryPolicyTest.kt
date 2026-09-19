package io.github.molleware.porygonlist.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkFingerprintTest {

  @Test
  fun `the same link fingerprints the same every time`() {
    val a = NetworkFingerprint.of(listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))
    val b = NetworkFingerprint.of(listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))

    assertEquals(a, b)
  }

  @Test
  fun `the order the system reports routes in does not matter`() {
    val a = NetworkFingerprint.of(listOf("10.0.0.1", "10.0.0.2"), emptyList(), listOf("1.1.1.1", "9.9.9.9"))
    val b = NetworkFingerprint.of(listOf("10.0.0.2", "10.0.0.1"), emptyList(), listOf("9.9.9.9", "1.1.1.1"))

    assertEquals(a, b)
  }

  @Test
  fun `different networks fingerprint differently`() {
    val home = NetworkFingerprint.of(listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))
    val work = NetworkFingerprint.of(listOf("10.20.0.1"), listOf("10.20.0.0/16"), listOf("10.20.0.53"))

    assertNotEquals(home, work)
  }

  @Test
  fun `a link with nothing to go on cannot be fingerprinted`() {
    // Callers must treat null as "cannot identify", never as a match against the approved list.
    assertNull(NetworkFingerprint.of(emptyList(), emptyList(), emptyList()))
    assertNull(NetworkFingerprint.of(emptyList(), emptyList(), listOf("1.1.1.1")))
  }

  @Test
  fun `two homes on stock router defaults collide`() {
    // Documenting the known weakness rather than pretending it away. The blast radius is bounded:
    // discovery starts somewhere unintended, leaking presence but no list data, and the connection
    // still has to authenticate before anything is shared.
    val hers = NetworkFingerprint.of(listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))
    val his = NetworkFingerprint.of(listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))

    assertEquals(hers, his)
  }
}

class DiscoveryPolicyTest {

  private val home = NetworkFingerprint("aaaaaa")
  private val work = NetworkFingerprint("bbbbbb")

  private fun wifi(fingerprint: NetworkFingerprint?) =
    NetworkSnapshot(fingerprint = fingerprint, isWifi = true, isUsable = true)

  @Test
  fun `an approved wifi is worth listening on`() {
    assertEquals(DiscoveryDecision.Discover, discoveryDecision(wifi(home), setOf(home)))
  }

  @Test
  fun `an unapproved wifi stays silent`() {
    // The whole point: no packets all day on the office network.
    assertEquals(DiscoveryDecision.Hold(HoldReason.NOT_APPROVED), discoveryDecision(wifi(work), setOf(home)))
  }

  @Test
  fun `a network that cannot be identified is held, not guessed at`() {
    assertEquals(DiscoveryDecision.Hold(HoldReason.UNIDENTIFIABLE), discoveryDecision(wifi(null), setOf(home)))
  }

  @Test
  fun `a vpn is held even on an approved wifi`() {
    // The tunnel's fingerprint is what gets computed while a VPN is up, and it is the same one
    // everywhere — so a match against the approved list means nothing here.
    val tunnelled = NetworkSnapshot(fingerprint = home, isWifi = true, isUsable = true, isVpn = true)

    assertEquals(DiscoveryDecision.Hold(HoldReason.VPN), discoveryDecision(tunnelled, setOf(home)))
  }

  @Test
  fun `a vpn is reported as a vpn rather than as the wrong network`() {
    // This is the case the ordering exists for. A VPN reports the transports of whatever it runs
    // over, so it can arrive with isWifi false — and saying "not wifi" would send somebody to look
    // at their router when the thing to turn off is the VPN.
    val overCellular = NetworkSnapshot(fingerprint = work, isWifi = false, isUsable = true, isVpn = true)

    assertEquals(DiscoveryDecision.Hold(HoldReason.VPN), discoveryDecision(overCellular, setOf(home)))
  }

  @Test
  fun `a vpn on an unidentifiable network still names the vpn`() {
    val tunnelled = NetworkSnapshot(fingerprint = null, isWifi = true, isUsable = true, isVpn = true)

    assertEquals(DiscoveryDecision.Hold(HoldReason.VPN), discoveryDecision(tunnelled, setOf(home)))
  }

  @Test
  fun `being offline outranks a vpn, because there is nothing to tunnel over`() {
    val nothing = NetworkSnapshot(fingerprint = home, isWifi = true, isUsable = false, isVpn = true)

    assertEquals(DiscoveryDecision.Hold(HoldReason.OFFLINE), discoveryDecision(nothing, setOf(home)))
  }

  @Test
  fun `no vpn is the ordinary case and changes nothing`() {
    assertEquals(DiscoveryDecision.Discover, discoveryDecision(wifi(home), setOf(home)))
  }

  @Test
  fun `mobile data is held even if somehow approved`() {
    val cellular = NetworkSnapshot(fingerprint = home, isWifi = false, isUsable = true)

    assertEquals(DiscoveryDecision.Hold(HoldReason.NOT_WIFI), discoveryDecision(cellular, setOf(home)))
  }

  @Test
  fun `a link that is not usable yet is held`() {
    // Captive portals and half-finished handshakes: packets into a wall.
    val portal = NetworkSnapshot(fingerprint = home, isWifi = true, isUsable = false)

    assertEquals(DiscoveryDecision.Hold(HoldReason.OFFLINE), discoveryDecision(portal, setOf(home)))
    assertEquals(DiscoveryDecision.Hold(HoldReason.OFFLINE), discoveryDecision(NetworkSnapshot.Offline, setOf(home)))
  }

  @Test
  fun `approving nothing discovers nowhere`() {
    assertEquals(DiscoveryDecision.Hold(HoldReason.NOT_APPROVED), discoveryDecision(wifi(home), emptySet()))
  }
}
