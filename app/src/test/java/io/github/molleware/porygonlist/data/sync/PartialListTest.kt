package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.crypto.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")

private fun stamp(wall: Long, device: DeviceId) = Hlc(wall, 0, device)

private fun item(id: String, name: String, at: Hlc) =
  GroceryItem(id = ItemId(id), name = Field(name, at), qty = Field(1, at), checkedAt = at, removed = Field(false, at))

private val both = listOf(Person(AVA, "Ava", "A"), Person(HUGO, "Hugo", "H"))
private val weekly = ListId("${HUGO.value}:1")

private fun list(vararg items: GroceryItem) = GroceryList(weekly, "Weekly shop", ListAccent.ACCENT, items.toList(), both)

private fun phone(me: DeviceId, lists: List<GroceryList>) =
  AppState(
    localDevice = me,
    idCounter = 100,
    clockHead = stamp(1_000, me),
    lists = lists,
    activeListId = lists.firstOrNull()?.id ?: ListId(""),
    networks = emptyList(),
    peers = both.filterNot { it.device == me }.map { TrustedPeer(it.device, byteArrayOf(1), it.name, 0) },
  )

/** Over the wire and back, so what is tested is what a phone would read. */
private fun AppState.receiving(payload: SyncPayload): AppState =
  (receive(SyncCodec.decode(SyncCodec.encode(payload))!!, HybridClock(localDevice, now = { 9_000 })) as SyncResult.Merged).state

/**
 * A list sent partial: its own record and its people, only some of its items, and the ids of the
 * rest. What it leaves out has to mean nothing to the phone that reads it.
 */
class PartialListTest {

  private val rice = item("${HUGO.value}:2", "Rice", stamp(1_000, HUGO))
  private val milk = item("${HUGO.value}:3", "Milk", stamp(1_000, HUGO))

  @Test
  fun `a partial list round trips, with the ids it left out`() {
    val held = setOf(ItemId("${HUGO.value}:3"), ItemId("${HUGO.value}:4"), ItemId("${HUGO.value}:5"), ItemId("${AVA.value}:9"), ItemId("odd"))
    val payload = SyncPayload(HUGO, stamp(5_000, HUGO), listOf(list(rice)), callback = 40_123, run = "r1", held = mapOf(weekly to held))

    val encoded = SyncCodec.encode(payload)
    val decoded = SyncCodec.decode(encoded)!!

    assertEquals(held, decoded.held[weekly])
    assertEquals("r1", decoded.run)
    assertEquals(40_123, decoded.callback)
    assertEquals(listOf(rice), decoded.lists.single().items)
    // Runs, not one id after another: a list of two hundred items costs a line, not two hundred ids.
    assertTrue(encoded.lines().single { it.startsWith("held|") }.contains("|3-5|"))
  }

  @Test
  fun `a partial list that left nothing out is still partial`() {
    val payload = SyncPayload(HUGO, stamp(5_000, HUGO), listOf(list(rice)), run = "r1", held = mapOf(weekly to emptySet()))

    assertTrue(SyncCodec.decode(SyncCodec.encode(payload))!!.isPartial(weekly))
    assertTrue("and a whole one is not", !SyncCodec.decode(SyncCodec.encode(payload.copy(held = emptyMap())))!!.isPartial(weekly))
  }

  @Test
  fun `the run goes after the callback port, where 0 1 0 stops reading`() {
    // 0.1.0 reads the port as column three, an empty one as none, and nothing after it.
    fun fromLine(p: SyncPayload) = Records.split(SyncCodec.encode(p).lines()[1])
    val plain = SyncPayload(HUGO, stamp(5_000, HUGO), listOf(list(rice)))

    assertEquals(listOf("from", HUGO.value, stamp(5_000, HUGO).encode(), "40123", "r1"), fromLine(plain.copy(callback = 40_123, run = "r1")))
    assertEquals(listOf("from", HUGO.value, stamp(5_000, HUGO).encode(), "", "r1"), fromLine(plain.copy(run = "r1")))
    assertEquals("without a run, the line is as it always was", listOf("from", HUGO.value, stamp(5_000, HUGO).encode()), fromLine(plain))
    assertNull(SyncCodec.decode(SyncCodec.encode(plain.copy(run = "r1")))!!.callback)
  }

  @Test
  fun `a damaged list of left-out ids refuses the whole payload`() {
    // Skipped like any other bad line, it would make the list whole, and everything it left out
    // would read as never seen.
    val good = SyncCodec.encode(SyncPayload(HUGO, stamp(5_000, HUGO), listOf(list(rice)), run = "r1", held = mapOf(weekly to setOf(milk.id))))
    val heldLine = good.lines().single { it.startsWith("held|") }

    assertNull(SyncCodec.decode(good.replace(heldLine, "held|${weekly.value}|${HUGO.value}|3-x")))
    assertNull(SyncCodec.decode(good.replace(heldLine, "held|${weekly.value}|${HUGO.value}")))
    assertNull("nor can one fill memory", SyncCodec.decode(good.replace(heldLine, "held|${weekly.value}|${HUGO.value}|0-99999999999")))
  }

