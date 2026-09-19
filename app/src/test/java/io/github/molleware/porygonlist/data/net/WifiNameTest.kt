package io.github.molleware.porygonlist.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What `WifiInfo.getSSID` actually hands back, which is more varied than "the network's name".
 *
 * Every case that is not a real name has to come back null, because the caller's fallback is the
 * fingerprint's short form — and the failure this guards against is putting `<unknown ssid>` on
 * screen in the place a person expects to read their own wifi.
 */
class WifiNameTest {

  @Test
  fun `a normal name comes back unquoted`() {
    assertEquals("Kingfisher", WifiName.clean("\"Kingfisher\""))
  }

  @Test
  fun `an unquoted name is taken as it is`() {
    assertEquals("Kingfisher", WifiName.clean("Kingfisher"))
  }

  @Test
  fun `the framework's refusal is not a name`() {
    // This is what arrives when the permission is missing, and — the one that catches people out —
    // when the permission is granted but location services are switched off.
    assertNull(WifiName.clean("<unknown ssid>"))
    assertNull(WifiName.clean("  <unknown ssid>  "))
  }

  @Test
  fun `nothing is not a name`() {
    assertNull(WifiName.clean(null))
    assertNull(WifiName.clean(""))
    assertNull(WifiName.clean("   "))
    assertNull(WifiName.clean("\"\""))
    assertNull(WifiName.clean("\"   \""))
  }

  @Test
  fun `a hex ssid is refused rather than shown`() {
    // Not valid UTF-8, so the framework hex-encodes it. "0x48656c6c6f" on screen is worse than the
    // fingerprint, which is at least short and obviously an identifier.
    assertNull(WifiName.clean("0x48656c6c6f"))
  }

  @Test
  fun `quotes inside a name survive`() {
    assertEquals("Ava's \"wifi\"", WifiName.clean("\"Ava's \"wifi\"\""))
  }

  @Test
  fun `a name that is only quotes-worth of spaces does not become blank`() {
    assertEquals("a", WifiName.clean("\" a \""))
  }

  @Test
  fun `emoji and accents are left alone`() {
    assertEquals("Café 📶", WifiName.clean("\"Café 📶\""))
  }
}
