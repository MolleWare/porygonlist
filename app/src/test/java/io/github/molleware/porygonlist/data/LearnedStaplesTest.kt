package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
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
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val AVA = DeviceId("AVAAAAAAAAAAAAAA")

private val WEEKLY = ListId("${HUGO.value}:1")
private val PARTY = ListId("${HUGO.value}:2")

private val LONDON = ZoneId.of("Europe/London")

/** Noon on a day in London, as a wall time. */
private fun day(year: Int, month: Int, dayOfMonth: Int, hour: Int = 12, minute: Int = 0) =
  LocalDateTime.of(year, month, dayOfMonth, hour, minute).atZone(LONDON).toInstant().toEpochMilli()

private val JAN_10 = day(2026, 1, 10)

private fun aList(id: ListId, people: List<Person> = listOf(Person(HUGO, "Hugo", "H"))) =
  GroceryList(id, "List $id", ListAccent.ACCENT, emptyList(), people)

private val WITH_AVA = listOf(Person(HUGO, "Hugo", "H"), Person(AVA, "Ava", "A"))

private fun hugos(vararg lists: GroceryList) =
  AppState(
    localDevice = HUGO,
    displayName = "Hugo",
    idCounter = 1_000,
    clockHead = Hlc(1_000, 0, HUGO),
    lists = lists.toList().ifEmpty { listOf(aList(WEEKLY)) },
    activeListId = WEEKLY,
    networks = emptyList(),
    peers = listOf(TrustedPeer(AVA, byteArrayOf(1), "Ava", 0)),
  )

/**
 * [runs] on [list], oldest first, as names: one run a day from [JAN_10], so the last is the newest
 * and its wall time is that day's noon.
 */
private fun AppState.cleared(list: ListId, vararg runs: List<String>): AppState =
  copy(
    history =
      history +
        (list to
          runs.mapIndexed { i, names -> ClearedRun(Hlc(JAN_10 + i * DAY, 0, HUGO), names.map { Ingredient(it) }) }.reversed())
  )

private const val DAY = 24 * 60 * 60 * 1000L

/** The newest run's time when [runs] runs were cleared from [JAN_10]. */
private fun newest(runs: Int) = JAN_10 + (runs - 1) * DAY

private fun AppState.learn(list: ListId = WEEKLY, now: Long = newest(10)) = withStaplesLearned(list, now, LONDON)

private fun AppState.names(list: ListId = WEEKLY) = learnedOn(list).map { it.name }

private fun AppState.shown(list: ListId = WEEKLY, now: Long = newest(10)) = staplesShownOn(list, now, LONDON).map { it.name }

private fun saved(state: AppState) = StateCodec.decode(StateCodec.encode(state))!!

/**
 * Staples a list teaches this phone by being cleared with the same things in it, and forgets once
 * they stop being added. Local to this phone, like the hand-made ones, and never sent.
 */
class LearnedStaplesTest {

  private val eggs = listOf("Eggs")
  private val none = listOf("Bread")

  // ── Learning ──────────────────────────────────────────────────────────────

  @Test
  fun `in 2 of the last 4 runs is not enough`() {
    assertEquals(emptyList<String>(), hugos().cleared(WEEKLY, eggs, none, eggs, none).learn().names())
  }

  @Test
  fun `in 3 of the last 4 runs is learned`() {
    assertEquals(listOf("Eggs"), hugos().cleared(WEEKLY, eggs, none, eggs, eggs).learn().names())
    assertEquals("whichever three", listOf("Eggs"), hugos().cleared(WEEKLY, eggs, eggs, eggs, none).learn().names())
  }

  @Test
  fun `with only 3 runs, all 3 are needed`() {
    assertEquals(listOf("Eggs"), hugos().cleared(WEEKLY, eggs, eggs, eggs).learn().names())
    assertEquals(emptyList<String>(), hugos().cleared(WEEKLY, eggs, none, eggs).learn().names())
  }

  @Test
  fun `fewer than 3 runs never teach anything`() {
    assertEquals(emptyList<String>(), hugos().cleared(WEEKLY, eggs, eggs).learn().names())
    assertEquals(emptyList<String>(), hugos().cleared(WEEKLY, eggs).learn().names())
    assertEquals(emptyList<String>(), hugos().learn().names())
  }

