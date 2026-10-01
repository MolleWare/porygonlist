package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val SAM = DeviceId("SAMAAAAAAAAAAAAA")

private fun stamp(wall: Long, device: DeviceId) = Hlc(wall, 0, device)

private fun item(id: String, name: String, qty: Int = 1, at: Hlc = stamp(1_000, AVA), removed: Boolean = false) =
  GroceryItem(
    id = ItemId(id),
    name = Field(name, at),
    qty = Field(qty, at),
    checkedAt = at,
    removed = Field(removed, at),
  )

private val people = listOf(Person(AVA, "Ava", "A"), Person(HUGO, "Hugo", "H"))

private fun list(vararg items: GroceryItem) =
  GroceryList(ListId("list:1"), "Weekly shop", ListAccent.ACCENT, items.toList(), people)

/**
 * Two phones adding the same thing while apart.
 *
 * Nobody types an id when they put rice on a list, so this cannot be found by id — it is found by
 * name, and reported rather than resolved, because whether two people wanting rice means one bag or
 * two is not something the app can know.
 */
class DuplicateTest {

  private val myRice = item("${AVA.value}:1", "Rice", at = stamp(1_000, AVA))
  private val theirRice = item("${HUGO.value}:1", "Rice", at = stamp(2_000, HUGO))

  @Test
  fun `the same thing added on both phones is one clash`() {
    val result = merge(list(myRice), list(theirRice))

    val duplicate = result.duplicates.single()
    assertEquals(myRice, duplicate.yours)
    assertEquals(theirRice, duplicate.theirs)
    assertEquals("Rice", duplicate.itemName)
  }

  @Test
  fun `both items are still on the list afterwards`() {
    // Nothing is resolved here. The person answers the card, and until they do the list is honest
    // about holding two entries.
    val merged = merge(list(myRice), list(theirRice)).list

    assertEquals(listOf("Rice", "Rice"), merged.liveItems.map { it.name.value })
  }

  @Test
  fun `spelling differences that do not matter still match`() {
    val theirs = item("${HUGO.value}:1", "rice", at = stamp(2_000, HUGO))

    assertEquals(1, merge(list(myRice), list(theirs)).duplicates.size)
  }

  @Test
  fun `accents do not split a pair`() {
    val mine = item("${AVA.value}:9", "Crème fraîche")
    val theirs = item("${HUGO.value}:9", "Creme fraiche", at = stamp(2_000, HUGO))

    assertEquals(1, merge(list(mine), list(theirs)).duplicates.size)
  }

  @Test
  fun `spelling differences that do matter are two things, which is the point of the suggestions`() {
    val theirs = item("${HUGO.value}:1", "Ric", at = stamp(2_000, HUGO))

    assertTrue(merge(list(myRice), list(theirs)).duplicates.isEmpty())
  }

  @Test
  fun `editing one item is never a duplicate of itself`() {
    val base = item("${AVA.value}:1", "Rice")
    val theirs = base.copy(qty = base.qty.set(2, stamp(2_000, HUGO)))

    assertTrue("same id, so it is an edit", merge(list(base), list(theirs)).duplicates.isEmpty())
  }

  @Test
  fun `a second bag added knowing about the first is not a clash`() {
    // Their rice is already here, so mine is a deliberate second one rather than a collision.
    val mineToo = item("${AVA.value}:2", "Rice", at = stamp(3_000, AVA))

    val result = merge(list(theirRice, mineToo), list(theirRice))

    assertTrue(result.duplicates.isEmpty())
  }

  @Test
  fun `a pair is reported once, not on every handover afterwards`() {
    val first = merge(list(myRice), list(theirRice))
    assertEquals(1, first.duplicates.size)

    // Both phones now hold both items, which is exactly the condition that stops it firing again.
    val theirsAfter = merge(list(theirRice), list(myRice)).list
    val second = merge(first.list, theirsAfter)

    assertTrue("the card must not come back every time they meet", second.duplicates.isEmpty())
  }

  @Test
  fun `a removal is not an addition`() {
    val theirsGone = item("${HUGO.value}:1", "Rice", at = stamp(2_000, HUGO), removed = true)

    assertTrue(
      "offering to merge something deliberately taken off would be nonsense",
      merge(list(myRice), list(theirsGone)).duplicates.isEmpty(),
    )
  }

  @Test
  fun `three phones adding rice do not produce a pile of cards`() {
    val samsRice = item("${SAM.value}:1", "Rice", at = stamp(3_000, SAM))

    val result = merge(list(myRice), list(theirRice, samsRice))

    assertEquals("one question about rice, not two", 1, result.duplicates.size)
  }

  @Test
  fun `different things added at once are each reported`() {
    val myBeans = item("${AVA.value}:2", "Beans")
    val theirBeans = item("${HUGO.value}:2", "Beans", at = stamp(2_000, HUGO))

    val result = merge(list(myRice, myBeans), list(theirRice, theirBeans))

    assertEquals(setOf("Rice", "Beans"), result.duplicates.map { it.itemName }.toSet())
  }

  @Test
  fun `nothing in common means nothing to ask about`() {
    val theirs = item("${HUGO.value}:1", "Butter", at = stamp(2_000, HUGO))

    assertTrue(merge(list(myRice), list(theirs)).duplicates.isEmpty())
  }
}
