package io.github.molleware.porygonlist.ui

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.Conflict
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.ListRepository
import io.github.molleware.porygonlist.data.LocalNode
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.Staple
import io.github.molleware.porygonlist.data.crypto.InMemoryIdentityStore
import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PairingCodec
import io.github.molleware.porygonlist.data.net.NetworkMonitor
import io.github.molleware.porygonlist.data.net.NetworkSnapshot
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.IdFactory
import io.github.molleware.porygonlist.data.sync.ItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An in-memory stand-in for the file-backed repository, applying updates exactly as it does. */
private class FakeRepository(private val identity: () -> LocalIdentity) : ListRepository {

  private val _state = MutableStateFlow<AppState?>(null)
  override val state: StateFlow<AppState?> = _state.asStateFlow()

  private var node: LocalNode? = null

  override suspend fun load() {
    if (_state.value == null) seed()
  }

  override fun update(transform: (AppState, LocalNode) -> AppState) {
    val current = _state.value ?: return
    val localNode = node ?: return
    _state.value = transform(current, localNode).copy(idCounter = localNode.ids.peek(), clockHead = localNode.clock.head())
  }

  override suspend fun reset() = seed()

  private fun seed() {
    val fresh = AppState.seed(localDevice = identity().deviceId)
    node =
      LocalNode(
        device = fresh.localDevice,
        ids = IdFactory(fresh.localDevice, start = fresh.idCounter),
        clock = HybridClock(fresh.localDevice, start = fresh.clockHead),
      )
    _state.value = fresh
  }
}

private object OfflineMonitor : NetworkMonitor {
  override val snapshots = flowOf(NetworkSnapshot.Offline)
}

class PorygonViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val store = InMemoryIdentityStore()
  private lateinit var repo: FakeRepository
  private lateinit var vm: PorygonViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repo = FakeRepository(store::identity)
    vm = PorygonViewModel(repo, OfflineMonitor, store::identity, store::forget)
  }

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun state() = repo.state.value!!

  // ── First run ─────────────────────────────────────────────────────────────

  @Test
  fun `naming the owner ends first run`() = runTest(dispatcher) {
    repo.load()
    assertFalse(state().named)

    vm.onNameDraftChange("Hugo")
    vm.saveName()

    assertTrue(state().named)
    assertEquals("Hugo", state().displayName)
    assertEquals("", vm.nameDraft)
  }

  // ── Lists ─────────────────────────────────────────────────────────────────

  @Test
  fun `a new list holds only you, and opens`() = runTest(dispatcher) {
    repo.load()
    vm.onNameDraftChange("Hugo")
    vm.saveName()

    vm.onListDraftChange("Hardware shop")
    vm.createList()

    val made = state().activeList
    assertEquals("Hardware shop", made.name)
    assertEquals(listOf(state().localDevice), made.people.map { it.device })
    assertTrue("a new list starts empty", made.items.isEmpty())
  }

  @Test
  fun `a blank name makes no list`() = runTest(dispatcher) {
    repo.load()
    val before = state().lists.size

    vm.onListDraftChange("   ")
    vm.createList()

    assertEquals(before, state().lists.size)
  }

  @Test
  fun `deleting the open list moves you to another`() = runTest(dispatcher) {
    repo.load()
    val victim = state().activeListId

    vm.deleteList(victim)

    assertTrue(state().lists.none { it.id == victim })
    assertTrue("the app is never left with no open list", state().lists.any { it.id == state().activeListId })
  }

  @Test
  fun `the last list is never deleted`() = runTest(dispatcher) {
    repo.load()
    while (state().lists.size > 1) vm.deleteList(state().lists.first().id)

    vm.deleteList(state().lists.single().id)

    assertEquals("there is no state that shows no list at all", 1, state().lists.size)
  }

  @Test
  fun `renaming a list keeps its contents`() = runTest(dispatcher) {
    repo.load()
    val list = state().activeList
    val itemCount = list.items.size

    vm.startRename(list)
    vm.onRenameDraftChange("  Big shop  ")
    vm.saveRename()

    assertEquals("Big shop", state().activeList.name)
    assertEquals(itemCount, state().activeList.items.size)
    assertNull("the form closes after saving", vm.renamingList)
  }

  // ── Staples ───────────────────────────────────────────────────────────────

  @Test
  fun `using a staple adds it to the open list and counts the use`() = runTest(dispatcher) {
    repo.load()
    val before = state().activeList.liveItems.size

    vm.useStaple("Eggs")

    assertEquals(before + 1, state().activeList.liveItems.size)
    assertTrue(state().activeList.liveItems.any { it.name.value == "Eggs" })
    assertEquals(1, state().staples.single { it.name == "Eggs" }.uses)
  }

  @Test
  fun `adding a staple that is already there changes nothing`() = runTest(dispatcher) {
    repo.load()
    val before = state().staples

    vm.onStapleDraftChange("eggs")
    vm.addStaple()

    assertEquals(before, state().staples)
  }

  @Test
  fun `a staple can be added and taken off again`() = runTest(dispatcher) {
    repo.load()

    vm.onStapleDraftChange("Tofu")
    vm.addStaple()
    assertTrue(state().staples.contains(Staple("Tofu")))

    vm.removeStaple("Tofu")
    assertFalse(state().staples.any { it.name == "Tofu" })
  }

  // ── Suggestions ───────────────────────────────────────────────────────────

  @Test
  fun `nothing is offered until there is something to go on`() = runTest(dispatcher) {
    repo.load()

    vm.onDraftChange("")
    assertTrue(vm.suggestions(state()).isEmpty())

    vm.onDraftChange("c")
    assertTrue("one letter matches most of the vocabulary", vm.suggestions(state()).isEmpty())

    vm.onDraftChange("co")
    assertTrue(vm.suggestions(state()).isNotEmpty())
  }

  @Test
  fun `typing a prefix offers what starts with it`() = runTest(dispatcher) {
    repo.load()

    vm.onDraftChange("courg")

    assertEquals(listOf("Courgette"), vm.suggestions(state()))
  }

  @Test
  fun `what is already on the list is never offered`() = runTest(dispatcher) {
    repo.load()
    // The seeded list already holds Tomatoes, and a second one is the duplicate this prevents.
    assertTrue(state().activeList.liveItems.any { it.name.value == "Tomatoes" })

    vm.onDraftChange("tomato")

    assertTrue(vm.suggestions(state()).none { it == "Tomatoes" })
  }

  @Test
  fun `a name typed in full is not read back`() = runTest(dispatcher) {
    repo.load()

    vm.onDraftChange("Courgette")

    assertTrue("the add button already does that", vm.suggestions(state()).none { it == "Courgette" })
  }

  @Test
  fun `taking a suggestion adds it and clears the field`() = runTest(dispatcher) {
    repo.load()
    vm.onDraftChange("courg")

    vm.takeSuggestion("Courgette")

    assertTrue(state().activeList.liveItems.any { it.name.value == "Courgette" })
    assertEquals("", vm.draft)
  }

  @Test
  fun `a name the owner has used is offered back, their spelling`() = runTest(dispatcher) {
    repo.load()
    vm.onStapleDraftChange("rocket")
    vm.addStaple()

    vm.onDraftChange("rock")

    // Their lowercase, not the built-in list's "Rocket" — the app does not correct people.
    assertEquals("rocket", vm.suggestions(state()).first())
  }

  @Test
  fun `suggestions follow the list you are on`() = runTest(dispatcher) {
    repo.load()
    vm.onNameDraftChange("Hugo")
    vm.saveName()
    vm.onDraftChange("tomato")
    val onWeeklyShop = vm.suggestions(state())

    vm.onListDraftChange("Market")
    vm.createList()
    val onNewList = vm.suggestions(state())

    // Tomatoes is on the seeded list and withheld there; the new list has nothing, so it returns.
    assertTrue("Tomatoes" !in onWeeklyShop)
    assertTrue("Tomatoes" in onNewList)
  }

  // ── Clearing the trolley ──────────────────────────────────────────────────

  @Test
  fun `clearing takes the ticked items off and leaves the rest`() = runTest(dispatcher) {
    repo.load()
    val ticked = state().activeList.liveItems.filter { it.checked }.map { it.id }
    val untickedBefore = state().activeList.liveItems.count { !it.checked }
    assertTrue("the seeded list has something in the trolley", ticked.isNotEmpty())

    vm.clearChecked()

    assertEquals(0, state().activeList.liveItems.count { it.checked })
    assertEquals(untickedBefore, state().activeList.liveItems.size)
    // Tombstoned, not dropped: the other phone would otherwise hand the whole trolley back.
    ticked.forEach { id -> assertTrue(state().activeList.items.single { it.id == id }.removed.value) }
  }

  @Test
  fun `clearing is asked about first`() = runTest(dispatcher) {
    repo.load()
    val before = state().activeList.liveItems.size

    vm.askClearChecked()
    assertTrue(vm.confirmingClear)
    vm.cancelClearChecked()

    assertFalse(vm.confirmingClear)
    assertEquals("backing out changes nothing", before, state().activeList.liveItems.size)
  }

  @Test
  fun `clearing an empty trolley is harmless`() = runTest(dispatcher) {
    repo.load()
    vm.clearChecked()
    val after = state().activeList.liveItems.size

    vm.clearChecked()

    assertEquals(after, state().activeList.liveItems.size)
  }

  // ── Sharing a list with somebody new ──────────────────────────────────────

  @Test
  fun `pairing started from a list puts them on that list`() = runTest(dispatcher) {
    repo.load()
    vm.onNameDraftChange("Hugo")
    vm.saveName()
    vm.onListDraftChange("Boat trip")
    vm.createList()
    val shared = state().activeListId

    vm.startPairing(forListId = shared)
    vm.onPairCodeChange(otherPhone("Ava"))
    val theirDevice = vm.pendingInvite!!.deviceId
    vm.pairAsNew()

    val list = state().lists.single { it.id == shared }
    assertTrue("trusted", state().peers.any { it.deviceId == theirDevice })
    assertTrue("and on the list, in one action", list.people.any { it.device == theirDevice })
    assertEquals("Ava", list.people.single { it.device == theirDevice }.name)
  }

  @Test
  fun `pairing on its own shares nothing`() = runTest(dispatcher) {
    repo.load()

    vm.startPairing()
    vm.onPairCodeChange(otherPhone("Ava"))
    val theirDevice = vm.pendingInvite!!.deviceId
    vm.pairAsNew()

    assertTrue(state().peers.any { it.deviceId == theirDevice })
    assertTrue("trusting a phone is not giving it your lists", state().lists.none { list -> list.people.any { it.device == theirDevice } })
  }

  @Test
  fun `the sharing intent is spent once`() = runTest(dispatcher) {
    repo.load()
    vm.onNameDraftChange("Hugo")
    vm.saveName()
    vm.startPairing(forListId = state().activeListId)
    vm.onPairCodeChange(otherPhone("Ava"))
    vm.pairAsNew()

    assertNull(vm.pairingForList)
  }

  @Test
  fun `sharing a list that is not the one open still works`() = runTest(dispatcher) {
    repo.load()
    vm.onNameDraftChange("Hugo")
    vm.saveName()
    val other = state().lists.last { it.id != state().activeListId }.id

    vm.startPairing(forListId = other)
    vm.onPairCodeChange(otherPhone("Ava"))
    val theirDevice = vm.pendingInvite!!.deviceId
    vm.pairAsNew()

    assertTrue(state().lists.single { it.id == other }.people.any { it.device == theirDevice })
  }

  // ── Answering a duplicate ─────────────────────────────────────────────────

  @Test
  fun `merging a duplicate leaves one entry with both quantities`() = runTest(dispatcher) {
    repo.load()
    // The two competing items on the list, as they are after a real handover.
    val (mine, theirs) = putBothOnList()

    vm.mergeConflict()

    val rice = state().activeList.liveItems.filter { it.name.value == "Rice" }
    assertEquals("one entry, not three", 1, rice.size)
    assertEquals(3, rice.single().qty.value)
    assertEquals(Origin.MERGED, rice.single().origin)

    // Tombstoned rather than dropped: the other phone still holds them and would hand them back.
    val items = state().activeList.items
    assertTrue(items.single { it.id == mine.id }.removed.value)
    assertTrue(items.single { it.id == theirs.id }.removed.value)
    assertNull(state().conflict)
  }

  @Test
  fun `keeping both leaves exactly the two that were there`() = runTest(dispatcher) {
    repo.load()
    putBothOnList()

    vm.keepBoth()

    assertEquals(2, state().activeList.liveItems.count { it.name.value == "Rice" })
    assertNull(state().conflict)
  }

  @Test
  fun `answering a card that arrived before the items does not lose them`() = runTest(dispatcher) {
    repo.load()
    // The seeded fixture: the two sides are held on the conflict and are not on the list.
    val conflict = state().conflict!!

    vm.keepBoth()

    val eggs = state().activeList.liveItems.filter { it.name.value == conflict.itemName }
    assertEquals(2, eggs.size)
  }

  /** Puts a competing pair on the open list and surfaces it, the way a handover leaves things. */
  private fun putBothOnList(): kotlin.Pair<GroceryItem, GroceryItem> {
    val stamp = Hlc(1_700_000_000_000, 0, state().localDevice)
    val theirDevice = state().activeList.people.first { it.device != state().localDevice }.device
    val mine =
      GroceryItem(
        id = ItemId("${state().localDevice.value}:900"),
        name = Field("Rice", stamp),
        qty = Field(1, stamp),
        checkedAt = stamp,
        removed = Field(false, stamp),
      )
    val theirs =
      mine.copy(
        id = ItemId("${theirDevice.value}:900"),
        qty = Field(2, Hlc(1_700_000_001_000, 0, theirDevice)),
      )
    repo.update { s, _ ->
      s.copy(
        conflict = Conflict(mine, theirs),
        lists = s.lists.map { if (it.id == s.activeListId) it.copy(items = it.items + mine + theirs) else it },
      )
    }
    return mine to theirs
  }

  // ── Pairing ───────────────────────────────────────────────────────────────

  private fun otherPhone(name: String): String =
    PairingCodec.encode(InMemoryIdentityStore().identity(), name)

  @Test
  fun `a pasted code is read before anything is committed`() = runTest(dispatcher) {
    repo.load()

    vm.onPairCodeChange(otherPhone("Ava"))

    assertEquals("Ava", vm.pendingInvite?.displayName)
    assertEquals("", vm.pairNote)
    assertTrue("nothing is trusted until it is acted on", state().peers.isEmpty())
  }

  @Test
  fun `nonsense is reported rather than silently ignored`() = runTest(dispatcher) {
    repo.load()

    vm.onPairCodeChange("have you got milk")

    assertNull(vm.pendingInvite)
    assertTrue(vm.pairNote.isNotEmpty())
  }

  @Test
  fun `this phone cannot pair with itself`() = runTest(dispatcher) {
    repo.load()

    vm.onPairCodeChange(vm.invite("Me"))

    assertNull("pairing with yourself would wait on your own receipts", vm.pendingInvite)
    assertTrue(vm.pairNote.isNotEmpty())
  }

  @Test
  fun `pairing trusts the key and clears the field`() = runTest(dispatcher) {
    repo.load()
    vm.onPairCodeChange(otherPhone("Ava"))
    val invited = vm.pendingInvite!!

    vm.pairAsNew()

    assertEquals(listOf(invited.deviceId), state().peers.map { it.deviceId })
    assertTrue(state().peers.single().publicKey.contentEquals(invited.publicKey))
    assertEquals("", vm.pairCode)
    assertNull(vm.pendingInvite)
  }

  @Test
  fun `a paired phone is not on a list until it is added`() = runTest(dispatcher) {
    repo.load()
    vm.onPairCodeChange(otherPhone("Ava"))
    vm.pairAsNew()
    val peer = state().peers.single().deviceId

    assertTrue("pairing alone shares nothing", state().activeList.people.none { it.device == peer })
    assertEquals(listOf(peer), vm.peersNotOnActiveList(state()).map { it.deviceId })

    vm.addPersonToActiveList(peer)

    assertTrue(state().activeList.people.any { it.device == peer })
    assertTrue("and then they are no longer offered", vm.peersNotOnActiveList(state()).isEmpty())
  }

  @Test
  fun `a replaced phone keeps the person and retires the old id`() = runTest(dispatcher) {
    repo.load()
    val old = state().activeList.people.first { it.device != state().localDevice }

    vm.onPairCodeChange(otherPhone(old.name))
    val newDevice = vm.pendingInvite!!.deviceId
    vm.pairAsReplacementFor(old.device)

    val person = state().activeList.people.single { it.wasEver(old.device) }
    assertEquals("the same person, a different phone", old.name, person.name)
    assertEquals(newDevice, person.device)
    assertTrue("the dead id is kept only to attribute what it wrote", old.device in person.formerDevices)
    assertFalse("and is no longer waited on", state().activeList.peersOf(state().localDevice).contains(old.device))
  }

  // ── Identity ──────────────────────────────────────────────────────────────

  @Test
  fun `deleting the identity comes back as a different phone, at first run`() = runTest(dispatcher) {
    repo.load()
    vm.onNameDraftChange("Hugo")
    vm.saveName()
    vm.onPairCodeChange(otherPhone("Ava"))
    vm.pairAsNew()
    val was = state().localDevice

    vm.confirmDeleteIdentity()
    testScheduler.advanceUntilIdle()

    assertNotEquals("a new key is a new device", was, state().localDevice)
    assertFalse("and it has never been told a name", state().named)
    assertTrue("nothing it was trusted by carries over", state().peers.isEmpty())
  }

  @Test
  fun `deleting is asked about before it happens`() = runTest(dispatcher) {
    repo.load()
    val was = state().localDevice

    vm.askDeleteIdentity()
    assertTrue(vm.confirmingIdentityDelete)
    vm.cancelDeleteIdentity()
    testScheduler.advanceUntilIdle()

    assertFalse(vm.confirmingIdentityDelete)
    assertEquals("backing out changes nothing", was, state().localDevice)
  }
}
