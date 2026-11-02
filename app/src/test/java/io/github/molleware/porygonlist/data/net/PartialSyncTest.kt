package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.ListRepository
import io.github.molleware.porygonlist.data.LocalNode
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.IdFactory
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.PeerCopy
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncExchange
import io.github.molleware.porygonlist.data.sync.SyncPayload
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phones that send each other only what changed, end to end minus the radio, and checked against
 * phones that send whole lists.
 *
 * As in [SyncCoordinatorTest], every phone runs the real coordinator and a call runs the other's
 * listener over pipes. Here every frame that crosses is also kept, so a test can see what was sent,
 * and the clocks are the test's, so two runs of the same edits produce the same stamps.
 */
class PartialSyncTest {

  private val pool = Executors.newCachedThreadPool()

  @After fun stop() = pool.shutdownNow().let {}

  /** The test's notion of now, for every phone's clock, moved on by every edit. */
  private var wall = 1_000L

  private class Repo(initial: AppState, now: () -> Long) : ListRepository {
    private val flow = MutableStateFlow<AppState?>(initial)
    override val state: StateFlow<AppState?> = flow
    private val node =
      LocalNode(initial.localDevice, IdFactory(initial.localDevice, start = initial.idCounter), HybridClock(initial.localDevice, now, initial.clockHead))

    override suspend fun load() = Unit

    @Synchronized
    override fun update(transform: (AppState, LocalNode) -> AppState) {
      flow.value = transform(flow.value!!, node).copy(idCounter = node.ids.peek(), clockHead = node.clock.head())
    }

    override suspend fun reset() = Unit

    val now: AppState
      get() = flow.value!!
  }

  /** One frame that crossed: who wrote it, and the payload in it, if it was one. */
  private data class Sent(val by: DeviceId, val payload: SyncPayload)

  private val wire = mutableListOf<Sent>()

  private inner class Phone(val id: DeviceId, val key: ByteArray, state: AppState, val partial: Boolean) {
    var repo = Repo(state, { wall })
    lateinit var coordinator: SyncCoordinator

    val now: AppState
      get() = repo.now

    /** A new process: nothing in memory, and the state as it was last saved — [saved], if given. */
    fun restart(saved: AppState = repo.now, partial: Boolean = this.partial) {
      repo = Repo(saved, { wall })
      coordinator = coordinatorFor(this, partial)
    }

    fun edit(change: (AppState, LocalNode) -> AppState) {
      wall += 10
      repo.update(change)
    }
  }

  private val phones = mutableMapOf<DeviceId, Phone>()

  /** When set, the next call's reply never reaches the caller, though the listener has the call. */
  private var loseNextReply = false

  private fun coordinatorFor(me: Phone, partial: Boolean): SyncCoordinator =
    SyncCoordinator(
      repo = me.repo,
      identity = { error("no TLS in this test") },
      found = emptyFlow(),
      scope = CoroutineScope(Dispatchers.Unconfined),
      dial = { peer, payload -> call(me, phones.getValue(peer.peer.deviceId), payload) },
      now = { 0L },
      callbackPort = { null },
      partial = partial,
    )

  /** Calls placed, answered or not. */
  private var calls = 0

  private fun call(caller: Phone, callee: Phone, payload: SyncPayload): SyncExchange.Exchange? {
    calls++
    val toListener = PipedInputStream(1 shl 16)
    val callerOut = PipedOutputStream(toListener)
    val toCaller = PipedInputStream(1 shl 16)
    val listenerPipe = PipedOutputStream(toCaller)
    val callerTape = ByteArrayOutputStream()
    val listenerTape = ByteArrayOutputStream()
    val lose = loseNextReply.also { loseNextReply = false }
    // The listener writes its ack as two writes, header then body, and its reply after: a lost reply
    // is every write after those two failing, as a dropped connection would.
    var writes = 0
    val listenerOut =
      object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
          if (lose && ++writes > 2) throw IOException("reply lost")
          listenerTape.write(b, off, len)
          listenerPipe.write(b, off, len)
        }

        override fun flush() = listenerPipe.flush()

