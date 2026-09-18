package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val STRANGER = DeviceId("STRANGERAAAAAAAA")

/**
 * A phone, for the purposes of an exchange: its state and its clock, which move together.
 *
 * Two of these are enough to play out a whole sync without a socket, which is the point — the part
 * that can lose data is the part that does not need a network to test.
 */
private class Phone(val device: DeviceId, wall: Long, items: List<GroceryItem>, people: List<Person>) {
  val clock = HybridClock(device, now = { wall })
  var state =
    AppState(
      localDevice = device,
      idCounter = 100,
      clockHead = Hlc(wall, 0, device),
      lists = listOf(GroceryList(1, "Weekly shop", ListAccent.ACCENT, items, people)),
      activeListId = 1,
      online = true,
      networks = emptyList(),
      peers = people.filterNot { it.device == device }.map { TrustedPeer(it.device, byteArrayOf(1), it.name, 0) },
      conflict = null,
    )

  val list: GroceryList
    get() = state.lists.first()

  /** Hands everything to [other] and takes the receipt back, exactly as the transport will. */
  fun syncTo(other: Phone): SyncResult {
    val payload = state.payloadFor(other.device, clock) ?: error("nothing shared with ${other.device}")
    // Over the wire and back, so the codec is on the path rather than assumed away.
    val received = SyncCodec.decode(SyncCodec.encode(payload))!!
    val result = other.state.receive(received, other.clock)
    if (result is SyncResult.Merged) {
      other.state = result.state
      state = state.confirmDelivery(other.device, result.receipt)
    }
    return result
  }
}

private fun item(id: String, name: String, at: Hlc, qty: Int = 1) =
  GroceryItem(
    id = ItemId(id),
    name = Field(name, at),
    qty = Field(qty, at),
    checkedAt = at,
    removed = Field(false, at),
  )

private val pair = listOf(Person(AVA, "Ava", "A"), Person(HUGO, "Hugo", "H"))

class SyncPayloadTest {

  @Test
  fun `a payload round trips through the wire format`() {
    val payload =
      SyncPayload(
        from = AVA,
        at = Hlc(5_000, 2, AVA),
        lists =
          listOf(
            GroceryList(1, "Weekly shop", ListAccent.ACCENT, listOf(item("${AVA.value}:1", "Butter", Hlc(1_000, 0, AVA))), pair)
          ),
      )

    val decoded = SyncCodec.decode(SyncCodec.encode(payload))!!

    assertEquals(payload.from, decoded.from)
    assertEquals(payload.at, decoded.at)
    assertEquals(payload.lists.single().items, decoded.lists.single().items)
    assertEquals(payload.lists.single().people, decoded.lists.single().people)
  }

  @Test
  fun `a payload carries no private business of the sender`() {
    val phone = Phone(AVA, 1_000, listOf(item("${AVA.value}:1", "Butter", Hlc(1_000, 0, AVA))), pair)
    val encoded = SyncCodec.encode(phone.state.payloadFor(HUGO, phone.clock)!!)

    // Networks, peers, receipts and the id counter are this phone's own business. A peer needs the
    // groceries, not a list of every wifi you frequent or everyone you have ever paired with.
    assertFalse(encoded.contains("net|"))
    assertFalse(encoded.contains("peer|"))
    assertFalse(encoded.contains("receipt|"))
    assertFalse(encoded.contains("meta|"))
  }

  @Test
  fun `malformed payloads are discarded rather than half-applied`() {
    assertNull(SyncCodec.decode(""))
    assertNull(SyncCodec.decode("PLSYNC9\nfrom|x|1-0-x"))
    // No sender, so nobody to answer and nothing to confirm.
    assertNull(SyncCodec.decode("PLSYNC1\nlist|1|Weekly shop|ACCENT"))
  }

  @Test
  fun `only lists the peer is on are sent`() {
    val phone = Phone(AVA, 1_000, emptyList(), pair)
    phone.state =
      phone.state.copy(
        lists =
          phone.state.lists +
            GroceryList(2, "Private", ListAccent.NEUTRAL, emptyList(), listOf(Person(AVA, "Ava", "A")))
      )

    val payload = phone.state.payloadFor(HUGO, phone.clock)!!

    assertEquals(listOf(1L), payload.lists.map { it.id })
  }

  @Test
  fun `there is nothing to send to someone who shares no list`() {
    val phone = Phone(AVA, 1_000, emptyList(), listOf(Person(AVA, "Ava", "A")))

    assertNull(phone.state.payloadFor(HUGO, phone.clock))
    assertNull("a phone does not sync with itself", phone.state.payloadFor(AVA, phone.clock))
  }
}

