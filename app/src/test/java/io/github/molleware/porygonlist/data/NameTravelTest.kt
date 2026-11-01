package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.payloadFor
import io.github.molleware.porygonlist.data.sync.receive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val SAM = DeviceId("SAMAAAAAAAAAAAAA")

private val WEEKLY = ListId("${HUGO.value}:1")

private fun at(wall: Long, device: DeviceId) = Hlc(wall, 0, device)

private fun weekly(vararg people: Person) = GroceryList(WEEKLY, "Weekly shop", ListAccent.ACCENT, emptyList(), people.toList())

/** One phone, on the weekly shop with [people], paired with everyone else on it. */
private fun phone(device: DeviceId, name: String, vararg people: Person) =
  AppState(
    localDevice = device,
    displayName = name,
    idCounter = 10,
    clockHead = at(1_000, device),
    lists = listOf(weekly(*people)),
    activeListId = WEEKLY,
    networks = emptyList(),
    peers = people.filterNot { it.device == device }.map { TrustedPeer(it.device, byteArrayOf(1), it.name, 0) },
  )

private fun AppState.person(device: DeviceId) = lists.single { it.id == WEEKLY }.people.single { it.device == device }

/** [this] once [payload] has arrived over the wire, as [wire] puts it. */
private fun AppState.receiving(payload: SyncPayload, wire: (String) -> String = { it }): AppState {
  val decoded = SyncCodec.decode(wire(SyncCodec.encode(payload)))!!
  val result = receive(decoded, HybridClock(localDevice, now = { 9_000 }))
  assertTrue("the payload should apply, got $result", result is SyncResult.Merged)
  return (result as SyncResult.Merged).state
}

/** Everything [from] would send [to], as [from] holds it now. */
private fun AppState.sentTo(to: DeviceId) = payloadFor(to, HybridClock(localDevice, now = { 8_000 }))!!

class NameTravelTest {

  private val hugo = Person(HUGO, "Hugo", "H")
  private val ava = Person(AVA, "Ava", "A")
  private val sam = Person(SAM, "Sam", "S")

  @Test
  fun `a rename is stamped, here and on every list, and the initial follows`() {
    val renamed = phone(HUGO, "Hugo", hugo, ava).withDisplayName("Zabb", at(2_000, HUGO))

    assertEquals(at(2_000, HUGO), renamed.displayNameAt)
    assertEquals(Person(HUGO, "Zabb", "Z", nameAt = at(2_000, HUGO)), renamed.person(HUGO))
    assertEquals("someone else's name is theirs", ava, renamed.person(AVA))
  }

  @Test
  fun `a rename reaches a peer`() {
    val hugos = phone(HUGO, "Hugo", hugo, ava).withDisplayName("Zabb", at(2_000, HUGO))
    val avas = phone(AVA, "Ava", hugo, ava)

    val after = avas.receiving(hugos.sentTo(AVA))

    assertEquals("Zabb", after.person(HUGO).name)
    assertEquals("Z", after.person(HUGO).initial)
    assertEquals("and the screens about paired phones say so", "Zabb", after.nameOf(after.peers.single()))
  }

  @Test
  fun `a rename passed on by someone else reaches a third phone too`() {
    // Sam renamed, and Ava heard; Hugo hears it from Ava, never having spoken to Sam since.
    val avas = phone(AVA, "Ava", hugo, ava, sam.copy(name = "Samuel", initial = "S", nameAt = at(2_000, SAM)))
    val hugos = phone(HUGO, "Hugo", hugo, ava, sam)

    assertEquals("Samuel", hugos.receiving(avas.sentTo(HUGO)).person(SAM).name)
  }

  @Test
  fun `a 0_1_0 phone re-sending an old name never undoes a rename`() {
    // Hugo renamed himself, and heard of Sam's rename. Ava is on 0.1.0: her copy has both old names,
    // and she sends them without stamps.
    val hugos =
      phone(HUGO, "Hugo", hugo, ava, sam.copy(name = "Samuel", nameAt = at(1_500, SAM))).withDisplayName("Zabb", at(2_000, HUGO))
    val fromAva = SyncPayload(AVA, at(5_000, AVA), listOf(weekly(hugo, ava, sam)))

    val after = hugos.receiving(fromAva, ::payloadAs010)

    assertEquals("Zabb", after.displayName)
    assertEquals("Zabb", after.person(HUGO).name)
    assertEquals("Samuel", after.person(SAM).name)
    assertEquals("and it goes on being sent", "Zabb", after.sentTo(AVA).lists.single().people.single { it.device == HUGO }.name)
  }

  @Test
  fun `before anyone renames, a person's own phone corrects a stale name, and only theirs`() {
    // Ava knows Hugo by the name he gave when pairing; he has called himself Zabb since, on 0.1.0,
    // where names did not travel. Neither name is stamped.
    val avas = phone(AVA, "Ava", hugo, ava, sam)
    val fromHugo = SyncPayload(HUGO, at(5_000, HUGO), listOf(weekly(hugo.copy(name = "Zabb", initial = "Z"), ava, sam.copy(name = "Old Sam"))))

    val after = avas.receiving(fromHugo, ::payloadAs010)

    assertEquals("Zabb", after.person(HUGO).name)
    assertEquals("Z", after.person(HUGO).initial)
    assertEquals("Hugo's copy of Sam is not Sam's word", "Sam", after.person(SAM).name)
  }

