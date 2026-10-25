package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.OrderKey
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.merge
import io.github.molleware.porygonlist.data.sync.receive
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving items and lists, and pinning lists.
 *
 * Item order is shared: one key per item, so two people moving two different items both keep their
 * move, and the same item moved twice settles on the later move. List order and pins are this
 * phone's own and never leave it.
 */
class ReorderingTest {

  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")
  private val listId = ListId("${ava.value}:100")

  private fun item(n: Int, at: Long = 1_000) =
    Hlc(at, 0, ava).let { stamp ->
      GroceryItem(ItemId("${ava.value}:$n"), Field("item $n", stamp), Field(1, stamp), checkedAt = stamp, removed = Field(false, stamp))
    }

  private fun list(vararg ns: Int) =
    GroceryList(listId, "Weekly shop", ListAccent.ACCENT, ns.map { item(it) }, listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H")))

  private fun GroceryList.names() = orderedItems.map { it.name.value.removePrefix("item ").toInt() }

  // ── Keys ──────────────────────────────────────────────────────────────────

  @Test
  fun `there is always a key between two neighbours`() {
    val random = Random(7)
    val keys = mutableListOf(OrderKey.between(null, null))
    repeat(2_000) {
      val at = random.nextInt(keys.size + 1)
      val key = OrderKey.between(keys.getOrNull(at - 1), keys.getOrNull(at))
      keys.add(at, key)
    }
    assertEquals("inserting in place keeps them sorted", keys.sorted(), keys)
    assertEquals("and distinct", keys.size, keys.toSet().size)
    assertTrue("none ends in the lowest digit", keys.none { it.endsWith('0') })
  }

  @Test
  fun `a spread is in order`() {
    val keys = OrderKey.spread(50)
    assertEquals(keys.sorted(), keys)
    assertEquals(50, keys.toSet().size)
  }

  // ── Items ─────────────────────────────────────────────────────────────────

  @Test
  fun `a list nobody has arranged keeps the order things were added`() {
    assertEquals(listOf(1, 2, 3), list(1, 2, 3).names())
  }

  @Test
  fun `the first move gives the whole list positions, later moves touch one item`() {
    val first = list(1, 2, 3, 4).withItemMoved(ItemId("${ava.value}:4"), 0, Hlc(2_000, 0, ava))
    assertEquals(listOf(4, 1, 2, 3), first.names())
    assertTrue(first.items.all { it.position.value.isNotEmpty() })

    val second = first.withItemMoved(ItemId("${ava.value}:1"), 3, Hlc(3_000, 0, ava))
    assertEquals(listOf(4, 2, 3, 1), second.names())
    val changed = second.items.filter { it.position.at == Hlc(3_000, 0, ava) }.map { it.id.value }
    assertEquals("only the moved item is rewritten", listOf("${ava.value}:1"), changed)
  }

  @Test
  fun `a move does not make the mover the author of what it placed`() {
    // Seen on hardware: Hugo ticked one item in Ava's never-arranged list, the tick placed every
    // item, and every row then read "Hugo, just now".
    val tick = Hlc(2_000, 0, hugo)
    val one = ItemId("${ava.value}:1")
    val ticked =
      list(1, 2, 3)
        .let { l -> l.copy(items = l.items.map { if (it.id == one) it.copy(checked = true, checkedAt = tick) else it }) }
        .withItemSettled(one, tick)

    assertTrue("the tick did place every item", ticked.items.all { it.position.at == tick })
    val untouched = ticked.items.filterNot { it.id == one }
    assertEquals(listOf(ava, ava), untouched.map { it.lastWriter })
    assertEquals(listOf(1_000L, 1_000L), untouched.map { it.at })
    assertEquals("the tick itself is still Hugo's", hugo, ticked.items.single { it.id == one }.lastWriter)
  }

  @Test
  fun `something added to an arranged list goes to the bottom`() {
    val arranged = list(1, 2, 3).withItemMoved(ItemId("${ava.value}:3"), 0, Hlc(2_000, 0, ava))
    val added = arranged.withItemsAppended(listOf(item(9)), Hlc(3_000, 0, ava))
    assertEquals(listOf(3, 1, 2, 9), added.names())
  }

  private fun GroceryList.ticked(n: Int, at: Long): GroceryList {
    val id = ItemId("${ava.value}:$n")
    val stamp = Hlc(at, 0, ava)
    return copy(items = items.map { if (it.id == id) it.copy(checked = true, checkedAt = stamp) else it }).withItemSettled(id, stamp)
  }

  @Test
  fun `something added goes above the trolley, not into it`() {
    // Seen on hardware: Jam, added after Milk was ticked, landed below Milk.
    val shopping = list(1, 2, 3).ticked(1, 2_000)
    assertEquals(listOf(2, 3, 1), shopping.names())

    val added = shopping.withItemsAppended(listOf(item(8), item(9)), Hlc(3_000, 0, ava))

    assertEquals(listOf(2, 3, 8, 9, 1), added.names())
  }

  @Test
  fun `with everything ticked, something added goes to the top`() {
    val done = list(1, 2).ticked(1, 2_000).ticked(2, 3_000)

    assertEquals(listOf(9, 1, 2), done.withItemsAppended(listOf(item(9)), Hlc(4_000, 0, ava)).names())
  }

  @Test
  fun `something added to a list nobody has arranged gets a position, and keeps the order`() {
    val added = list(1, 2, 3).withItemsAppended(listOf(item(9)), Hlc(3_000, 0, ava))

    assertEquals(listOf(1, 2, 3, 9), added.names())
    assertTrue("every item placed", added.liveItems.all { it.position.value.isNotEmpty() })
    assertEquals("and nobody made the author of the others", listOf(1_000L, 1_000L, 1_000L), added.liveItems.take(3).map { it.at })
  }

  @Test
  fun `items added on two phones to an unarranged list draw in the same order on both`() {
    // Seen in session 5: the same 8 items, the last three in a different order on each phone,
    // because unplaced items sort in each phone's own arrival order.
    val base = list(1, 2)
    val avas = base.withItemsAppended(listOf(item(8)), Hlc(2_000, 0, ava))
    val hugos = base.withItemsAppended(listOf(item(9).copy(id = ItemId("${hugo.value}:9"))), Hlc(2_100, 0, hugo))

    val onAva = merge(avas, hugos).list
    val onHugo = merge(hugos, avas).list

    assertEquals(onAva.orderedItems.map { it.id }, onHugo.orderedItems.map { it.id })
  }

  @Test
  fun `an item added before the other phone's first tick arrives does not jump to the top`() {
    // Seen on hardware: Butter, added on the Pixel while its copy was still unarranged, had no
    // position, and unplaced items always sort first — so it went to the top once Ava's tick landed.
    val base = list(1, 2, 3)
    val avaTicked = base.ticked(1, 2_000)
    val hugoAdded = base.withItemsAppended(listOf(item(9).copy(id = ItemId("${hugo.value}:9"))), Hlc(2_100, 0, hugo))

    val onHugo = merge(hugoAdded, avaTicked).list

    assertFalse("not first", onHugo.orderedItems.first().id == ItemId("${hugo.value}:9"))
  }

  @Test
  fun `two people moving different items both keep their move`() {
    // A shared starting point where everything already has a position: 2 up, then 1 back above it.
    val base =
      list(1, 2, 3, 4)
        .withItemMoved(ItemId("${ava.value}:2"), 0, Hlc(1_500, 0, ava))
        .withItemMoved(ItemId("${ava.value}:1"), 0, Hlc(1_600, 0, ava))
    assertEquals(listOf(1, 2, 3, 4), base.names())

    val avas = base.withItemMoved(ItemId("${ava.value}:4"), 0, Hlc(2_000, 0, ava)) // 4 to the top
    val hugos = base.withItemMoved(ItemId("${ava.value}:1"), 3, Hlc(2_000, 0, hugo)) // 1 to the bottom

    val onAva = merge(avas, hugos).list
    val onHugo = merge(hugos, avas).list
    assertEquals(listOf(4, 2, 3, 1), onAva.names())
    assertEquals("both phones draw the same list", onAva.names(), onHugo.names())
  }

  @Test
  fun `the same item moved on both phones settles on the later move, with no question asked`() {
    val base = list(1, 2, 3).withItemMoved(ItemId("${ava.value}:3"), 0, Hlc(1_500, 0, ava))
    val avas = base.withItemMoved(ItemId("${ava.value}:1"), 2, Hlc(2_000, 0, ava))
    val hugos = base.withItemMoved(ItemId("${ava.value}:1"), 0, Hlc(2_500, 0, hugo))

    val result = merge(avas, hugos)
    assertEquals(listOf(1, 3, 2), result.list.names())
    assertTrue("a move is never a clash", result.clashes.isEmpty())
  }

  // ── On disk and on the wire ───────────────────────────────────────────────

  private fun stateOf(vararg lists: GroceryList) =
    AppState(
      localDevice = ava,
      idCounter = 200,
      clockHead = Hlc(5_000, 0, ava),
      lists = lists.toList(),
      activeListId = lists.first().id,
      networks = emptyList(),
      peers = listOf(TrustedPeer(hugo, byteArrayOf(1), "Hugo", 0)),
    )

  @Test
  fun `positions and pins survive a save`() {
    val arranged = list(1, 2, 3).withItemMoved(ItemId("${ava.value}:3"), 0, Hlc(2_000, 0, ava)).copy(pinned = true)
    val back = StateCodec.decode(StateCodec.encode(stateOf(arranged)))!!

    assertEquals(listOf(3, 1, 2), back.lists.single().names())
    assertTrue(back.lists.single().pinned)
  }

  @Test
  fun `positions travel to the other phone, pins do not`() {
    val arranged = list(1, 2, 3).withItemMoved(ItemId("${ava.value}:3"), 0, Hlc(2_000, 0, ava)).copy(pinned = true)
    val sent = SyncCodec.decode(SyncCodec.encode(SyncPayload(ava, Hlc(5_000, 0, ava), listOf(arranged))))!!

    assertEquals(listOf(3, 1, 2), sent.lists.single().names())
    assertFalse("a pin is this phone's own", sent.lists.single().pinned)
  }

  @Test
  fun `an item written before reordering reads as never placed`() {
    val line = StateCodec.encode(stateOf(list(1)))
    // Drop the three position columns, as a file from an older build would have them.
    val old = line.lines().joinToString("\n") { l -> if (l.startsWith("item|")) l.split('|').dropLast(3).joinToString("|") else l }
    val back = StateCodec.decode(old)!!

    assertEquals("", back.lists.single().items.single().position.value)
  }

  // ── Lists ─────────────────────────────────────────────────────────────────

  private fun named(n: Int, pinned: Boolean = false) =
    GroceryList(ListId("${ava.value}:$n"), "list $n", ListAccent.ACCENT, emptyList(), listOf(Person(ava, "Ava", "A")), pinned = pinned)

  private fun AppState.shown() = orderedVisibleLists.map { it.name.removePrefix("list ").toInt() }

  @Test
  fun `pinned lists come first`() {
    val s = stateOf(named(1), named(2, pinned = true), named(3), named(4, pinned = true))
    assertEquals(listOf(2, 4, 1, 3), s.shown())
  }

  @Test
  fun `a list moves within its own group`() {
    val s = stateOf(named(1, pinned = true), named(2), named(3), named(4))
    assertEquals(listOf(1, 4, 2, 3), s.withListMoved(ListId("${ava.value}:4"), 1).shown())
    // Dragged above the pinned one, it stops at the top of the unpinned group.
    assertEquals(listOf(1, 4, 2, 3), s.withListMoved(ListId("${ava.value}:4"), 0).shown())
  }

  @Test
  fun `pinning moves a list up to the pinned group`() {
    val s = stateOf(named(1, pinned = true), named(2), named(3))
    assertEquals(listOf(1, 3, 2), s.withPinned(ListId("${ava.value}:3"), true).shown())
    assertEquals(listOf(2, 3, 1), s.withPinned(ListId("${ava.value}:1"), false).shown())
  }

  @Test
  fun `an exchange keeps this phone's arrangement and pins`() {
    val mine = list(1).copy(pinned = true)
    val other = named(7)
    val s = stateOf(other, mine).withListMoved(listId, 0)

    val theirs = list(1, 2).copy(pinned = false)
    val result = s.receive(SyncPayload(hugo, Hlc(6_000, 0, hugo), listOf(theirs)), HybridClock(ava))
    val merged = (result as SyncResult.Merged).state

    assertEquals(listOf(listId, other.id), merged.orderedVisibleLists.map { it.id })
    assertTrue(merged.lists.first { it.id == listId }.pinned)
  }
}
