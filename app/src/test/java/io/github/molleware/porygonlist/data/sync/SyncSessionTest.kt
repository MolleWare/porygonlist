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
      lists = listOf(GroceryList(ListId("list:1"), "Weekly shop", ListAccent.ACCENT, items, people)),
      activeListId = ListId("list:1"),
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
            GroceryList(
              ListId("list:1"),
              "Weekly shop",
              ListAccent.ACCENT,
              listOf(item("${AVA.value}:1", "Butter", Hlc(1_000, 0, AVA))),
              pair,
            )
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
            GroceryList(ListId("list:2"), "Private", ListAccent.NEUTRAL, emptyList(), listOf(Person(AVA, "Ava", "A")))
      )

    val payload = phone.state.payloadFor(HUGO, phone.clock)!!

    assertEquals(listOf(ListId("list:1")), payload.lists.map { it.id })
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
  fun `a peer cannot introduce a list this phone is not on`() {
    // Joining happens by invitation, not by assertion. A list naming only other people is not an
    // invitation, and a paired phone does not get to use this one as somewhere to keep its lists.
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val notHers =
      GroceryList(ListId("list:99"), "Not yours", ListAccent.NEUTRAL, emptyList(), listOf(Person(HUGO, "Hugo", "H")))
    val payload = SyncPayload(from = HUGO, at = Hlc(9_000, 0, HUGO), lists = listOf(notHers))

    val result = ava.state.receive(payload, ava.clock)

    assertEquals(SyncResult.Rejected(RejectReason.NOTHING_SHARED), result)
    assertEquals(listOf(ListId("list:1")), ava.state.lists.map { it.id })
  }

  @Test
  fun `a list naming this phone arrives whole`() {
    // The invitation case: a paired phone sends a list this one has never seen, with this one on
    // it. That is someone sharing a list, and it is the whole point of the exchange.
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val invited =
      GroceryList(
        ListId("${HUGO.value}:4"),
        "Party, Saturday",
        ListAccent.NEUTRAL,
        listOf(item("${HUGO.value}:9", "Ice", Hlc(2_000, 0, HUGO))),
        pair,
      )
    val payload = SyncPayload(from = HUGO, at = Hlc(9_000, 0, HUGO), lists = listOf(invited))

    val result = ava.state.receive(payload, ava.clock)

    assertTrue("an invitation should be accepted", result is SyncResult.Merged)
    val after = (result as SyncResult.Merged).state
    assertEquals(listOf(ListId("list:1"), ListId("${HUGO.value}:4")), after.lists.map { it.id })
    // Taken whole: there was no local copy to merge against, so the items come across as sent.
    assertEquals(listOf("Ice"), after.lists.last().items.map { it.name.value })
  }

  @Test
  fun `an invitation from a phone that is not paired is refused`() {
    // The pairing check comes first, so being named on a list buys a stranger nothing.
    val ava = Phone(AVA, 1_000, emptyList(), pair)
    val stranger = DeviceId("STRANGERSTRANGER")
    val invited =
      GroceryList(ListId("${stranger.value}:1"), "Free money", ListAccent.NEUTRAL, emptyList(), pair)
    val payload = SyncPayload(from = stranger, at = Hlc(9_000, 0, stranger), lists = listOf(invited))

    assertEquals(SyncResult.Rejected(RejectReason.UNKNOWN_PEER), ava.state.receive(payload, ava.clock))
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

/**
 * The clash a person actually hits: both of them put rice on the list while apart.
 *
 * Until this existed the app could not produce that card at all — the one on screen was a fixture in
 * the seed, and a real handover quietly left two entries called Rice.
 */
class DuplicateOverSyncTest {

  private fun phones(): kotlin.Pair<Phone, Phone> =
    Phone(AVA, 1_000, emptyList(), pair) to Phone(HUGO, 2_000, emptyList(), pair)

  private fun Phone.add(seq: Int, name: String, qty: Int = 1) {
    val stamp = clock.tick()
    state = state.copy(lists = state.lists.map { it.copy(items = it.items + item("${device.value}:$seq", name, stamp, qty)) })
  }

  @Test
  fun `a handover turns two rices into a question`() {
    val (ava, hugo) = phones()
    ava.add(1, "Rice")
    hugo.add(1, "rice", qty = 2)

    val result = ava.syncTo(hugo) as SyncResult.Merged

    assertEquals(1, result.duplicates.size)
    val card = hugo.state.conflict!!
    assertEquals("Rice", card.itemName.replaceFirstChar { it.uppercase() })
    assertEquals("both entries stay until someone answers", 2, hugo.list.liveItems.size)
  }

  @Test
  fun `the question is asked once, not on every handover`() {
    val (ava, hugo) = phones()
    ava.add(1, "Rice")
    hugo.add(1, "Rice")

    ava.syncTo(hugo)
    hugo.state = hugo.state.copy(conflict = null) // answered, however they answered it
    hugo.syncTo(ava)
    val again = ava.syncTo(hugo) as SyncResult.Merged

    assertTrue(again.duplicates.isEmpty())
    assertNull(hugo.state.conflict)
  }

  @Test
  fun `an unanswered question is not replaced by a newer one`() {
    val (ava, hugo) = phones()
    ava.add(1, "Rice")
    hugo.add(1, "Rice")
    ava.syncTo(hugo)
    val first = hugo.state.conflict!!

    ava.add(2, "Beans")
    hugo.add(2, "Beans")
    ava.syncTo(hugo)

    assertEquals("the person is answering the first one", first, hugo.state.conflict)
  }

  @Test
  fun `ordinary syncing still reports nothing`() {
    val (ava, hugo) = phones()
    ava.add(1, "Rice")
    ava.add(2, "Butter")

    val result = ava.syncTo(hugo) as SyncResult.Merged

    assertTrue(result.duplicates.isEmpty())
    assertNull(hugo.state.conflict)
    assertEquals(2, hugo.list.liveItems.size)
  }
}
