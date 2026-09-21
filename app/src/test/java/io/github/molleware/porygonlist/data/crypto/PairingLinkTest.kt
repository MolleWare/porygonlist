package io.github.molleware.porygonlist.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live parts of an invite: where to call back, and the token that proves somebody looked.
 *
 * The rule under all of it is that the *code* never changes. A link can gain parameters; the
 * string a person sends as a text stays the thing it always was, and a phone that knows nothing
 * about addresses or tokens still reads it.
 */
class PairingLinkTest {

  private val ava = InMemoryIdentityStore().identity()

  private val here = PeerAddress("192.168.1.42", 8931)

  @Test
  fun `the code inside a link is the same with or without the live parts`() {
    val bare = PairingCodec.link(ava, "Ava")
    val live = PairingCodec.link(ava, "Ava", at = here, token = PairingToken("abc"))

    // The property that keeps a texted code working: everything added is after the code, and
    // codeIn already stopped at the first further '&'.
    assertEquals(PairingCodec.decode(bare), PairingCodec.decode(live))
  }

  @Test
  fun `an address survives the round trip`() {
    val link = PairingCodec.link(ava, "Ava", at = here)

    assertEquals(here, PairingCodec.addressIn(link))
  }

  @Test
  fun `a token survives the round trip`() {
    val link = PairingCodec.link(ava, "Ava", at = here, token = PairingToken("s3cret"))

    assertEquals(PairingToken("s3cret"), PairingCodec.tokenIn(link))
  }

  @Test
  fun `an invite with no live parts has neither`() {
    val link = PairingCodec.link(ava, "Ava")

    assertNull(PairingCodec.addressIn(link))
    assertNull(PairingCodec.tokenIn(link))
  }

  @Test
  fun `a bare code is not a link and carries nothing live`() {
    val code = PairingCodec.encode(ava, "Ava")

    assertNull(PairingCodec.addressIn(code))
    assertNull(PairingCodec.tokenIn(code))
  }

  @Test
  fun `an address off the local network is refused`() {
    // The one that matters. A QR code is something a stranger can print on a poster, and without
    // this check scanning one would point the phone at whatever host the poster named.
    listOf("8.8.8.8", "1.1.1.1", "203.0.113.5", "172.32.0.1", "11.0.0.1").forEach { host ->
      val link = PairingCodec.link(ava, "Ava", at = PeerAddress(host, 8931))
      assertNull("$host should not be dialled", PairingCodec.addressIn(link))
    }
  }

  @Test
  fun `the ranges a home network actually hands out are accepted`() {
    listOf("192.168.0.1", "10.1.2.3", "172.16.0.9", "172.31.255.254", "169.254.3.4", "127.0.0.1").forEach { host ->
      val link = PairingCodec.link(ava, "Ava", at = PeerAddress(host, 8931))
      assertEquals(host, PairingCodec.addressIn(link)?.host)
    }
  }

  @Test
  fun `a hostname is refused rather than resolved`() {
    // Resolving it would be an off-network lookup dressed up as a local address.
    val link = "porygonlist://pair?c=${PairingCodec.encode(ava, "Ava")}&a=pair.example.com:8931"

    assertNull(PairingCodec.addressIn(link))
  }

  @Test
  fun `an octal-looking octet is refused`() {
    // "010.1.2.3" is 8.1.2.3 to a resolver that reads it as octal and 10.1.2.3 to one that does
    // not. A disagreement about which host is meant is worth never having.
    val link = "porygonlist://pair?c=${PairingCodec.encode(ava, "Ava")}&a=010.1.2.3:8931"

    assertNull(PairingCodec.addressIn(link))
  }

  @Test
  fun `a nonsense port is refused`() {
    listOf("192.168.1.1:0", "192.168.1.1:70000", "192.168.1.1:-1", "192.168.1.1", "192.168.1.1:http").forEach { raw ->
      val link = "porygonlist://pair?c=${PairingCodec.encode(ava, "Ava")}&a=$raw"
      assertNull("$raw should not parse", PairingCodec.addressIn(link))
    }
  }

  @Test
  fun `parameters this app does not define are ignored`() {
    val link = PairingCodec.link(ava, "Ava", at = here) + "&utm_source=whatever"

    assertEquals(here, PairingCodec.addressIn(link))
    assertEquals("Ava", PairingCodec.decode(link)?.displayName)
  }

  @Test
  fun `tokens do not repeat`() {
    val minted = List(200) { PairingToken.mint().value }

    assertEquals(minted.size, minted.toSet().size)
    assertNotEquals(minted[0], minted[1])
  }

  @Test
  fun `a token is long enough not to be guessed in the minute it lives`() {
    // 16 bytes, base64url without padding. Shorter than this and a value that admits one online
    // guess would still be fine — but the cost of overshooting is twenty characters of QR.
    assertTrue(PairingToken.mint().value.length >= 20)
  }
}
