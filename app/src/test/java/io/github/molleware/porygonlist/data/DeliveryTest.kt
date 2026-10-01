package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tombstones are kept until there is proof every peer received them, then collected.
 *
 * The failure these guard against is resurrection: drop a removal while a phone still holds the
 * live item and has never been told, and the next handover reads that item as news and puts it
 * back. So every case below is really asking the same question — is there proof?
 */
class DeliveryTest {

  private val ava = DeviceId("avaphone01")
  private val hugo = DeviceId("hugophone2")
  private val kid = DeviceId("kidphone03")

  private fun at(wall: Long, device: DeviceId = ava) = Hlc(wall, 0, device)

  private fun item(name: String, removedAt: Hlc? = null): GroceryItem {
    val created = at(1_000)
    return GroceryItem(
      id = ItemId("${ava.value}:${name.hashCode()}"),
      name = Field(name, created),
      qty = Field(1, created),
      checkedAt = created,
      removed = if (removedAt == null) Field(false, created) else Field(true, removedAt),
    )
  }

  private fun list(vararg items: GroceryItem, people: List<Person> = listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H"))) =
    GroceryList(ListId("list:1"), "Weekly shop", ListAccent.ACCENT, items.toList(), people)

  // ── The log ────────────────────────────────────────────────────────────────

  @Test
  fun `a peer that has confirmed nothing has no mark`() {
    assertNull(DeliveryLog().confirmedBy(hugo))
    assertFalse(DeliveryLog().deliveredToAll(at(5_000), listOf(hugo)))
  }

  @Test
  fun `a receipt only ever moves forward`() {
    // A late or replayed receipt must not walk the mark back and make dropped tombstones look
    // undelivered again.
    val log = DeliveryLog().record(hugo, at(5_000)).record(hugo, at(2_000))

    assertEquals(at(5_000), log.confirmedBy(hugo))
  }

  @Test
  fun `delivery needs every peer, not any peer`() {
    val log = DeliveryLog().record(hugo, at(5_000))

    assertTrue(log.deliveredToAll(at(3_000), listOf(hugo)))
    // The third phone has confirmed nothing, so nothing is safe to drop yet.
    assertFalse(log.deliveredToAll(at(3_000), listOf(hugo, kid)))
  }

  @Test
  fun `a mark exactly on the stamp counts as delivered`() {
    val log = DeliveryLog().record(hugo, at(3_000))

    assertTrue(log.deliveredToAll(at(3_000), listOf(hugo)))
  }

  @Test
  fun `peers who left are forgotten`() {
    val log = DeliveryLog().record(hugo, at(5_000)).record(kid, at(5_000))

    assertEquals(setOf(hugo), log.retaining(listOf(hugo)).receipts.keys)
  }

  // ── Collecting ─────────────────────────────────────────────────────────────

  @Test
  fun `an unconfirmed tombstone is kept`() {
    val l = list(item("Butter"), item("Oat milk", removedAt = at(3_000)))

    val pruned = l.pruneDelivered(DeliveryLog(), ava)

    assertEquals(2, pruned.items.size)
    assertEquals(1, pruned.liveItems.size)
  }

  @Test
  fun `a confirmed tombstone is collected`() {
    val l = list(item("Butter"), item("Oat milk", removedAt = at(3_000)))

    val pruned = l.pruneDelivered(DeliveryLog().record(hugo, at(4_000)), ava)

    assertEquals(listOf("Butter"), pruned.items.map { it.name.value })
  }

  @Test
  fun `a tombstone newer than the receipt is kept`() {
    // Hugo confirmed at 4000; this removal happened afterwards and he has not seen it.
    val l = list(item("Oat milk", removedAt = at(6_000)))

    val pruned = l.pruneDelivered(DeliveryLog().record(hugo, at(4_000)), ava)

    assertEquals(1, pruned.items.size)
  }

  @Test
  fun `one silent peer keeps every tombstone`() {
    val threeWay = listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H"), Person(kid, "Kid", "K"))
    val l = list(item("Oat milk", removedAt = at(3_000)), people = threeWay)

    val pruned = l.pruneDelivered(DeliveryLog().record(hugo, at(9_000)), ava)

    assertEquals("the phone that never confirmed still holds the live item", 1, pruned.items.size)
  }

  @Test
  fun `a list nobody else is on keeps no tombstones`() {
    val solo = list(item("Oat milk", removedAt = at(3_000)), people = listOf(Person(ava, "Ava", "A")))

    val pruned = solo.pruneDelivered(DeliveryLog(), ava)

    // With nobody to tell, the removal has nothing to survive for.
    assertTrue(pruned.items.isEmpty())
  }

  @Test
  fun `live items are never collected`() {
    val l = list(item("Butter"), item("Sourdough"))

    assertEquals(2, l.pruneDelivered(DeliveryLog().record(hugo, at(9_000)), ava).items.size)
  }

  // ── Through the whole state ────────────────────────────────────────────────

  @Test
  fun `recording a receipt settles what it makes settleable`() {
    val state = state(list(item("Butter"), item("Oat milk", removedAt = at(3_000))))

    val before = state.pruneDeliveredTombstones()
    val after = state.copy(deliveredTo = state.deliveredTo.record(hugo, at(4_000))).pruneDeliveredTombstones()

    assertEquals(2, before.lists.first().items.size)
    assertEquals(1, after.lists.first().items.size)
  }

  @Test
  fun `receipts survive a restart`() {
    // Otherwise every restart would forget what had landed and keep tombstones for ever.
    val state = state(list(item("Butter"))).copy(deliveredTo = DeliveryLog().record(hugo, at(4_000)))

    val restored = StateCodec.decode(StateCodec.encode(state))!!

    assertEquals(at(4_000), restored.deliveredTo.confirmedBy(hugo))
  }

  @Test
  fun `a file written before its receipt still settles on load`() {
    val state =
      state(list(item("Oat milk", removedAt = at(3_000)))).copy(deliveredTo = DeliveryLog().record(hugo, at(4_000)))

    // Encoding keeps the tombstone; the load-time sweep is what finally drops it.
    val encoded = StateCodec.encode(state)
    assertTrue(encoded.contains("Oat milk"))

    val settled = StateCodec.decode(encoded)!!.pruneDeliveredTombstones()
    assertTrue(settled.lists.first().items.isEmpty())
  }

  private fun state(vararg lists: GroceryList) =
    AppState(
      localDevice = ava,
      idCounter = 9,
      clockHead = at(9_000),
      lists = lists.toList(),
      activeListId = ListId("list:1"),
      online = true,
      networks = emptyList(),
      conflict = null,
    )
}
