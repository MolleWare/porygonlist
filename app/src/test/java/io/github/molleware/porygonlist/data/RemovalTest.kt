package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.merge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removal is confirmed once, at the moment it happens, and then the item is simply gone everywhere.
 *
 * What is stored is still a tombstone rather than a dropped row: an absent item and an item the
 * other phone has not heard about yet look identical, so a plain deletion would be undone by the
 * next handover. These cover that the tombstone behaves, and stays invisible while doing so.
 */
class RemovalTest {

  private val ava = DeviceId("avaphone01")
  private val hugo = DeviceId("hugophone2")

  private val pair = listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H"))

  private fun item(name: String = "Oat milk", removedAt: Hlc? = null): GroceryItem {
    val created = Hlc(1_000, 0, ava)
    return GroceryItem(
      id = ItemId("${ava.value}:1"),
      name = Field(name, created),
      qty = Field(1, created),
      checkedAt = created,
      removed = if (removedAt == null) Field(false, created) else Field(true, removedAt),
    )
  }

  private fun list(vararg items: GroceryItem) =
    GroceryList(ListId("list:1"), "Weekly shop", ListAccent.ACCENT, items.toList(), pair)

  @Test
  fun `a removed item is off the list for everyone`() {
    val l = list(item("Butter"), item("Oat milk", removedAt = Hlc(2_000, 0, hugo)))

    // No per-device view: whoever removed it, it is gone for both.
    assertEquals(listOf("Butter"), l.liveItems.map { it.name.value })
  }

  @Test
  fun `the tombstone stays in the file even though nothing renders it`() {
    val l = list(item("Butter"), item("Oat milk", removedAt = Hlc(2_000, 0, hugo)))

    // This is the whole point: the removal has to be something the other phone can receive.
    assertEquals(2, l.items.size)
    assertEquals(1, l.liveItems.size)
  }

  @Test
  fun `counts and totals ignore removed items`() {
    val l = list(item("Butter"), item("Tomatoes").copy(checked = true), item("Oat milk", removedAt = Hlc(2_000, 0, hugo)))

    assertEquals(2, l.liveItems.size)
    assertEquals(1, l.doneCount)
  }

  @Test
  fun `a removed item is left out of the shared message`() {
    val l = list(item("Butter"), item("Oat milk", removedAt = Hlc(2_000, 0, hugo)))

    assertEquals("PL1 · Weekly shop · butter", ShareCodec.encode(l))
  }

  @Test
  fun `a removal made after seeing the item wins over the older state`() {
    val live = item().removed
    val removal = live.set(true, Hlc(3_000, 0, hugo))

    assertTrue(removal.supersedes(live))
    assertEquals(true, merge(removal, live).field.value)
    assertFalse(merge(removal, live).wasConcurrent)
  }

  @Test
  fun `re-adding after a removal is a later write, not a fight`() {
    // Hugo removes it; Ava, having seen that, puts milk back on the list. The informed decision
    // stands rather than the two stamps competing.
    val removal = item().removed.set(true, Hlc(3_000, 0, hugo))
    val readded = removal.set(false, Hlc(4_000, 0, ava))

    assertTrue(readded.supersedes(removal))
    assertEquals(false, merge(readded, removal).field.value)
  }

  @Test
  fun `removing and editing at the same time is reported as concurrent`() {
    // Neither saw the other. The merge still has to produce one answer so both phones agree, but
    // it says so rather than pretending the question did not arise.
    val base = item().removed
    val hugoRemoves = base.set(true, Hlc(3_000, 0, hugo))
    val avaKeeps = base.set(false, Hlc(3_000, 1, ava))

    assertTrue(hugoRemoves.concurrentWith(avaKeeps))
    assertTrue(merge(hugoRemoves, avaKeeps).wasConcurrent)
  }

  @Test
  fun `a tombstone survives a restart`() {
    val removal = Hlc(2_000, 0, hugo)
    val state =
      AppState(
        localDevice = ava,
        idCounter = 1,
        clockHead = Hlc(5_000, 0, ava),
        lists = listOf(list(item("Oat milk", removedAt = removal))),
        activeListId = ListId("list:1"),
        online = true,
        networks = emptyList(),
          conflict = null,
      )

    val restored = StateCodec.decode(StateCodec.encode(state))!!.lists.first()

    // Otherwise the item would quietly reappear the next time the phones met.
    assertEquals(1, restored.items.size)
    assertEquals(true, restored.items.first().removed.value)
    assertEquals(removal, restored.items.first().removed.at)
    assertTrue(restored.liveItems.isEmpty())
  }
}
