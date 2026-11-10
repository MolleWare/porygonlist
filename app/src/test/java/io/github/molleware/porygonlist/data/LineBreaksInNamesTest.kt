package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.DeviceIdentity
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import org.junit.Assert.assertEquals
import org.junit.Test

private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val HUGO = DeviceId("HUGOOOOOOOOOOOOO")

/**
 * A line break typed or pasted into a name.
 *
 * A name pasted with a Windows line ending carries a carriage return, and the record format escaped
 * `\n` but not `\r`, while both readers split lines on a lone `\r` too. So the next start dropped the
 * item, the whole list, or the pairing with that name in it, and the checksum still matched, so no
 * earlier save was tried. A peer dropped the same lines from a payload. Now `\r` is escaped like
 * `\n`, and names lose their control characters as they are typed.
 */
class LineBreaksInNamesTest {

  private val names = listOf("Oat\rmilk", "Milk\r\n", "Eggs\nlarge", "Rice\r\nbasmati", "Tea\r")

  private fun stamp(wall: Long, by: DeviceId = AVA) = Hlc(wall, 0, by)

  private fun item(n: Int, name: String) =
    stamp(1_000L + n).let { s ->
      GroceryItem(ItemId("${AVA.value}:${10 + n}"), Field(name, s), Field(1, s), checkedAt = s, removed = Field(false, s))
    }

  private val list =
    GroceryList(
      ListId("${AVA.value}:1"),
      "Weekly\r\nshop\r",
      ListAccent.ACCENT,
      names.mapIndexed { n, name -> item(n, name) },
      listOf(Person(AVA, "Ava\r", "A"), Person(HUGO, "Hugo\r\nB.", "H")),
      nameAt = stamp(900),
    )

  @Test
  fun `names with line breaks survive the save file`() {
    val key = byteArrayOf(4, 8, 15, 16, 23, 42)
    val sam = TrustedPeer(DeviceIdentity.deviceIdFor(key), key, "Sam\r\nthe neighbour", 0)
    val state =
      AppState(
        localDevice = AVA,
        displayName = "Ava\r",
        idCounter = 100,
        clockHead = stamp(2_000),
        lists = listOf(list),
        activeListId = list.id,
        networks = listOf(ApprovedNetwork(NetworkFingerprint("aaaaaa"), "Kitchen\rwifi", "Approved", true)),
        peers = listOf(sam),
      )

    val restored = StateCodec.decode(StateCodec.encode(state))!!

    assertEquals("Ava\r", restored.displayName)
    assertEquals(listOf(list.name), restored.lists.map { it.name })
    assertEquals(names, restored.lists.single().items.map { it.name.value })
    assertEquals(list.people.map { it.name }, restored.lists.single().people.map { it.name })
    assertEquals(listOf(sam), restored.peers)
    assertEquals("Kitchen\rwifi", restored.networks.single().name)
  }

  @Test
  fun `names with line breaks survive the trip to a peer`() {
    val sent = SyncPayload(AVA, stamp(2_000), listOf(list))

    val received = SyncCodec.decode(SyncCodec.encode(sent))!!

    assertEquals(list.name, received.lists.single().name)
    assertEquals(names, received.lists.single().items.map { it.name.value })
    assertEquals(list.people.map { it.name }, received.lists.single().people.map { it.name })
  }

  @Test
  fun `a typed name loses its control characters`() {
    assertEquals("Milk", plainName("Milk\r\n"))
    assertEquals("Oat milk", plainName("Oat\r\nmilk"))
    assertEquals("Oat milk", plainName("Oat\rmilk"))
    assertEquals("Rice basmati", plainName("Rice\tbasmati"))
    assertEquals("Ava", plainName("  Ava\n"))
    assertEquals("", plainName("\r\n"))
  }

  @Test
  fun `the owner's name is stored without them`() {
    assertEquals("Ava B.", AppState.empty(AVA).withDisplayName("Ava\r\nB.\r\n", stamp(3_000)).displayName)
  }

  @Test
  fun `a pasted list loses them too`() {
    assertEquals(listOf("Oat milk", "Eggs"), ShareCodec.decode("oat\rmilk\r\neggs\r\n").map { it.name })
  }

  @Test
  fun `a recipe and its ingredients lose them too, and survive the save file`() {
    val recipe = Recipe.of("Lasagne\r\nal forno\r\n", "2 eggs\r\nflour\r\n")!!
    val withLineBreak = Recipe("Pie\rcrust", recipe.ingredients)
    val state = AppState.empty(AVA).copy(recipes = listOf(recipe, withLineBreak))

    assertEquals("Lasagne al forno", recipe.name)
    assertEquals(listOf("Eggs", "Flour"), recipe.ingredients.map { it.name })
    assertEquals(listOf(recipe, withLineBreak), StateCodec.decode(StateCodec.encode(state))!!.recipes)
  }
}
