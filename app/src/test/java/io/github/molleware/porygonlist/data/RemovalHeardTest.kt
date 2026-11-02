package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.confirmDelivery
import io.github.molleware.porygonlist.data.sync.receive
import io.github.molleware.porygonlist.ui.listCardMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A removal Hugo hears of from Sam is kept until Ava has confirmed something Hugo sent after he
 * heard — not merely something sent after Sam made it.
 *
 * Hugo's receipts are his own clock when he put a payload together; Sam's removal carries Sam's
 * stamp. Compared directly, a removal made before Ava's last receipt but heard of after it was
 * collected the moment it arrived, and Ava, who had confirmed a payload without it, never heard.
 */
class RemovalHeardTest {

  private val hugo = DeviceId("HUGOAAAAAAAAAAAA")
  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val sam = DeviceId("SAMAAAAAAAAAAAAA")
  private val weekly = ListId("${hugo.value}:1")

  private fun at(wall: Long, device: DeviceId = hugo) = Hlc(wall, 0, device)

  private val created = at(1_000)
  private val milk =
    GroceryItem(ItemId("${hugo.value}:2"), Field("Milk", created), Field(1, created), checkedAt = created, removed = Field(false, created))
  private val three = listOf(Person(hugo, "Hugo", "H"), Person(ava, "Ava", "A"), Person(sam, "Sam", "S"))

  /** Hugo at 5 000 on his clock, with Ava and Sam both confirmed up to there. */
  private fun hugos(items: List<GroceryItem> = listOf(milk), people: List<Person> = three) =
    AppState(
      localDevice = hugo,
      idCounter = 100,
      clockHead = at(5_000),
      lists = listOf(GroceryList(weekly, "Weekly shop", ListAccent.ACCENT, items, people)),
      activeListId = weekly,
      networks = emptyList(),
      peers = listOf(ava, sam).map { TrustedPeer(it, byteArrayOf(1), it.value, 0) },
      deliveredTo = DeliveryLog().record(ava, at(5_000)).record(sam, at(5_000)),
    )

  /** Sam's list as Sam sends it, at 6 000 on Sam's clock. */
  private fun fromSam(items: List<GroceryItem> = listOf(milk), people: List<Person> = three) =
    SyncPayload(sam, at(6_000, sam), listOf(GroceryList(weekly, "Weekly shop", ListAccent.ACCENT, items, people)))

  private fun AppState.hearing(payload: SyncPayload): AppState {
    val wire = SyncCodec.decode(SyncCodec.encode(payload))!!
    return (receive(wire, HybridClock(localDevice, now = { 7_000 }, start = clockHead)) as SyncResult.Merged).state
  }

  // Sam took it off at 3 000 by his clock: before Ava's receipt, though Hugo hears only now.
  private val samsRemoval = milk.copy(removed = milk.removed.set(true, at(3_000, sam)))

  @Test
  fun `a removal heard of from a third phone waits for the other phone's next receipt`() {
    val heard = hugos().hearing(fromSam(listOf(samsRemoval)))

    val tombstone = heard.lists.single().items.singleOrNull { it.id == milk.id }
    assertNotNull("kept: Ava confirmed nothing that had it", tombstone)
    assertTrue(tombstone!!.removed.value)
    assertEquals("marked with Hugo's clock as it stood after hearing", heard.clockHead, tombstone.removalHeardAt)

    // Ava confirms a payload put together after Hugo heard, which carried it. Then it goes.
    val confirmed = heard.confirmDelivery(ava, heard.clockHead).confirmDelivery(sam, heard.clockHead)
    assertTrue(confirmed.lists.single().items.none { it.id == milk.id })
  }

  @Test
  fun `a removal made here is still collected by its own stamp`() {
    val gone = milk.copy(removed = milk.removed.set(true, at(4_000)))
    val state = hugos(listOf(gone))

    assertNull("its stamp is this phone's own clock already", gone.removalHeardAt)
    assertTrue(state.pruneDeliveredTombstones().lists.single().items.isEmpty())
  }

