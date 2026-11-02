package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.IdFactory
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.payloadFor
import io.github.molleware.porygonlist.data.sync.receive
import io.github.molleware.porygonlist.ui.behind
import io.github.molleware.porygonlist.ui.lastChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merging one list into another, from the list's edit card.
 *
 * A local act. What it leaves — new items on the list merged into, and the other list gone the way
 * Delete takes it — travels by what already travels, so no format changes for it.
 */
class MergingListsTest {

  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")
  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val sam = DeviceId("SAMMMMMMMMMMMMMM")

  private val then = Hlc(1_000, 0, ava)
  private val now = Hlc(5_000, 0, hugo)

  private val me = Person(hugo, "Hugo", "H")

  /** An item as some phone left it a while ago, under that phone's id. */
  private fun item(
    id: String,
    name: String,
    qty: Int = 1,
    checked: Boolean = false,
    removed: Boolean = false,
    recipes: List<String> = emptyList(),
    position: String = "",
    by: Hlc = then,
  ) =
    GroceryItem(
      id = ItemId(id),
      name = Field(name, by),
      qty = Field(qty, by),
      checked = checked,
      checkedAt = by,
      removed = Field(removed, by),
      position = Field(position, by),
      recipes = Field(recipes, by),
    )

  private fun list(id: String, name: String, items: List<GroceryItem>, people: List<Person> = listOf(me)) =
    GroceryList(ListId(id), name, ListAccent.ACCENT, items, people)

  private fun ids() = IdFactory(hugo, start = 100)

  private fun merged(into: GroceryList, from: GroceryList, ids: IdFactory = ids()) = into.withItemsMergedFrom(from, ids::nextItem, now)

  private fun named(list: GroceryList, name: String) = list.liveItems.filter { it.name.value == name }

  // ── Items ─────────────────────────────────────────────────────────────────

  @Test
  fun `the same thing still to get on both becomes one line, asking for both and for every recipe`() {
    val into = list("${hugo.value}:1", "Weekly shop", listOf(item("${hugo.value}:2", "Milk", qty = 1, recipes = listOf("Pancakes"), position = "m")))
    val from = list("${hugo.value}:3", "Weekend", listOf(item("${ava.value}:4", "  milk ", qty = 2, recipes = listOf("Lasagne", "pancakes"))))

    val after = merged(into, from)

    val milk = after.liveItems.single()
    assertEquals("it stays this list's line", ItemId("${hugo.value}:2"), milk.id)
    assertEquals(3, milk.qty.value)
    assertEquals(listOf("Pancakes", "Lasagne"), milk.recipes.value)
    assertEquals("the bump is stamped, so it merges rather than overwrites", now, milk.qty.at)
    assertEquals("where it already sat", "m", milk.position.value)
  }

  @Test
  fun `something ticked on either side is copied, not combined`() {
    val into =
      list(
        "${hugo.value}:1",
        "Weekly shop",
        listOf(item("${hugo.value}:2", "Eggs", checked = true), item("${hugo.value}:3", "Bread")),
      )
    val from =
      list("${hugo.value}:4", "Weekend", listOf(item("${hugo.value}:5", "Eggs"), item("${hugo.value}:6", "Bread", qty = 2, checked = true)))

    val after = merged(into, from)

    assertEquals(2, named(after, "Eggs").size)
    val breads = named(after, "Bread")
    assertEquals(2, breads.size)
    assertEquals("this list's bread is not asked for more", 1, breads.single { !it.checked }.qty.value)
    assertEquals("the ticked one comes across as it was", 2, breads.single { it.checked }.qty.value)
  }

  @Test
  fun `anything else comes across as it is, recipes and all`() {
    val into = list("${hugo.value}:1", "Weekly shop", emptyList())
    val from = list("${hugo.value}:2", "Weekend", listOf(item("${ava.value}:3", "Basil", qty = 3, recipes = listOf("Pesto"))))

    val basil = merged(into, from).liveItems.single()

    assertEquals("Basil", basil.name.value)
    assertEquals(3, basil.qty.value)
    assertFalse(basil.checked)
    assertEquals(listOf("Pesto"), basil.recipes.value)
  }

  @Test
  fun `removed items stay behind`() {
    val into = list("${hugo.value}:1", "Weekly shop", listOf(item("${hugo.value}:2", "Milk")))
    val from =
      list("${hugo.value}:3", "Weekend", listOf(item("${hugo.value}:4", "Milk", qty = 4, removed = true), item("${hugo.value}:5", "Rice", removed = true)))

    val after = merged(into, from)

    assertEquals(listOf("Milk"), after.items.map { it.name.value })
    assertEquals("a removal is not an amount", 1, after.items.single().qty.value)
  }

