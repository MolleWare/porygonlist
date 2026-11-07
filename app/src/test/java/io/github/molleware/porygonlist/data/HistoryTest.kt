package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.IdFactory
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.payloadFor
import io.github.molleware.porygonlist.data.sync.receive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val AVA = DeviceId("AVAAAAAAAAAAAAAA")

private val WEEKLY = ListId("${HUGO.value}:1")
private val PARTY = ListId("${HUGO.value}:2")

private fun at(wall: Long, device: DeviceId = HUGO) = Hlc(wall, 0, device)

private var nextItem = 100

private fun item(name: String, qty: Int = 1, ticked: Boolean = false, removed: Boolean = false) =
  at(1_000).let { stamp ->
    GroceryItem(
      id = ItemId("${HUGO.value}:${nextItem++}"),
      name = Field(name, stamp),
      qty = Field(qty, stamp),
      checked = ticked,
      checkedAt = stamp,
      removed = Field(removed, stamp),
    )
  }

private fun aList(id: ListId, vararg items: GroceryItem, people: List<Person> = listOf(Person(HUGO, "Hugo", "H"))) =
  GroceryList(id, "List $id", ListAccent.ACCENT, items.toList(), people)

private val WITH_AVA = listOf(Person(HUGO, "Hugo", "H"), Person(AVA, "Ava", "A"))

private fun hugos(vararg lists: GroceryList) =
  AppState(
    localDevice = HUGO,
    displayName = "Hugo",
    idCounter = 1_000,
    clockHead = at(1_000),
    lists = lists.toList().ifEmpty { listOf(aList(WEEKLY)) },
    activeListId = WEEKLY,
    networks = emptyList(),
    peers = listOf(TrustedPeer(AVA, byteArrayOf(1), "Ava", 0)),
  )

private fun run(wall: Long, vararg items: Ingredient) = ClearedRun(at(wall), items.toList())

private fun runs(vararg walls: Long) = walls.map { run(it, Ingredient("Eggs $it", 2)) }

private fun AppState.withHistory(id: ListId, vararg walls: Long) = copy(history = history + (id to runs(*walls)))

private fun saved(state: AppState) = StateCodec.decode(StateCodec.encode(state))!!

/** The history a save read back, with everything else about it set aside. */
private fun historyOfSave(text: String) = StateCodec.decode(text)!!.history

/**
 * What this phone cleared from each list, kept on this phone: only ever its own presses, in a record
 * 0.1.0 skips, and nothing about it on the wire.
 */
class HistoryTest {

  // ── Recording ─────────────────────────────────────────────────────────────

  @Test
  fun `a run is the ticked items still on the list, in the order shown, at the press`() {
    val state =
      hugos(
        aList(
          WEEKLY,
          item("Bread"),
          item("Eggs", qty = 2, ticked = true),
          item("Gone", ticked = true, removed = true),
          item("Milk", ticked = true),
        )
      )

    val after = state.withClearRecorded(WEEKLY, at(2_000))

    assertEquals(listOf(ClearedRun(at(2_000), listOf(Ingredient("Eggs", 2), Ingredient("Milk")))), after.historyOf(WEEKLY))
  }

  @Test
  fun `a press that clears nothing records nothing`() {
    val nothingTicked = hugos(aList(WEEKLY, item("Bread"), item("Gone", ticked = true, removed = true)))

    assertSame(nothingTicked, nothingTicked.withClearRecorded(WEEKLY, at(2_000)))
    assertEquals(emptyMap<ListId, List<ClearedRun>>(), nothingTicked.withClearRecorded(WEEKLY, at(2_000)).history)
  }

  @Test
  fun `a press on a list that has gone records nothing`() {
    val state = hugos()

    assertSame(state, state.withClearRecorded(PARTY, at(2_000)))
  }

  @Test
  fun `a quantity of nothing is kept as one, as a recipe reads it`() {
    // A pasted "0 eggs" makes such an item, and put back it would ask for none.
    val state = hugos(aList(WEEKLY, item("Eggs", qty = 0, ticked = true)))

    assertEquals(listOf(Ingredient("Eggs", 1)), state.withClearRecorded(WEEKLY, at(2_000)).historyOf(WEEKLY).single().items)
  }

