package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ME = DeviceId("avaphone01")
private val THEM = DeviceId("hugophone2")

/**
 * First run: the app has an identity before it has a name, and has to ask for exactly one of them.
 */
class FirstRunTest {

  private fun seeded() = AppState.seed(localDevice = ME, partner = THEM, now = 1_700_000_000_000)

  @Test
  fun `a fresh install is unnamed`() {
    val state = seeded()

    assertFalse("a seeded state has nobody's name in it", state.named)
    assertEquals("", state.displayName)
  }

  @Test
  fun `the owner is never given a placeholder name`() {
    val you = seeded().lists.flatMap { it.people }.filter { it.device == ME }

    assertTrue("the owner is on the seeded lists", you.isNotEmpty())
    assertTrue("no list introduces the owner as someone they did not choose", you.all { it.name.isEmpty() })
  }

  @Test
  fun `naming reaches the top level and every list`() {
    val named = seeded().withDisplayName("Hugo")

    assertTrue(named.named)
    assertEquals("Hugo", named.displayName)
    named.lists.forEach { list ->
      val you = list.people.single { it.device == ME }
      assertEquals("Hugo", you.name)
      assertEquals("H", you.initial)
    }
  }

  @Test
  fun `naming leaves everyone else alone`() {
    val before = seeded().lists.flatMap { it.people }.filterNot { it.device == ME }
    val after = seeded().withDisplayName("Hugo").lists.flatMap { it.people }.filterNot { it.device == ME }

    assertEquals(before, after)
  }

  @Test
  fun `the initial follows the name`() {
    assertEquals("É", seeded().withDisplayName("élodie").lists.first().people.single { it.device == ME }.initial)
  }

  @Test
  fun `surrounding space is not part of a name`() {
    assertEquals("Ava", seeded().withDisplayName("  Ava  ").displayName)
  }

  @Test
  fun `a blank name is ignored rather than stored`() {
    val named = seeded().withDisplayName("Ava")

    // Otherwise clearing the field would drop the app back into first run and lose the real name.
    assertEquals("Ava", named.withDisplayName("   ").displayName)
    assertFalse(seeded().withDisplayName("   ").named)
  }

  @Test
  fun `the name survives a round trip to disk`() {
    val restored = StateCodec.decode(StateCodec.encode(seeded().withDisplayName("Ava")))

    assertEquals("Ava", restored?.displayName)
  }

  @Test
  fun `a name containing a separator survives`() {
    val restored = StateCodec.decode(StateCodec.encode(seeded().withDisplayName("Ava | A")))

    assertEquals("Ava | A", restored?.displayName)
  }

  @Test
  fun `a state file written before names existed still loads, and asks`() {
    // A version 6 file is version 7 without the trailing name on the meta record.
    val v7 = StateCodec.encode(seeded().withDisplayName("Ava"))
    val v6 =
      v7.lineSequence()
        .map {
          when {
            it.trim() == "PLSTATE7" -> "PLSTATE6"
            it.startsWith("meta|") -> it.substringBeforeLast('|')
            else -> it
          }
        }
        .joinToString("\n")

    val restored = StateCodec.decode(v6)

    assertNotNull("an older file is readable, not discarded", restored)
    assertFalse("the owner is asked their name once, rather than losing their lists", restored!!.named)
    assertEquals("everything else comes back", seeded().lists.size, restored.lists.size)
  }

  @Test
  fun `a file from a format this build does not know is still refused`() {
    val text = StateCodec.encode(seeded()).replaceFirst("PLSTATE7", "PLSTATE5")

    assertEquals(null, StateCodec.decode(text))
  }
}
