package io.github.molleware.porygonlist.data.net

import java.time.Instant
import org.junit.Assert.assertEquals
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
}