  @Test
  fun `the newest run goes first, and the oldest drops off the 51st`() {
    var state = hugos(aList(WEEKLY, item("Eggs", ticked = true)))
    for (press in 1L..51L) state = state.withClearRecorded(WEEKLY, at(press))

    val kept = state.historyOf(WEEKLY).map { it.at.wall }
    assertEquals(50, kept.size)
    assertEquals((51L downTo 2L).toList(), kept)
  }

  @Test
  fun `each list keeps its own`() {
    val state = hugos(aList(WEEKLY, item("Eggs", ticked = true)), aList(PARTY, item("Crisps", ticked = true)))

    val after = state.withClearRecorded(WEEKLY, at(2_000)).withClearRecorded(PARTY, at(3_000))

    assertEquals("Eggs", after.historyOf(WEEKLY).single().items.single().name)
    assertEquals("Crisps", after.historyOf(PARTY).single().items.single().name)
  }

  @Test
  fun `two presses in the same minute of a clock held up by a peer's are still two runs, in order`() {
    val state = hugos(aList(WEEKLY, item("Eggs", ticked = true)))

    val after = state.withClearRecorded(WEEKLY, Hlc(2_000, 1, HUGO)).withClearRecorded(WEEKLY, Hlc(2_000, 2, HUGO))

    assertEquals(listOf(2, 1), after.historyOf(WEEKLY).map { it.at.counter })
  }

  @Test
  fun `a clear heard from another phone is not recorded`() {
    val eggs = item("Eggs", ticked = true)
    val mine = aList(WEEKLY, eggs, people = WITH_AVA)
    val cleared = mine.copy(items = listOf(eggs.copy(removed = eggs.removed.set(true, at(2_000, AVA)))))

    val result = hugos(mine).receive(SyncPayload(AVA, at(2_100, AVA), listOf(cleared)), HybridClock(HUGO, now = { 9_000 }))

    assertEquals(emptyMap<ListId, List<ClearedRun>>(), (result as SyncResult.Merged).state.history)
  }

  @Test
  fun `an exchange leaves the history alone`() {
    val mine = aList(WEEKLY, item("Eggs"), people = WITH_AVA)
    val state = hugos(mine).withHistory(WEEKLY, 2_000)

    val result = state.receive(SyncPayload(AVA, at(2_100, AVA), listOf(mine)), HybridClock(HUGO, now = { 9_000 }))

    assertEquals(state.history, (result as SyncResult.Merged).state.history)
  }

  // ── Never on the wire ─────────────────────────────────────────────────────

  @Test
  fun `a payload built from a list with history has none of it`() {
    val list = aList(WEEKLY, item("Bread"), people = WITH_AVA)
    val state = hugos(list).copy(history = mapOf(WEEKLY to listOf(ClearedRun(at(123_456), listOf(Ingredient("Secret sauce", 7))))))

    val payload = state.payloadFor(AVA, HybridClock(HUGO, now = { 9_000 }))!!
    val wire = SyncCodec.encode(payload)

    assertEquals("the list itself is sent, unchanged", list, payload.lists.single())
    assertFalse(wire.contains("Secret sauce"))
    assertFalse(wire.contains("123456"))
    assertFalse(wire.lines().any { it.startsWith("history|") })
    assertEquals("and history makes no difference to what is sent", SyncCodec.encode(hugos(list).payloadFor(AVA, HybridClock(HUGO, now = { 9_000 }))!!), wire)
  }

  // ── The save ──────────────────────────────────────────────────────────────

  @Test
  fun `runs survive a save, on their own lists, newest first, a bar in a name and all`() {
    val weekly = listOf(run(3_000, Ingredient("Salt | pepper", 2), Ingredient("Eggs")), run(2_000, Ingredient("Milk")))
    val state =
      hugos(aList(WEEKLY), aList(PARTY)).copy(history = mapOf(WEEKLY to weekly, PARTY to listOf(run(2_500, Ingredient("Crisps", 3)))))

    assertEquals(state.history, saved(state).history)
  }

  @Test
  fun `a run keeps its whole stamp, so two at one wall time are still two`() {
    val state = hugos().copy(history = mapOf(WEEKLY to listOf(ClearedRun(Hlc(2_000, 2, HUGO), listOf(Ingredient("A"))), ClearedRun(Hlc(2_000, 1, HUGO), listOf(Ingredient("B"))))))

    assertEquals(state.history, saved(state).history)
  }

