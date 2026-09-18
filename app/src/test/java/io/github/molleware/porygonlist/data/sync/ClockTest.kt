package io.github.molleware.porygonlist.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridClockTest {

  private val ava = DeviceId("ava0000000")
  private val hugo = DeviceId("hugo000000")

  /** A clock whose wall reading only moves when the test says so. */
  private class FakeTime(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke(): Long = now
  }

  @Test
  fun `stamps keep increasing while the wall clock stands still`() {
    val clock = HybridClock(ava, FakeTime(1_000L))

    val stamps = List(5) { clock.tick() }

    assertEquals(stamps.sorted(), stamps)
    assertEquals(5, stamps.distinct().size)
    // Same millisecond throughout, so the counter is doing all the work.
    assertTrue(stamps.all { it.wall == 1_000L })
    assertEquals(listOf(0, 1, 2, 3, 4), stamps.map { it.counter })
  }

  @Test
  fun `a wall clock that jumps backwards does not reorder edits`() {
    val time = FakeTime(5_000L)
    val clock = HybridClock(ava, time)
    val before = clock.tick()

    // NTP corrects the phone's clock backwards mid-session. This happens.
    time.now = 4_000L
    val after = clock.tick()

    assertTrue("edit after a backwards jump must still sort later", after > before)
    assertEquals(5_000L, after.wall)
  }

  @Test
  fun `observing a peer from the future moves this clock past it`() {
    val clock = HybridClock(ava, FakeTime(1_000L))
    // Hugo's phone is an hour fast. Without folding his stamp in, every edit of his would outrank
    // every edit of Ava's for the next hour.
    val hugosEdit = Hlc(3_601_000L, 0, hugo)

    val mine = clock.observe(hugosEdit)

    assertTrue(mine > hugosEdit)
    assertTrue(clock.tick() > hugosEdit)
  }

  @Test
  fun `observing a stamp in the same millisecond still produces a later one`() {
    val clock = HybridClock(ava, FakeTime(2_000L))
    val local = clock.tick()
    val remote = Hlc(2_000L, 7, hugo)

    val merged = clock.observe(remote)

    assertTrue(merged > local)
    assertTrue(merged > remote)
    assertEquals(8, merged.counter)
  }

  @Test
  fun `the order is total, so two phones sort the same edits the same way`() {
    // Identical wall and counter: only the device id can break the tie, and it must break it the
    // same way on both phones.
    val a = Hlc(1_000L, 0, ava)
    val h = Hlc(1_000L, 0, hugo)

    assertNotEquals(0, a.compareTo(h))
    assertEquals(a.compareTo(h), -h.compareTo(a))
    assertEquals(listOf(a, h).sorted(), listOf(h, a).sorted())
  }

  @Test
  fun `head survives a round trip through the stored form`() {
    val clock = HybridClock(ava, FakeTime(1_700_000_000_000L))
    repeat(3) { clock.tick() }
    val head = clock.head()

    assertEquals(head, Hlc.decode(head.encode()))
  }

  @Test
  fun `a restored clock carries on rather than restarting`() {
    val stored = Hlc(9_000L, 4, ava)
    // The phone was killed and reopened; its wall clock now reads earlier than the stored stamp.
    val clock = HybridClock(ava, FakeTime(8_000L), start = stored)

    assertTrue(clock.tick() > stored)
  }

  @Test
  fun `decode refuses anything it cannot read rather than guessing`() {
    assertNull(Hlc.decode(""))
    assertNull(Hlc.decode("1700000000000"))
    assertNull(Hlc.decode("1700000000000-2"))
    assertNull(Hlc.decode("notanumber-2-ava0000000"))
    assertNull(Hlc.decode("1700000000000-x-ava0000000"))
    assertNull(Hlc.decode("1700000000000-2-"))
  }
}

class IdentityTest {

  // Device ids used to be drawn at random and were tested for uniqueness here. They are now the
  // fingerprint of a public key, which is a stronger property than uniqueness — an id cannot be
  // claimed without the matching private key. See DeviceIdentityTest.

  @Test
  fun `two phones cannot mint the same item id`() {
    val avas = IdFactory(DeviceId("ava0000000"))
    val hugos = IdFactory(DeviceId("hugo000000"))

    val mine = List(100) { avas.nextItem().value }
    val theirs = List(100) { hugos.nextItem().value }

    assertTrue((mine intersect theirs.toSet()).isEmpty())
  }

  @Test
  fun `ids carry on from where the phone left off`() {
    val first = IdFactory(DeviceId("ava0000000"))
    repeat(4) { first.nextItem() }

    val reopened = IdFactory(DeviceId("ava0000000"), start = first.peek())

    assertEquals("ava0000000:5", reopened.nextItem().value)
  }

  @Test
  fun `items and lists share one counter so an id is never reused`() {
    val ids = IdFactory(DeviceId("ava0000000"))

    val list = ids.nextList().value
    val item = ids.nextItem().value

    assertNotEquals(list, item)
  }
}
