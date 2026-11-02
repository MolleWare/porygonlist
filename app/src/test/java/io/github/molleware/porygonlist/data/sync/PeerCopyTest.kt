package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val SAM = DeviceId("SAMAAAAAAAAAAAAA")

private fun stamp(wall: Long, device: DeviceId = HUGO) = Hlc(wall, 0, device)

private fun item(n: Int, name: String, at: Hlc = stamp(1_000), device: DeviceId = HUGO) =
  GroceryItem(ItemId("${device.value}:$n"), Field(name, at), Field(1, at), checkedAt = at, removed = Field(false, at))

private val weekly = ListId("${HUGO.value}:1")
private val party = ListId("${HUGO.value}:500")
private val both = listOf(Person(AVA, "Ava", "A"), Person(HUGO, "Hugo", "H"))

/** What Hugo's phone would send Ava whole, at [wall]. */
private fun whole(lists: List<GroceryList>, wall: Long = 5_000) = SyncPayload(HUGO, stamp(wall), lists, run = "hugo-1")

private fun weeklyWith(items: List<GroceryItem>, people: List<Person> = both) =
  GroceryList(weekly, "Weekly shop", ListAccent.ACCENT, items, people)

/**
 * What Hugo's phone remembers Ava confirming, and what that leaves to send her.
 *
 * Plain values and no network: the coordinator only decides when to consult it and when to write to
 * it, and those are tested where the coordinator is.
 */
class PeerCopyTest {

  private val shop = (1..5).map { item(it + 1, "Thing $it") }
  private val confirmed = PeerCopy.of(whole(listOf(weeklyWith(shop))), run = "ava-1")

  @Test
  fun `after one whole exchange only the item that changed goes`() {
    val ticked = shop[2].copy(checked = true, checkedAt = stamp(6_000))
    val now = whole(listOf(weeklyWith(shop.map { if (it.id == ticked.id) ticked else it })), wall = 6_000)

    val sent = confirmed.partOf(now)

    val list = sent.lists.single()
    assertEquals(listOf(ticked), list.items)
    assertEquals("its people always go with it", both, list.people)
    assertEquals("and the ids of the rest", shop.map { it.id }.toSet() - ticked.id, sent.held[weekly])
    assertEquals("the stamp is the whole payload's", now.at, sent.at)
  }

  @Test
  fun `a list with nothing new is left out altogether`() {
    val sent = confirmed.partOf(whole(listOf(weeklyWith(shop)), wall = 6_000))

    assertTrue(sent.lists.isEmpty())
    assertTrue(sent.held.isEmpty())
  }

  @Test
  fun `a rename of the list goes, with nothing else`() {
    val sent = confirmed.partOf(whole(listOf(weeklyWith(shop).copy(name = "Big shop", nameAt = stamp(6_000))), wall = 6_000))

    assertEquals("Big shop", sent.lists.single().name)
    assertTrue(sent.lists.single().items.isEmpty())
    assertTrue(sent.isPartial(weekly))
  }

  @Test
  fun `a list newly shared with the peer goes whole`() {
    val newly = GroceryList(party, "Party", ListAccent.NEUTRAL, listOf(item(501, "Ice")), both)

    val sent = confirmed.partOf(whole(listOf(weeklyWith(shop), newly), wall = 6_000))

    assertEquals(listOf(party), sent.lists.map { it.id })
    assertFalse(sent.isPartial(party))
  }

  @Test
  fun `a list whose people changed goes whole`() {
    // Sam has never had it. It is simpler, and rare enough, to send it whole to everyone.
    val withSam = weeklyWith(shop, both + Person(SAM, "Sam", "S", removed = Field(false, stamp(6_000))))

    val sent = confirmed.partOf(whole(listOf(withSam), wall = 6_000))

    assertEquals(shop, sent.lists.single().items)
    assertFalse(sent.isPartial(weekly))
  }

  @Test
  fun `an item that came from a third phone goes, however old its stamps`() {
    // Sam's edit reached Hugo after Ava's last receipt but was made before it. Chosen by stamp, it
    // would never reach Ava from Hugo; chosen by what she has confirmed, it does. The same holds for
    // an item a merge of two lists brought over, which keeps its old stamps on a new id.
    val fromSam = item(9, "Sam's flour", at = stamp(10, SAM), device = SAM)

    val sent = confirmed.partOf(whole(listOf(weeklyWith(shop + fromSam)), wall = 6_000))

    assertEquals(listOf(fromSam), sent.lists.single().items)
  }