        override fun close() = listenerPipe.close()
      }

    val answered =
      pool.submit {
        try {
          callee.coordinator.answerOn(caller.key, toListener, listenerOut)
        } finally {
          listenerOut.close()
        }
      }
    val tee =
      object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
          callerTape.write(b, off, len)
          callerOut.write(b, off, len)
        }

        override fun flush() = callerOut.flush()
      }
    val result = SyncExchange.call(payload, tee, toCaller)
    callerOut.close()
    answered.get(5, TimeUnit.SECONDS)
    payloadsIn(callerTape.toByteArray()).forEach { wire += Sent(caller.id, it) }
    payloadsIn(listenerTape.toByteArray()).forEach { wire += Sent(callee.id, it) }
    return result
  }

  /** The payloads among the frames in [bytes], acks left out. */
  private fun payloadsIn(bytes: ByteArray): List<SyncPayload> {
    val out = mutableListOf<SyncPayload>()
    var i = 0
    while (i < bytes.size) {
      val end = bytes.indexOfFirstFrom(i, '\n'.code.toByte())
      val size = String(bytes, i, end - i).substringAfter(' ').toInt()
      SyncCodec.decode(String(bytes, end + 1, size))?.let { out += it }
      i = end + 1 + size
    }
    return out
  }

  private fun ByteArray.indexOfFirstFrom(from: Int, b: Byte): Int = (from until size).first { this[it] == b }

  // ── Phones and lists ───────────────────────────────────────────────────────

  private val hugoId = DeviceId("HUGOOOOOOOOOOOOO")
  private val avaId = DeviceId("AVAAAAAAAAAAAAAA")
  private val samId = DeviceId("SAMAAAAAAAAAAAAA")
  private val keys = mapOf(hugoId to byteArrayOf(1), avaId to byteArrayOf(2), samId to byteArrayOf(3))
  private val names = mapOf(hugoId to "Hugo", avaId to "Ava", samId to "Sam")
  private val weekly = ListId("${hugoId.value}:1")

  private fun item(id: String, name: String, at: Hlc) =
    GroceryItem(ItemId(id), Field(name, at), Field(1, at), checkedAt = at, removed = Field(false, at))

  /**
   * Hugo with a list of [items] shared with everyone in [ids], each of whom already holds it — the
   * moment after a first exchange, but with nothing yet confirmed by anyone.
   */
  private fun phonesOf(ids: List<DeviceId>, partial: (DeviceId) -> Boolean = { true }, items: Int = 20): List<Phone> {
    val people = ids.map { Person(it, names.getValue(it), names.getValue(it).take(1)) }
    val list =
      GroceryList(weekly, "Weekly shop", ListAccent.ACCENT, (1..items).map { item("${hugoId.value}:${it + 1}", "Thing $it", Hlc(500, 0, hugoId)) }, people)
    return ids.map { me ->
      val state =
        AppState(
          localDevice = me,
          idCounter = 100,
          clockHead = Hlc(900, 0, me),
          lists = listOf(list),
          activeListId = weekly,
          networks = emptyList(),
          peers = ids.filterNot { it == me }.map { TrustedPeer(it, keys.getValue(it), names.getValue(it), 0) },
        )
      Phone(me, keys.getValue(me), state, partial(me)).also {
        it.coordinator = coordinatorFor(it, it.partial)
        phones[me] = it
      }
    }
  }

  private fun Phone.pushTo(other: Phone) =
    coordinator.pushIfOwed(ReachablePeer(TrustedPeer(other.id, other.key, names.getValue(other.id), 0), "10.0.0.1", 1), now)

  /**
   * A call placed whether or not anything is owed, as the start of a background window places it.
   *
   * What the comparison of partial with whole needs: whether something is owed depends on the
   * tombstones a phone still holds, and those are collected at different moments either way — a
   * whole list hands back one already collected, which then waits for the next receipt. Left to
   * decide, the two runs would call at different steps and stop being the same sequence at all.
   */
  private fun Phone.callsNow(other: Phone) {
    coordinator.callEveryoneOnce()
    pushTo(other)
  }

  private fun Phone.adds(name: String) = edit { s, node ->
    s.copy(lists = s.lists.map { it.withItemsAppended(listOf(item(node.ids.nextItem().value, name, node.clock.tick())), node.clock.head()) })
  }

  private fun Phone.ticks(name: String) = edit { s, node ->
    s.copy(lists = s.lists.map { l -> l.copy(items = l.items.map { if (it.name.value == name) it.copy(checked = !it.checked, checkedAt = node.clock.tick()) else it }) })
  }

  private fun Phone.has(name: String) = now.lists.singleOrNull()?.liveItems?.any { it.name.value == name } == true

  private fun sentBy(phone: Phone, since: Int = 0) = wire.drop(since).filter { it.by == phone.id && it.payload.lists.isNotEmpty() }.map { it.payload }

  // ── With a phone that never says it takes partial lists ────────────────────

  @Test
  fun `a phone that has never said it takes partial lists gets whole ones, and sends them`() {
    // Every 0.1.0 phone. One with partial lists off stands in for it: it sends no run, so nothing
    // tells Hugo it would read one.
    val (hugo, ava) = phonesOf(listOf(hugoId, avaId), partial = { it == hugoId })

    repeat(3) { round ->
      hugo.adds("Hugo's $round")
      hugo.pushTo(ava)
      ava.adds("Ava's $round")
      ava.pushTo(hugo)
    }

    assertTrue(wire.none { it.payload.held.isNotEmpty() })
    assertTrue("Hugo sent her the whole list every time", sentBy(hugo).all { it.lists.single().items.size >= 20 })
    assertTrue("and said nothing about a run that she would read", sentBy(ava).all { it.run == null })
    assertEquals(hugo.now.lists.single().liveItems, ava.now.lists.single().liveItems)
  }

  // ── Two phones that do ─────────────────────────────────────────────────────

  @Test
  fun `after the first exchange only what changed goes`() {
    val (hugo, ava) = phonesOf(listOf(hugoId, avaId))
    hugo.pushTo(ava)
    assertFalse("the first is whole", wire.first().payload.isPartial(weekly))

    val mark = wire.size
    hugo.ticks("Thing 7")
    hugo.pushTo(ava)

    val sent = sentBy(hugo, since = mark).single()
    assertTrue(sent.isPartial(weekly))
    assertEquals(listOf("Thing 7"), sent.lists.single().items.map { it.name.value })
    assertTrue("and it arrived", ava.now.lists.single().items.single { it.name.value == "Thing 7" }.checked)
  }

  @Test
  fun `a reply that is lost is made good by the next exchange`() {
    val (hugo, ava) = phonesOf(listOf(hugoId, avaId))
    hugo.pushTo(ava)
    ava.pushTo(hugo)

    hugo.adds("Cups")
    ava.adds("Lemons")
    val receipt = hugo.now.deliveredTo.confirmedBy(avaId)
    loseNextReply = true
    hugo.pushTo(ava)
    assertTrue("Ava kept what she acknowledged", ava.has("Cups"))
    assertFalse("but Hugo never heard back", hugo.has("Lemons"))
    assertEquals("so he has banked nothing", receipt, hugo.now.deliveredTo.confirmedBy(avaId))

    repeat(2) {
      hugo.pushTo(ava)
      ava.pushTo(hugo)
    }

    assertTrue(hugo.has("Lemons"))
    assertEquals(hugo.now.lists.single().liveItems.sortedBy { it.id.value }, ava.now.lists.single().liveItems.sortedBy { it.id.value })
  }

  @Test
  fun `a new process starts with whole lists`() {
    val (hugo, ava) = phonesOf(listOf(hugoId, avaId))
    hugo.pushTo(ava)
    hugo.adds("Cups")
    hugo.pushTo(ava)
    assertTrue(sentBy(hugo).last().isPartial(weekly))

    hugo.restart()
    hugo.adds("Plates")
    val mark = wire.size
    hugo.pushTo(ava)

    assertFalse("Hugo remembers nothing of what Ava confirmed", sentBy(hugo, since = mark).single().isPartial(weekly))
  }

  @Test
  fun `a peer restarted from an older save gets whole lists again, and its ack proves nothing`() {
    val (hugo, ava) = phonesOf(listOf(hugoId, avaId))
    hugo.pushTo(ava)
    ava.pushTo(hugo)
    val older = ava.now

    // Ava confirms Cups, then her phone falls back to the save before it, in a new process.
    hugo.adds("Cups")
    hugo.pushTo(ava)
    ava.restart(saved = older)
    assertFalse(ava.has("Cups"))

    // Hugo's next payload was cut for her old run, so it leaves Cups out. Her answer says another.
    hugo.adds("Plates")
    val receipt = hugo.now.deliveredTo.confirmedBy(avaId)
    hugo.pushTo(ava)
    assertTrue(sentBy(hugo).last().isPartial(weekly))
    assertEquals("no receipt banked on the strength of it", receipt, hugo.now.deliveredTo.confirmedBy(avaId))

    hugo.pushTo(ava)

    assertFalse("the next went whole", sentBy(hugo).last().isPartial(weekly))
    assertTrue("and Cups is back on her phone", ava.has("Cups"))
  }

  @Test
  fun `a peer with nothing to send back gets the whole list, and the calls stop`() {
    // Ava has lost the list without a new run — nothing known does that, which is why it is tested.
    // Her answer is "nothing for you", which names Hugo rather than her and says no run. A partial
    // list could never reach her, and its ack would never be banked, so Hugo would call for ever.
    val (hugo, ava) = phonesOf(listOf(hugoId, avaId))
    hugo.pushTo(ava)
    ava.repo.update { s, _ -> s.copy(lists = emptyList(), activeListId = ListId("")) }

    hugo.adds("Cups")
    repeat(4) { hugo.pushTo(ava) }
    val settled = calls
    repeat(3) { hugo.pushTo(ava) }

    assertTrue("the whole list went, so she is invited back", ava.has("Cups"))
    assertEquals("and nothing more is owed", settled, calls)
  }

  @Test
  fun `every so often a whole list goes anyway`() {
    val (hugo, ava) = phonesOf(listOf(hugoId, avaId))
    hugo.pushTo(ava)

    repeat(PeerCopy.WHOLE_EVERY + 1) {
      hugo.adds("Thing $it again")
      hugo.pushTo(ava)
    }

    assertEquals(
      listOf(false) + List(PeerCopy.WHOLE_EVERY) { true } + false,
      sentBy(hugo).map { it.isPartial(weekly) },
    )
  }

  // ── A removal passed on by the phone in the middle ─────────────────────────

  /**
   * Sam takes Thing 3 off; only Hugo talks to both. Hugo hears of it after he last handed Ava a
   * payload, but Sam made it before — and Hugo once collected it at once on that receipt, so Ava never
   * heard. Whole lists then brought the item back to Hugo from Ava; partial ones left it on Ava's
   * phone alone. Either way, every phone must end without it.
   */
  private fun removalPassedOn(partial: Boolean) {
    phones.clear()
    val (hugo, ava, sam) = phonesOf(listOf(hugoId, avaId, samId), partial = { partial })
    repeat(2) {
      hugo.callsNow(ava)
      hugo.callsNow(sam)
    }

    sam.edit { s, node ->
      s.copy(lists = s.lists.map { l -> l.copy(items = l.items.map { if (it.name.value == "Thing 3") it.copy(removed = it.removed.set(true, node.clock.tick())) else it }) })
    }
    hugo.adds("Cups")
    hugo.callsNow(ava)
    hugo.callsNow(sam)
    repeat(3) {
      hugo.callsNow(ava)
      ava.callsNow(hugo)
      hugo.callsNow(sam)
      sam.callsNow(hugo)
    }

    for (phone in listOf(hugo, ava, sam)) assertFalse("${names[phone.id]} still has it, partial $partial", phone.has("Thing 3"))
    assertTrue(ava.has("Cups"))
  }

  @Test
  fun `a removal passed on by a middle phone reaches the third with whole lists, and does not come back`() = removalPassedOn(partial = false)

  @Test
  fun `a removal passed on by a middle phone reaches the third with partial lists`() = removalPassedOn(partial = true)

  // ── The same edits, sent partial and sent whole ────────────────────────────

  /**
   * Plays one seeded sequence of edits and exchanges across [ids], each phone sending partial lists
   * or not as [partial] says, then lets them settle. Returns what each phone ends up showing, and
   * how many bytes of payload crossed.
   *
   * Edits are additions, renames, quantities, ticks, moves, list renames, and copies the way merging
   * two lists makes them; removals too, when [removals] says so. Exchanges include lost replies,
   * calls everyone owes nothing, and phones starting a new process.
   */
  private fun play(seed: Int, ids: List<DeviceId>, partial: Boolean, removals: Boolean): Pair<Map<DeviceId, Any>, Int> {
    phones.clear()
    wire.clear()
    wall = 1_000L
    val all = phonesOf(ids, partial = { partial })
    val rnd = Random(seed)
    val words = listOf("Rice", "Milk", "Eggs", "Bread", "Tea", "Lemons", "Flour", "Ice")

    fun Phone.randomEdit() = edit { s, node ->
      val list = s.lists.single()
      val live = list.liveItems.sortedBy { it.id.value }
      val target = live.randomOrNull(rnd)
      val at = node.clock.tick()
      val next =
        when (rnd.nextInt(9)) {
          0, 1 -> list.withItemsAppended(listOf(item(node.ids.nextItem().value, words.random(rnd), at)), at)
          2 -> target?.let { t -> list.copy(items = list.items.map { if (it.id == t.id) it.copy(name = it.name.set(words.random(rnd), at)) else it }) }
          3 -> target?.let { t -> list.copy(items = list.items.map { if (it.id == t.id) it.copy(qty = it.qty.set(1 + rnd.nextInt(4), at)) else it }) }
          4 -> target?.let { t -> list.copy(items = list.items.map { if (it.id == t.id) it.copy(checked = !it.checked, checkedAt = at) else it }) }
          5 -> target?.takeIf { removals }?.let { t -> list.copy(items = list.items.map { if (it.id == t.id) it.copy(removed = it.removed.set(true, at)) else it }) }
          6 -> target?.let { list.withItemMoved(it.id, rnd.nextInt(live.size), at) }
          7 -> list.copy(name = "Shop ${rnd.nextInt(100)}", nameAt = at)
          // What merging two lists does: a new id, the old stamps, only its place new.
          else -> target?.let { t -> list.withItemsAppended(listOf(t.copy(id = node.ids.nextItem(), checked = false)), at) }
        } ?: list
      s.copy(lists = listOf(next))
    }

    repeat(120) { step ->
      val phone = all.random(rnd)
      when (rnd.nextInt(10)) {
        in 0..4 -> phone.randomEdit()
        in 5..7 -> phone.callsNow(all.filterNot { it === phone }.random(rnd))
        8 -> {
          loseNextReply = true
          phone.callsNow(all.filterNot { it === phone }.random(rnd))
        }
        else -> if (step % 3 == 0) phone.restart() else phone.coordinator.callEveryoneOnce()
      }
    }
    // Settle: everyone calls everyone, until a round passes in which nobody owed anything.
    repeat(6) { all.forEach { a -> a.coordinator.callEveryoneOnce(); all.filterNot { it === a }.forEach { a.pushTo(it) } } }

    val shown =
      all.associate { p ->
        val list = p.now.lists.single()
        p.id to
          listOf(
            list.name,
            // Without this phone's own note of when it heard of a leaving, which is not what anyone sees.
            list.people.map { it.copy(removalHeardAt = null) },
            list.liveItems.sortedBy { it.id.value },
            p.now.openConflicts.map { it.question }.toSet(),
          )
      }
    return shown to wire.sumOf { SyncCodec.encode(it.payload).length }
  }

  private fun sameEitherWay(ids: List<DeviceId>, removals: Boolean) {
    var wholeBytes = 0
    var partialBytes = 0
    for (seed in 1..25) {
      val (whole, wb) = play(seed, ids, partial = false, removals = removals)
      val (partial, pb) = play(seed, ids, partial = true, removals = removals)
      assertEquals("seed $seed", whole, partial)
      // Everyone ended up on the same page, so the comparison is of something settled.
      assertEquals("seed $seed", 1, whole.values.map { (it as List<*>).take(3) }.distinct().size)
      wholeBytes += wb
      partialBytes += pb
    }
    println("${ids.size} phones, 25 sequences: whole $wholeBytes bytes, partial $partialBytes bytes")
    assertNotEquals(wholeBytes, partialBytes)
  }

  @Test
  fun `two phones end the same whether lists go partial or whole`() = sameEitherWay(listOf(hugoId, avaId), removals = true)

  @Test
  fun `three phones end the same whether lists go partial or whole`() = sameEitherWay(listOf(hugoId, avaId, samId), removals = true)
}
