package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.receive
import io.github.molleware.porygonlist.ui.listCardMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A list someone shares says who it is from, until it is first opened.
 *
 * It used to arrive without a word, and one named like a list you already had looked like a
 * mysterious second copy of it.
 */
class SharedListArrivalTest {

  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")

  private val mine = GroceryList(ListId("${hugo.value}:1"), "groceries", ListAccent.ACCENT, emptyList(), listOf(Person(hugo, "Hugo", "H")))
  private val avas =
    GroceryList(ListId("${ava.value}:1"), "groceries", ListAccent.ACCENT_2, emptyList(), listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H")))

  private val hugosPhone =
    AppState(
      localDevice = hugo,
      idCounter = 10,
      clockHead = Hlc(1_000, 0, hugo),
      lists = listOf(mine),
      activeListId = mine.id,
      online = true,
      networks = emptyList(),
      peers = listOf(TrustedPeer(ava, byteArrayOf(1), "Ava", 0)),
      conflict = null,
    )

  private fun arrive(): AppState =
    (hugosPhone.receive(SyncPayload(ava, Hlc(2_000, 0, ava), listOf(avas)), HybridClock(hugo)) as SyncResult.Merged).state

  @Test
  fun `a shared list is marked with who shared it`() {
    val shared = arrive().lists.single { it.id == avas.id }

    assertEquals(ava, shared.arrivedFrom)
    assertEquals("new, from Ava", listCardMeta(shared, online = true, localDevice = hugo, delivered = DeliveryLog()))
  }

  @Test
  fun `your own list of the same name is left alone`() {
    val after = arrive()

    assertEquals(2, after.lists.size)
    assertNull(after.lists.single { it.id == mine.id }.arrivedFrom)
  }

  @Test
  fun `the note survives a restart but never leaves the phone`() {
    val after = arrive()

    assertEquals(ava, StateCodec.decode(StateCodec.encode(after))!!.lists.single { it.id == avas.id }.arrivedFrom)
    val sent = SyncCodec.decode(SyncCodec.encode(SyncPayload(hugo, Hlc(3_000, 0, hugo), after.lists.filter { it.id == avas.id })))!!
    assertNull(sent.lists.single().arrivedFrom)
  }

  @Test
  fun `a later exchange keeps the note until the list is opened`() {
    val after = arrive()
    val again = (after.receive(SyncPayload(ava, Hlc(4_000, 0, ava), listOf(avas)), HybridClock(hugo)) as SyncResult.Merged).state

    assertEquals(ava, again.lists.single { it.id == avas.id }.arrivedFrom)
  }
}
