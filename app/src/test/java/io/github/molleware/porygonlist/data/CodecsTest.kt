package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ME = DeviceId("avaphone01")
private val THEM = DeviceId("hugophone2")

private fun stamp(wall: Long, device: DeviceId = ME, counter: Int = 0) = Hlc(wall, counter, device)

private fun item(
  seq: Long,
  name: String,
  creator: DeviceId = ME,
  writer: DeviceId = creator,
  qty: Int = 1,
  checked: Boolean = false,
  wall: Long = 1_700_000_000_000,
) =
  GroceryItem(
    id = ItemId("${creator.value}:$seq"),
    name = Field(name, stamp(wall, writer)),
    qty = Field(qty, stamp(wall, writer)),
    checked = checked,
    checkedAt = stamp(wall, writer),
    removed = Field(false, stamp(wall, writer)),
  )

class ShareCodecTest {

  private fun list(vararg items: GroceryItem) =
    GroceryList(ListId("${ME.value}:1"), "Weekly shop", ListAccent.ACCENT, items.toList(), listOf(Person(ME, "Ava", "A")))

  @Test
  fun `encode matches the format the design specifies`() {
    val text = ShareCodec.encode(list(item(1, "Sourdough"), item(2, "Oat milk", qty = 2), item(3, "Butter")))

    assertEquals("PL1 · Weekly shop · sourdough, 2 oat milk, butter", text)
  }

  @Test
  fun `encode leaves out what is already in the trolley`() {
    val text = ShareCodec.encode(list(item(1, "Sourdough", checked = true), item(2, "Butter")))

    assertEquals("PL1 · Weekly shop · butter", text)
  }

  @Test
  fun `decode reads back what encode wrote`() {
    val parsed = ShareCodec.decode(ShareCodec.encode(list(item(1, "Sourdough"), item(2, "Oat milk", qty = 2))))

    assertEquals(listOf("Sourdough" to 1, "Oat milk" to 2), parsed.map { it.name to it.qty })
  }

  @Test
  fun `decode accepts a bare list with no header`() {
    assertEquals(
      listOf("Milk" to 1, "Eggs" to 3, "Bread" to 1),
      ShareCodec.decode("milk, 3 eggs, bread").map { it.name to it.qty },
    )
  }

  @Test
  fun `decode splits on newlines as well as commas`() {
    assertEquals(listOf("Milk", "Eggs", "Bread"), ShareCodec.decode("milk\n2 eggs\n\nbread").map { it.name })
  }

  @Test
  fun `decode keeps an interpunct that belongs to an item name`() {
    val parsed = ShareCodec.decode("PL1 · Weekly shop · tea · earl grey, milk")

    assertEquals(listOf("Tea · earl grey", "Milk"), parsed.map { it.name })
  }

  @Test
  fun `decode of nothing useful yields nothing`() {
    assertTrue(ShareCodec.decode("   ").isEmpty())
    assertTrue(ShareCodec.decode(",,, ,").isEmpty())
  }
}

class ItemIdentityTest {

  @Test
  fun `an id names the phone that minted it`() {
    assertEquals(ME, ItemId("${ME.value}:7").device)
  }

  @Test
  fun `two phones at the same counter do not collide`() {
    // The whole point: offline, both phones mint "their next item" and the ids still differ.
    assertNotEquals(ItemId("${ME.value}:7"), ItemId("${THEM.value}:7"))
  }

  @Test
  fun `an item knows its creator apart from its last writer`() {
    val hugosRenamedByMe = item(1, "Oat milk", creator = THEM, writer = ME)

    assertEquals(THEM, hugosRenamedByMe.createdBy)
    assertEquals(ME, hugosRenamedByMe.lastWriter)
  }

  @Test
  fun `the newest stamp across fields is what dates the item`() {
    val base = item(1, "Butter", wall = 1_000)
    val renamedLater = base.copy(name = base.name.set("Salted butter", stamp(9_000, THEM)))

    assertEquals(9_000, renamedLater.at)
    assertEquals(THEM, renamedLater.lastWriter)
  }
}

class StateCodecTest {

  @Test
  fun `state survives a round trip`() {
    val original = AppState.seed(localDevice = ME, partner = THEM, now = 1_700_000_000_000)

    val restored = StateCodec.decode(StateCodec.encode(original))!!

    assertEquals(original.localDevice, restored.localDevice)
    assertEquals(original.idCounter, restored.idCounter)
    assertEquals(original.lists.map { it.name }, restored.lists.map { it.name })
    assertEquals(original.activeListId, restored.activeListId)
    assertEquals(original.online, restored.online)
    assertEquals(original.networks, restored.networks)
  }

  @Test
  fun `identity is what makes a restart continuous`() {
    val restored = StateCodec.decode(StateCodec.encode(AppState.seed(localDevice = ME, partner = THEM)))!!

    // Without this, every item this phone authored would look like a stranger's after a restart.
    assertEquals(ME, restored.localDevice)
    assertTrue(restored.lists.first().items.all { it.createdBy == ME || it.createdBy == THEM })
  }

