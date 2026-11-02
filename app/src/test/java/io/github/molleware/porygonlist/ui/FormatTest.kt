package io.github.molleware.porygonlist.ui

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.net.BackgroundSwitch
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
  fun `the time follows the phone's 12 or 24 hour setting`() {
    val evening = ZonedDateTime.of(2026, 9, 17, 21, 30, 0, 0, utc).toInstant().toEpochMilli()
    val later = evening + 3_600_000L

    assertEquals("21:30", relativeTime(evening, later, utc, is24Hour = true))
    // The AM/PM marker is the phone's language's own; the hour is what the setting decides.
    val twelve = relativeTime(evening, later, utc, is24Hour = false)
    assertTrue(twelve, twelve.startsWith("9:30") && !twelve.contains("21"))
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
  fun `an approved network is stamped with the date, never a relative time`() {
    assertEquals("Approved 17 Sep", approvedDetail(now, utc))
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
  fun `an item says which recipes it is for, joined as names are`() {
    val eggs = item("Eggs")

    assertEquals("", recipeNote(eggs))
    assertEquals("for Lasagne", recipeNote(eggs.forRecipe("Lasagne", Hlc(now, 1, me))))
    assertEquals(
      "for Lasagne, Pancakes & Quiche",
      recipeNote(eggs.forRecipe("Lasagne", Hlc(now, 1, me)).forRecipe("Pancakes", Hlc(now, 2, me)).forRecipe("Quiche", Hlc(now, 3, me))),
    )
  }

  @Test
  fun `what an item is for comes after what its line already said`() {
    val eggs = item("Eggs", at = now - 30_000).forRecipe("Lasagne", Hlc(now - 30_000, 1, me)).forRecipe("Pancakes", Hlc(now - 30_000, 2, me))

    assertEquals("you, just now · for Lasagne & Pancakes", itemSubLabel(eggs, list, me, now))
    assertEquals("you added this · for Lasagne & Pancakes", shopSubLabel(eggs, list, me))
    assertEquals("in the trolley · for Lasagne & Pancakes", shopSubLabel(eggs.copy(checked = true), list, me))
    assertEquals("and nothing at all for none", "you, just now", itemSubLabel(item("Eggs", at = now - 30_000), list, me, now))
  }

  @Test
  fun `a list nobody else is on says just you`() {
    val solo = list.copy(people = listOf(Person(me, "Ava", "A")))

    assertEquals("just you", listCardMeta(solo, localDevice = me, delivered = DeliveryLog()))
  }

  // ── The copy kept after being taken off a shared list ────────────────────

  private val kept = list.copy(people = listOf(Person(me, "Ava", "A")), wasSharedWith = listOf("Hugo"))

  @Test
  fun `a kept copy says who it was shared with, not just you`() {
    assertEquals("was shared with Hugo", listCardMeta(kept, me, DeliveryLog()))
    assertEquals("was shared with Hugo & Sam", listCardMeta(kept.copy(wasSharedWith = listOf("Hugo", "Sam")), me, DeliveryLog()))
  }

  @Test
  fun `a kept copy still gives way to a question or a new arrival`() {
    assertEquals("a change needs you", listCardMeta(kept, me, DeliveryLog(), needsAnswer = true))
    assertEquals("new, from Hugo", listCardMeta(kept.copy(arrivedFrom = hugo), me, DeliveryLog(), paired = listOf(hugoPaired)))
  }

  @Test
  fun `shared with someone again, it reads as any shared list`() {
    val sharedAgain = kept.copy(people = kept.people + Person(sam, "Sam", "S"))

    assertEquals("not handed over yet", listCardMeta(sharedAgain, me, DeliveryLog()))
  }

  // ── Merging one list into another ─────────────────────────────────────────

  private val weekend = list.copy(id = ListId("list:2"), name = "Weekend", people = listOf(Person(me, "Ava", "A")))
  private val solo = list.copy(people = listOf(Person(me, "Ava", "A")))

  @Test
  fun `the merge question names both lists`() {
    assertEquals("Merge Weekend into Weekly shop?", mergeQuestion(weekend, list))
  }

  @Test
  fun `merging away a list of your own says it is then deleted`() {
    assertEquals("Weekend is then deleted.", mergeConsequence(weekend, solo, me))
  }

  @Test
  fun `merging away a shared list says, as Delete does, that the others keep their copy`() {
    val shared = weekend.copy(people = weekend.people + Person(hugo, "Hugo", "H"))
    val sharedWithTwo = shared.copy(people = shared.people + Person(sam, "Sam", "S"))

    assertEquals("Weekend is then deleted from this phone only — Hugo keeps a copy.", mergeConsequence(shared, solo, me))
    assertEquals(
      "Weekend is then deleted from this phone only — Hugo & Sam keep a copy.",
      mergeConsequence(sharedWithTwo, solo, me),
    )
  }

  @Test
  fun `merging into a shared list says who will see what was on the other`() {
    assertEquals("Weekend is then deleted. Hugo will see everything on it.", mergeConsequence(weekend, list, me))
  }

  // ── A list someone shared, not yet opened ─────────────────────────────────

  private val hugoPaired = TrustedPeer(hugo, byteArrayOf(1), "Hugo", pairedAt = now)

  @Test
  fun `a new list still names its sender once they are unpaired`() {
    val arrived = list.copy(arrivedFrom = hugo)
    val unpaired = stateWith(arrived).copy(peers = listOf(hugoPaired)).retireDevice(hugo, Hlc(now, 0, me))

    // Off the list, but kept on it as a tombstone until his phone hears, and his name with it.
    assertEquals("new, from Hugo", listCardMeta(unpaired.lists.single(), localDevice = me, delivered = DeliveryLog()))
  }

  @Test
  fun `a sender the list no longer names is named from pairing`() {
    // He left, everyone heard, and his tombstone was collected; this phone is still paired with his.
    val forgotten = list.copy(people = listOf(Person(me, "Ava", "A")), arrivedFrom = hugo)

    assertEquals("new, from Hugo", listCardMeta(forgotten, me, DeliveryLog(), paired = listOf(hugoPaired)))
  }

  @Test
  fun `a sender nobody here knows any more is someone`() {
    val forgotten = list.copy(people = listOf(Person(me, "Ava", "A")), arrivedFrom = hugo)

    assertEquals("new, from someone", listCardMeta(forgotten, me, DeliveryLog(), paired = emptyList()))
  }

  // ── The clock on the Lists screen ─────────────────────────────────────────

  private val sundayAfternoon = ZonedDateTime.of(2026, 11, 1, 14, 5, 30, 0, utc).toInstant().toEpochMilli()

  @Test
  fun `the clock reads the day, the date and the time`() {
    assertEquals("Sunday 1 November · 14:05", clockLine(sundayAfternoon, utc, is24Hour = true))
  }

  @Test
  fun `the clock follows the phone's 12 or 24 hour setting`() {
    val twelve = clockLine(sundayAfternoon, utc, is24Hour = false)

    // The AM/PM marker is the phone's language's own; the hour is what the setting decides.
    assertTrue(twelve, twelve.startsWith("Sunday 1 November · 2:05") && !twelve.contains("14"))
  }

  @Test
  fun `the clock waits exactly until the next minute`() {
    assertEquals(29_500, millisToNextMinute(sundayAfternoon + 500))
    val onTheMinute = ZonedDateTime.of(2026, 11, 1, 14, 6, 0, 0, utc).toInstant().toEpochMilli()
    assertEquals("a full minute from the minute itself", 60_000, millisToNextMinute(onTheMinute))
  }

  // ── In step, from receipts ────────────────────────────────────────────────

  private val sam = DeviceId("samphone03")
  private val shopping = list.copy(items = listOf(item("Butter", at = now - 60_000)))

  @Test
  fun `in step only once the other phone has confirmed the latest change`() {
    val confirmed = DeliveryLog().record(hugo, Hlc(now, 0, me))
    assertEquals("in step with Hugo", listCardMeta(shopping, localDevice = me, delivered = confirmed))
  }

  @Test
  fun `a phone that never confirmed anything has not been handed the list`() {
    assertEquals("not handed over yet", listCardMeta(shopping, localDevice = me, delivered = DeliveryLog()))
  }

  @Test
  fun `a phone that confirmed an older state is waited for`() {
    val stale = DeliveryLog().record(hugo, Hlc(now - 120_000, 0, me))
    assertEquals("waiting for Hugo", listCardMeta(shopping, localDevice = me, delivered = stale))
  }

  @Test
  fun `three people are all named, and only the one behind is waited for`() {
    val three = shopping.copy(people = shopping.people + Person(sam, "Sam", "S"))
    val oneBehind = DeliveryLog().record(hugo, Hlc(now, 0, me)).record(sam, Hlc(now - 120_000, 0, me))
    val bothIn = oneBehind.record(sam, Hlc(now, 0, me))

    assertEquals("waiting for Sam", listCardMeta(three, localDevice = me, delivered = oneBehind))
    assertEquals("in step with Hugo & Sam", listCardMeta(three, localDevice = me, delivered = bothIn))
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

  private fun stateWith(l: GroceryList, delivered: DeliveryLog = DeliveryLog()) =
    AppState(
      localDevice = me,
      idCounter = 0,
      clockHead = Hlc(now, 0, me),
      lists = listOf(l),
      activeListId = l.id,
      networks = emptyList(),
      peers = emptyList(),
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
  }

  @Test
  fun `behind a VPN the banner says so, rather than blaming the other phone`() {
    // Pixel, always-on Proton, 2026-10-24: "Waiting for Sam" all evening, and the VPN the reason.
    assertEquals("VPN is on", syncHeadline(stateWith(shopping), vpnOn = true))
    assertEquals("VPN is on", syncHeadline(stateWith(shopping, DeliveryLog().record(hugo, Hlc(now, 0, me))), vpnOn = true))
    val solo = shopping.copy(people = listOf(Person(me, "Ava", "A")))
    assertEquals("with nobody to sync with, a VPN changes nothing", "Nobody to sync with yet", syncHeadline(stateWith(solo), vpnOn = true))
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

  @Test
  fun `background sync off without alarms says, before the tap, that on needs them`() {
    assertTrue(backgroundSyncNote(BackgroundSwitch.OFF, alarmsAllowed = false).contains("needs alarms allowed"))
    assertTrue(backgroundSyncAsk(BackgroundSwitch.OFF, alarmsAllowed = false).contains("needs alarms allowed"))
    // With them allowed there is nothing to explain: the tap simply turns it on.
    assertEquals("Lists sync only while the app is open.", backgroundSyncNote(BackgroundSwitch.OFF, alarmsAllowed = true))
  }

  @Test
  fun `alarms revoked under background sync says they were turned off, and the way back`() {
    for (text in listOf(backgroundSyncNote(BackgroundSwitch.ALARMS_TURNED_OFF, false), backgroundSyncAsk(BackgroundSwitch.ALARMS_TURNED_OFF, false))) {
      assertTrue(text, text.startsWith("Alarms were turned off"))
      assertTrue(text, text.endsWith("Turn it on to allow them again."))
    }
  }
}
