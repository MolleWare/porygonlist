package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ListId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val AVA = DeviceId("AVAAAAAAAAAAAAAA")
private val WEEKLY = ListId("${HUGO.value}:1")

private fun hugos(backgroundSync: Boolean? = null) =
  AppState(
    localDevice = HUGO,
    displayName = "Hugo",
    idCounter = 10,
    clockHead = Hlc(1_000, 0, HUGO),
    lists = listOf(GroceryList(WEEKLY, "Weekly shop", ListAccent.ACCENT, emptyList(), listOf(Person(HUGO, "Hugo", "H")))),
    activeListId = WEEKLY,
    networks = emptyList(),
    peers = listOf(TrustedPeer(AVA, byteArrayOf(1), "Ava", 0)),
    backgroundSync = backgroundSync,
  )

/** The "Background sync" switch is this phone's own, kept in the state file beside everything else. */
class BackgroundSyncSaveTest {

  @Test
  fun `the switch survives a save, on, off and never touched`() {
    for (chosen in listOf(true, false, null)) {
      assertEquals("$chosen", chosen, StateCodec.decode(StateCodec.encode(hugos(chosen)))!!.backgroundSync)
    }
  }

  @Test
  fun `it is a record of its own, written once chosen, and the meta line is as 0_1_0 reads it`() {
    val untouched = StateCodec.encode(hugos())
    val on = StateCodec.encode(hugos(backgroundSync = true))

    assertFalse(untouched.lines().any { it.startsWith("background|") })
    assertTrue(on.lines().contains("background|1"))
    // 0.1.0 skips a record type it does not know; the one it does read is left exactly as it was.
    assertEquals(untouched.lines().first { it.startsWith("meta|") }, on.lines().first { it.startsWith("meta|") })
  }

  @Test
  fun `a 0_1_0 save reads as never touched`() {
    val encoded = StateCodec.encode(hugos(backgroundSync = false))

    for (old in listOf(as010(encoded), asVersion10(as010(encoded)))) {
      val restored = StateCodec.decode(old)!!

      assertNull(restored.backgroundSync)
      assertEquals("Hugo", restored.displayName)
      assertEquals(listOf(WEEKLY), restored.lists.map { it.id })
    }
  }

  @Test
  fun `a choice comes back from a save set aside, unless one was made again`() {
    assertEquals(false, hugos().withRecovered(hugos(backgroundSync = false)).backgroundSync)
    assertEquals(true, hugos(backgroundSync = true).withRecovered(hugos(backgroundSync = false)).backgroundSync)
  }
}
