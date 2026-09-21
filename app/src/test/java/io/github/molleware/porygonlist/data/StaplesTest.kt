package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val PHONE = DeviceId("avaphone01")

/**
 * The staples grid is the owner's own, and unlike everything else on a list it is not shared: it is
 * a habit, not an agreement.
 */
class StaplesTest {

  private fun seeded() = AppState.seed(localDevice = PHONE, now = 1_700_000_000_000)

  @Test
  fun `a fresh install opens onto an empty grid`() {
    // A shortlist of what you buy over and over is only meaningful if you put it there. The screen
    // says what the grid is for while it is empty, so there is nothing to prefill.
    assertEquals(emptyList<Staple>(), AppState.empty(localDevice = PHONE).staples)
  }

  @Test
  fun `a staple starts unused`() {
    assertTrue(seeded().staples.isNotEmpty())
    assertTrue("nothing claims a use it has not had", seeded().staples.all { it.uses == 0 })
  }

  @Test
  fun `the note counts real use rather than a claimed cadence`() {
    assertEquals("not used yet", Staple("Eggs").note)
    assertEquals("used once", Staple("Eggs", uses = 1).note)
    assertEquals("used 4 times", Staple("Eggs", uses = 4).note)
  }

  @Test
  fun `staples survive a round trip, counts and all`() {
    val state = seeded().copy(staples = listOf(Staple("Eggs", uses = 3), Staple("Oat milk")))

    val restored = StateCodec.decode(StateCodec.encode(state))

    assertEquals(listOf(Staple("Eggs", uses = 3), Staple("Oat milk")), restored?.staples)
  }

  @Test
  fun `an emptied grid stays empty across a restart`() {
    val restored = StateCodec.decode(StateCodec.encode(seeded().copy(staples = emptyList())))

    assertNotNull(restored)
    assertEquals(emptyList<Staple>(), restored!!.staples)
  }

  @Test
  fun `a file written before staples existed comes back with an empty grid`() {
    val older =
      StateCodec.encode(seeded())
        .lineSequence()
        .filterNot { it.startsWith("staple|") }
        .mapIndexed { index, line -> if (index == 0) "PLSTATE7" else line }
        .joinToString("\n")

    // There is nothing to restore and nothing to invent: a file that never stored staples is
    // indistinguishable from one whose owner cleared them, and both mean an empty grid.
    assertEquals(emptyList<Staple>(), StateCodec.decode(older)?.staples)
  }

  @Test
  fun `a staple name containing a separator survives`() {
    val state = seeded().copy(staples = listOf(Staple("Salt | pepper", uses = 2)))

    assertEquals(state.staples, StateCodec.decode(StateCodec.encode(state))?.staples)
  }
}