  @Test
  fun `a run is one line of a type 0_1_0 skips, and every other line is as it was`() {
    val without = hugos(aList(WEEKLY, item("Bread")))
    val with = without.withHistory(WEEKLY, 3_000, 2_000)

    fun lines(state: AppState) = StateCodec.encode(state).lines().filterNot { it.startsWith("end|") }
    assertEquals(2, lines(with).count { it.startsWith("history|") })
    assertEquals(lines(without), lines(with).filterNot { it.startsWith("history|") })
  }

  @Test
  fun `a sealed file with history in it still passes its checksum`() {
    val state = hugos().withHistory(WEEKLY, 2_000)

    assertTrue(StateCodec.encode(state).lines().first() == "PLSTATE11")
    assertEquals(state.history, StateCodec.decode(StateCodec.encode(state))?.history)
  }

  @Test
  fun `a 0_1_0 save reads as no history, and otherwise as it was`() {
    val state = hugos(aList(WEEKLY, item("Bread"), item("Eggs", qty = 2, ticked = true)))
    val withHistory = state.withClearRecorded(WEEKLY, at(2_000))

    val old = as010(StateCodec.encode(withHistory))
    assertFalse(old.contains("history|"))
    val restored = StateCodec.decode(old)!!

    assertEquals(emptyMap<ListId, List<ClearedRun>>(), restored.history)
    assertEquals(listOf("Bread", "Eggs ×2"), restored.lists.single().liveItems.map { it.label })
  }

  @Test
  fun `a version 10 save reads as no history, and otherwise as it was`() {
    val state = hugos(aList(WEEKLY, item("Bread")))

    val restored = StateCodec.decode(asVersion10(as010(StateCodec.encode(state))))!!

    assertEquals(emptyMap<ListId, List<ClearedRun>>(), restored.history)
    assertEquals(listOf("Bread"), restored.lists.single().liveItems.map { it.name.value })
  }

  @Test
  fun `a damaged line costs only that run`() {
    val state = hugos().withHistory(WEEKLY, 4_000, 3_000, 2_000)
    val encoded = asVersion10(StateCodec.encode(state))
    val whole = encoded.lines().filter { it.startsWith("history|") }
    assertEquals(3, whole.size)

    val unreadable =
      listOf(
        // A quantity that is not one, a name with no quantity, a stamp that is not one, nothing cleared.
        whole[1].replaceAfterLast('|', "lots"),
        whole[1].substringBeforeLast('|'),
        whole[1].replace(at(3_000).encode(), "yesterday"),
        "history|${WEEKLY.value}|${at(3_000).encode()}",
        "history|${WEEKLY.value}",
      )
    for (damaged in unreadable) {
      val restored = historyOfSave(encoded.replace(whole[1], damaged))
      assertEquals(damaged, listOf(4_000L, 2_000L), restored.getValue(WEEKLY).map { it.at.wall })
    }
  }

  @Test
  fun `a damaged line leaves the rest of the file alone`() {
    val state = hugos(aList(WEEKLY, item("Bread"))).withHistory(WEEKLY, 2_000).withRecipeSaved(Recipe("Toast", listOf(Ingredient("Bread"))))
    val encoded = asVersion10(StateCodec.encode(state))
    val line = encoded.lines().single { it.startsWith("history|") }

    val restored = StateCodec.decode(encoded.replace(line, "history|${WEEKLY.value}|nonsense|Eggs"))!!

    assertEquals(listOf("Bread"), restored.lists.single().liveItems.map { it.name.value })
    assertEquals(state.recipes, restored.recipes)
    assertEquals(emptyMap<ListId, List<ClearedRun>>(), restored.history)
  }

  @Test
  fun `runs of a list the file does not hold are dropped`() {
    val encoded = asVersion10(StateCodec.encode(hugos()))

    val restored = historyOfSave(encoded + "\nhistory|${PARTY.value}|${at(2_000).encode()}|Eggs|1\n")

    assertEquals(emptyMap<ListId, List<ClearedRun>>(), restored)
  }

  @Test
  fun `a save read back holds a list's runs as a list keeps them`() {
    // The page keys its rows by stamp, and the cap is what keeps the startup budget, so a file that
    // somehow holds more, or the same line twice, must not get either past the read.
    val encoded = asVersion10(StateCodec.encode(hugos()))
    val line = { wall: Long -> "history|${WEEKLY.value}|${at(wall).encode()}|Eggs|1" }
    val many = (1L..60L).map(line) + line(60L)

    val restored = historyOfSave(encoded + "\n" + many.joinToString("\n") + "\n").getValue(WEEKLY)

    assertEquals((60L downTo 11L).toList(), restored.map { it.at.wall })
  }