  @Test
  fun `a field keeps its stamp and what it was written against`() {
    val base = item(1, "Butter")
    val renamed = base.copy(name = base.name.set("Salted butter", stamp(2_000, THEM, counter = 4)))

    val restored = StateCodec.decode(StateCodec.encode(single(renamed)))!!.lists.first().items.first()

    assertEquals(renamed, restored)
    assertEquals("Salted butter", restored.name.value)
    assertEquals(Hlc(2_000, 4, THEM), restored.name.at)
    // basedOn is what lets a peer tell an informed edit from a blind one — it has to survive.
    assertEquals(base.name.at, restored.name.basedOn)
  }

  @Test
  fun `a field never written against anything round trips with no basedOn`() {
    val restored = StateCodec.decode(StateCodec.encode(single(item(1, "Butter"))))!!.lists.first().items.first()

    assertNull(restored.name.basedOn)
  }

  @Test
  fun `name and quantity carry independent stamps`() {
    val base = item(1, "Oat milk")
    val edited =
      base.copy(name = base.name.set("Oat milk barista", stamp(3_000, ME)), qty = base.qty.set(2, stamp(5_000, THEM)))

    val restored = StateCodec.decode(StateCodec.encode(single(edited)))!!.lists.first().items.first()

    assertEquals(Hlc(3_000, 0, ME), restored.name.at)
    assertEquals(Hlc(5_000, 0, THEM), restored.qty.at)
  }

  @Test
  fun `a conflict keeps both real items`() {
    val mine = item(1, "Eggs", creator = ME)
    val theirs = item(1, "Eggs", creator = THEM)
    val state = single().copy(conflict = Conflict(mine, theirs))

    val restored = StateCodec.decode(StateCodec.encode(state))!!.conflict!!

    // The items are the whole record: what the card says about them is written when it is shown.
    assertEquals(mine, restored.yours)
    assertEquals(theirs, restored.theirs)
  }

  @Test
  fun `a separator inside a name survives`() {
    val restored = StateCodec.decode(StateCodec.encode(single(item(1, "Bread | Milk \\ Eggs"))))!!

    assertEquals("Bread | Milk \\ Eggs", restored.lists.first().items.first().name.value)
  }

  @Test
  fun `presence is not written to disk`() {
    // `editing` means someone else has it open right now — meaningless once the app closes.
    val restored = StateCodec.decode(StateCodec.encode(single(item(1, "Coffee beans").copy(editing = true))))!!

    assertEquals(false, restored.lists.first().items.first().editing)
  }

  @Test
  fun `a damaged id counter never hands out an id already in use`() {
    val state = single(item(5, "Sourdough"), item(9, "Butter")).copy(idCounter = 2)

    val restored = StateCodec.decode(StateCodec.encode(state))!!

    assertTrue("idCounter must clear the highest local id", restored.idCounter >= 9)
  }

  @Test
  fun `the clock never resumes behind a stamp already on disk`() {
    // Otherwise a fresh edit could sort before one made last week and silently lose to it.
    val state = single(item(1, "Butter", wall = 9_000_000)).copy(clockHead = Hlc(1_000, 0, ME))

    val restored = StateCodec.decode(StateCodec.encode(state))!!

    assertTrue(restored.clockHead >= Hlc(9_000_000, 0, ME))
  }

  @Test
  fun `older formats are rejected rather than half-read`() {
    // v1 and v2 had no hybrid clock; there is no honest way to invent stamps for their items.
    assertNull(StateCodec.decode("PLSTATE1\nmeta|1|1|Home-Wifi-2G"))
    assertNull(StateCodec.decode("PLSTATE4|meta|abc|1|1|1|1"))
    assertNull(StateCodec.decode(""))
    assertNull(StateCodec.decode("garbage"))
  }

  @Test
  fun `a file with no identity is unreadable`() {
    val encoded = StateCodec.encode(single(item(1, "Butter")))
    val withoutMeta = encoded.lineSequence().filterNot { it.startsWith("meta|") }.joinToString("\n")

    assertNull(StateCodec.decode(withoutMeta))
  }

  @Test
  fun `one damaged line does not cost the rest of the file`() {
    val encoded = StateCodec.encode(single(item(1, "Sourdough"), item(2, "Butter")))
    val damaged = encoded.lineSequence().joinToString("\n") { if (it.contains("|Sourdough|")) "item|1|oops" else it }

    val restored = StateCodec.decode(damaged)!!

    assertEquals(listOf("Butter"), restored.lists.first().items.map { it.name.value })
  }

  private fun single(vararg items: GroceryItem) =
    AppState(
      localDevice = ME,
      idCounter = items.filter { it.createdBy == ME }.mapNotNull { it.id.value.substringAfter(':').toLongOrNull() }.maxOrNull() ?: 0L,
      clockHead = Hlc(1_700_000_000_000, 0, ME),
      lists =
        listOf(
          GroceryList(
            ListId("${ME.value}:1"),
            "Weekly shop",
            ListAccent.ACCENT,
            items.toList(),
            listOf(Person(ME, "Ava", "A")),
          )
        ),
      activeListId = ListId("${ME.value}:1"),
      online = true,
      networks = emptyList(),
      conflict = null,
    )
}
