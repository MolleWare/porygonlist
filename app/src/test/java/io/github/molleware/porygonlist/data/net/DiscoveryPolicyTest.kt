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
