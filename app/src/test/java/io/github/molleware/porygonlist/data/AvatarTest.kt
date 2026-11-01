package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.payloadFor
import io.github.molleware.porygonlist.data.sync.receive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val SAM = DeviceId("SAMAAAAAAAAAAAAA")

private val WEEKLY = ListId("${HUGO.value}:1")
private val PARTY = ListId("${HUGO.value}:2")

private fun at(wall: Long, device: DeviceId = HUGO) = Hlc(wall, 0, device)

private val hugo = Person(HUGO, "Hugo", "H")
private val ava = Person(AVA, "Ava", "A")
private val sam = Person(SAM, "Sam", "S")

private fun list(id: ListId, vararg people: Person) = GroceryList(id, "Weekly shop", ListAccent.ACCENT, emptyList(), people.toList())

/** Hugo's phone: the weekly shop shared with Ava, and a party list of his own. */
private fun hugos() = hugos(list(WEEKLY, hugo, ava), list(PARTY, hugo))

private fun hugos(vararg lists: GroceryList) =
  AppState(
    localDevice = HUGO,
    displayName = "Hugo",
    idCounter = 10,
    clockHead = at(1_000),
    lists = lists.toList(),
    activeListId = lists.first().id,
    networks = emptyList(),
    peers = listOf(TrustedPeer(AVA, byteArrayOf(1), "Ava", 0)),
  )

private fun AppState.mine(id: ListId) = lists.single { it.id == id }.people.single { it.device == HUGO }

private fun AppState.person(id: ListId, device: DeviceId) = lists.single { it.id == id }.people.single { it.device == device }

/** What Hugo's phone has once [payload] from Ava has arrived, over the wire as [wire] puts it. */
private fun AppState.receiving(payload: SyncPayload, wire: (String) -> String = { it }): AppState {
  val decoded = SyncCodec.decode(wire(SyncCodec.encode(payload)))!!
  val result = receive(decoded, HybridClock(HUGO, now = { 9_000 }))
  assertTrue("the payload should apply, got $result", result is SyncResult.Merged)
  return (result as SyncResult.Merged).state
}

class AvatarTest {

  // ── The catalog ───────────────────────────────────────────────────────────

  @Test
  fun `six avatars to choose, all free, each known by its own id`() {
    assertEquals(listOf("apple", "carrot", "avocado", "lemon", "mushroom", "broccoli"), Avatars.all.map { it.id })
    assertEquals(listOf("🍎", "🥕", "🥑", "🍋", "🍄", "🥦"), Avatars.all.map { it.emoji })
    assertTrue(Avatars.all.all { it.free })
    assertEquals(Avatars.all, Avatars.free)
  }

  @Test
  fun `an avatar with a cost is not free`() {
    assertFalse(AvatarChoice("dragonfruit", "🐉", "Dragon fruit", cost = 50).free)
  }

  @Test
  fun `none, or an id this build does not know, draws the initial`() {
    assertNull(Avatars.emojiOf(Avatars.NONE))
    assertNull("an avatar from a newer build", Avatars.emojiOf("dragonfruit"))
    assertEquals("🍎", Avatars.emojiOf("apple"))
  }

  @Test
  fun `an emoji this phone cannot draw is the initial, and is not offered`() {
    val noBroccoli: (String) -> Boolean = { it != "🥦" }

    assertNull(Avatars.emojiOf("broccoli", noBroccoli))
    assertEquals("🍎", Avatars.emojiOf("apple", noBroccoli))
    assertEquals(listOf("apple", "carrot", "avocado", "lemon", "mushroom"), Avatars.choosable(noBroccoli).map { it.id })
  }

  @Test
  fun `whether an emoji can be drawn is asked once, then remembered`() {
    val asked = mutableListOf<String>()
    val glyphs = GlyphCheck { asked += it; it != "🥦" }

    repeat(3) {
      assertTrue(glyphs.canDraw("🍎"))
      assertFalse(glyphs.canDraw("🥦"))
    }

    assertEquals(listOf("🍎", "🥦"), asked)
  }