  @Test
  fun `history comes back with a list from a save set aside, unless it has some again`() {
    val kept = hugos(aList(WEEKLY), aList(PARTY)).withHistory(WEEKLY, 2_000).withHistory(PARTY, 2_500)

    val onlyList = hugos(aList(WEEKLY))
    assertEquals(kept.history, onlyList.withRecovered(kept).history)

    val hasSome = onlyList.withHistory(WEEKLY, 3_000)
    val recovered = hasSome.withRecovered(kept)
    assertEquals(runs(3_000), recovered.historyOf(WEEKLY))
    assertEquals(runs(2_500), recovered.historyOf(PARTY))
  }

  // ── Delete, merge, a kept copy ────────────────────────────────────────────

  @Test
  fun `deleting a list takes its history away, and only its`() {
    val state = hugos(aList(WEEKLY), aList(PARTY)).withHistory(WEEKLY, 2_000).withHistory(PARTY, 3_000)

    val after = state.withoutList(WEEKLY, at(4_000))

    assertEquals(mapOf(PARTY to runs(3_000)), after.history)
  }

  @Test
  fun `leaving a shared list takes its history away at once, though the list stays until the others hear`() {
    val state = hugos(aList(WEEKLY, people = WITH_AVA), aList(PARTY)).withHistory(WEEKLY, 2_000)

    val after = state.withoutList(WEEKLY, at(4_000))

    assertTrue("still held, for the others to hear", after.lists.any { it.id == WEEKLY })
    assertEquals(emptyMap<ListId, List<ClearedRun>>(), after.history)
  }

  @Test
  fun `merging moves the other list's runs into the target, newest first`() {
    val state = hugos(aList(WEEKLY), aList(PARTY)).withHistory(WEEKLY, 5_000, 3_000).withHistory(PARTY, 4_000, 2_000)

    val after = state.withListMerged(PARTY, WEEKLY, { ItemId("${HUGO.value}:${nextItem++}") }, at(6_000))

    assertEquals(listOf(5_000L, 4_000L, 3_000L, 2_000L), after.historyOf(WEEKLY).map { it.at.wall })
    assertEquals(setOf(WEEKLY), after.history.keys)
  }

  @Test
  fun `a merge gives history to a target that had none, and takes the source's from a shared list too`() {
    val state = hugos(aList(WEEKLY), aList(PARTY, people = WITH_AVA)).withHistory(PARTY, 2_000)

    val after = state.withListMerged(PARTY, WEEKLY, { ItemId("${HUGO.value}:${nextItem++}") }, at(6_000))

    assertEquals(runs(2_000), after.historyOf(WEEKLY))
    assertEquals(setOf(WEEKLY), after.history.keys)
  }

  @Test
  fun `a merge holds at most 50 runs`() {
    val state =
      hugos(aList(WEEKLY), aList(PARTY))
        .withHistory(WEEKLY, *(1L..40L step 2).toList().toLongArray().let { it + (41L..60L).toList() })
        .withHistory(PARTY, *(2L..40L step 2).toList().toLongArray().let { it + (61L..80L).toList() })

    val after = state.withListMerged(PARTY, WEEKLY, { ItemId("${HUGO.value}:${nextItem++}") }, at(9_000))

    assertEquals((80L downTo 31L).toList(), after.historyOf(WEEKLY).map { it.at.wall })
  }

  @Test
  fun `a merge that cannot happen leaves the history where it was`() {
    val state = hugos(aList(WEEKLY)).withHistory(WEEKLY, 2_000)

    assertSame(state, state.withListMerged(PARTY, WEEKLY, { ItemId("x") }, at(6_000)))
  }

  @Test
  fun `a list kept as a private copy keeps its history`() {
    val mine = aList(WEEKLY, item("Bread"), people = WITH_AVA)
    val state = hugos(mine, aList(PARTY)).withHistory(WEEKLY, 3_000, 2_000).withHistory(PARTY, 2_500)
    val removed = mine.withPersonLeft(HUGO, at(4_000, AVA))

    val result = state.receive(SyncPayload(AVA, at(4_100, AVA), listOf(removed)), HybridClock(HUGO, now = { 9_000 }), IdFactory(HUGO, start = 1_000))
    val after = (result as SyncResult.Merged).state

    val copy = after.visibleLists.single { it.id != PARTY }
    assertEquals("a new id", false, copy.id == WEEKLY)
    assertEquals(runs(3_000, 2_000), after.historyOf(copy.id))
    assertNull("none left behind on the old one", after.history[WEEKLY])
    assertEquals(runs(2_500), after.historyOf(PARTY))
    assertEquals("and it is saved under the new id", after.history, saved(after).history)
  }