  @Test
  fun `only the last 4 runs count`() {
    // Three times, long ago, then not in the four since, which held Bread every time.
    assertEquals(listOf("Bread"), hugos().cleared(WEEKLY, eggs, eggs, eggs, none, none, none, none).learn().names())
  }

  @Test
  fun `twice in one run counts as one run`() {
    assertEquals(emptyList<String>(), hugos().cleared(WEEKLY, listOf("Eggs", "eggs"), none, eggs, none).learn().names())
  }

  @Test
  fun `names fold as matching folds them, and the newest run's spelling is the one kept`() {
    val state = hugos().cleared(WEEKLY, listOf("jalapenos"), listOf("JALAPEÑOS "), none, listOf("Jalapeños"))

    assertEquals(listOf("Jalapeños"), state.learn().names())
  }

  @Test
  fun `last added starts as the newest run that holds it`() {
    val state = hugos().cleared(WEEKLY, eggs, eggs, eggs, none).learn()

    assertEquals(newest(3), state.learnedOn(WEEKLY).single().lastAdded)
    assertEquals("and no taps from the grid yet", 0, state.learnedOn(WEEKLY).single().uses)
  }

  @Test
  fun `several at once keep the order the newest run has them`() {
    val both = listOf("Milk", "Eggs")
    val state = hugos().cleared(WEEKLY, both, both, both).learn()

    assertEquals(listOf("Milk", "Eggs"), state.names())
  }

  @Test
  fun `a hand-made staple of that name is not learned`() {
    val state = hugos().copy(staples = listOf(Staple("eggs"))).cleared(WEEKLY, eggs, eggs, eggs)

    assertEquals(emptyList<String>(), state.learn().names())
  }

  @Test
  fun `one already learned is not learned again, and keeps its date and count`() {
    val once = hugos().cleared(WEEKLY, eggs, eggs, eggs).learn(now = newest(3))
    val used = once.withStapleUsed(WEEKLY, "Eggs")
    val more = used.cleared(WEEKLY, eggs, eggs, eggs, eggs, eggs).learn(now = newest(5))

    assertEquals(listOf(LearnedStaple("Eggs", newest(3), uses = 1)), more.learnedOn(WEEKLY))
  }

  @Test
  fun `a press that teaches nothing new leaves the state as it was`() {
    val state = hugos().cleared(WEEKLY, eggs, eggs, eggs).learn(now = newest(3))

    assertSame(state, state.learn(now = newest(3)))
  }

  @Test
  fun `each list learns for itself`() {
    val state = hugos(aList(WEEKLY), aList(PARTY)).cleared(WEEKLY, eggs, eggs, eggs).cleared(PARTY, none, none, none)

    val after = state.learn(WEEKLY)

    assertEquals(listOf("Eggs"), after.names(WEEKLY))
    assertEquals("only the list cleared is looked at", emptyList<String>(), after.names(PARTY))
  }

  @Test
  fun `one whose newest run is already three months old is not learned`() {
    val state = hugos().cleared(WEEKLY, eggs, eggs, eggs)

    assertEquals(emptyList<String>(), state.learn(now = day(2026, 4, 12)).names())
    assertEquals(listOf("Eggs"), state.learn(now = day(2026, 4, 11)).names())
  }

  // ── The grid ──────────────────────────────────────────────────────────────

  @Test
  fun `hand-made first, in the order made, then the list's learned, most recently added first`() {
    val state =
      hugos(aList(WEEKLY), aList(PARTY))
        .copy(
          staples = listOf(Staple("Tea"), Staple("Bin bags")),
          learnedStaples =
            mapOf(
              WEEKLY to listOf(LearnedStaple("Eggs", newest(2)), LearnedStaple("Milk", newest(5)), LearnedStaple("Oats", newest(3))),
              PARTY to listOf(LearnedStaple("Crisps", newest(4))),
            ),
        )

    assertEquals(listOf("Tea", "Bin bags", "Milk", "Oats", "Eggs"), state.shown(WEEKLY))
    assertEquals(listOf("Tea", "Bin bags", "Crisps"), state.shown(PARTY))
  }