  @Test
  fun `a removal goes until it is confirmed, and then not again`() {
    val gone = shop[0].copy(removed = shop[0].removed.set(true, stamp(6_000)))
    val now = whole(listOf(weeklyWith(listOf(gone) + shop.drop(1))), wall = 6_000)

    val sent = confirmed.partOf(now)
    assertEquals(listOf(gone), sent.lists.single().items)
    assertEquals("not confirmed, so it goes again", listOf(gone), confirmed.partOf(now).lists.single().items)

    val afterAck = confirmed.confirming(sent)
    assertTrue("confirmed, so it does not", afterAck.partOf(now).lists.isEmpty())

    // Collected once everyone has heard, and forgotten here the next time the list goes.
    val collected = whole(listOf(weeklyWith(shop.drop(1)).copy(name = "Renamed", nameAt = stamp(7_000))), wall = 7_000)
    val later = afterAck.confirming(afterAck.partOf(collected))
    assertFalse(gone.id in later.lists.getValue(weekly).items)
  }

  @Test
  fun `a list left out keeps what was confirmed of it`() {
    val two = whole(listOf(weeklyWith(shop), GroceryList(party, "Party", ListAccent.NEUTRAL, listOf(item(501, "Ice")), both)))
    val copy = PeerCopy.of(two, run = "ava-1")
    val partyEdit = item(502, "Cups", at = stamp(6_000))
    val next = whole(listOf(weeklyWith(shop), GroceryList(party, "Party", ListAccent.NEUTRAL, listOf(item(501, "Ice"), partyEdit), both)), wall = 6_000)

    val after = copy.confirming(copy.partOf(next))

    assertEquals(copy.lists.getValue(weekly), after.lists.getValue(weekly))
    assertTrue(after.partOf(next).lists.isEmpty())
  }

  @Test
  fun `a copy is only cut from while the peer is in the same run, and not past the safety net`() {
    assertTrue(confirmed.usableFor("ava-1"))
    assertFalse("restarted, perhaps from an older save", confirmed.usableFor("ava-2"))
    assertFalse("never said", confirmed.usableFor(null))

    val due = (1..PeerCopy.WHOLE_EVERY).fold(confirmed) { copy, _ -> copy.confirming(copy.partOf(whole(listOf(weeklyWith(shop))))) }
    assertFalse("every so often one goes whole regardless", due.usableFor("ava-1"))
  }

  @Test
  fun `a two hundred item list with one tick costs a fraction of the whole`() {
    // The battery night: 204 items, sent whole several times a quarter hour. Real device ids, which
    // are what make every stamp long.
    val hugo = DeviceId("KQ7MZXW3JRB5TNHAP4Y6CVDSLG2F")
    val ava = DeviceId("P2GXDNWA7RK3QTYHM5ZLBV6CJSF4")
    val people = listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H"))
    val places = OrderKey.spread(200)
    val items =
      (1..200).map { n ->
        val at = Hlc(1_793_500_000_000 + n * 1_000L, 0, if (n % 2 == 0) hugo else ava)
        GroceryItem(
          ItemId("${(if (n % 2 == 0) hugo else ava).value}:$n"),
          Field("Item %03d".format(n), at),
          Field(1 + n % 3, at),
          checkedAt = at,
          removed = Field(false, at),
          position = Field(places[n - 1], at),
        )
      }
    val list = GroceryList(ListId("${hugo.value}:1"), "Weekly shop", ListAccent.ACCENT, items, people)
    val state =
      AppState(localDevice = hugo, idCounter = 300, clockHead = Hlc(1_793_600_000_000, 0, hugo), lists = listOf(list), activeListId = list.id, networks = emptyList())
    val first = SyncPayload(hugo, state.clockHead, state.lists, callback = 40_123, run = "0123456789abcdef")
    val copy = PeerCopy.of(first, run = "fedcba9876543210")

    val tickedAt = Hlc(1_793_600_100_000, 0, hugo)
    val oneTick = items.mapIndexed { i, it -> if (i == 100) it.copy(checked = true, checkedAt = tickedAt) else it }
    val now = first.copy(at = tickedAt, lists = listOf(list.copy(items = oneTick)))

    val wholeBytes = SyncCodec.encode(now).toByteArray().size
    val partialBytes = SyncCodec.encode(copy.partOf(now)).toByteArray().size
    println("200 items, one tick: whole $wholeBytes bytes, partial $partialBytes bytes")

    // About 75 kB against 1.7 kB, most of it the ids left out: the two phones' items alternate here,
    // so their counters make no runs at all, which is the worst case for that line.
    assertTrue("whole is $wholeBytes, partial $partialBytes", partialBytes * 40 < wholeBytes)
  }
}
