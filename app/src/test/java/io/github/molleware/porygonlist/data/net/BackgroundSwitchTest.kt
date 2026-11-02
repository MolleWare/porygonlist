package io.github.molleware.porygonlist.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background sync is one switch: on the quarter hour by exact alarm, or not at all. On needs alarms,
 * and revoking them turns it off.
 */
class BackgroundSwitchTest {

  @Test
  fun `never touched, it follows the alarms, for a new install and a phone from 0_1_0 alike`() {
    assertEquals(BackgroundSwitch.ON, backgroundSwitch(chosen = null, alarmsAllowed = true))
    assertEquals(BackgroundSwitch.OFF, backgroundSwitch(chosen = null, alarmsAllowed = false))
  }

  @Test
  fun `turned on, it is on while alarms are allowed`() {
    assertEquals(BackgroundSwitch.ON, backgroundSwitch(chosen = true, alarmsAllowed = true))
  }

  @Test
  fun `turned on and then alarms revoked, it reads as off and says why`() {
    assertEquals(BackgroundSwitch.ALARMS_TURNED_OFF, backgroundSwitch(chosen = true, alarmsAllowed = false))
  }

  @Test
  fun `turned off, it is off whatever the alarms`() {
    assertEquals(BackgroundSwitch.OFF, backgroundSwitch(chosen = false, alarmsAllowed = true))
    assertEquals(BackgroundSwitch.OFF, backgroundSwitch(chosen = false, alarmsAllowed = false))
  }

  @Test
  fun `the default is written down as on while alarms are allowed, so a revoke later can say so`() {
    assertEquals(true, settledChoice(chosen = null, alarmsAllowed = true))
    assertEquals(BackgroundSwitch.ALARMS_TURNED_OFF, backgroundSwitch(settledChoice(null, alarmsAllowed = true), alarmsAllowed = false))
  }

  @Test
  fun `without alarms the default stays unmade, so allowing them later turns it on`() {
    assertNull(settledChoice(chosen = null, alarmsAllowed = false))
    assertEquals(BackgroundSwitch.ON, backgroundSwitch(settledChoice(null, alarmsAllowed = false), alarmsAllowed = true))
  }

  @Test
  fun `a choice made is never changed by settling`() {
    for (allowed in listOf(true, false)) {
      assertEquals(true, settledChoice(chosen = true, alarmsAllowed = allowed))
      assertEquals(false, settledChoice(chosen = false, alarmsAllowed = allowed))
    }
  }

  @Test
  fun `a tap turns it off when on, on with alarms, and asks for alarms without`() {
    assertEquals(SwitchTap.TURN_OFF, switchTap(BackgroundSwitch.ON, alarmsAllowed = true))
    assertEquals(SwitchTap.TURN_ON, switchTap(BackgroundSwitch.OFF, alarmsAllowed = true))
    assertEquals(SwitchTap.ASK_FOR_ALARMS, switchTap(BackgroundSwitch.OFF, alarmsAllowed = false))
    assertEquals(SwitchTap.ASK_FOR_ALARMS, switchTap(BackgroundSwitch.ALARMS_TURNED_OFF, alarmsAllowed = false))
  }

  @Test
  fun `the Lists screen asks while it is off, unless it was turned off in Settings`() {
    assertTrue("off for want of alarms", asksForBackgroundSync(chosen = null, alarmsAllowed = false))
    assertTrue("alarms revoked", asksForBackgroundSync(chosen = true, alarmsAllowed = false))
    assertFalse("turned off on purpose", asksForBackgroundSync(chosen = false, alarmsAllowed = true))
    assertFalse("turned off on purpose, alarms since revoked", asksForBackgroundSync(chosen = false, alarmsAllowed = false))
    assertFalse("on", asksForBackgroundSync(chosen = null, alarmsAllowed = true))
    assertFalse("on", asksForBackgroundSync(chosen = true, alarmsAllowed = true))
  }

  @Test
  fun `the quarter-hour jobs 0_1_0 booked are retired, and the one-off window is not`() {
    // 0x5059 and 0x5062, as `dumpsys jobscheduler` shows them.
    assertTrue(isRetiredJob(20569))
    assertTrue(isRetiredJob(20578))
    // 0x5060: the window straight away on joining an approved wifi, still booked by this build.
    assertFalse(isRetiredJob(20576))
  }
}
