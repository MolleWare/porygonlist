package io.github.molleware.porygonlist.ui

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.ListId
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
      id = ListId("list:1"),
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

    assertEquals("1 change waiting", listCardMeta(waiting, online = false, localDevice = me, delivered = DeliveryLog()))
  }

  @Test
  fun `a list nobody else is on says just you`() {
    val solo = list.copy(people = listOf(Person(me, "Ava", "A")))

    assertEquals("just you", listCardMeta(solo, online = true, localDevice = me, delivered = DeliveryLog()))
  }

  // ── In step, from receipts ────────────────────────────────────────────────
  // Found on hardware: a card read "in step with Ava" about a list her phone had never received,
  // because it only checked that this phone was online.

  private val sam = DeviceId("samphone03")
  private val shopping = list.copy(items = listOf(item("Butter", at = now - 60_000)))

  @Test
  fun `in step only once the other phone has confirmed the latest change`() {
    val confirmed = DeliveryLog().record(hugo, Hlc(now, 0, me))
    assertEquals("in step with Hugo", listCardMeta(shopping, online = true, localDevice = me, delivered = confirmed))
  }

  @Test
  fun `a phone that never confirmed anything has not been handed the list`() {
    assertEquals("not handed over yet", listCardMeta(shopping, online = true, localDevice = me, delivered = DeliveryLog()))
  }

  @Test
  fun `a phone that confirmed an older state is waited for`() {
    val stale = DeliveryLog().record(hugo, Hlc(now - 120_000, 0, me))
    assertEquals("waiting for Hugo", listCardMeta(shopping, online = true, localDevice = me, delivered = stale))
  }

  @Test
  fun `three people are all named, and only the one behind is waited for`() {
    val three = shopping.copy(people = shopping.people + Person(sam, "Sam", "S"))
    val oneBehind = DeliveryLog().record(hugo, Hlc(now, 0, me)).record(sam, Hlc(now - 120_000, 0, me))
    val bothIn = oneBehind.record(sam, Hlc(now, 0, me))

    assertEquals("waiting for Sam", listCardMeta(three, online = true, localDevice = me, delivered = oneBehind))
    assertEquals("in step with Hugo & Sam", listCardMeta(three, online = true, localDevice = me, delivered = bothIn))
  }

  @Test
  fun `names read as a sentence however many there are`() {
    val hugoP = Person(hugo, "Hugo", "H")
    val samP = Person(sam, "Sam", "S")
    val lea = Person(DeviceId("leaphone004"), "Léa", "L")
    assertEquals("Hugo", names(listOf(hugoP)))
    assertEquals("Hugo & Sam", names(listOf(hugoP, samP)))
    assertEquals("Hugo, Sam & Léa", names(listOf(hugoP, samP, lea)))
  }

  private fun stateWith(l: GroceryList, delivered: DeliveryLog = DeliveryLog(), online: Boolean = true) =
    AppState(
      localDevice = me,
      idCounter = 0,
      clockHead = Hlc(now, 0, me),
      lists = listOf(l),
      activeListId = l.id,
      online = online,
      networks = emptyList(),
      peers = emptyList(),
      conflict = null,
      deliveredTo = delivered,
    )

  @Test
  fun `the banner says when something last reached another phone, not a moment ago`() {
    val delivered = DeliveryLog().record(hugo, Hlc(hoursAgo(2), 0, me))
    assertEquals("Home · last handover at 12:30", syncDetail(stateWith(shopping, delivered), "Home", now, utc))
    assertEquals("Home · nothing handed over yet", syncDetail(stateWith(shopping), "Home", now, utc))
  }

  @Test
  fun `the banner waits for whoever is behind`() {
    assertEquals("Waiting for Hugo", syncHeadline(stateWith(shopping)))
    assertEquals("In step with Hugo", syncHeadline(stateWith(shopping, DeliveryLog().record(hugo, Hlc(now, 0, me)))))
    assertEquals("Off the network", syncHeadline(stateWith(shopping, online = false)))
  }

  @Test
  fun `the edit sheet names everyone a change goes to`() {
    val three = shopping.copy(people = shopping.people + Person(sam, "Sam", "S"))
    assertEquals("Goes to Hugo as soon as their phone is around.", editSyncNote(stateWith(shopping)))
    assertEquals("Goes to Hugo & Sam as soon as their phones are around.", editSyncNote(stateWith(three)))
    assertEquals("It goes from your phone and Hugo's.", removeNote(stateWith(shopping)))
    assertEquals("It goes from everyone's phone on this list.", removeNote(stateWith(three)))
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

  // The line the design shipped as a fixed "Last handover this morning", read at 22:30 on hardware.

  @Test
  fun `a phone nothing has been handed to says so`() {
    assertEquals("Nothing handed over yet", handoverLabel(null, now, utc))
  }

  @Test
  fun `a handover is dated from the receipt, not invented`() {
    assertEquals("Last handover just now", handoverLabel(Hlc(now - 10_000, 0, hugo), now, utc))
    assertEquals("Last handover at 12:30", handoverLabel(Hlc(hoursAgo(2), 0, hugo), now, utc))
    assertEquals("Last handover yesterday", handoverLabel(Hlc(hoursAgo(24), 0, hugo), now, utc))
    assertEquals("Last handover on 10 Sep", handoverLabel(Hlc(hoursAgo(24 * 7), 0, hugo), now, utc))
  }
}