class SyncSessionTest {

  @Test
  fun `each phone ends up with both additions`() {
    val ava = Phone(AVA, 1_000, listOf(item("${AVA.value}:1", "Butter", Hlc(1_000, 0, AVA))), pair)
    val hugo = Phone(HUGO, 1_100, listOf(item("${HUGO.value}:1", "Coffee", Hlc(1_100, 0, HUGO))), pair)

    ava.syncTo(hugo)
    hugo.syncTo(ava)

    assertEquals(setOf("Butter", "Coffee"), ava.list.liveItems.map { it.name.value }.toSet())
    assertEquals(setOf("Butter", "Coffee"), hugo.list.liveItems.map { it.name.value }.toSet())
  }

  @Test
  fun `a second exchange changes nothing`() {
    // If a quiet sync still moved something, the two phones would never settle.
    val ava = Phone(AVA, 1_000, listOf(item("${AVA.value}:1", "Butter", Hlc(1_000, 0, AVA))), pair)
    val hugo = Phone(HUGO, 1_100, listOf(item("${HUGO.value}:1", "Coffee", Hlc(1_100, 0, HUGO))), pair)

    ava.syncTo(hugo)
    hugo.syncTo(ava)
    val settled = ava.list.items

    ava.syncTo(hugo)
    hugo.syncTo(ava)

    assertEquals(settled, ava.list.items)
  }

  @Test
  fun `a stranger is refused`() {
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val payload = SyncPayload(from = STRANGER, at = Hlc(9_000, 0, STRANGER), lists = listOf(ava.list))

    val result = ava.state.receive(payload, ava.clock)

    assertEquals(SyncResult.Rejected(RejectReason.UNKNOWN_PEER), result)
  }

  @Test
  fun `our own words played back at us are refused`() {
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val payload = SyncPayload(from = AVA, at = Hlc(9_000, 0, AVA), lists = listOf(ava.list))

    assertEquals(SyncResult.Rejected(RejectReason.SELF), ava.state.receive(payload, ava.clock))
  }

  @Test
  fun `a peer cannot introduce a list by sending one`() {
    // Joining a list happens by invitation, not by assertion.
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val smuggled = GroceryList(99, "Not yours", ListAccent.NEUTRAL, emptyList(), pair)
    val payload = SyncPayload(from = HUGO, at = Hlc(9_000, 0, HUGO), lists = listOf(smuggled))

    val result = ava.state.receive(payload, ava.clock)

    assertEquals(SyncResult.Rejected(RejectReason.NOTHING_SHARED), result)
    assertEquals(listOf(1L), ava.state.lists.map { it.id })
  }

  @Test
  fun `a removal reaches the other phone`() {
    val milk = item("${AVA.value}:1", "Oat milk", Hlc(1_000, 0, AVA))
    val ava = Phone(AVA, 2_000, listOf(milk), pair)
    val hugo = Phone(HUGO, 2_000, listOf(milk), pair)

    ava.state =
      ava.state.copy(
        lists =
          ava.state.lists.map { l ->
            l.copy(items = l.items.map { it.copy(removed = it.removed.set(true, ava.clock.tick())) })
          }
      )
    ava.syncTo(hugo)

    assertTrue("it must actually go from Hugo's list", hugo.list.liveItems.isEmpty())
    assertEquals("and the tombstone stays, to pass on", 1, hugo.list.items.size)
  }

  @Test
  fun `a delivered removal is collected once the receipt lands`() {
    val milk = item("${AVA.value}:1", "Oat milk", Hlc(1_000, 0, AVA))
    val ava = Phone(AVA, 2_000, listOf(milk), pair)
    val hugo = Phone(HUGO, 2_000, listOf(milk), pair)

    ava.state =
      ava.state.copy(
        lists =
          ava.state.lists.map { l ->
            l.copy(items = l.items.map { it.copy(removed = it.removed.set(true, ava.clock.tick())) })
          }
      )

    assertEquals("held until there is proof", 1, ava.list.items.size)
    ava.syncTo(hugo)
    assertTrue("the echo is the proof", ava.list.items.isEmpty())
  }