  @Test
  fun `a partial list is never taken as an invitation`() {
    // Ava does not hold the list. Whole, it would be an invitation; partial, it is two items of a
    // list she would end up holding with the rest missing.
    val ava = phone(AVA, emptyList())
    val partial = SyncPayload(HUGO, stamp(5_000, HUGO), listOf(list(rice)), run = "r1", held = mapOf(weekly to setOf(milk.id)))

    val result = ava.receive(SyncCodec.decode(SyncCodec.encode(partial))!!, HybridClock(AVA))

    assertEquals(SyncResult.Rejected(RejectReason.NOTHING_SHARED), result)
    assertTrue("the same list whole still arrives", ava.receiving(partial.copy(held = emptyMap())).lists.any { it.id == weekly })
  }

  @Test
  fun `what a partial list left out is not mistaken for something its sender never saw`() {
    // Both phones have had Ava's rice for a week. Hugo adds a second bag, knowingly, and his list
    // goes partial with only that in it: her rice is among what he left out, not something he has
    // never seen, so there is nothing to ask.
    val herRice = item("${AVA.value}:1", "Rice", stamp(1_000, AVA))
    val secondBag = item("${HUGO.value}:7", "Rice", stamp(4_000, HUGO))
    val ava = phone(AVA, listOf(list(herRice, milk)))

    val after = ava.receiving(SyncPayload(HUGO, stamp(5_000, HUGO), listOf(list(secondBag)), run = "r1", held = mapOf(weekly to setOf(herRice.id, milk.id))))

    assertTrue("no card", after.conflicts.isEmpty())
    assertEquals("and nothing left out is lost", listOf("Rice", "Milk", "Rice"), after.lists.single().liveItems.map { it.name.value })

    // Had he not seen hers — it is not among what he holds — it is the clash it always was.
    val apart = ava.receiving(SyncPayload(HUGO, stamp(5_000, HUGO), listOf(list(secondBag)), run = "r1", held = mapOf(weekly to setOf(milk.id))))
    assertEquals(listOf(herRice.id to secondBag.id), apart.conflicts.map { it.yours.id to it.theirs.id })
  }

  /** [lists] as Hugo sends them, with [held] lines written by hand after them. */
  private fun withHeld(lists: List<GroceryList>, vararg held: String): String =
    SyncCodec.encode(SyncPayload(HUGO, stamp(5_000, HUGO), lists, run = "r1")) + held.joinToString("") { "$it\n" }

  private fun lists(count: Int) =
    (1..count).map { n -> GroceryList(ListId("${HUGO.value}:$n"), "List $n", ListAccent.ACCENT, emptyList(), both) }

  @Test
  fun `the limit on left-out ids is one for the whole payload, not one per line`() {
    val most = HeldIds.MOST
    val four = lists(4)
    fun quarters(extra: Int) = four.map { "held|${it.id.value}|${AVA.value}|1-${most / 4 + extra}" }.toTypedArray()

    val full = SyncCodec.decode(withHeld(four, *quarters(extra = 0)))
    assertEquals("up to the limit, between them", most, full!!.held.values.sumOf { it.size })
    assertNull("one more refuses the payload", SyncCodec.decode(withHeld(four, *quarters(extra = 1))))

    // A line of a few bytes, again and again: each well under the limit, and naming the same ids
    // every time, so the set never grows. Still refused, before it is walked thousands of times.
    val again = Array(1_000) { "held|${weekly.value}|${AVA.value}|1-100" }
    assertNull(SyncCodec.decode(withHeld(listOf(list()), *again)))
  }

  @Test
  fun `one run longer than the limit is refused before it is expanded`() {
    val most = HeldIds.MOST
    fun weeklyHolding(prefix: String, run: String) = SyncCodec.decode(withHeld(listOf(list()), "held|${weekly.value}|$prefix|$run"))

    assertEquals(most, weeklyHolding(AVA.value, "1-$most")!!.held.getValue(weekly).size)
    assertNull(weeklyHolding(AVA.value, "1-${most + 1}"))
    assertNull("the twenty bytes that once named a hundred thousand", weeklyHolding("P", "0-99999"))
    assertNull("nor does the arithmetic overflow", weeklyHolding("P", "0-${Long.MAX_VALUE}"))
  }

  @Test
  fun `left-out ids for a list the payload does not carry are dropped unread`() {
    val stranger = "${AVA.value}:99"
    val decoded =
      SyncCodec.decode(
        withHeld(
          listOf(list()),
          "held|$stranger|P|0-${Long.MAX_VALUE}",
          "held|$stranger|P|3-x",
          "held|${weekly.value}|${AVA.value}|1-${HeldIds.MOST}",
        )
      )

    // Read, the first would have refused the payload, and its ids would have counted against the
    // limit the list it does carry uses up whole.
    assertEquals(setOf(weekly), decoded!!.held.keys)
    assertEquals(HeldIds.MOST, decoded.held.getValue(weekly).size)
  }

  @Test
  fun `no honest sender holds more items than the limit, since sent whole they would not fit a frame`() {
    // The emptiest item there is, with ids as short as real ones: the limit assumes no item line is
    // shorter than SHORTEST_ITEM_LINE, and says it is less than half of this.
    val empty = item("${HUGO.value}:1", "", stamp(0, HUGO))

    assertEquals("as long as a real one", DeviceIdentity.deviceIdFor(byteArrayOf(1)).value.length, HUGO.value.length)
    assertTrue(SyncCodec.itemLine(weekly, empty).length >= 2 * HeldIds.SHORTEST_ITEM_LINE)
    assertTrue(HeldIds.MOST.toLong() * HeldIds.SHORTEST_ITEM_LINE <= SyncExchange.MAX_FRAME)
  }
}
