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
import io.github.molleware.porygonlist.data.sync.SyncExchange
import io.github.molleware.porygonlist.data.sync.SyncPayload
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two phones, end to end, minus the radio.
 *
 * Each has a real coordinator over its own state; a call from one runs the other's listener on a
 * second thread, joined by pipes. Everything above the socket is the production code, which is the
 * point: the part most likely to be wrong is not the bytes but what each end decides to do next.
 */
class SyncCoordinatorTest {

  private val avaId = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugoId = DeviceId("HUGOOOOOOOOOOOOO")
  private val avaKey = byteArrayOf(1, 1, 1)
  private val hugoKey = byteArrayOf(2, 2, 2)

  private val listener = Executors.newCachedThreadPool()

  @After fun stop() = listener.shutdownNow().let {}

  /** A repository that is just a value in memory, with a node that behaves like the real one. */
  private class MemoryRepo(initial: AppState) : ListRepository {
    private val flow = MutableStateFlow<AppState?>(initial)
    override val state: StateFlow<AppState?> = flow
    private val node =
      LocalNode(
        initial.localDevice,
        IdFactory(initial.localDevice, start = initial.idCounter),
        HybridClock(initial.localDevice, start = initial.clockHead),
      )

    override suspend fun load() = Unit

    @Synchronized
    override fun update(transform: (AppState, LocalNode) -> AppState) {
      flow.value = transform(flow.value!!, node).copy(idCounter = node.ids.peek(), clockHead = node.clock.head())
    }

    override suspend fun reset() = Unit

    val now: AppState
      get() = flow.value!!
  }

  private inner class Phone(me: DeviceId, peer: TrustedPeer, lists: List<GroceryList>) {
    val repo =
      MemoryRepo(
        AppState(
          localDevice = me,
          idCounter = 100,
          clockHead = Hlc(1_000, 0, me),
          lists = lists,
          activeListId = lists.firstOrNull()?.id ?: ListId(""),
          online = true,
          networks = emptyList(),
          peers = listOf(peer),
          conflict = null,
        )
      )
    lateinit var coordinator: SyncCoordinator
  }

  private fun item(id: String, name: String, at: Hlc) =
    GroceryItem(
      id = ItemId(id),
      name = Field(name, at),
      qty = Field(1, at),
      checked = false,
      checkedAt = at,
      removed = Field(false, at),
    )

  private val shared = ListId("${hugoId.value}:7")
  private val both = listOf(Person(avaId, "Ava", "A"), Person(hugoId, "Hugo", "H"))

  private val calls = AtomicInteger()

  /** The coordinators' notion of now, moved by hand so absences can be timed exactly. */
  private var clockMs = 0L

  /**
   * Ava with no lists, and Hugo with one list naming them both — the moment just after he added her.
   */
  private fun pair(): Pair<Phone, Phone> {
    val ava = Phone(avaId, TrustedPeer(hugoId, hugoKey, "Hugo", 0), emptyList())
    val hugo =
      Phone(
        hugoId,
        TrustedPeer(avaId, avaKey, "Ava", 0),
        listOf(
          GroceryList(
            shared,
            "Party, Saturday",
            ListAccent.NEUTRAL,
            listOf(item("${hugoId.value}:8", "Ice", Hlc(2_000, 0, hugoId))),
            both,
          )
        ),
      )
    ava.coordinator = coordinator(ava, callingInto = { hugo }, asKey = avaKey)
    hugo.coordinator = coordinator(hugo, callingInto = { ava }, asKey = hugoKey)
    return ava to hugo
  }

  /** A coordinator whose calls run the other phone's listener over a pair of pipes. */
  private fun coordinator(me: Phone, callingInto: () -> Phone, asKey: ByteArray) =
    SyncCoordinator(
      repo = me.repo,
      identity = { error("no TLS in this test") },
      found = emptyFlow(),
      scope = CoroutineScope(Dispatchers.Unconfined),
      dial = { _, payload ->
        calls.incrementAndGet()
        val toListener = PipedInputStream(1 shl 16)
        val callerOut = PipedOutputStream(toListener)
        val toCaller = PipedInputStream(1 shl 16)
        val listenerOut = PipedOutputStream(toCaller)

        val answered =
          listener.submit {
            try {
              callingInto().coordinator.answerOn(asKey, toListener, listenerOut)
            } finally {
              // What the endpoint's finally does with the socket.
              listenerOut.close()
            }
          }
        val result = SyncExchange.call(payload, callerOut, toCaller)
        callerOut.close()
        answered.get(5, TimeUnit.SECONDS)
        result
      },
      now = { clockMs },
    )