  // ── Choosing ──────────────────────────────────────────────────────────────

  @Test
  fun `no avatar is the initial until one is chosen`() {
    assertEquals(Avatars.NONE, hugos().avatar.value)
    assertEquals(Field(Avatars.NONE, Hlc(0, 0, HUGO)), hugos().avatar)
    assertEquals(Field(Avatars.NONE, Hlc(0, 0, AVA)), ava.avatar)
  }

  @Test
  fun `choosing an avatar wears it on every list you are on, stamped`() {
    val chosen = hugos().withAvatar("carrot", at(2_000))

    assertEquals(Field("carrot", at(2_000), basedOn = Hlc(0, 0, HUGO)), chosen.avatar)
    assertEquals(chosen.avatar, chosen.mine(WEEKLY).avatar)
    assertEquals(chosen.avatar, chosen.mine(PARTY).avatar)
    assertEquals("someone else's is theirs to choose", ava.avatar, chosen.person(WEEKLY, AVA).avatar)
  }

  @Test
  fun `the initial can be chosen again, and that travels too`() {
    val back = hugos().withAvatar("carrot", at(2_000)).withAvatar(Avatars.NONE, at(3_000))

    assertEquals(Avatars.NONE, back.avatar.value)
    assertEquals(at(3_000), back.mine(WEEKLY).avatar.at)
  }

  @Test
  fun `an avatar this build does not offer cannot be chosen`() {
    val state = hugos()

    assertSame(state, state.withAvatar("dragonfruit", at(2_000)))
  }

  @Test
  fun `choosing the avatar already worn changes nothing`() {
    val apple = hugos().withAvatar("apple", at(2_000))

    assertSame(apple, apple.withAvatar("apple", at(3_000)))
  }

  @Test
  fun `a copy kept after being taken off a list keeps your avatar`() {
    val chosen = hugos().withAvatar("lemon", at(2_000))
    var counter = 50L

    val kept = chosen.lists.first().keptAsPrivate(ListId("${HUGO.value}:49"), { ItemId("${HUGO.value}:${++counter}") }, HUGO)

    assertEquals(chosen.avatar, kept.people.single().avatar)
  }

  @Test
  fun `a paired phone's avatar is the newest any list has for them`() {
    val state =
      hugos(
        list(WEEKLY, hugo, ava.copy(avatar = Field("apple", at(2_000, AVA)))),
        list(PARTY, hugo, ava.copy(avatar = Field("lemon", at(3_000, AVA)))),
      )

    assertEquals("lemon", state.avatarOf(AVA))
    assertEquals(Avatars.NONE, state.avatarOf(SAM))
    assertEquals("carrot", state.withAvatar("carrot", at(4_000)).avatarOf(HUGO))
  }

  // ── Saving ────────────────────────────────────────────────────────────────

  @Test
  fun `an avatar survives a save, yours and theirs`() {
    val state =
      hugos(list(WEEKLY, hugo, ava.copy(avatar = Field("mushroom", at(1_500, AVA), basedOn = at(900, AVA)))))
        .withAvatar("broccoli", at(2_000))

    val restored = StateCodec.decode(StateCodec.encode(state))!!

    assertEquals(state.avatar, restored.avatar)
    assertEquals(state.lists.single().people, restored.lists.single().people)
  }

  @Test
  fun `a 0_1_0 save reads as nobody having chosen one, and otherwise as it was`() {
    val state = hugos().withAvatar("broccoli", at(2_000))

    for (old in listOf(as010(StateCodec.encode(state)), asVersion10(as010(StateCodec.encode(state))))) {
      val restored = StateCodec.decode(old)!!

      assertEquals(Field(Avatars.NONE, Hlc(0, 0, HUGO)), restored.avatar)
      assertEquals("Hugo", restored.displayName)
      assertEquals(state.lists.map { it.id }, restored.lists.map { it.id })
      assertEquals(
        state.lists.map { l -> l.people.map { it.copy(avatar = Field(Avatars.NONE, Hlc(0, 0, it.device))) } },
        restored.lists.map { it.people },
      )
    }
  }