  @Test
  fun `copied items get new ids from this phone, never the old ones`() {
    val into = list("${hugo.value}:1", "Weekly shop", emptyList())
    val old = listOf(item("${ava.value}:7", "Rice"), item("${hugo.value}:8", "Beans"), item("${sam.value}:9", "Oats", checked = true))
    val from = list("${hugo.value}:3", "Weekend", old)
    val ids = ids()

    val after = merged(into, from, ids)

    val fresh = after.items.map { it.id }
    assertTrue("the old ids still name items on the other list", fresh.none { it in old.map { o -> o.id } })
    assertTrue("minted here, never under another phone's prefix", fresh.all { it.device == hugo })
    assertEquals("each from this phone's own counter", (101L..103L).map { ItemId("${hugo.value}:$it") }.toSet(), fresh.toSet())
    assertEquals(103L, ids.peek())
  }

  @Test
  fun `a copied item is still whoever added it, and reads as it did`() {
    val into = list("${hugo.value}:1", "Weekly shop", emptyList(), people = listOf(me, Person(sam, "Sam", "S")))
    val from = list("${hugo.value}:2", "Weekend", listOf(item("${ava.value}:3", "Rice")), people = listOf(me, Person(ava, "Ava", "A")))

    val rice = merged(into, from).liveItems.single()

    // A new id, which says this phone, but still Ava's errand — even on a list she is not on.
    assertEquals(hugo, rice.id.device)
    assertEquals(ava, rice.addedBy)
    assertEquals(ava, rice.createdBy)
    // "Ava, 9:12" under its name, as on the list it came from: its stamps are kept, only its place is new.
    assertEquals(ava, rice.lastWriter)
    assertEquals(then.wall, rice.at)
    assertEquals(now, rice.position.at)
  }

  @Test
  fun `something this phone added needs no adder kept`() {
    val into = list("${hugo.value}:1", "Weekly shop", emptyList())
    val from = list("${hugo.value}:2", "Weekend", listOf(item("${hugo.value}:3", "Rice", by = Hlc(1_000, 0, hugo))))

    val rice = merged(into, from).liveItems.single()

    assertEquals(null, rice.addedBy)
    assertEquals(hugo, rice.createdBy)
  }

  @Test
  fun `an adder a merge already kept is kept again`() {
    val into = list("${hugo.value}:1", "Weekly shop", emptyList())
    // Merged in once already, from Sam's list: a Hugo id, Sam's errand.
    val from = list("${hugo.value}:2", "Weekend", listOf(item("${hugo.value}:3", "Rice").copy(addedBy = sam)))

    assertEquals(sam, merged(into, from).liveItems.single().createdBy)
  }

  @Test
  fun `the merge is still the list's newest change, so in step waits for it`() {
    val into = list("${hugo.value}:1", "Weekly shop", emptyList(), people = listOf(me, Person(ava, "Ava", "A")))
    val from = list("${hugo.value}:2", "Weekend", listOf(item("${ava.value}:3", "Rice")))

    val after = merged(into, from)

    assertEquals(now, after.lastChange())
    // Ava had confirmed everything up to just before the merge: she is behind until she hears of it.
    assertEquals(listOf(ava), after.behind(hugo, DeliveryLog().record(ava, Hlc(4_999, 0, hugo))).map { it.device })
  }

  @Test
  fun `copied items keep their order, what is still to get above the trolley and what is ticked under it`() {
    val into =
      list(
        "${hugo.value}:1",
        "Weekly shop",
        listOf(item("${hugo.value}:2", "Apples", position = "g"), item("${hugo.value}:3", "Bread", checked = true, position = "p")),
      )
    val from =
      list(
        "${hugo.value}:4",
        "Weekend",
        listOf(
          item("${hugo.value}:5", "Xmas pudding", position = "c"),
          item("${hugo.value}:6", "Yoghurt", position = "e"),
          item("${hugo.value}:7", "Zest", checked = true, position = "t"),
        ),
      )

    val after = merged(into, from)

    assertEquals(listOf("Apples", "Xmas pudding", "Yoghurt", "Bread", "Zest"), after.orderedItems.map { it.name.value })
    assertTrue("every one placed, so every phone draws the same list", after.items.all { it.position.value.isNotEmpty() })
  }

  @Test
  fun `copied items follow the other list's order, not its storage order`() {
    val into = list("${hugo.value}:1", "Weekly shop", listOf(item("${hugo.value}:2", "Apples", position = "g")))
    val from =
      list("${hugo.value}:3", "Weekend", listOf(item("${hugo.value}:4", "Second", position = "m"), item("${hugo.value}:5", "First", position = "c")))

    assertEquals(listOf("Apples", "First", "Second"), merged(into, from).orderedItems.map { it.name.value })
  }

  // ── Lists ─────────────────────────────────────────────────────────────────

