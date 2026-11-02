package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.Records
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.merge
import io.github.molleware.porygonlist.ui.adderOf
import io.github.molleware.porygonlist.ui.conflictSide
import io.github.molleware.porygonlist.ui.itemSubLabel
import io.github.molleware.porygonlist.ui.shopSubLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who added an item that a merge of two lists brought across.
 *
 * The copy has a new id, minted by the phone that merged, so the id no longer says who added it.
 * [GroceryItem.addedBy] does, and has to survive everything the item goes through: a restart, a
 * handover, a merge with a copy from a phone that does not know about it, and every line that names
 * an adder.
 */
class MergedAdderTest {

  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")
  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val sam = DeviceId("SAMMMMMMMMMMMMMM")

  private val then = Hlc(1_000_000, 0, ava)

  /** Rice from Ava's list, as a merge on Hugo's phone left it: Hugo's id, Ava's errand and stamps. */
  private val rice =
    GroceryItem(
      id = ItemId("${hugo.value}:101"),
      name = Field("Rice", then),
      qty = Field(2, then),
      checkedAt = then,
      removed = Field(false, then),
      position = Field("m", Hlc(2_000_000, 0, hugo)),
      addedBy = ava,
    )

  private val plain = rice.copy(id = ItemId("${hugo.value}:102"), addedBy = null)

  private val weekly =
    GroceryList(ListId("${hugo.value}:1"), "Weekly shop", ListAccent.ACCENT, listOf(rice, plain), listOf(Person(hugo, "Hugo", "H"), Person(sam, "Sam", "S")))

  private val hugosPhone =
    AppState(
      localDevice = hugo,
      idCounter = 102,
      clockHead = Hlc(2_000_000, 0, hugo),
      lists = listOf(weekly),
      activeListId = weekly.id,
      networks = emptyList(),
      peers = listOf(TrustedPeer(ava, byteArrayOf(1), "Ava", 0), TrustedPeer(sam, byteArrayOf(2), "Sam", 0)),
      conflicts = listOf(Conflict(rice, plain, listId = weekly.id)),
    )

  // ── On disk and on the wire ───────────────────────────────────────────────

  @Test
  fun `the adder is one column after the recipes, empty for the id's own`() {
    val fields = Records.itemFields(rice)

    assertEquals("seventeen as 0.1.0 wrote, three for the recipes, then the adder", ITEM_COLUMNS_010 - 2 + 4, fields.size)
    assertEquals(ava.value, fields.last())
    assertEquals("", Records.itemFields(plain).last())
    assertEquals("nothing before it moves", Records.itemFields(rice.copy(addedBy = null)).dropLast(1), fields.dropLast(1))
  }

  @Test
  fun `the adder survives a restart, on an item and on a card`() {
    val restored = StateCodec.decode(StateCodec.encode(hugosPhone))!!

    assertEquals(listOf(rice, plain), restored.lists.single().items)
    assertEquals(ava, restored.conflicts.single().yours.addedBy)
    assertNull(restored.conflicts.single().theirs.addedBy)
  }

  @Test
  fun `the adder travels in a payload`() {
    val sent = SyncCodec.decode(SyncCodec.encode(SyncPayload(hugo, Hlc(3_000_000, 0, hugo), listOf(weekly))))!!

    assertEquals(listOf(ava, null), sent.lists.single().items.map { it.addedBy })
  }

  @Test
  fun `what 0_1_0 wrote and sends reads as no adder kept, and otherwise as it was`() {
    val save = as010(StateCodec.encode(hugosPhone))
    assertTrue(save.lines().filter { it.startsWith("item|") || it.startsWith("conflictitem|") }.all { it.split('|').size == ITEM_COLUMNS_010 })
    assertEquals(listOf(null, null), StateCodec.decode(save)!!.lists.single().items.map { it.addedBy })

    val payload = payloadAs010(SyncCodec.encode(SyncPayload(hugo, Hlc(3_000_000, 0, hugo), listOf(weekly))))
    val read = SyncCodec.decode(payload)!!.lists.single().items.single { it.id == rice.id }
    assertEquals(rice.copy(addedBy = null, recipes = Field(emptyList(), Hlc(0, 0, hugo))), read)
  }

  @Test
  fun `a copy without the adder never wipes it off, whichever side it is on`() {
    // Sam's 0.1.0 phone sends the item back with no adder, and a newer edit to it.
    val samsCopy = rice.copy(addedBy = null, qty = rice.qty.set(3, Hlc(4_000_000, 0, sam)))

    assertEquals(ava, merge(rice, samsCopy).item.addedBy)
    assertEquals(ava, merge(samsCopy, rice).item.addedBy)
    assertEquals(ava, merge(weekly, weekly.copy(items = listOf(samsCopy, plain))).list.items.single { it.id == rice.id }.addedBy)
  }

  // ── Every line that names an adder ────────────────────────────────────────

  @Test
  fun `the lines name the kept adder, by pairing when the list does not have them`() {
    val now = then.wall + 30_000

    assertEquals("Ava added this", shopSubLabel(rice, weekly, hugo, hugosPhone.peers))
    assertEquals("Ava, just now", itemSubLabel(rice, weekly, hugo, now, paired = hugosPhone.peers))
    assertTrue(conflictSide(rice, weekly, hugo, now, paired = hugosPhone.peers).startsWith("Ava added it "))
  }

  @Test
  fun `the shop marks it with the kept adder`() {
    val mark = hugosPhone.adderOf(rice, weekly)!!

    assertEquals(ava, mark.device)
    assertEquals("A", mark.initial)
    assertEquals("Ava", mark.name)
  }

  @Test
  fun `an adder this phone cannot name gets no mark`() {
    val stranger = rice.copy(addedBy = DeviceId("NOBODYYYYYYYYYYY"))

    assertNull(hugosPhone.adderOf(stranger, weekly))
    assertEquals("someone added this", shopSubLabel(stranger, weekly, hugo, hugosPhone.peers))
  }
}