  @Test
  fun `someone leaving, heard of from them, waits the same way`() {
    // Sam leaves at 3 000 on his clock. Collected at once, Ava would go on naming Sam and hand him
    // straight back to Hugo's list.
    val samLeft = three.map { if (it.device == sam) it.copy(removed = it.removed.set(true, at(3_000, sam))) else it }

    val heard = hugos().hearing(fromSam(people = samLeft))

    val record = heard.lists.single().personFor(sam)
    assertNotNull("kept until Ava has heard", record)
    assertEquals(heard.clockHead, record!!.removalHeardAt)
    assertTrue(heard.confirmDelivery(ava, heard.clockHead).lists.single().personFor(sam) == null)
  }

  @Test
  fun `someone removed by another, heard of here, waits for them as well as the rest`() {
    // Sam takes Ava off at 3 000. She has to hear it herself (mustHear), and so does nobody else
    // here; either way not on a receipt from before Hugo knew.
    val avaOff = three.map { if (it.device == ava) it.copy(removed = it.removed.set(true, at(3_000, sam))) else it }

    val heard = hugos().hearing(fromSam(people = avaOff))

    assertNotNull(heard.lists.single().personFor(ava))
    assertNotNull("still, with Sam confirmed", heard.confirmDelivery(sam, heard.clockHead).lists.single().personFor(ava))
    assertNull(heard.confirmDelivery(sam, heard.clockHead).confirmDelivery(ava, heard.clockHead).lists.single().personFor(ava))
  }

  @Test
  fun `the mark moves no receipt and changes no label`() {
    val before = hugos()
    val heard = before.hearing(fromSam(listOf(samsRemoval)))
    val unmarked = heard.copy(lists = heard.lists.map { l -> l.copy(items = l.items.map { it.copy(removalHeardAt = null) }) })

    assertEquals(before.deliveredTo, heard.deliveredTo)
    val list = heard.lists.single()
    assertEquals(listCardMeta(unmarked.lists.single(), hugo, unmarked.deliveredTo), listCardMeta(list, hugo, heard.deliveredTo))
    assertEquals("last handover still reads Ava's own receipt", at(5_000), heard.deliveredTo.confirmedBy(ava))
  }

  @Test
  fun `the mark is kept in the save and never sent`() {
    val heard = hugos().hearing(fromSam(listOf(samsRemoval)))

    val reread = StateCodec.decode(StateCodec.encode(heard))!!
    assertEquals(heard.lists.single().items, reread.lists.single().items)

    val wire = SyncCodec.encode(SyncPayload(hugo, at(9_000), heard.lists))
    // `item|list|` and the item's own 21 columns, and nothing after them.
    assertTrue(wire.lines().filter { it.startsWith("item|") }.all { it.split('|').size == 23 })
    assertFalse(wire.contains(heard.clockHead.encode()))
    assertNull(SyncCodec.decode(wire)!!.lists.single().items.single().removalHeardAt)
  }

  @Test
  fun `a removal heard of before saves kept the mark reads as heard at load`() {
    // 0.1.0's save, and version 10's, have no mark. When it arrived cannot be known, so it waits for
    // a receipt from after this load; a removal made here does not.
    val ownRemoval =
      GroceryItem(ItemId("${hugo.value}:3"), Field("Eggs", created), Field(1, created), checkedAt = created, removed = Field(true, at(4_000)))
    val samLeft = three.map { if (it.device == sam) it.copy(removed = it.removed.set(true, at(3_000, sam))) else it }
    val state = hugos(listOf(samsRemoval, ownRemoval), samLeft).copy(clockHead = at(8_000))

    for (old in listOf(as010(StateCodec.encode(state)), asVersion10(as010(StateCodec.encode(state))))) {
      val read = StateCodec.decode(old)!!
      val items = read.lists.single().items.associateBy { it.id }

      assertEquals(read.clockHead, items.getValue(samsRemoval.id).removalHeardAt)
      assertNull(items.getValue(ownRemoval.id).removalHeardAt)
      assertEquals(read.clockHead, read.lists.single().personFor(sam)!!.removalHeardAt)
      // Both receipts are past Sam's stamps, as before: only the removal made here can go yet.
      val settled = read.pruneDeliveredTombstones().lists.single()
      assertEquals(listOf(samsRemoval.id), settled.items.map { it.id })
      assertNotNull("and Sam's leaving stays until Ava has heard it", settled.personFor(sam))
    }
  }
}
