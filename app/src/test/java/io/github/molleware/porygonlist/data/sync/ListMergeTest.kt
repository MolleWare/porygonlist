package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")

private fun stamp(wall: Long, device: DeviceId) = Hlc(wall, 0, device)

private fun item(
  id: String = "${AVA.value}:1",
  name: String = "Oat milk",
  qty: Int = 1,
  at: Hlc = stamp(1_000, AVA),
): GroceryItem =
  GroceryItem(
    id = ItemId(id),
    name = Field(name, at),
    qty = Field(qty, at),
    checkedAt = at,
    removed = Field(false, at),
  )

private fun list(vararg items: GroceryItem, people: List<Person> = pair) =
  GroceryList(1, "Weekly shop", ListAccent.ACCENT, items.toList(), people)

private val pair = listOf(Person(AVA, "Ava", "A"), Person(HUGO, "Hugo", "H"))

class ItemMergeTest {

  @Test
  fun `edits to different fields both survive`() {
    // The reason fields merge separately. Whole-item last-writer-wins would drop one of these.
    val base = item()
    val mine = base.copy(name = base.name.set("Oat milk barista", stamp(2_000, AVA)))
    val theirs = base.copy(qty = base.qty.set(3, stamp(3_000, HUGO)))

    val merged = merge(mine, theirs).item

    assertEquals("Oat milk barista", merged.name.value)
    assertEquals(3, merged.qty.value)
  }

  @Test
  fun `an informed edit wins without being reported`() {
    val base = item()
    val hugoRenames = base.copy(name = base.name.set("Semi-skimmed", stamp(2_000, HUGO)))
    // Ava saw Hugo's rename and then renamed again.
    val avaRenamesAfter = hugoRenames.copy(name = hugoRenames.name.set("Whole milk", stamp(3_000, AVA)))

    val result = merge(avaRenamesAfter, hugoRenames)

    assertEquals("Whole milk", result.item.name.value)
    assertTrue("a sequence is not a clash", result.concurrent.isEmpty())
  }

  @Test
  fun `a blind clash still resolves but says so`() {
    val base = item()
    val mine = base.copy(name = base.name.set("Whole milk", stamp(3_000, AVA)))
    val theirs = base.copy(name = base.name.set("Semi-skimmed", stamp(2_000, HUGO)))

    val result = merge(mine, theirs)

    // Both phones must land on the same answer, so one is picked by total order...
    assertEquals("Whole milk", result.item.name.value)
    // ...but the caller is told it was a coin toss, not a decision.
    assertEquals(setOf(ItemField.NAME), result.concurrent)
  }

  @Test
  fun `merging is symmetric`() {
    // Both phones have to reach the same list, or they will never stop exchanging.
    val base = item()
    val mine = base.copy(name = base.name.set("Whole milk", stamp(3_000, AVA)))
    val theirs = base.copy(qty = base.qty.set(2, stamp(2_000, HUGO)))

    val a = merge(mine, theirs).item
    val b = merge(theirs, mine).item

    assertEquals(a.name.value, b.name.value)
    assertEquals(a.qty.value, b.qty.value)
    assertEquals(a.removed.value, b.removed.value)
    assertEquals(a.checked, b.checked)
  }

  @Test
  fun `merging is idempotent`() {
    val base = item()
    val mine = base.copy(name = base.name.set("Whole milk", stamp(3_000, AVA)))
    val theirs = base.copy(qty = base.qty.set(2, stamp(2_000, HUGO)))

    val once = merge(mine, theirs).item
    val twice = merge(once, theirs).item

    assertEquals(once, twice)
  }

  @Test
  fun `the later tick wins and is not treated as a clash`() {
    val base = item()
    val ticked = base.copy(checked = true, checkedAt = stamp(5_000, HUGO))
    val untouched = base.copy(checked = false, checkedAt = stamp(2_000, AVA))

    val result = merge(untouched, ticked)

    assertTrue(result.item.checked)
    assertEquals(stamp(5_000, HUGO), result.item.checkedAt)
    assertTrue("ticking twice is harmless; nothing to report", result.concurrent.isEmpty())
  }

  @Test
  fun `a removal racing a restore is reported`() {
    val base = item()
    val hugoRemoves = base.copy(removed = base.removed.set(true, stamp(3_000, HUGO)))
    val avaKeeps = base.copy(removed = base.removed.set(false, stamp(3_000, AVA)))

    val result = merge(avaKeeps, hugoRemoves)

    assertEquals(setOf(ItemField.PRESENCE), result.concurrent)
  }

  @Test
  fun `local-only state is never taken from a peer`() {
    // `pending` is what this phone still owes; `editing` is who has it open here. A peer's answers
    // to those are about the peer, not about us.
    val mine = item().copy(pending = true, editing = false)
    val theirs = item().copy(pending = false, editing = true)

    val merged = merge(mine, theirs).item

    assertTrue(merged.pending)
    assertFalse(merged.editing)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `merging two different items is refused`() {
    merge(item(id = "${AVA.value}:1"), item(id = "${HUGO.value}:1"))
  }
}

class ListMergeTest {

  @Test
  fun `each side keeps what the other has not seen`() {
    val mine = list(item(id = "${AVA.value}:1", name = "Butter"))
    val theirs = list(item(id = "${HUGO.value}:1", name = "Sourdough"))

    val merged = merge(mine, theirs).list

    assertEquals(listOf("Butter", "Sourdough"), merged.items.map { it.name.value })
  }