  @Test
  fun `a learned tile carries its own count, as a hand-made one does`() {
    val state = hugos().copy(learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Eggs", newest(2), uses = 4))))

    assertEquals(listOf(Staple("Eggs", 4)), state.staplesShownOn(WEEKLY, newest(3), LONDON))
  }

  @Test
  fun `a tap from the grid counts on the open list's learned staple`() {
    val state = hugos(aList(WEEKLY), aList(PARTY))
      .copy(learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Eggs", newest(2))), PARTY to listOf(LearnedStaple("Eggs", newest(2)))))

    val after = state.withStapleUsed(WEEKLY, "Eggs").withStapleUsed(WEEKLY, "Eggs")

    assertEquals(2, after.learnedOn(WEEKLY).single().uses)
    assertEquals(0, after.learnedOn(PARTY).single().uses)
  }

  // ── Forgetting ────────────────────────────────────────────────────────────

  private fun learnedOn10Jan() = hugos().copy(learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Eggs", JAN_10))))

  @Test
  fun `unused for exactly three calendar months, it is hidden`() {
    val state = learnedOn10Jan()

    assertEquals(listOf("Eggs"), state.shown(now = day(2026, 4, 10, hour = 11, minute = 59)))
    assertEquals(emptyList<String>(), state.shown(now = day(2026, 4, 10)))
    assertTrue(LearnedStaple("Eggs", JAN_10).forgottenBy(day(2026, 4, 10), LONDON))
    assertFalse(LearnedStaple("Eggs", JAN_10).forgottenBy(day(2026, 4, 10) - 1, LONDON))
  }

  @Test
  fun `months are calendar months in the phone's zone, the clocks going forward and all`() {
    // 31 January plus three months is 30 April; the clocks went forward in March, so it is not 89 days of 24 hours.
    val jan31 = LearnedStaple("Eggs", day(2026, 1, 31))

    assertFalse(jan31.forgottenBy(day(2026, 4, 30) - 1, LONDON))
    assertTrue(jan31.forgottenBy(day(2026, 4, 30), LONDON))
  }

  @Test
  fun `hidden ones stay in the save until the next clear on that list`() {
    val state = hugos(aList(WEEKLY), aList(PARTY))
      .copy(learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Eggs", JAN_10)), PARTY to listOf(LearnedStaple("Crisps", JAN_10))))
    val later = day(2026, 5, 1)

    assertEquals("only hidden", listOf("Eggs"), state.names(WEEKLY))
    val cleared = state.learn(WEEKLY, now = later)

    assertEquals(emptyList<String>(), cleared.names(WEEKLY))
    assertEquals("the other list's waits for its own clear", listOf("Crisps"), cleared.names(PARTY))
    assertTrue(cleared.learnedStaples.keys.none { it == WEEKLY })
  }

  @Test
  fun `adding its name keeps it, on that list only, whatever the capitals`() {
    val state = hugos(aList(WEEKLY), aList(PARTY))
      .copy(learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Eggs", JAN_10)), PARTY to listOf(LearnedStaple("Eggs", JAN_10))))
    val march = day(2026, 3, 1)

    val after = state.withStapleAdded(WEEKLY, " eggs", march, LONDON)

    assertEquals(march, after.learnedOn(WEEKLY).single().lastAdded)
    assertEquals(JAN_10, after.learnedOn(PARTY).single().lastAdded)
    assertEquals(listOf("Eggs"), after.shown(WEEKLY, now = day(2026, 5, 1)))
    assertEquals("and keeps its own spelling", "Eggs", after.learnedOn(WEEKLY).single().name)
  }

  @Test
  fun `adding something that is no learned staple changes nothing`() {
    val state = learnedOn10Jan()

    assertSame(state, state.withStapleAdded(WEEKLY, "Milk", day(2026, 3, 1), LONDON))
  }

  @Test
  fun `once forgotten, adding its name does not bring it back`() {
    val state = learnedOn10Jan()

    val after = state.withStapleAdded(WEEKLY, "Eggs", day(2026, 4, 11), LONDON)

    assertEquals(emptyList<String>(), after.shown(now = day(2026, 4, 11)))
  }

  @Test
  fun `forgotten, it can be learned again from new runs`() {
    val forgotten = learnedOn10Jan()
    val state = forgotten.copy(history = mapOf(WEEKLY to (1..3).map { ClearedRun(Hlc(day(2026, 5, it), 0, HUGO), listOf(Ingredient("Eggs"))) }.reversed()))

    val after = state.learn(now = day(2026, 5, 3))

    assertEquals(listOf(LearnedStaple("Eggs", day(2026, 5, 3))), after.learnedOn(WEEKLY))
  }

  // ── By hand ───────────────────────────────────────────────────────────────

  @Test
  fun `removed by hand, it goes and is never learned on that list again`() {
    val state = hugos(aList(WEEKLY), aList(PARTY)).cleared(WEEKLY, eggs, eggs, eggs).cleared(PARTY, eggs, eggs, eggs).learn(WEEKLY)

    val removed = state.withStapleRemoved(WEEKLY, "Eggs")

    assertEquals(emptyList<String>(), removed.names(WEEKLY))
    assertEquals(setOf("eggs"), removed.unwantedOn(WEEKLY))
    assertEquals(emptyList<String>(), removed.cleared(WEEKLY, eggs, eggs, eggs, eggs).learn(WEEKLY).names(WEEKLY))
    assertEquals("another list still learns it", listOf("Eggs"), removed.learn(PARTY).names(PARTY))
  }

  @Test
  fun `removing a name that is not a learned staple changes nothing`() {
    val state = hugos().cleared(WEEKLY, eggs, eggs, eggs).learn()

    assertSame(state, state.withStapleRemoved(WEEKLY, "Milk"))
  }

  @Test
  fun `a hand-made staple of an unwanted name lifts it on every list`() {
    val state = hugos(aList(WEEKLY), aList(PARTY)).copy(unwantedStaples = mapOf(WEEKLY to setOf("eggs", "milk"), PARTY to setOf("eggs")))

    val after = state.withHandMadeStaple("Eggs")

    assertEquals(listOf(Staple("Eggs")), after.staples)
    assertEquals(mapOf(WEEKLY to setOf("milk")), after.unwantedStaples)
  }

  @Test
  fun `a hand-made staple takes the place of a learned one of that name, on every list`() {
    val state =
      hugos(aList(WEEKLY), aList(PARTY))
        .copy(learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Eggs", JAN_10), LearnedStaple("Milk", JAN_10)), PARTY to listOf(LearnedStaple("eggs", JAN_10))))

    val after = state.withHandMadeStaple("EGGS")

    assertEquals(listOf("EGGS", "Milk"), after.shown(WEEKLY, now = JAN_10))
    assertEquals(mapOf(WEEKLY to listOf(LearnedStaple("Milk", JAN_10))), after.learnedStaples)
  }

  @Test
  fun `a hand-made staple already there is left as it was`() {
    val state = hugos().copy(staples = listOf(Staple("Eggs", 3)))

    assertSame(state, state.withHandMadeStaple("eggs"))
    assertSame(state, state.withHandMadeStaple("  "))
  }

  // ── Delete, merge, a kept copy, a recovered save ──────────────────────────

  private fun both() =
    hugos(aList(WEEKLY), aList(PARTY))
      .copy(
        learnedStaples =
          mapOf(
            WEEKLY to listOf(LearnedStaple("Eggs", newest(2), uses = 3)),
            PARTY to listOf(LearnedStaple("eggs", newest(4), uses = 9), LearnedStaple("Crisps", newest(3)), LearnedStaple("Milk", newest(3))),
          ),
        unwantedStaples = mapOf(WEEKLY to setOf("milk"), PARTY to setOf("tea")),
      )

  @Test
  fun `deleting a list takes its learned staples and unwanted names with it`() {
    val after = both().withoutList(PARTY, Hlc(9_000, 0, HUGO))

    assertEquals(setOf(WEEKLY), after.learnedStaples.keys)
    assertEquals(setOf(WEEKLY), after.unwantedStaples.keys)
  }

  @Test
  fun `leaving a shared list takes them at once`() {
    val state = both().copy(lists = listOf(aList(WEEKLY), aList(PARTY, people = WITH_AVA)))

    val after = state.withoutList(PARTY, Hlc(9_000, 0, HUGO))

    assertEquals(setOf(WEEKLY), after.learnedStaples.keys)
    assertEquals(setOf(WEEKLY), after.unwantedStaples.keys)
  }

  @Test
  fun `merging moves them to the target, which keeps its own of a name, and its unwanted ones stay unwanted`() {
    val after = both().withListMerged(PARTY, WEEKLY, { ItemId("${HUGO.value}:${counter++}") }, Hlc(9_000, 0, HUGO))

    assertEquals(listOf(LearnedStaple("Eggs", newest(2), uses = 3), LearnedStaple("Crisps", newest(3))), after.learnedOn(WEEKLY))
    assertEquals(setOf("milk", "tea"), after.unwantedOn(WEEKLY))
    assertEquals(setOf(WEEKLY), after.learnedStaples.keys)
    assertEquals(setOf(WEEKLY), after.unwantedStaples.keys)
  }

  @Test
  fun `a merge into a list with none gives it the other's`() {
    val state = both().copy(learnedStaples = both().learnedStaples - WEEKLY, unwantedStaples = both().unwantedStaples - WEEKLY)

    val after = state.withListMerged(PARTY, WEEKLY, { ItemId("${HUGO.value}:${counter++}") }, Hlc(9_000, 0, HUGO))

    assertEquals(both().learnedOn(PARTY), after.learnedOn(WEEKLY))
    assertEquals(setOf("tea"), after.unwantedOn(WEEKLY))
  }

  private var counter = 500

  @Test
  fun `a list kept as a private copy keeps them`() {
    val mine = aList(WEEKLY, people = WITH_AVA)
    val state = both().copy(lists = listOf(mine, aList(PARTY)))
    val removed = mine.withPersonLeft(HUGO, Hlc(4_000, 0, AVA))

    val result = state.receive(SyncPayload(AVA, Hlc(4_100, 0, AVA), listOf(removed)), HybridClock(HUGO, now = { 9_000 }), IdFactory(HUGO, start = 1_000))
    val after = (result as SyncResult.Merged).state

    val copy = after.visibleLists.single { it.id != PARTY }.id
    assertEquals(state.learnedOn(WEEKLY), after.learnedOn(copy))
    assertEquals(state.unwantedOn(WEEKLY), after.unwantedOn(copy))
    assertTrue(WEEKLY !in after.learnedStaples && WEEKLY !in after.unwantedStaples)
    assertEquals(state.learnedOn(PARTY), after.learnedOn(PARTY))
  }

  @Test
  fun `an exchange leaves them alone`() {
    val mine = aList(WEEKLY, people = WITH_AVA)
    val state = both().copy(lists = listOf(mine, aList(PARTY)))

    val result = state.receive(SyncPayload(AVA, Hlc(4_100, 0, AVA), listOf(mine)), HybridClock(HUGO, now = { 9_000 }))
    val after = (result as SyncResult.Merged).state

    assertEquals(state.learnedStaples, after.learnedStaples)
    assertEquals(state.unwantedStaples, after.unwantedStaples)
  }

  @Test
  fun `they come back with a list from a save set aside, unless it has some again`() {
    val kept = both()
    val onlyWeekly = hugos(aList(WEEKLY)).copy(learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Oats", newest(1)))))

    val recovered = onlyWeekly.withRecovered(kept)

    assertEquals("what is here wins", listOf(LearnedStaple("Oats", newest(1))), recovered.learnedOn(WEEKLY))
    assertEquals(kept.learnedOn(PARTY), recovered.learnedOn(PARTY))
    assertEquals(kept.unwantedStaples, recovered.unwantedStaples)
  }

  // ── Never on the wire ─────────────────────────────────────────────────────

  @Test
  fun `no payload carries them`() {
    val list = aList(WEEKLY, people = WITH_AVA)
    val state = hugos(list).copy(
      learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Secret sauce", 123_456_789, uses = 77))),
      unwantedStaples = mapOf(WEEKLY to setOf("mystery")),
    )

    val wire = SyncCodec.encode(state.payloadFor(AVA, HybridClock(HUGO, now = { 9_000 }))!!)

    assertFalse(wire.contains("Secret sauce") || wire.contains("123456789") || wire.contains("mystery"))
    assertEquals(SyncCodec.encode(hugos(list).payloadFor(AVA, HybridClock(HUGO, now = { 9_000 }))!!), wire)
  }

  // ── The save ──────────────────────────────────────────────────────────────

  @Test
  fun `they survive a save, on their own lists, in order, a bar in a name and all`() {
    val state = both().copy(learnedStaples = both().learnedStaples + (WEEKLY to listOf(LearnedStaple("Salt | pepper", newest(2), uses = 2))))

    val restored = saved(state)

    assertEquals(state.learnedStaples, restored.learnedStaples)
    assertEquals(state.unwantedStaples, restored.unwantedStaples)
  }

  @Test
  fun `they are lines of types 0_1_0 skips, and every other line is as it was`() {
    val without = hugos(aList(WEEKLY), aList(PARTY))
    val with = both()

    fun lines(state: AppState) = StateCodec.encode(state).lines().filterNot { it.startsWith("end|") }
    assertEquals(4, lines(with).count { it.startsWith("learned|") })
    assertEquals(2, lines(with).count { it.startsWith("unwanted|") })
    assertEquals(lines(without), lines(with).filterNot { it.startsWith("learned|") || it.startsWith("unwanted|") })
  }

  @Test
  fun `a 0_1_0 save and a version 10 save read as none learned, and otherwise as they were`() {
    val state = both()

    for (old in listOf(as010(StateCodec.encode(state)), asVersion10(as010(StateCodec.encode(state))))) {
      assertFalse(old.contains("learned|") || old.contains("unwanted|"))
      val restored = StateCodec.decode(old)!!

      assertEquals(emptyMap<ListId, List<LearnedStaple>>(), restored.learnedStaples)
      assertEquals(emptyMap<ListId, Set<String>>(), restored.unwantedStaples)
      assertEquals(state.lists.map { it.id }, restored.lists.map { it.id })
    }
  }

  @Test
  fun `a damaged line costs only itself`() {
    val encoded = asVersion10(StateCodec.encode(both()))
    val crisps = encoded.lines().single { it.startsWith("learned|") && it.contains("Crisps") }
    val tea = encoded.lines().single { it.startsWith("unwanted|") && it.endsWith("tea") }

    for (damaged in listOf(crisps.replace("|${newest(3)}|", "|soon|"), crisps.substringBeforeLast('|'), "learned|${PARTY.value}||${newest(3)}|0")) {
      val restored = StateCodec.decode(encoded.replace(crisps, damaged))!!
      assertEquals(damaged, listOf("eggs", "Milk"), restored.names(PARTY))
      assertEquals(both().learnedOn(WEEKLY), restored.learnedOn(WEEKLY))
    }
    val restored = StateCodec.decode(encoded.replace(tea, "unwanted|${PARTY.value}|"))!!
    assertEquals(emptySet<String>(), restored.unwantedOn(PARTY))
    assertEquals(setOf("milk"), restored.unwantedOn(WEEKLY))
  }

  @Test
  fun `lines for a list the file does not hold are dropped`() {
    val encoded = asVersion10(StateCodec.encode(hugos()))

    val restored = StateCodec.decode(encoded + "\nlearned|${PARTY.value}|Eggs|1000|0\nunwanted|${PARTY.value}|milk\n")!!

    assertEquals(emptyMap<ListId, List<LearnedStaple>>(), restored.learnedStaples)
    assertEquals(emptyMap<ListId, Set<String>>(), restored.unwantedStaples)
  }

  @Test
  fun `the same name twice in a save is read once`() {
    val encoded = asVersion10(StateCodec.encode(hugos()))

    val restored = StateCodec.decode(encoded + "\nlearned|${WEEKLY.value}|Eggs|1000|2\nlearned|${WEEKLY.value}|eggs|2000|0\n")!!

    assertEquals(listOf(LearnedStaple("Eggs", 1000, 2)), restored.learnedOn(WEEKLY))
  }

  // ── Suggestions ───────────────────────────────────────────────────────────

  @Test
  fun `the open list's learned staples are suggested with the hand-made ones, ahead of everything else`() {
    val state =
      hugos(aList(WEEKLY), aList(PARTY))
        .copy(
          staples = listOf(Staple("Tea")),
          learnedStaples = mapOf(WEEKLY to listOf(LearnedStaple("Zaatar", newest(2))), PARTY to listOf(LearnedStaple("Quince", newest(2)))),
        )

    val words = state.suggestionVocabulary(now = newest(3), zone = LONDON)

    assertEquals(listOf("Tea", "Zaatar"), words.take(2))
    assertFalse("another list's are not", words.contains("Quince"))
    assertFalse("nor a forgotten one", state.suggestionVocabulary(now = day(2026, 6, 1), zone = LONDON).contains("Zaatar"))
  }
}