  @Test
  fun `your own name is yours, even on a list you are invited onto`() {
    // Never renamed since names were stamped, so this phone's word is all there is.
    val hugos = phone(HUGO, "Zabb", hugo.copy(name = "Zabb", initial = "Z"), ava)
    val invite = GroceryList(ListId("${AVA.value}:7"), "Camping", ListAccent.ACCENT_2, emptyList(), listOf(ava, hugo))

    val after = hugos.receiving(SyncPayload(AVA, at(5_000, AVA), listOf(invite)))

    val me = after.lists.single { it.id == invite.id }.people.single { it.device == HUGO }
    assertEquals("Zabb", me.name)
    assertEquals("Z", me.initial)
    assertEquals("Zabb", after.displayName)
  }

  @Test
  fun `a rename made on the phone yours replaced is taken`() {
    val old = DeviceId("OLDHUGOAAAAAAAAA")
    val hugos = phone(HUGO, "Hugo", hugo.copy(nameAt = at(1_000, HUGO)), ava).copy(displayNameAt = at(1_000, HUGO))
    val theirs = weekly(hugo.copy(name = "Zabb", initial = "Z", formerDevices = setOf(old), nameAt = at(1_500, old)), ava)

    val after = hugos.receiving(SyncPayload(AVA, at(5_000, AVA), listOf(theirs)))

    assertEquals("Zabb", after.displayName)
    assertEquals(at(1_500, old), after.displayNameAt)
    assertEquals("Zabb", after.person(HUGO).name)
  }

  @Test
  fun `a paired phone is called by the newest name a list has, or the one it paired with`() {
    val peer = TrustedPeer(AVA, byteArrayOf(1), "Ava", 0)
    val state = phone(HUGO, "Hugo", hugo, ava.copy(name = "Ava B", nameAt = at(2_000, AVA))).copy(peers = listOf(peer))

    assertEquals("Ava B", state.nameOf(peer))
    assertEquals("Ava", state.copy(lists = emptyList()).nameOf(peer))
  }

  @Test
  fun `a new phone's name brings its own initial`() {
    val replaced = weekly(hugo, ava).replaceDevice(AVA, DeviceId("NEWAVAAAAAAAAAAA"), name = "Bea")

    assertEquals("B", replaced.people.single { it.name == "Bea" }.initial)
  }

  // ── On disk and on the wire ───────────────────────────────────────────────

  @Test
  fun `a name keeps its stamp through a save`() {
    val state = phone(HUGO, "Hugo", hugo, ava.copy(name = "Ava B", nameAt = at(1_500, AVA))).withDisplayName("Zabb", at(2_000, HUGO))

    val restored = StateCodec.decode(StateCodec.encode(state))!!

    assertEquals(state.displayNameAt, restored.displayNameAt)
    assertEquals(state.lists.single().people, restored.lists.single().people)
  }

  @Test
  fun `a 0_1_0 save reads as nobody renamed since`() {
    val state = phone(HUGO, "Hugo", hugo, ava).withDisplayName("Zabb", at(2_000, HUGO))

    for (old in listOf(as010(StateCodec.encode(state)), asVersion10(as010(StateCodec.encode(state))))) {
      val restored = StateCodec.decode(old)!!

      assertEquals("Zabb", restored.displayName)
      assertEquals(Hlc(0, 0, HUGO), restored.displayNameAt)
      assertEquals("Zabb", restored.person(HUGO).name)
      assertEquals(Hlc(0, 0, HUGO), restored.person(HUGO).nameAt)
    }
  }

  @Test
  fun `the new columns come after everything 0_1_0 reads`() {
    val state =
      phone(HUGO, "Hugo", hugo, ava).withAvatar("apple", at(1_500, HUGO)).withDisplayName("Zabb", at(2_000, HUGO))
    val disk = StateCodec.encode(state).lines()
    val wire = SyncCodec.encode(state.sentTo(AVA)).lines()

    val meta = disk.single { it.startsWith("meta|") }.split('|')
    assertEquals(11, meta.size)
    assertEquals("Zabb", meta[6]) // the last column 0.1.0 reads
    assertEquals(listOf("apple", at(1_500, HUGO).encode(), Hlc(0, 0, HUGO).encode()), meta.subList(7, 10))
    assertEquals(at(2_000, HUGO).encode(), meta[10])

    for (person in listOf(disk, wire).map { lines -> lines.map { it.split('|') }.first { it[0] == "person" && it[2] == HUGO.value } }) {
      assertEquals(12, person.size)
      assertEquals(listOf("Zabb", "Z", "", "0"), person.subList(3, 7))
      assertEquals(Hlc(0, 0, HUGO).encode(), person[7]) // the last column 0.1.0 reads
      assertEquals(listOf("apple", at(1_500, HUGO).encode(), Hlc(0, 0, HUGO).encode()), person.subList(8, 11))
      assertEquals(at(2_000, HUGO).encode(), person[11])
    }
  }

  @Test
  fun `an avatar chosen before a rename survives it`() {
    val state = phone(HUGO, "Hugo", hugo, ava).withAvatar("lemon", at(1_500, HUGO)).withDisplayName("Zabb", at(2_000, HUGO))

    assertEquals(Field("lemon", at(1_500, HUGO), basedOn = Hlc(0, 0, HUGO)), state.person(HUGO).avatar)
  }
}
