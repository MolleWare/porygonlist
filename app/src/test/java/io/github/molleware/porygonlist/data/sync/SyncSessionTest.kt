package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.Conflict
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
      networks = emptyList(),
      peers = people.filterNot { it.device == device }.map { TrustedPeer(it.device, byteArrayOf(1), it.name, 0) },
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
  fun `a paired phone not on this phone's copy can neither join nor change a list`() {
    // Found in review, 2026-11-10: receive checked the sender against its own copy of the list, so
    // a modified build that knew a list's id — `deviceId:counter`, guessable — could name itself on
    // it, be merged in, and from then on be sent the list. Being on it is this phone's copy's to say.
    val sam = DeviceId("SAMMMMMMMMMMMMMM")
    val butter = item("${AVA.value}:1", "Butter", Hlc(1_000, 0, AVA))
    val eggs = item("${AVA.value}:2", "Eggs", Hlc(1_000, 0, AVA))
    val ava = Phone(AVA, 2_000, listOf(butter, eggs), pair)
    ava.state = ava.state.copy(peers = ava.state.peers + TrustedPeer(sam, byteArrayOf(2), "Sam", 0))
    val before = ava.list

    val claimed = Hlc(5_000, 0, sam)
    val forged =
      before.copy(
        name = "Mine now",
        nameAt = claimed,
        items =
          listOf(
            butter.copy(name = butter.name.set("Margarine", claimed)),
            eggs.copy(removed = eggs.removed.set(true, claimed)),
            item("${sam.value}:1", "Something of Sam's", claimed),
          ),
        people = before.withPersonLeft(HUGO, claimed).people + Person(sam, "Sam", "S", removed = Field(false, claimed)),
      )
    val result = ava.state.receive(SyncPayload(sam, claimed, listOf(forged)), ava.clock)
    val after = (result as? SyncResult.Merged)?.state ?: ava.state

    assertEquals("not joined", before.people, after.lists.single().people)
    assertEquals("nothing changed", before.items, after.lists.single().items)
    assertEquals("Weekly shop", after.lists.single().name)
    assertNull("and not sent the list", after.payloadFor(sam, ava.clock))
  }

  @Test
  fun `nor can it with a partial list`() {
    val sam = DeviceId("SAMMMMMMMMMMMMMM")
    val butter = item("${AVA.value}:1", "Butter", Hlc(1_000, 0, AVA))
    val eggs = item("${AVA.value}:2", "Eggs", Hlc(1_000, 0, AVA))
    val ava = Phone(AVA, 2_000, listOf(butter, eggs), pair)
    ava.state = ava.state.copy(peers = ava.state.peers + TrustedPeer(sam, byteArrayOf(2), "Sam", 0))
    val before = ava.list

    // Only the changes, with the rest of the list said to be held — the shape a phone sends a peer
    // it believes already has the list.
    val claimed = Hlc(5_000, 0, sam)
    val changes =
      before.copy(
        items = listOf(butter.copy(name = butter.name.set("Margarine", claimed)), eggs.copy(removed = eggs.removed.set(true, claimed))),
        people = listOf(Person(sam, "Sam", "S", removed = Field(false, claimed))),
      )
    val payload = SyncPayload(sam, claimed, listOf(changes), held = mapOf(before.id to emptySet()))
    val after = (ava.state.receive(SyncCodec.decode(SyncCodec.encode(payload))!!, ava.clock) as? SyncResult.Merged)?.state ?: ava.state

    assertEquals("not joined", before.people, after.lists.single().people)
    assertEquals("nothing changed", before.items, after.lists.single().items)
    assertNull(after.payloadFor(sam, ava.clock))
  }

  // The ways onto and off a list that must still work with the sender checked against this copy.

  @Test
  fun `someone new gets on through the copy of someone already on it`() {
    val sam = DeviceId("SAMMMMMMMMMMMMMM")
    val ava = Phone(AVA, 2_000, emptyList(), pair)
    ava.state = ava.state.copy(peers = ava.state.peers + TrustedPeer(sam, byteArrayOf(2), "Sam", 0))
    val withSam = ava.list.withPersonAdded(sam, "Sam", Hlc(3_000, 0, HUGO))
    val samsEdit = withSam.copy(items = listOf(item("${sam.value}:1", "Coffee", Hlc(3_500, 0, sam))))

    // Sam's own copy first, before Hugo's has arrived: not yet.
    val early = ava.state.receive(SyncPayload(sam, Hlc(3_500, 0, sam), listOf(samsEdit)), ava.clock)
    assertTrue(((early as? SyncResult.Merged)?.state ?: ava.state).lists.single().items.isEmpty())

    ava.state = (ava.state.receive(SyncPayload(HUGO, Hlc(3_000, 0, HUGO), listOf(withSam)), ava.clock) as SyncResult.Merged).state
    ava.state = (ava.state.receive(SyncPayload(sam, Hlc(3_600, 0, sam), listOf(samsEdit)), ava.clock) as SyncResult.Merged).state

    assertTrue("on the list", ava.list.personFor(sam)!!.present)
    assertEquals(listOf("Coffee"), ava.list.liveItems.map { it.name.value })
  }

  @Test
  fun `someone leaving is heard from their own phone`() {
    val ava = Phone(AVA, 2_000, emptyList(), pair)
    val hugoLeft = ava.list.withPersonLeft(HUGO, Hlc(3_000, 0, HUGO))

    ava.state = (ava.state.receive(SyncPayload(HUGO, Hlc(3_000, 0, HUGO), listOf(hugoLeft)), ava.clock) as SyncResult.Merged).state

    // Off the list; with nobody else left to tell, the record of it goes at once.
    assertTrue(ava.list.people.none { it.wasEver(HUGO) && it.present })
  }

  @Test
  fun `a replacement phone is heard from once someone on the list has introduced it`() {
    val sam = DeviceId("SAMMMMMMMMMMMMMM")
    val hugosNewPhone = DeviceId("HUGONEWAAAAAAAAA")
    val three = pair + Person(sam, "Sam", "S")
    val ava = Phone(AVA, 2_000, emptyList(), three)
    ava.state = ava.state.copy(peers = ava.state.peers + TrustedPeer(hugosNewPhone, byteArrayOf(3), "Hugo", 0))
    // Sam paired Hugo's new phone as the replacement for his old one, and sent it the list.
    val samsCopy = ava.list.replaceDevice(HUGO, hugosNewPhone)
    val fromNewPhone = samsCopy.copy(items = listOf(item("${hugosNewPhone.value}:1", "Tea", Hlc(3_500, 0, hugosNewPhone))))

    val early = ava.state.receive(SyncPayload(hugosNewPhone, Hlc(3_500, 0, hugosNewPhone), listOf(fromNewPhone)), ava.clock)
    assertTrue(((early as? SyncResult.Merged)?.state ?: ava.state).lists.single().items.isEmpty())

    ava.state = (ava.state.receive(SyncPayload(sam, Hlc(3_000, 0, sam), listOf(samsCopy)), ava.clock) as SyncResult.Merged).state
    ava.state =
      (ava.state.receive(SyncPayload(hugosNewPhone, Hlc(3_600, 0, hugosNewPhone), listOf(fromNewPhone)), ava.clock) as SyncResult.Merged).state

    assertEquals(hugosNewPhone, ava.list.personFor(HUGO)!!.device)
    assertEquals(listOf("Tea"), ava.list.liveItems.map { it.name.value })
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
    val card = hugo.state.conflicts.single()
    assertEquals("Rice", card.itemName.replaceFirstChar { it.uppercase() })
    assertEquals("both entries stay until someone answers", 2, hugo.list.liveItems.size)
  }

  @Test
  fun `the question is asked once, not on every handover`() {
    val (ava, hugo) = phones()
    ava.add(1, "Rice")
    hugo.add(1, "Rice")

    ava.syncTo(hugo)
    hugo.state = hugo.state.copy(conflicts = emptyList()) // answered, however they answered it
    hugo.syncTo(ava)
    val again = ava.syncTo(hugo) as SyncResult.Merged

    assertTrue(again.duplicates.isEmpty())
    assertEquals(emptyList<Conflict>(), hugo.state.conflicts)
  }

  @Test
  fun `a newer question joins an unanswered one, behind it`() {
    val (ava, hugo) = phones()
    ava.add(1, "Rice")
    hugo.add(1, "Rice")
    ava.syncTo(hugo)
    val first = hugo.state.conflicts.single()

    ava.add(2, "Beans")
    hugo.add(2, "Beans")
    ava.syncTo(hugo)

    assertEquals("the person is answering the first one", first, hugo.state.conflicts.first())
    assertEquals(listOf("Rice", "Beans"), hugo.state.conflicts.map { it.itemName })
  }

  @Test
  fun `ordinary syncing still reports nothing`() {
    val (ava, hugo) = phones()
    ava.add(1, "Rice")
    ava.add(2, "Butter")

    val result = ava.syncTo(hugo) as SyncResult.Merged

    assertTrue(result.duplicates.isEmpty())
    assertEquals(emptyList<Conflict>(), hugo.state.conflicts)
    assertEquals(2, hugo.list.liveItems.size)
  }
}
