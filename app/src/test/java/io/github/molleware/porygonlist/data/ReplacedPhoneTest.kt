package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity is tied to an installation, so replacing a handset produces a new device.
 *
 * That is a deliberate trade — a list lives on every holder's phone, so a new phone pairs and
 * refills from the others. What it must not do is strand the old id: a phone that will never
 * confirm another receipt would hold every tombstone on the list open for ever.
 */
class ReplacedPhoneTest {

  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugoOld = DeviceId("HUGOOLDAAAAAAAAA")
  private val hugoNew = DeviceId("HUGONEWAAAAAAAAA")

  private fun at(wall: Long, device: DeviceId = ava) = Hlc(wall, 0, device)

  private fun item(name: String, author: DeviceId = ava, removedAt: Hlc? = null): GroceryItem {
    val created = at(1_000, author)
    return GroceryItem(
      id = ItemId("${author.value}:1"),
      name = Field(name, created),
      qty = Field(1, created),
      checkedAt = created,
      removed = if (removedAt == null) Field(false, created) else Field(true, removedAt),
    )
  }

  private fun state(vararg items: GroceryItem, people: List<Person>) =
    AppState(
      localDevice = ava,
      idCounter = 1,
      clockHead = at(9_000),
      lists = listOf(GroceryList(1, "Weekly shop", ListAccent.ACCENT, items.toList(), people)),
      activeListId = 1,
      online = true,
      networks = emptyList(),
      conflict = null,
    )

  private val pair = listOf(Person(ava, "Ava", "A"), Person(hugoOld, "Hugo", "H"))

  @Test
  fun `a retired phone stops holding tombstones open`() {
    // The bug this guards: Hugo's old handset is gone and will never confirm anything again.
    val before = state(item("Oat milk", removedAt = at(3_000)), people = pair)
    assertEquals(1, before.pruneDeliveredTombstones().lists.first().items.size)

    val after = before.retireDevice(hugoOld)

    assertTrue("the tombstone had nobody left to wait for", after.lists.first().items.isEmpty())
  }

  @Test
  fun `replacing a phone stops waiting on the old one`() {
    val before = state(item("Oat milk", removedAt = at(3_000)), people = pair)

    val after = before.replaceDevice(hugoOld, hugoNew)

    // The new phone has confirmed nothing, so the tombstone is still owed to it — but to it, not
    // to a handset in a drawer.
    assertEquals(listOf(hugoNew), after.lists.first().peersOf(ava))
    assertEquals(1, after.lists.first().items.size)
  }

  @Test
  fun `a new phone does not inherit its predecessor's receipts`() {
    // Otherwise the replacement would look like it had received everything the old one had, and
    // tombstones it has never seen would be collected out from under it.
    val before =
      state(item("Oat milk", removedAt = at(3_000)), people = pair)
        .copy(deliveredTo = DeliveryLog().record(hugoOld, at(8_000)))

    val after = before.replaceDevice(hugoOld, hugoNew)

    assertNull(after.deliveredTo.confirmedBy(hugoNew))
    assertNull(after.deliveredTo.confirmedBy(hugoOld))
  }

  @Test
  fun `history written on the old phone stays attributed to the same person`() {
    val before = state(item("Sourdough", author = hugoOld), people = pair)

    val after = before.replaceDevice(hugoOld, hugoNew)
    val list = after.lists.first()

    // The item's id still carries the dead device — history is history — but it still reads "Hugo".
    assertEquals(hugoOld, list.items.first().createdBy)
    assertEquals("Hugo", list.personFor(hugoOld)?.name)
    assertEquals("Hugo", list.personFor(hugoNew)?.name)
  }

  @Test
  fun `a retired phone is not waited on but is still recognised`() {
    val list = GroceryList(1, "Weekly shop", ListAccent.ACCENT, emptyList(), pair).replaceDevice(hugoOld, hugoNew)

    assertFalse("a dead handset must never be waited on", hugoOld in list.peersOf(ava))
    assertTrue(list.people.single { it.name == "Hugo" }.wasEver(hugoOld))
  }

  @Test
  fun `unpairing removes the person entirely`() {
    val before = state(item("Sourdough", author = hugoOld), people = pair)

    val after = before.retireDevice(hugoOld)

    assertEquals(listOf("Ava"), after.lists.first().people.map { it.name })
    // Nothing left to attribute the old items to, which is honest rather than invented.
    assertNull(after.lists.first().personFor(hugoOld))
  }

  @Test
  fun `former devices survive a restart`() {
    val state = state(item("Sourdough", author = hugoOld), people = pair).replaceDevice(hugoOld, hugoNew)

    val restored = StateCodec.decode(StateCodec.encode(state))!!.lists.first()

    assertEquals("Hugo", restored.personFor(hugoOld)?.name)
    assertEquals(setOf(hugoOld), restored.people.single { it.name == "Hugo" }.formerDevices)
  }

  @Test
  fun `a person with no former phones round trips`() {
    val restored = StateCodec.decode(StateCodec.encode(state(people = pair)))!!.lists.first()

    assertEquals(emptySet<DeviceId>(), restored.people.single { it.name == "Ava" }.formerDevices)
  }

  @Test
  fun `replacing twice keeps the whole chain`() {
    val hugoThird = DeviceId("HUGO3RDAAAAAAAAA")
    val list =
      GroceryList(1, "Weekly shop", ListAccent.ACCENT, emptyList(), pair)
        .replaceDevice(hugoOld, hugoNew)
        .replaceDevice(hugoNew, hugoThird)

    val hugo = list.people.single { it.name == "Hugo" }
    assertEquals(hugoThird, hugo.device)
    assertEquals(setOf(hugoOld, hugoNew), hugo.formerDevices)
    // Items from either old phone still read as his.
    assertEquals("Hugo", list.personFor(hugoOld)?.name)
    assertEquals("Hugo", list.personFor(hugoNew)?.name)
  }
}
