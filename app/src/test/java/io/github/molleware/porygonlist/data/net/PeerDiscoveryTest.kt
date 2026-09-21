package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.sync.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDiscoveryTest {

  private val me = DeviceId("MEMEMEMEMEMEMEME")
  private val ava = DeviceId("AVAAVAAVAAVAAVAA")
  private val stranger = DeviceId("XXXXXXXXXXXXXXXX")

  private fun peer(id: DeviceId, name: String) = TrustedPeer(id, byteArrayOf(1, 2, 3), name, pairedAt = 0)

  @Test
  fun `a paired phone on the network is reachable`() {
    val found = listOf(ServiceRecord(ava, "192.168.1.42", 8931))

    val reachable = reachablePeers(found, listOf(peer(ava, "Ava")), me)

    assertEquals(1, reachable.size)
    assertEquals("Ava", reachable.single().peer.name)
    assertEquals("192.168.1.42", reachable.single().host)
    assertEquals(8931, reachable.single().port)
  }

  @Test
  fun `this phone's own advertisement is not a peer`() {
    // A browse hears its own responder. Left in, this device would open a connection to itself and
    // then sit waiting for its own delivery receipt.
    val found = listOf(ServiceRecord(me, "192.168.1.7", 8931))

    assertTrue(reachablePeers(found, listOf(peer(me, "Me")), me).isEmpty())
  }

  @Test
  fun `an unpaired phone advertising the same service is ignored`() {
    // Not an error and not worth surfacing: someone else on the wifi has the app. The point is
    // that nothing downstream is ever handed an address for a phone we hold no key for.
    val found = listOf(ServiceRecord(stranger, "192.168.1.99", 8931))

    assertTrue(reachablePeers(found, listOf(peer(ava, "Ava")), me).isEmpty())
  }

  @Test
  fun `a peer advertising on two interfaces is reported once`() {
    // Wifi and a second address for the same phone, or a stale record beside a fresh one. Two
    // connections to one peer would both be told the same news and both claim the same receipt.
    val found =
      listOf(ServiceRecord(ava, "192.168.1.42", 8931), ServiceRecord(ava, "192.168.1.43", 8931))

    assertEquals(1, reachablePeers(found, listOf(peer(ava, "Ava")), me).size)
  }

  @Test
  fun `the pinned key travels with the address`() {
    // The address came off the wire and is worth nothing on its own. What makes the connection
    // that follows checkable is the key from the paired record, carried through here.
    val key = byteArrayOf(9, 8, 7)
    val found = listOf(ServiceRecord(ava, "192.168.1.42", 8931))

    val reachable = reachablePeers(found, listOf(TrustedPeer(ava, key, "Ava", 0)), me)

    assertTrue(key.contentEquals(reachable.single().peer.publicKey))
  }

  @Test
  fun `nothing found is nobody reachable`() {
    assertTrue(reachablePeers(emptyList(), listOf(peer(ava, "Ava")), me).isEmpty())
  }

  @Test
  fun `a phone with no pairings reaches nobody`() {
    val found = listOf(ServiceRecord(ava, "192.168.1.42", 8931))

    assertTrue(reachablePeers(found, emptyList(), me).isEmpty())
  }
}