  private val weekly = list("${hugo.value}:1", "Weekly shop", listOf(item("${hugo.value}:2", "Milk", position = "m")))
  private val weekend = list("${hugo.value}:3", "Weekend", listOf(item("${hugo.value}:4", "Rice", position = "m")))

  private fun phone(vararg lists: GroceryList, active: GroceryList = lists.first()) =
    AppState(
      localDevice = hugo,
      idCounter = 100,
      clockHead = Hlc(4_000, 0, hugo),
      lists = lists.toList(),
      activeListId = active.id,
      networks = emptyList(),
      peers = listOf(TrustedPeer(ava, byteArrayOf(1), "Ava", 0), TrustedPeer(sam, byteArrayOf(2), "Sam", 0)),
    )

  private fun AppState.merging(from: GroceryList, into: GroceryList, ids: IdFactory = ids()) =
    withListMerged(from.id, into.id, ids::nextItem, now)

  @Test
  fun `nothing is offered to merge into when there is only one list`() {
    assertEquals(emptyList<GroceryList>(), phone(weekly).mergeTargetsFor(weekly.id))
  }

  @Test
  fun `every other list this phone shows is offered, and nothing else`() {
    val left = list("${ava.value}:9", "Left", emptyList(), people = listOf(me.copy(removed = Field(true, now)), Person(ava, "Ava", "A")))

    val offered = phone(weekly, weekend, left).mergeTargetsFor(weekend.id)

    assertEquals("not itself, and not one this phone has left", listOf(weekly.id), offered.map { it.id })
  }

  @Test
  fun `a list nobody else has is gone once merged`() {
    val after = phone(weekly, weekend).merging(weekend, weekly)

    assertEquals(listOf(weekly.id), after.lists.map { it.id })
    assertEquals(listOf("Milk", "Rice"), after.lists.single().orderedItems.map { it.name.value })
  }

  @Test
  fun `a shared list merged away is left, the way Delete leaves it, and the others keep their copy`() {
    val shared = weekend.copy(people = listOf(me, Person(ava, "Ava", "A")))

    val after = phone(weekly, shared).merging(shared, weekly)

    assertEquals("out of sight", listOf(weekly.id), after.visibleLists.map { it.id })
    val husk = after.lists.single { it.id == shared.id }
    assertFalse(husk.personFor(hugo)!!.present)
    assertEquals("stamped, so a stale copy cannot put this phone back", now, husk.personFor(hugo)!!.removed.at)
    assertEquals("Ava's copy is left with what it had", shared.items, husk.items)
    assertEquals("exactly what Delete does", phone(weekly, shared).withoutList(shared.id, now).lists.single { it.id == shared.id }, husk)

    // Still sent to Ava, because it is the only way she hears this phone has left it.
    assertTrue(after.payloadFor(ava, HybridClock(hugo))!!.lists.any { it.id == shared.id })
  }

  @Test
  fun `the list merged into keeps exactly the people it had`() {
    val into = weekly.copy(people = listOf(me, Person(sam, "Sam", "S")))
    val from = weekend.copy(people = listOf(me, Person(ava, "Ava", "A")))

    val after = phone(into, from).merging(from, into)

    assertEquals(into.people, after.lists.single { it.id == into.id }.people)
    assertTrue("Ava is not shared with by being on the other list", after.payloadFor(ava, HybridClock(hugo))!!.lists.none { it.id == into.id })
  }

  @Test
  fun `what is merged into a shared list reaches the people on it`() {
    val into = weekly.copy(people = listOf(me, Person(ava, "Ava", "A")))
    val avasCopy = into.copy(people = into.people)
    val avasPhone =
      AppState(
        localDevice = ava,
        idCounter = 10,
        clockHead = Hlc(4_000, 0, ava),
        lists = listOf(avasCopy),
        activeListId = avasCopy.id,
        networks = emptyList(),
        peers = listOf(TrustedPeer(hugo, byteArrayOf(3), "Hugo", 0)),
      )

    val after = phone(into, weekend).merging(weekend, into)
    // Through the wire format as it is, which a 0.1.0 phone reads too.
    val sent = SyncCodec.decode(SyncCodec.encode(after.payloadFor(ava, HybridClock(hugo))!!))!!
    val arrived = (avasPhone.receive(sent, HybridClock(ava)) as SyncResult.Merged).state

    assertEquals(listOf("Milk", "Rice"), arrived.lists.single { it.id == into.id }.orderedItems.map { it.name.value })
  }

  @Test
  fun `a list cannot be merged into itself, or into one that is not there`() {
    val before = phone(weekly, weekend)

    assertSame(before, before.merging(weekend, weekend))
    assertSame(before, before.merging(weekend, list("${hugo.value}:50", "Gone", emptyList())))
  }
}