  @Test
  fun `an item deleted here does not come back from a peer who still has it`() {
    // The resurrection the whole tombstone design exists to prevent.
    val milk = item("${AVA.value}:1", "Oat milk", Hlc(1_000, 0, AVA))
    val ava = Phone(AVA, 2_000, listOf(milk), pair)
    val hugo = Phone(HUGO, 2_000, listOf(milk), pair)

    ava.state =
      ava.state.copy(
        lists =
          ava.state.lists.map { l ->
            l.copy(items = l.items.map { it.copy(removed = it.removed.set(true, ava.clock.tick())) })
          }
      )

    // Hugo, who has not heard yet, speaks first and offers his live copy back.
    hugo.syncTo(ava)

    assertTrue("Ava's removal is newer and informed", ava.list.liveItems.isEmpty())
  }

  @Test
  fun `concurrent edits to different fields both survive an exchange`() {
    val milk = item("${AVA.value}:1", "Oat milk", Hlc(1_000, 0, AVA))
    val ava = Phone(AVA, 2_000, listOf(milk), pair)
    val hugo = Phone(HUGO, 2_000, listOf(milk), pair)

    ava.state =
      ava.state.copy(
        lists = ava.state.lists.map { l ->
          l.copy(items = l.items.map { it.copy(name = it.name.set("Oat milk barista", ava.clock.tick())) })
        }
      )
    hugo.state =
      hugo.state.copy(
        lists = hugo.state.lists.map { l ->
          l.copy(items = l.items.map { it.copy(qty = it.qty.set(3, hugo.clock.tick())) })
        }
      )

    ava.syncTo(hugo)
    hugo.syncTo(ava)

    assertEquals("Oat milk barista", ava.list.items.single().name.value)
    assertEquals(3, ava.list.items.single().qty.value)
  }

  @Test
  fun `a blind clash is reported to the caller`() {
    val milk = item("${AVA.value}:1", "Oat milk", Hlc(1_000, 0, AVA))
    val ava = Phone(AVA, 2_000, listOf(milk), pair)
    val hugo = Phone(HUGO, 2_000, listOf(milk), pair)

    ava.state =
      ava.state.copy(
        lists = ava.state.lists.map { l ->
          l.copy(items = l.items.map { it.copy(name = it.name.set("Whole milk", ava.clock.tick())) })
        }
      )
    hugo.state =
      hugo.state.copy(
        lists = hugo.state.lists.map { l ->
          l.copy(items = l.items.map { it.copy(name = it.name.set("Semi-skimmed", hugo.clock.tick())) })
        }
      )

    val result = ava.syncTo(hugo) as SyncResult.Merged

    assertEquals(setOf(ItemField.NAME), result.clashes.single().concurrent)
  }

  @Test
  fun `receiving folds the peer's clock in so the next local edit outranks it`() {
    // Without this a phone running fast would win every merge for ever.
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val hugo = Phone(HUGO, 9_000_000, listOf(item("${HUGO.value}:1", "Coffee", Hlc(9_000_000, 0, HUGO))), pair)

    hugo.syncTo(ava)

    assertTrue(ava.clock.head() >= Hlc(9_000_000, 0, HUGO))
    assertTrue("a later local edit must beat what just arrived", ava.clock.tick() > Hlc(9_000_000, 0, HUGO))
  }

  @Test
  fun `the receipt is the sender's own stamp, echoed untouched`() {
    val ava = Phone(AVA, 5_000, emptyList(), pair)
    val hugo = Phone(HUGO, 1_000, emptyList(), pair)
    val sent = ava.state.payloadFor(HUGO, ava.clock)!!

    val result = hugo.state.receive(sent, hugo.clock) as SyncResult.Merged

    assertEquals(sent.at, result.receipt)
    assertNotNull(ava.state.confirmDelivery(HUGO, result.receipt).deliveredTo.confirmedBy(HUGO))
  }

  @Test
  fun `a peer who replaced their phone is reconciled, not duplicated`() {
    val hugoNew = DeviceId("HUGONEWAAAAAAAAA")
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val hugo = Phone(HUGO, 1_000, emptyList(), pair)
    hugo.state =
      hugo.state.copy(
        lists = hugo.state.lists.map { it.replaceDevice(HUGO, hugoNew) },
        peers = hugo.state.peers,
      )

    hugo.state = hugo.state.copy(localDevice = HUGO) // he still speaks as the paired device here
    hugo.syncTo(ava)

    assertEquals(1, ava.list.people.count { it.name == "Hugo" })
    assertEquals(hugoNew, ava.list.people.single { it.name == "Hugo" }.device)
    assertFalse("the retired handset must stop being waited on", HUGO in ava.list.peersOf(AVA))
  }
}
