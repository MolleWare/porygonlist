package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.receive
import io.github.molleware.porygonlist.data.sync.payloadFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving a list somebody else is on.
 *
 * Deleting a shared list cannot simply drop it. The other phone's copy still names the leaver, and
 * [io.github.molleware.porygonlist.data.sync.merge] unions the people — so without a record of the
 * decision, the next exchange would put them straight back and the list would reappear. The owner
 * would be entitled to call that the app ignoring them.
 *
 * So leaving behaves exactly as removing an item does: a stamped tombstone, kept and kept being
 * sent until every remaining peer has confirmed hearing it, and only then collected.
 */
class LeavingAListTest {

  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")

  private val listId = ListId("${ava.value}:1")

  private fun both() = listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H"))

  private fun stateOf(people: List<Person>, delivered: DeliveryLog = DeliveryLog()) =
    AppState(
      localDevice = ava,
      idCounter = 10,
      clockHead = Hlc(1_000, 0, ava),
      deliveredTo = delivered,
      lists = listOf(GroceryList(listId, "Weekly shop", ListAccent.ACCENT, emptyList(), people)),
      activeListId = listId,
      online = true,
      networks = emptyList(),
      peers = listOf(TrustedPeer(hugo, byteArrayOf(1, 2, 3), "Hugo", 0)),
      conflict = null,
    )

  private fun leave(state: AppState, at: Hlc = Hlc(2_000, 0, ava)) =
    state.copy(lists = state.lists.map { it.withPersonLeft(ava, at) })

  @Test
  fun `a left list is out of sight but still held`() {
    val after = leave(stateOf(both()))

    assertTrue("the owner should not still see it", after.visibleLists.isEmpty())
    // Still held, because it is the only thing that can carry the news to the other phone.
    assertEquals(1, after.lists.size)
  }

  @Test
  fun `a left list is still sent to the people still on it`() {
    val after = leave(stateOf(both()))

    val payload = after.payloadFor(hugo, HybridClock(ava))

    assertEquals("Hugo has to be told, so the list still goes to him", 1, payload?.lists?.size)
  }

  @Test
  fun `leaving survives a peer whose copy still lists you`() {
    val after = leave(stateOf(both()))

    // Hugo has not heard yet, so his copy still has Ava on it. A plain union would re-add her.
    val stale =
      SyncPayload(
        from = hugo,
        at = Hlc(1_500, 0, hugo),
        lists = listOf(GroceryList(listId, "Weekly shop", ListAccent.ACCENT, emptyList(), both())),
      )

    val result = after.receive(stale, HybridClock(ava))

    assertTrue(result is SyncResult.Merged)
    val merged = (result as SyncResult.Merged).state
    assertTrue("leaving must not be undone by a stale copy", merged.visibleLists.isEmpty())
    assertFalse("Ava is still off the list", merged.lists.single().personFor(ava)!!.present)
  }

  @Test
  fun `the list goes once everyone still on it has heard`() {
    val at = Hlc(2_000, 0, ava)
    val after = leave(stateOf(both()), at)

    // Hugo confirms receiving everything up to the moment Ava left.
    val confirmed = after.copy(deliveredTo = after.deliveredTo.record(hugo, at)).pruneDeliveredTombstones()

    assertTrue("nothing left to tell anyone, so nothing left to keep", confirmed.lists.isEmpty())
  }

  @Test
  fun `the list is kept while anyone still on it has not heard`() {
    val after = leave(stateOf(both()))

    // No receipt from Hugo. Age is not evidence of delivery, so the tombstone stays.
    assertEquals(1, after.pruneDeliveredTombstones().lists.size)
  }

  @Test
  fun `leaving a list nobody else is on keeps no tombstone`() {
    val alone = stateOf(listOf(Person(ava, "Ava", "A")))

    val after = leave(alone).pruneDeliveredTombstones()

    // Nobody to tell, so the tombstone is collected on the spot and the owner sees nothing.
    assertTrue(after.visibleLists.isEmpty())
    assertTrue("nothing is waiting to be delivered", after.lists.single().people.isEmpty())

    // The husk is left deliberately rather than deleted: pruneDeliveredTombstones runs on every
    // load, and refusing to drop a list with nobody on it is what stops an odd state costing
    // somebody their groceries. In the app this path is never reached — deleteList removes a list
    // nobody else is on outright, which PorygonViewModelTest covers.
  }
}