  // ── Selecting ─────────────────────────────────────────────────────────────

  private val newer = run(3_000, Ingredient("Eggs", 6), Ingredient("Milk"), Ingredient("Bread"))
  private val older = run(2_000, Ingredient("eggs", 2), Ingredient("Jalapeños", 3), Ingredient("Tea"))

  private fun all(vararg of: ClearedRun) = of.flatMap { r -> r.items.indices.map { ClearedPick(r.at, it) } }.toSet()

  @Test
  fun `tapping an item selects it, and tapping it again unselects it`() {
    val pick = ClearedPick(newer.at, 1)

    assertEquals(setOf(pick), emptySet<ClearedPick>().toggling(pick))
    assertEquals(emptySet<ClearedPick>(), setOf(pick).toggling(pick))
  }

  @Test
  fun `tapping a run's title selects the whole run, and tapping it again unselects it`() {
    val whole = emptySet<ClearedPick>().togglingRun(newer)

    assertEquals(all(newer), whole)
    assertTrue(whole.hasAllOf(newer))
    assertEquals(emptySet<ClearedPick>(), whole.togglingRun(newer))
  }

  @Test
  fun `a run partly selected is selected whole by its title`() {
    val some = setOf(ClearedPick(newer.at, 0))

    assertFalse(some.hasAllOf(newer))
    assertEquals(all(newer), some.togglingRun(newer))
  }

  @Test
  fun `a run's title touches only that run, so a selection can span runs`() {
    val both = all(older).togglingRun(newer)

    assertEquals(all(newer, older), both)
    assertEquals(all(older), both.togglingRun(newer))
  }

  @Test
  fun `the same name across runs counts once, with the newest run's quantity and spelling`() {
    val picked = listOf(newer, older).picked(all(newer, older))

    assertEquals(
      listOf(Ingredient("Eggs", 6), Ingredient("Milk"), Ingredient("Bread"), Ingredient("Jalapeños", 3), Ingredient("Tea")),
      picked,
    )
  }

  @Test
  fun `only what is selected counts, so the older run's quantity stands when the newer's is not selected`() {
    val picked = listOf(newer, older).picked(setOf(ClearedPick(older.at, 0), ClearedPick(newer.at, 1)))

    assertEquals(listOf(Ingredient("Milk"), Ingredient("eggs", 2)), picked)
  }

  @Test
  fun `names fold as they do when matching, accents and spaces and all`() {
    val one = run(3_000, Ingredient(" JALAPENOS ", 1))
    val two = run(2_000, Ingredient("Jalapeños", 3))

    assertEquals(listOf(Ingredient(" JALAPENOS ", 1)), listOf(one, two).picked(all(one, two)))
  }

  @Test
  fun `the same name twice in one run counts once, as it comes first`() {
    val twice = run(3_000, Ingredient("Milk", 2), Ingredient("milk", 5))

    assertEquals(listOf(Ingredient("Milk", 2)), listOf(twice).picked(all(twice)))
  }

  @Test
  fun `a selection naming a run that has gone adds up to nothing`() {
    assertEquals(emptyList<Ingredient>(), listOf(older).picked(all(newer)))
  }

  // ── Adding to a recipe ────────────────────────────────────────────────────

  @Test
  fun `an ingredient the recipe already has keeps its quantity, and the rest go last`() {
    val recipe = Recipe("Pancakes", listOf(Ingredient("Eggs", 2), Ingredient("Flour")))

    val more = recipe.withIngredients(listOf(Ingredient("eggs", 6), Ingredient("Milk", 3)))

    assertEquals(listOf(Ingredient("Eggs", 2), Ingredient("Flour"), Ingredient("Milk", 3)), more.ingredients)
  }

  @Test
  fun `a recipe that already has everything is left as it was`() {
    val recipe = Recipe("Pancakes", listOf(Ingredient("Jalapeños", 2)))

    assertEquals(recipe, recipe.withIngredients(listOf(Ingredient("JALAPENOS", 6))))
  }
}
