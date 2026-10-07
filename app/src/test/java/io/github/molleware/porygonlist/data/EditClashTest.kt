package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.receive
import io.github.molleware.porygonlist.ui.editClashText
import io.github.molleware.porygonlist.ui.listCardMeta
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Keep which?" — one item changed on two phones before either had seen the other's change.
 *
 * The merge always found these and threw the finding away, settling them silently by clock. Now the
 * phone that notices asks, with a sentence saying what each person did, and the answer travels as a
 * fresh edit that closes the question on the other phone too.
 */
class EditClashTest {

  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")
  private val listId = ListId("${ava.value}:1")
  private val itemId = ItemId("${ava.value}:2")

  private val created = Hlc(1_000, 0, ava)
  private val base =
    GroceryItem(itemId, Field("Oat milk", created), Field(1, created), checkedAt = created, removed = Field(false, created))

  private fun listWith(item: GroceryItem) =
    GroceryList(listId, "Weekly shop", ListAccent.ACCENT, listOf(item), listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H")))

  // Both start from the same item, then change its quantity without having talked.
  private val avasVersion = base.copy(qty = base.qty.set(2, Hlc(2_000, 0, ava)))
  private val hugosVersion = base.copy(qty = base.qty.set(3, Hlc(2_100, 0, hugo)))

  private fun phone(me: DeviceId, item: GroceryItem, other: DeviceId) =
    AppState(
      localDevice = me,
      idCounter = 10,
      clockHead = Hlc(2_500, 0, me),
      lists = listOf(listWith(item)),
      activeListId = listId,
      online = true,
      networks = emptyList(),
      peers = listOf(TrustedPeer(other, byteArrayOf(1), other.value, 0)),
      conflict = null,
    )

  private fun AppState.receiving(from: DeviceId, item: GroceryItem, at: Long) =
    (receive(SyncPayload(from, Hlc(at, 0, from), listOf(listWith(item))), HybridClock(localDevice)) as SyncResult.Merged).state

  @Test
  fun `two changes made blind to each other are asked about`() {
    val hugos = phone(hugo, hugosVersion, ava).receiving(ava, avasVersion, 2_200)

    val card = hugos.openConflict!!
    assertEquals(ConflictKind.EDITED, card.kind)
    assertEquals(listId, card.listId)
    assertEquals(3, card.yours.qty.value)
    assertEquals(2, card.theirs.qty.value)
  }

  @Test
  fun `the card says what each person did`() {
    val hugos = phone(hugo, hugosVersion, ava).receiving(ava, avasVersion, 2_200)
    val text = editClashText(hugos.openConflict!!, hugos.lists.single(), hugo)

    assertEquals("Changed on two phones at once", text.title)
    assertEquals(
      "You made it “Oat milk ×3” and Ava made it “Oat milk ×2”, each before seeing the other's change. Which should stay?",
      text.body,
    )
    assertEquals("Keep yours", text.keepYours)
    assertEquals("Keep Ava's", text.keepTheirs)
  }

  @Test
  fun `a removal on one side is said as taking it off the list`() {
    val avaRemoved = base.copy(removed = base.removed.set(true, Hlc(2_000, 0, ava)))
    val hugoRenamed = base.copy(name = base.name.set("Oat drink", Hlc(2_100, 0, hugo)))
    val hugos = phone(hugo, hugoRenamed, ava).receiving(ava, avaRemoved, 2_200)

    val text = editClashText(hugos.openConflict!!, hugos.lists.single(), hugo)
    assertEquals(
      "You made it “Oat drink” and Ava took it off the list, each before seeing the other's change. Which should stay?",
      text.body,
    )
  }

  @Test
  fun `keeping the edit over a removal puts the item back, even once the removal was collected`() {
    val avaRemoved = base.copy(removed = base.removed.set(true, Hlc(2_000, 0, ava)))
    val hugoRenamed = base.copy(name = base.name.set("Oat drink", Hlc(2_100, 0, hugo)))
    val hugos = phone(hugo, hugoRenamed, ava).receiving(ava, avaRemoved, 2_200)
    // Everyone confirmed the removal while the card waited, so its tombstone is gone.
    val collected = hugos.copy(lists = hugos.lists.map { it.copy(items = emptyList()) })

    val kept = collected.withVersionKept(yours = true, at = Hlc(3_000, 0, hugo))

    val item = kept.lists.single().liveItems.single()
    assertEquals("Oat drink", item.name.value)
  }

  @Test
  fun `a duplicate card is tied to its list too`() {
    val avasRice =
      GroceryItem(ItemId("${ava.value}:7"), Field("Rice", Hlc(2_000, 0, ava)), Field(1, Hlc(2_000, 0, ava)), checkedAt = Hlc(2_000, 0, ava), removed = Field(false, Hlc(2_000, 0, ava)))
    val hugosRice =
      GroceryItem(ItemId("${hugo.value}:7"), Field("Rice", Hlc(2_100, 0, hugo)), Field(1, Hlc(2_100, 0, hugo)), checkedAt = Hlc(2_100, 0, hugo), removed = Field(false, Hlc(2_100, 0, hugo)))
    val hugos = phone(hugo, hugosRice, ava).receiving(ava, avasRice, 2_200)

    assertEquals(ConflictKind.DUPLICATE, hugos.openConflict!!.kind)
    assertEquals(listId, hugos.openConflict!!.listId)
  }

  @Test
  fun `a change made after seeing the other is not a clash`() {
    // Hugo had Ava's 2 before he set 3: a sequence, not a clash.
    val informed = avasVersion.copy(qty = avasVersion.qty.set(3, Hlc(2_100, 0, hugo)))
    val avas = phone(ava, avasVersion, hugo).receiving(hugo, informed, 2_200)

    assertNull(avas.openConflict)
  }

  @Test
  fun `two people making the same change is not a clash`() {
    val alsoTwo = base.copy(qty = base.qty.set(2, Hlc(2_100, 0, hugo)))
    val avas = phone(ava, avasVersion, hugo).receiving(hugo, alsoTwo, 2_200)

    assertNull(avas.openConflict)
  }

  @Test
  fun `keeping a version writes it again, newer than both`() {
    val hugos = phone(hugo, hugosVersion, ava).receiving(ava, avasVersion, 2_200)

    val kept = hugos.withVersionKept(yours = false, at = Hlc(3_000, 0, hugo))

    val item = kept.lists.single().items.single()
    assertEquals(2, item.qty.value)
    assertEquals(Hlc(3_000, 0, hugo), item.qty.at)
    assertNull(kept.conflict)
  }

  @Test
  fun `the answer closes the same question on the other phone`() {
    // Ava's phone noticed the clash too.
    val avas = phone(ava, avasVersion, hugo).receiving(hugo, hugosVersion, 2_200)
    assertNotNull(avas.openConflict)

    // Hugo answers on his phone, and his answer arrives at Ava's.
    val hugos = phone(hugo, hugosVersion, ava).receiving(ava, avasVersion, 2_200).withVersionKept(yours = false, at = Hlc(3_000, 0, hugo))
    val after = avas.receiving(hugo, hugos.lists.single().items.single(), 3_100)

    assertNull("answered elsewhere, so not asked again", after.openConflict)
    assertEquals(2, after.lists.single().items.single().qty.value)
  }

  @Test
  fun `the list card says a change is waiting on it`() {
    val hugos = phone(hugo, hugosVersion, ava).receiving(ava, avasVersion, 2_200)

    assertEquals(
      "a change needs you",
      listCardMeta(hugos.lists.single(), online = true, localDevice = hugo, delivered = DeliveryLog(), needsAnswer = true),
    )
  }

  @Test
  fun `the question survives a restart, still on its list`() {
    val hugos = phone(hugo, hugosVersion, ava).receiving(ava, avasVersion, 2_200)

    val back = StateCodec.decode(StateCodec.encode(hugos))!!.conflict!!
    assertEquals(ConflictKind.EDITED, back.kind)
    assertEquals(listId, back.listId)
  }

  @Test
  fun `a question saved by an older build reads as a duplicate on the open list`() {
    val hugos = phone(hugo, hugosVersion, ava).receiving(ava, avasVersion, 2_200)
    val old = StateCodec.encode(hugos).lines().joinToString("\n") { if (it.startsWith("conflict|")) "conflict" else it }

    val back = StateCodec.decode(old)!!.conflict!!
    assertEquals(ConflictKind.DUPLICATE, back.kind)
    assertNull(back.listId)
  }
}
