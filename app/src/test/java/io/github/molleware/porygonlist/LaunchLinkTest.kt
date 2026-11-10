package io.github.molleware.porygonlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the link that opened the app is acted on.
 *
 * Found in review, 2026-11-10: the activity read its intent on every creation, so turning the
 * phone, changing the font size, or coming back after Android had ended the process threw away
 * where you were and opened pairing again on a link already used — "Ava's phone is already
 * paired." — with a socket and a fresh token behind it.
 */
class LaunchLinkTest {

  @Test
  fun `a link that opened the app is acted on`() {
    assertTrue(actsOnLaunchLink(restored = false, fromHistory = false))
  }

  @Test
  fun `the same link is not acted on again when the activity is recreated`() {
    // A rotation, a font size change, or coming back after the process was ended.
    assertFalse(actsOnLaunchLink(restored = true, fromHistory = false))
  }

  @Test
  fun `nor when the app is opened again from recents`() {
    // Android hands back the intent the task was started with, link and all.
    assertFalse(actsOnLaunchLink(restored = false, fromHistory = true))
  }
}
