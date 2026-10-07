package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.IdFactory
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.payloadFor
import io.github.molleware.porygonlist.data.sync.receive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking somebody else off a list, and unpairing.
 *
 * Found on hardware: removing someone only dropped them on this phone. Their phone's copy still
 * named them, so the next exchange put them straight back, and their phone never learned it had been
 * taken off. Now the removal is a stamped tombstone that reaches everyone, the removed person
 * included, and their phone keeps what it had as a private list — which is what the confirmation
 * ("They keep the copy they have") always promised.
 */
class RemovingSomeoneTest {

  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")
  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val sam = DeviceId("SAMMMMMMMMMMMMMM")
  private val listId = ListId("${hugo.value}:1")

  private fun at(wall: Long, by: DeviceId = hugo) = Hlc(wall, 0, by)

  private fun item(n: Int) =
    at(1_000).let { s -> GroceryItem(ItemId("${hugo.value}:${10 + n}"), Field("item $n", s), Field(1, s), checkedAt = s, removed = Field(false, s)) }

  // Value classes cannot be varargs, hence the optional third person.
  private fun shared(first: DeviceId, second: DeviceId, third: DeviceId? = null) =
    GroceryList(
      listId,
      "Weekly shop",
      ListAccent.ACCENT,
      listOf(item(1), item(2)),
      listOfNotNull(first, second, third).map { Person(it, it.value.take(3).lowercase().replaceFirstChar(Char::uppercase), it.value.take(1)) },
    )

  private fun phone(me: DeviceId, list: GroceryList, pairedWith: DeviceId, alsoPairedWith: DeviceId? = null) =
    AppState(
      localDevice = me,
      idCounter = 100,
      clockHead = at(1_500, me),
      lists = listOf(list),
      activeListId = list.id,
      online = true,
      networks = emptyList(),
      peers = listOfNotNull(pairedWith, alsoPairedWith).map { TrustedPeer(it, byteArrayOf(1), it.value, 0) },
      conflict = null,
    )

  // ── On the phone that removes ─────────────────────────────────────────────

  @Test
  fun `a removal survives the removed person's own copy`() {
    val hugos = phone(hugo, shared(hugo, ava), ava).removePersonFrom(listId, ava, at(2_000))

    // Ava has not heard, so her copy still has her on it.
    val avasCopy = SyncPayload(ava, at(1_800, ava), listOf(shared(hugo, ava)))
    val merged = (hugos.receive(avasCopy, HybridClock(hugo)) as SyncResult.Merged).state

    assertFalse("she stays off the list", merged.lists.single().personFor(ava)!!.present)
  }

  @Test
  fun `the removed person is still sent the list, so they find out`() {
    val hugos = phone(hugo, shared(hugo, ava), ava).removePersonFrom(listId, ava, at(2_000))

    assertEquals(1, hugos.payloadFor(ava, HybridClock(hugo))?.lists?.size)
  }

  @Test
  fun `the removal is kept until the removed person has confirmed it too`() {
    val removed = phone(hugo, shared(hugo, ava, sam), ava, sam).removePersonFrom(listId, ava, at(2_000))

    // Sam confirming is not enough: Ava's phone still believes it is on the list.
    val samOnly = removed.copy(deliveredTo = DeliveryLog().record(sam, at(3_000))).pruneDeliveredTombstones()
    assertTrue(samOnly.lists.single().personFor(ava) != null)

    val both = samOnly.copy(deliveredTo = samOnly.deliveredTo.record(ava, at(3_000))).pruneDeliveredTombstones()
    assertNull("everyone has heard, so it can be forgotten", both.lists.single().personFor(ava))
  }

  @Test
  fun `someone who left is not waited for — they already know`() {
    val left = phone(hugo, shared(hugo, ava, sam), ava, sam).let { s ->
      s.copy(lists = s.lists.map { it.withPersonLeft(ava, at(2_000, ava)) })
    }
    val samConfirmed = left.copy(deliveredTo = DeliveryLog().record(sam, at(3_000))).pruneDeliveredTombstones()

    assertNull(samConfirmed.lists.single().personFor(ava))
  }

  // ── On the phone that was removed ─────────────────────────────────────────

  private fun removalReaching(avas: AppState): AppState {
    val hugosList = shared(hugo, ava).withPersonLeft(ava, at(2_000))
    val result = avas.receive(SyncPayload(hugo, at(2_100), listOf(hugosList)), HybridClock(ava), IdFactory(ava, start = 100))
    return (result as SyncResult.Merged).state
  }

  @Test
  fun `the removed phone keeps the list as its own private copy`() {
    val after = removalReaching(phone(ava, shared(hugo, ava), hugo))

    val copy = after.visibleLists.single()
    assertNotEquals("a list of its own, not the shared one", listId, copy.id)
    assertEquals("Weekly shop", copy.name)
    assertEquals(listOf("item 1", "item 2"), copy.liveItems.map { it.name.value })
    assertEquals(listOf(ava), copy.people.map { it.device })
    assertTrue("new item ids, so the two lists can never be merged into each other", copy.items.none { it.id.value.startsWith(hugo.value) })
  }

  @Test
  fun `the copy opens in place of the list that was taken away`() {
    val after = removalReaching(phone(ava, shared(hugo, ava), hugo))

    assertEquals(after.visibleLists.single().id, after.activeListId)
  }

  @Test
  fun `the shared list stays hidden until the removal has gone round`() {
    val after = removalReaching(phone(ava, shared(hugo, ava), hugo))

    val original = after.lists.single { it.id == listId }
    assertFalse(original.personFor(ava)!!.present)
  }

  @Test
  fun `leaving keeps no copy — that was this phone's own choice`() {
    val avas = phone(ava, shared(hugo, ava), hugo).let { s -> s.copy(lists = s.lists.map { it.withPersonLeft(ava, at(2_000, ava)) }) }
    val echo = SyncPayload(hugo, at(2_100), listOf(shared(hugo, ava).withPersonLeft(ava, at(2_000, ava))))

    val after = (avas.receive(echo, HybridClock(ava), IdFactory(ava, start = 100)) as SyncResult.Merged).state

    assertTrue(after.visibleLists.isEmpty())
  }

  // ── Unpairing ─────────────────────────────────────────────────────────────

  @Test
  fun `an unpaired phone is not handed back by someone else on the list`() {
    val hugos = phone(hugo, shared(hugo, ava, sam), ava, sam).retireDevice(ava, at(2_000))

    // Sam has not heard yet and sends his copy, which still names Ava.
    val samsCopy = SyncPayload(sam, at(1_900, sam), listOf(shared(hugo, ava, sam)))
    val merged = (hugos.receive(samsCopy, HybridClock(hugo)) as SyncResult.Merged).state

    assertFalse(merged.lists.single().personFor(ava)?.present ?: false)
    assertTrue(merged.peers.none { it.deviceId == ava })
  }

  @Test
  fun `an unpaired phone is never waited for`() {
    val hugos = phone(hugo, shared(hugo, ava, sam), ava, sam).retireDevice(ava, at(2_000))

    val samConfirmed = hugos.copy(deliveredTo = DeliveryLog().record(sam, at(3_000))).pruneDeliveredTombstones()

    assertNull("nobody can ever ask Ava again, so only Sam is waited for", samConfirmed.lists.single().personFor(ava))
  }
}
