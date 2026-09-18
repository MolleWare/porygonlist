package io.github.molleware.porygonlist.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTest {

  private val ava = DeviceId("ava0000000")
  private val hugo = DeviceId("hugo000000")

  private fun avaAt(wall: Long, counter: Int = 0) = Hlc(wall, counter, ava)

  private fun hugoAt(wall: Long, counter: Int = 0) = Hlc(wall, counter, hugo)

  @Test
  fun `an edit made against a value supersedes it`() {
    val original = Field("Milk", avaAt(1_000))
    val renamed = original.set("Oat milk", hugoAt(2_000))

    assertTrue(renamed.supersedes(original))
    assertFalse(original.supersedes(renamed))
    assertFalse(renamed.concurrentWith(original))
  }

  @Test
  fun `two edits neither of which saw the other are concurrent`() {
    val shared = avaAt(1_000)
    // Both phones start from the same value and edit it before hearing from the other.
    val mine = Field("Milk", shared).set("Oat milk", avaAt(2_000))
    val theirs = Field("Milk", shared).set("Whole milk", hugoAt(2_001))

    assertTrue(mine.concurrentWith(theirs))
    assertTrue(theirs.concurrentWith(mine))
  }

  @Test
  fun `a week apart is not a conflict if the later edit saw the earlier`() {
    val week = 7 * 24 * 60 * 60 * 1000L
    val old = Field("Milk", avaAt(1_000))
    val recent = old.set("Oat milk", hugoAt(1_000 + week))

    assertFalse(recent.concurrentWith(old))
  }

  @Test
  fun `a second apart is a conflict if neither phone had heard from the other`() {
    val base = avaAt(1_000)
    val mine = Field("Milk", base).set("Oat milk", avaAt(9_000))
    val theirs = Field("Milk", base).set("Whole milk", hugoAt(9_001))

    assertTrue(mine.concurrentWith(theirs))
  }

  @Test
  fun `both phones land on the same value, whichever side does the merging`() {
    val base = avaAt(1_000)
    val mine = Field("Oat milk", avaAt(2_000), basedOn = base)
    val theirs = Field("Whole milk", hugoAt(2_001), basedOn = base)

    // The same two versions, merged on Ava's phone and on Hugo's. If these disagreed, the two
    // phones would show different lists and never converge.
    assertEquals(merge(mine, theirs).field, merge(theirs, mine).field)
    assertEquals(merge(mine, theirs).wasConcurrent, merge(theirs, mine).wasConcurrent)
  }

  @Test
  fun `a sequential edit merges with nothing to report`() {
    val original = Field("Milk", avaAt(1_000))
    val renamed = original.set("Oat milk", hugoAt(2_000))

    val merged = merge(original, renamed)

    assertEquals("Oat milk", merged.field.value)
    assertFalse(merged.wasConcurrent)
  }

  @Test
  fun `competing renames merge to one value but are flagged`() {
    val base = avaAt(1_000)
    val mine = Field("Oat milk", avaAt(2_000), basedOn = base)
    val theirs = Field("Whole milk", hugoAt(2_001), basedOn = base)

    val merged = merge(mine, theirs)

    // Something has to be on screen, and both phones must agree on what. The flag is how the caller
    // knows to ask a person about it anyway.
    assertEquals("Whole milk", merged.field.value)
    assertTrue(merged.wasConcurrent)
  }

  @Test
  fun `both ticking the same item off is not a conflict worth anyone's time`() {
    val base = avaAt(1_000)
    val mine = Field(false, base).set(true, avaAt(2_000))
    val theirs = Field(false, base).set(true, hugoAt(2_001))

    val merged = merge(mine, theirs)

    // Concurrent by the clock, but they agree, so there is nothing to settle.
    assertTrue(mine.concurrentWith(theirs))
    assertEquals(true, merged.field.value)
    assertFalse(merged.wasConcurrent)
  }

  @Test
  fun `a first value with nothing behind it never claims to supersede`() {
    val mine = Field("Milk", avaAt(2_000))
    val theirs = Field("Bread", hugoAt(1_000))

    assertFalse(mine.supersedes(theirs))
    assertTrue(mine.concurrentWith(theirs))
  }

  @Test
  fun `merging is stable, so re-merging an already merged value changes nothing`() {
    val base = avaAt(1_000)
    val mine = Field("Oat milk", avaAt(2_000), basedOn = base)
    val theirs = Field("Whole milk", hugoAt(2_001), basedOn = base)

    val once = merge(mine, theirs).field
    val twice = merge(once, theirs).field

    assertEquals(once, twice)
  }
}