  // ── Sync ──────────────────────────────────────────────────────────────────

  @Test
  fun `an avatar travels in a payload`() {
    val payload =
      SyncPayload(from = HUGO, at = at(5_000), lists = listOf(hugos().withAvatar("avocado", at(2_000)).lists.first()))

    val decoded = SyncCodec.decode(SyncCodec.encode(payload))!!

    assertEquals(payload.lists.single().people, decoded.lists.single().people)
  }

  @Test
  fun `a 0_1_0 phone sending its copy of everyone never wipes an avatar`() {
    // Hugo has chosen one, and Sam's arrived earlier from a phone that has them. Ava's phone is on
    // 0.1.0, which knows nothing of avatars and sends everyone's record without one.
    val state =
      hugos(list(WEEKLY, hugo, ava, sam.copy(avatar = Field("mushroom", at(1_500, SAM)))), list(PARTY, hugo))
        .withAvatar("apple", at(2_000))
    val fromAva = SyncPayload(from = AVA, at = at(5_000, AVA), lists = listOf(list(WEEKLY, hugo, ava, sam)))
    val wire = payloadAs010(SyncCodec.encode(fromAva))
    assertTrue(
      "0.1.0 sends eight columns per person",
      wire.lines().filter { it.startsWith("person|") }.all { it.split('|').size == 8 },
    )

    val after = state.receiving(fromAva, ::payloadAs010)

    assertEquals("apple", after.avatar.value)
    assertEquals("apple", after.mine(WEEKLY).avatar.value)
    assertEquals("mushroom", after.person(WEEKLY, SAM).avatar.value)
    val goingBack = after.payloadFor(AVA, HybridClock(HUGO, now = { 9_000 }))!!.lists.single()
    assertEquals("and it goes on being sent", after.avatar, goingBack.people.single { it.device == HUGO }.avatar)
  }

  @Test
  fun `a newer avatar from a peer replaces the older one, and an older one does not`() {
    val known = hugos(list(WEEKLY, hugo, ava.copy(avatar = Field("apple", at(2_000, AVA)))))

    fun avaWearing(stamp: Hlc) = SyncPayload(AVA, at(5_000, AVA), listOf(list(WEEKLY, hugo, ava.copy(avatar = Field("lemon", stamp)))))

    val newer = known.receiving(avaWearing(at(4_000, AVA)))
    val older = known.receiving(avaWearing(at(1_000, AVA)))

    assertEquals("lemon", newer.person(WEEKLY, AVA).avatar.value)
    assertEquals("apple", older.person(WEEKLY, AVA).avatar.value)
  }

  @Test
  fun `a list you are invited onto goes back out with your avatar`() {
    val state = hugos(list(PARTY, hugo)).withAvatar("carrot", at(2_000))
    // Ava's phone added Hugo to its list knowing nothing of his avatar.
    val invite = GroceryList(ListId("${AVA.value}:7"), "Camping", ListAccent.ACCENT_2, emptyList(), listOf(ava, hugo))

    val after = state.receiving(SyncPayload(AVA, at(5_000, AVA), listOf(invite)))

    assertEquals(state.avatar, after.mine(invite.id).avatar)
    assertEquals(state.avatar, after.avatar)
  }

  @Test
  fun `a phone that replaced yours wears the avatar you chose on the old one`() {
    val old = DeviceId("OLDHUGOAAAAAAAAA")
    val fresh = hugos(list(WEEKLY, hugo, ava))
    // Ava's copy already knows Hugo moved phone, and what he wore on the old one.
    val theirs = list(WEEKLY, hugo.copy(formerDevices = setOf(old), avatar = Field("lemon", at(1_500, old))), ava)

    val after = fresh.receiving(SyncPayload(AVA, at(5_000, AVA), listOf(theirs)))

    assertEquals("lemon", after.avatar.value)
    assertEquals("lemon", after.mine(WEEKLY).avatar.value)
  }
}