  private fun reach(id: DeviceId, key: ByteArray, name: String) = ReachablePeer(TrustedPeer(id, key, name, 0), "10.0.0.1", 1)

  private fun Phone.pushTo(other: DeviceId, key: ByteArray, name: String) =
    coordinator.pushIfOwed(reach(other, key, name), repo.now)

  @Test
  fun `a list someone is added to arrives on their phone`() {
    val (ava, hugo) = pair()

    hugo.pushTo(avaId, avaKey, "Ava")

    val arrived = ava.repo.now.visibleLists.single()
    assertEquals("Party, Saturday", arrived.name)
    assertEquals(listOf("Ice"), arrived.liveItems.map { it.name.value })
  }

  @Test
  fun `the pushing stops once both phones agree`() {
    val (ava, hugo) = pair()

    // Keep offering both phones the chance to push. A loop would show up as calls that never stop.
    repeat(6) {
      hugo.pushTo(avaId, avaKey, "Ava")
      ava.pushTo(hugoId, hugoKey, "Hugo")
    }
    val settled = calls.get()
    repeat(3) {
      hugo.pushTo(avaId, avaKey, "Ava")
      ava.pushTo(hugoId, hugoKey, "Hugo")
    }

    assertEquals("nothing new, so nobody should have called", settled, calls.get())
    assertTrue("it should have taken only a handful to settle", settled <= 3)
  }

  @Test
  fun `edits on both sides end up on both phones`() {
    val (ava, hugo) = pair()
    hugo.pushTo(avaId, avaKey, "Ava")

    ava.repo.update { s, node ->
      s.copy(lists = s.lists.map { it.copy(items = it.items + item("${avaId.value}:200", "Lemons", node.clock.tick())) })
    }
    hugo.repo.update { s, node ->
      s.copy(lists = s.lists.map { it.copy(items = it.items + item("${hugoId.value}:9", "Cups", node.clock.tick())) })
    }

    repeat(3) {
      ava.pushTo(hugoId, hugoKey, "Hugo")
      hugo.pushTo(avaId, avaKey, "Ava")
    }

    val expected = setOf("Ice", "Lemons", "Cups")
    assertEquals(expected, ava.repo.now.visibleLists.single().liveItems.map { it.name.value }.toSet())
    assertEquals(expected, hugo.repo.now.visibleLists.single().liveItems.map { it.name.value }.toSet())
  }

  @Test
  fun `a peer that blinks off the network is not pushed to again`() {
    // Seen on hardware: mDNS loses and finds a phone within seconds, and each blip cost an exchange.
    val (_, hugo) = pair()
    val ava = reach(avaId, avaKey, "Ava")
    hugo.coordinator.noteReachable(listOf(ava))
    hugo.coordinator.pushIfOwed(ava, hugo.repo.now)
    val before = calls.get()

    clockMs = 1_000
    hugo.coordinator.noteReachable(emptyList())
    clockMs = 6_000
    hugo.coordinator.noteReachable(listOf(ava))
    hugo.coordinator.pushIfOwed(ava, hugo.repo.now)

    assertEquals("five seconds away changes nothing", before, calls.get())
  }

  @Test
  fun `a peer that was really away is pushed to again`() {
    // It may have been reinstalled or restored meanwhile; an old ack says nothing about it now.
    val (_, hugo) = pair()
    val ava = reach(avaId, avaKey, "Ava")
    hugo.coordinator.noteReachable(listOf(ava))
    hugo.coordinator.pushIfOwed(ava, hugo.repo.now)
    val before = calls.get()

    clockMs = 1_000
    hugo.coordinator.noteReachable(emptyList())
    clockMs = 120_000
    hugo.coordinator.noteReachable(listOf(ava))
    hugo.coordinator.pushIfOwed(ava, hugo.repo.now)

    assertEquals("two minutes away earns a fresh push", before + 1, calls.get())
  }

