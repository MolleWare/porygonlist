package io.github.molleware.porygonlist.ui

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

  private val utc = ZoneId.of("UTC")
  private val now = ZonedDateTime.of(2026, 9, 17, 14, 30, 0, 0, utc).toInstant().toEpochMilli()

  private val me = DeviceId("avaphone01")
  private val hugo = DeviceId("hugophone2")

  private val list =
    GroceryList(
      id = 1,
      name = "Weekly shop",
      accent = ListAccent.ACCENT,
      items = emptyList(),
      people = listOf(Person(me, "Ava", "A"), Person(hugo, "Hugo", "H")),
    )

  private fun item(
    name: String,
    creator: DeviceId = me,
    writer: DeviceId = creator,
    at: Long = now,
    origin: Origin = Origin.LOCAL,
    checked: Boolean = false,
    pending: Boolean = false,
    editing: Boolean = false,
  ): GroceryItem {
    val stamp = Hlc(at, 0, writer)
    return GroceryItem(
      id = ItemId("${creator.value}:1"),
      name = Field(name, stamp),
      qty = Field(1, stamp),
      checked = checked,
      checkedAt = stamp,
      removed = Field(false, stamp),
      origin = origin,
      pending = pending,
      editing = editing,
    )
  }

  @Test
  fun `a change in the last minute reads as just now`() {
    assertEquals("just now", relativeTime(now - 30_000, now, utc))
  }

  @Test
  fun `earlier today shows the time`() {
    val thisMorning = ZonedDateTime.of(2026, 9, 17, 9, 12, 0, 0, utc).toInstant().toEpochMilli()

    assertEquals("9:12", relativeTime(thisMorning, now, utc))
  }

  @Test
  fun `the day before reads as yesterday`() {
    assertEquals("yesterday", relativeTime(now - 26 * 3_600_000L, now, utc))
  }

  @Test
  fun `further back shows the date`() {
    assertEquals("10 Sep", relativeTime(now - 7 * 24 * 3_600_000L, now, utc))
  }

  @Test
  fun `a peer is named you only on its own device`() {
    assertEquals("you", list.nameFor(me, me))
    assertEquals("Hugo", list.nameFor(hugo, me))
    // Seen from Hugo's phone the same two ids read the other way round.
    assertEquals("you", list.nameFor(hugo, hugo))
    assertEquals("Ava", list.nameFor(me, hugo))
  }

  @Test
  fun `an unknown peer is not guessed at`() {
    assertEquals("someone", list.nameFor(DeviceId("strangerxx"), me))
  }

  @Test
  fun `a waiting change says so instead of giving a time`() {
    assertEquals("you, not sent yet", itemSubLabel(item("Butter", pending = true), list, me, now))
  }

  @Test
  fun `an item someone else has open says who is editing`() {
    val open = item("Coffee beans", creator = hugo, editing = true)

    assertEquals("Hugo, editing now", itemSubLabel(open, list, me, now))
  }

  @Test
  fun `the detail line follows the last writer, not the creator`() {
    // Hugo added it; you renamed it. The design shows this as yours.
    val renamed = item("Oat milk", creator = hugo, writer = me, at = now - 30_000)

    assertEquals("you, just now", itemSubLabel(renamed, list, me, now))
  }

  @Test
  fun `the shop line follows the creator, not the last writer`() {
    // Ticking Hugo's item off must not quietly make it your errand.
    val hugosTicked = item("Oat milk", creator = hugo, writer = me)

    assertEquals("Hugo added this", shopSubLabel(hugosTicked.copy(checked = false), list, me))
  }

  @Test
  fun `a merged item is attributed to both`() {
    val merged = item("Eggs", origin = Origin.MERGED, at = now - 30_000)

    assertEquals("you and Hugo, just now", itemSubLabel(merged, list, me, now))
  }

  @Test
  fun `a pasted item says where it came from`() {
    val pasted = item("Lemons", origin = Origin.IMPORTED, at = now - 30_000)

    assertEquals("from a text, just now", itemSubLabel(pasted, list, me, now))
  }

  @Test
  fun `shop labels say whose errand an item is`() {
    assertEquals("Hugo added this", shopSubLabel(item("Oat milk", creator = hugo), list, me))
    assertEquals("you added this", shopSubLabel(item("Butter"), list, me))
    assertEquals("in the trolley", shopSubLabel(item("Butter", checked = true), list, me))
  }

  @Test
  fun `a list card counts what is waiting when off the network`() {
    val waiting = list.copy(items = listOf(item("Butter", pending = true)))

    assertEquals("1 change waiting", listCardMeta(waiting, online = false, localDevice = me))
    assertEquals("in step with Hugo", listCardMeta(waiting, online = true, localDevice = me))
  }

  @Test
  fun `a list nobody else is on says just you`() {
    val solo = list.copy(people = listOf(Person(me, "Ava", "A")))

    assertEquals("just you", listCardMeta(solo, online = true, localDevice = me))
  }

  @Test
  fun `item counts are singular where they should be`() {
    assertEquals("1 item", itemCountLabel(list.copy(items = listOf(item("Butter")))))
    assertEquals("0 items", itemCountLabel(list))
  }

  // ── The clash card ────────────────────────────────────────────────────────

  @Test
  fun `each side of a clash reads as a sentence`() {
    val mine = item("Eggs", creator = me, at = hoursAgo(5))
    val theirs = item("Eggs", creator = hugo, at = hoursAgo(5) + 60_000)

    assertEquals("you added it at 9:30", conflictSide(mine, list, me, now, utc))
    assertEquals("Hugo added it at 9:31", conflictSide(theirs, list, me, now, utc))
  }

  @Test
  fun `a clash is explained with words, never an item dumped to text`() {
    val side = conflictSide(item("Eggs", at = hoursAgo(5)), list, me, now, utc)

    // What this replaced put `GroceryItem.toString()` on the screen, ids, stamps and all.
    assertEquals(false, side.contains("GroceryItem"))
    assertEquals(false, side.contains("Hlc"))
    assertEquals(false, side.contains(me.value))
  }

  @Test
  fun `the time reads naturally however long ago it was`() {
    assertEquals("you added it just now", conflictSide(item("Eggs", at = now - 30_000), list, me, now, utc))
    assertEquals("you added it yesterday", conflictSide(item("Eggs", at = hoursAgo(20)), list, me, now, utc))
    assertEquals("you added it on 3 Sep", conflictSide(item("Eggs", at = hoursAgo(24 * 14)), list, me, now, utc))
  }

  @Test
  fun `a clash names whoever added it, not whoever wrote last`() {
    val hugosItem = item("Eggs", creator = hugo, writer = me, at = hoursAgo(2))

    assertEquals("Hugo added it at 12:30", conflictSide(hugosItem, list, me, now, utc))
  }

  private fun hoursAgo(hours: Int) = now - hours * 3_600_000L
}