  @Test
  fun `the same thing added on both phones stays two items`() {
    // Ids carry the phone that minted them, so these never collide — which is correct. This is the
    // case the conflict card exists for, and merging deliberately does not paper over it.
    val mine = list(item(id = "${AVA.value}:7", name = "Eggs"))
    val theirs = list(item(id = "${HUGO.value}:7", name = "Eggs"))

    val merged = merge(mine, theirs).list

    assertEquals(2, merged.items.size)
    assertEquals(listOf("Eggs", "Eggs"), merged.items.map { it.name.value })
  }

  @Test
  fun `a tombstone from the peer removes the item here`() {
    val live = item(name = "Oat milk")
    val theirTombstone = live.copy(removed = live.removed.set(true, stamp(5_000, HUGO)))

    val merged = merge(list(live), list(theirTombstone)).list

    assertTrue(merged.liveItems.isEmpty())
    assertEquals("the tombstone itself is kept, to pass on", 1, merged.items.size)
  }

  @Test
  fun `an item the peer has never heard of is not mistaken for a deletion`() {
    // Absence means "not known here", never "removed". Only a tombstone removes.
    val mine = list(item(id = "${AVA.value}:1", name = "Butter"))
    val theirs = list()

    val merged = merge(mine, theirs).list

    assertEquals(listOf("Butter"), merged.liveItems.map { it.name.value })
  }

  @Test
  fun `clashes are collected for the caller`() {
    val base = item(name = "Oat milk")
    val mine = list(base.copy(name = base.name.set("Whole milk", stamp(3_000, AVA))))
    val theirs = list(base.copy(name = base.name.set("Semi-skimmed", stamp(3_000, HUGO))))

    val result = merge(mine, theirs)

    assertEquals(1, result.clashes.size)
    assertEquals(setOf(ItemField.NAME), result.clashes.single().concurrent)
  }

  @Test
  fun `a clean merge reports no clashes`() {
    val merged = merge(list(item(id = "${AVA.value}:1")), list(item(id = "${HUGO.value}:1")))

    assertTrue(merged.clashes.isEmpty())
  }

  @Test
  fun `incoming items arrive owing nothing and held by nobody`() {
    val theirs = list(item(id = "${HUGO.value}:1").copy(pending = true, editing = true))

    val merged = merge(list(), theirs).list

    assertFalse("their outbox is not ours", merged.items.single().pending)
    assertFalse("their presence is not ours", merged.items.single().editing)
  }

  @Test
  fun `both phones reach the same set of items`() {
    val shared = item(id = "${AVA.value}:1", name = "Butter")
    val mine = list(shared, item(id = "${AVA.value}:2", name = "Sourdough"))
    val theirs = list(shared, item(id = "${HUGO.value}:1", name = "Coffee"))

    val a = merge(mine, theirs).list.items.map { it.id.value }.toSet()
    val b = merge(theirs, mine).list.items.map { it.id.value }.toSet()

    assertEquals(a, b)
  }

  @Test
  fun `merging twice changes nothing the second time`() {
    val mine = list(item(id = "${AVA.value}:1", name = "Butter"))
    val theirs = list(item(id = "${HUGO.value}:1", name = "Coffee"))

    val once = merge(mine, theirs).list
    val twice = merge(once, theirs).list

    assertEquals(once.items, twice.items)
  }
}

class MergePeopleTest {

  private val hugoNew = DeviceId("HUGONEWAAAAAAAAA")

  @Test
  fun `someone only the peer knows is added`() {
    val merged = merge(list(people = listOf(Person(AVA, "Ava", "A"))), list(people = pair)).list

    assertEquals(setOf("Ava", "Hugo"), merged.people.map { it.name }.toSet())
  }

  @Test
  fun `a peer's new phone replaces the one we knew, rather than doubling them`() {
    // Hugo replaced his handset and told his own phone. When we meet, we must adopt that, not end
    // up with two Hugos — the retired id would otherwise be waited on for receipts for ever.
    val mine = list(people = pair)
    val theirs = list(people = listOf(Person(AVA, "Ava", "A"), Person(hugoNew, "Hugo", "H", formerDevices = setOf(HUGO))))

    val merged = merge(mine, theirs).list

    assertEquals(1, merged.people.count { it.name == "Hugo" })
    val hugo = merged.people.single { it.name == "Hugo" }
    assertEquals(hugoNew, hugo.device)
    assertTrue(hugo.wasEver(HUGO))
    assertFalse("a retired handset must not be waited on", HUGO in merged.peersOf(AVA))
  }

  @Test
  fun `a replacement we already knew about is not undone`() {
    val mine = list(people = listOf(Person(AVA, "Ava", "A"), Person(hugoNew, "Hugo", "H", formerDevices = setOf(HUGO))))
    // The peer is still carrying the old view.
    val theirs = list(people = pair)

    val merged = merge(mine, theirs).list

    assertEquals(hugoNew, merged.people.single { it.name == "Hugo" }.device)
    assertEquals(1, merged.people.count { it.name == "Hugo" })
  }

  @Test
  fun `nobody ends up listed as their own former self`() {
    val mine = list(people = listOf(Person(HUGO, "Hugo", "H")))
    val theirs = list(people = listOf(Person(HUGO, "Hugo", "H", formerDevices = setOf(HUGO))))

    val merged = merge(mine, theirs).list

    assertFalse(HUGO in merged.people.single().formerDevices)
  }
}

class NewestStampTest {

  @Test
  fun `the newest stamp is what the clock should be folded forward to`() {
    val l = list(item(at = stamp(1_000, AVA)), item(id = "${HUGO.value}:1", at = stamp(9_000, HUGO)))

    assertEquals(stamp(9_000, HUGO), l.newestStamp())
  }

  @Test
  fun `an empty list has no stamp`() {
    assertNull(list().newestStamp())
  }
}
