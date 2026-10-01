package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What one finished exchange does to this phone's state.
 *
 * The network code decides *who* a connection is; this decides what to believe about it. Both
 * halves of that are where a confused or hostile peer would try something, so both get tested.
 */
class AfterExchangeTest {

  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")
  private val sam = DeviceId("SAMMMMMMMMMMMMMM")

  private val listId = ListId("${ava.value}:1")
  private val everyone = listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H"), Person(sam, "Sam", "S"))

  private fun item(id: String, name: String, at: Hlc) =
    GroceryItem(
      id = ItemId(id),
      name = Field(name, at),
      qty = Field(1, at),
      checked = false,
      checkedAt = at,
      removed = Field(false, at),
    )

  private fun list(vararg items: GroceryItem) =
    GroceryList(listId, "Weekly shop", ListAccent.ACCENT, items.toList(), everyone)

  private fun ours() =
    AppState(
      localDevice = ava,
      idCounter = 10,
      clockHead = Hlc(1_000, 0, ava),
      lists = listOf(list(item("${ava.value}:1", "Milk", Hlc(1_000, 0, ava)))),
      activeListId = listId,
      online = true,
      networks = emptyList(),
      // Both of them are paired, which is exactly what makes impersonation worth testing.
      peers = listOf(TrustedPeer(hugo, byteArrayOf(1), "Hugo", 0), TrustedPeer(sam, byteArrayOf(2), "Sam", 0)),
      conflict = null,
    )

  private fun clock() = HybridClock(ava, now = { 5_000 })

  private fun fromHugo(vararg names: String, at: Hlc = Hlc(2_000, 0, hugo)) =
    SyncPayload(
      from = hugo,
      at = at,
      lists = listOf(list(*names.mapIndexed { i, n -> item("${hugo.value}:${i + 1}", n, at) }.toTypedArray())),
    )

  @Test
  fun `a payload from the phone that called is applied`() {
    val after = ours().afterExchange(hugo, fromHugo("Bread"), ackOfMine = null, sent = null, clock = clock())

    assertEquals(setOf("Milk", "Bread"), after.lists.single().liveItems.map { it.name.value }.toSet())
  }

  @Test
  fun `a payload speaking for a different paired phone is ignored`() {
    // The connection authenticated as Hugo, but the payload says it is from Sam. Sam is paired, so
    // receive alone would accept it — this is the check that stops one peer speaking for another.
    val forged = fromHugo("Bread").copy(from = sam)

    val after = ours().afterExchange(hugo, forged, ackOfMine = null, sent = null, clock = clock())

    assertEquals(listOf("Milk"), after.lists.single().liveItems.map { it.name.value })
  }

  @Test
  fun `an ack that echoes what was sent is recorded`() {
    val sent = SyncPayload(from = ava, at = Hlc(1_000, 0, ava), lists = ours().lists)

    val after = ours().afterExchange(hugo, theirs = null, ackOfMine = sent.at, sent = sent, clock = clock())

    assertEquals(sent.at, after.deliveredTo.confirmedBy(hugo))
  }

  @Test
  fun `an ack for something that was not sent is not believed`() {
    val sent = SyncPayload(from = ava, at = Hlc(1_000, 0, ava), lists = ours().lists)

    // A stamp from the future would, if believed, let this phone collect tombstones nobody has seen.
    val after =
      ours().afterExchange(hugo, theirs = null, ackOfMine = Hlc(9_999_999, 0, ava), sent = sent, clock = clock())

    assertNull(after.deliveredTo.confirmedBy(hugo))
  }

  @Test
  fun `an ack with nothing sent is not believed`() {
    val after = ours().afterExchange(hugo, theirs = null, ackOfMine = Hlc(1_000, 0, ava), sent = null, clock = clock())

    assertNull(after.deliveredTo.confirmedBy(hugo))
  }

  @Test
  fun `nothing for us leaves the lists alone`() {
    val empty = SyncPayload(from = hugo, at = Hlc(2_000, 0, hugo), lists = emptyList())

    val after = ours().afterExchange(hugo, empty, ackOfMine = null, sent = null, clock = clock())

    assertEquals(ours().lists, after.lists)
  }
}