  @Test
  fun `a peer that never left is not forgotten between quiet looks`() {
    // Looks can be a minute apart when nothing happens. Time since last looked is not absence.
    val (_, hugo) = pair()
    val ava = reach(avaId, avaKey, "Ava")
    hugo.coordinator.noteReachable(listOf(ava))
    hugo.coordinator.pushIfOwed(ava, hugo.repo.now)
    val before = calls.get()

    clockMs = 90_000
    hugo.coordinator.noteReachable(listOf(ava))
    hugo.coordinator.pushIfOwed(ava, hugo.repo.now)

    assertEquals(before, calls.get())
  }

  @Test
  fun `a rename on one phone lands on the other`() {
    // Found on hardware: the rename was sent, and the receiver kept its own name anyway, because
    // nothing said which of the two was newer.
    val (ava, hugo) = pair()
    hugo.pushTo(avaId, avaKey, "Ava")

    ava.repo.update { s, node ->
      s.copy(lists = s.lists.map { it.copy(name = "Party, Sunday", nameAt = node.clock.tick()) })
    }
    repeat(2) {
      ava.pushTo(hugoId, hugoKey, "Hugo")
      hugo.pushTo(avaId, avaKey, "Ava")
    }

    assertEquals("Party, Sunday", hugo.repo.now.visibleLists.single().name)
    assertEquals("Party, Sunday", ava.repo.now.visibleLists.single().name)
  }

  @Test
  fun `an older rename does not undo a newer one`() {
    val (ava, hugo) = pair()
    hugo.pushTo(avaId, avaKey, "Ava")

    // Hugo renames first, Ava after; whichever order the exchanges happen in, Ava's name stands.
    hugo.repo.update { s, _ -> s.copy(lists = s.lists.map { it.copy(name = "Older", nameAt = Hlc(5_000, 0, hugoId)) }) }
    ava.repo.update { s, _ -> s.copy(lists = s.lists.map { it.copy(name = "Newer", nameAt = Hlc(6_000, 0, avaId)) }) }
    repeat(2) {
      hugo.pushTo(avaId, avaKey, "Ava")
      ava.pushTo(hugoId, hugoKey, "Hugo")
    }

    assertEquals("Newer", ava.repo.now.visibleLists.single().name)
    assertEquals("Newer", hugo.repo.now.visibleLists.single().name)
  }

  @Test
  fun `leaving reaches the other phone and then the list is gone`() {
    val (ava, hugo) = pair()
    hugo.pushTo(avaId, avaKey, "Ava")

    ava.repo.update { s, node -> s.copy(lists = s.lists.map { it.withPersonLeft(avaId, node.clock.tick()) }) }
    assertTrue("out of sight straight away", ava.repo.now.visibleLists.isEmpty())

    ava.pushTo(hugoId, hugoKey, "Hugo")

    // Hugo has heard. With nobody else on the list he has nobody to pass it on to, so he collects
    // her tombstone at once — on a list with a third person he would keep it until they confirmed.
    assertFalse("Hugo has heard", hugo.repo.now.visibleLists.single().personFor(avaId)?.present == true)
    assertEquals("and still has his list", 1, hugo.repo.now.visibleLists.size)
    // Hugo acknowledged, so there is nobody left to tell and nothing left to keep.
    assertTrue("the tombstone has done its job", ava.repo.now.lists.isEmpty())
  }

  @Test
  fun `a caller with an unknown key gets nothing`() {
    val (ava, hugo) = pair()
    val stranger = byteArrayOf(9, 9, 9)

    // Hugo's coordinator, but presenting a key Ava has never paired with.
    val impostor =
      SyncCoordinator(
        repo = hugo.repo,
        identity = { error("unused") },
        found = emptyFlow(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        dial = { _, payload ->
          val toListener = PipedInputStream(1 shl 16)
          val callerOut = PipedOutputStream(toListener)
          val toCaller = PipedInputStream(1 shl 16)
          val listenerOut = PipedOutputStream(toCaller)
          val answered =
            listener.submit {
              try {
                ava.coordinator.answerOn(stranger, toListener, listenerOut)
              } finally {
                listenerOut.close()
              }
            }
          val result = SyncExchange.call(payload, callerOut, toCaller)
          callerOut.close()
          answered.get(5, TimeUnit.SECONDS)
          result
        },
      )

    impostor.pushIfOwed(reach(avaId, avaKey, "Ava"), hugo.repo.now)

    assertTrue("nothing should have been accepted", ava.repo.now.lists.isEmpty())
  }
}
