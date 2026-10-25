package io.github.molleware.porygonlist.data.net

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarterHourTest {

  private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

  @Test
  fun `the next quarter hour is on the clock`() {
    assertEquals(at("2026-10-09T14:15:00Z"), nextQuarterHour(at("2026-10-09T14:07:31Z")))
    assertEquals(at("2026-10-09T15:00:00Z"), nextQuarterHour(at("2026-10-09T14:59:59.999Z")))
  }

  @Test
  fun `an alarm arming at its own moment aims for the next one, not itself`() {
    assertEquals(at("2026-10-09T14:30:00Z"), nextQuarterHour(at("2026-10-09T14:15:00Z")))
  }

  @Test
  fun `two phones a few seconds apart aim for the same moment`() {
    assertEquals(nextQuarterHour(at("2026-10-09T14:03:00Z")), nextQuarterHour(at("2026-10-09T14:03:04Z")))
  }

  @Test
  fun `the backstop job stands aside only while an allowed alarm is armed`() {
    assertTrue("the alarm carries it", quarterJobStandsAside(canAlign = true, alarmArmed = true))
    // Revoked: Android cancelled the alarm and killed the app, and said nothing (Pixel, 2026-10-21).
    assertFalse("revoked, the job is all there is", quarterJobStandsAside(canAlign = false, alarmArmed = false))
    assertFalse("revoked with the intent left over", quarterJobStandsAside(canAlign = false, alarmArmed = true))
    assertFalse("allowed but never armed: the job's window arms it", quarterJobStandsAside(canAlign = true, alarmArmed = false))
  }
}
