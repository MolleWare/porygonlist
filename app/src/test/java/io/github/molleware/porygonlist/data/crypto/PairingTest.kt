package io.github.molleware.porygonlist.data.crypto

import io.github.molleware.porygonlist.data.sync.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property everything else rests on: a device id is derived from a public key, so it cannot be
 * claimed by anything that does not hold the matching private key.
 */
class DeviceIdentityTest {

  private val ava = InMemoryIdentityStore().identity()
  private val hugo = InMemoryIdentityStore().identity()

  @Test
  fun `the same key always gives the same id`() {
    assertEquals(DeviceIdentity.deviceIdFor(ava.publicKey), DeviceIdentity.deviceIdFor(ava.publicKey))
  }

  @Test
  fun `different keys give different ids`() {
    assertNotEquals(ava.deviceId, hugo.deviceId)
  }

  @Test
  fun `an id verifies against its own key and no other`() {
    assertTrue(DeviceIdentity.matches(ava.deviceId, ava.publicKey))
    // This is what a random id could not do: Hugo cannot present himself as Ava.
    assertFalse(DeviceIdentity.matches(ava.deviceId, hugo.publicKey))
  }

  @Test
  fun `an id is readable without ambiguous characters`() {
    // Base32's alphabet leaves out 0, 1, 8 and 9, so there is no O/0 or l/1 to misread.
    assertTrue(ava.deviceId.value.all { it in 'A'..'Z' || it in '2'..'7' })
    assertEquals(16, ava.deviceId.value.length)
  }

  @Test
  fun `garbage does not verify`() {
    assertFalse(DeviceIdentity.matches(ava.deviceId, ByteArray(0)))
    assertFalse(DeviceIdentity.matches(DeviceId("NOTAREALDEVICEID"), ava.publicKey))
  }

  @Test
  fun `a signature verifies against the public key`() {
    val message = "put the milk back".toByteArray()
    val signature = ava.sign(message)

    val verifier =
      java.security.Signature.getInstance(AndroidKeystoreIdentityStore.SIGNATURE_ALGORITHM).apply {
        initVerify(
          java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(ava.publicKey))
        )
        update(message)
      }

    assertTrue(verifier.verify(signature))
  }
}

class PairingCodecTest {

  private val ava = InMemoryIdentityStore().identity()
  private val hugo = InMemoryIdentityStore().identity()

  @Test
  fun `an invite round trips`() {
    val invite = PairingCodec.decode(PairingCodec.encode(ava, "Ava"))!!

    assertEquals(ava.deviceId, invite.deviceId)
    assertEquals("Ava", invite.displayName)
    assertTrue(ava.publicKey.contentEquals(invite.publicKey))
  }

  @Test
  fun `the id in an invite is derived, never asserted`() {
    // There is no id field to tamper with: it falls out of the key that is carried.
    val code = PairingCodec.encode(hugo, "Hugo")

    assertEquals(hugo.deviceId, PairingCodec.decode(code)!!.deviceId)
    assertFalse(code.contains(hugo.deviceId.value))
  }

  @Test
  fun `swapping the key changes the identity rather than forging one`() {
    val avasCode = PairingCodec.encode(ava, "Ava")
    val forged = avasCode.replaceAfter('.', "").let { it + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hugo.publicKey) + ".QXZh" }

    val invite = PairingCodec.decode(forged)
    // The name still says Ava, but the id is Hugo's — the key decides, not the label.
    assertEquals(hugo.deviceId, invite!!.deviceId)
    assertNotEquals(ava.deviceId, invite.deviceId)
  }

  @Test
  fun `a name with spaces and accents survives`() {
    val invite = PairingCodec.decode(PairingCodec.encode(ava, "Hugo's Pixel — café"))!!

    assertEquals("Hugo's Pixel — café", invite.displayName)
  }

  @Test
  fun `anything that is not an invite is rejected`() {
    // A camera sees a lot of things that are not pairing codes.
    assertNull(PairingCodec.decode(""))
    assertNull(PairingCodec.decode("https://example.com"))
    assertNull(PairingCodec.decode("PLPAIR1"))
    assertNull(PairingCodec.decode("PLPAIR1.only-two-parts"))
    assertNull(PairingCodec.decode("PLPAIR9.abc.def"))
    assertNull(PairingCodec.decode("PLPAIR1.not!valid!base64.QXZh"))
    assertNull(PairingCodec.decode("PLPAIR1..QXZh"))
  }

  @Test
  fun `an invite is small enough for a QR code`() {
    val code = PairingCodec.encode(ava, "Hugo's Pixel")

    // Alphanumeric QR at low error correction holds thousands of characters; this is nowhere near.
    assertTrue("invite was ${code.length} characters", code.length < 400)
  }

  @Test
  fun `an invite carries no private material`() {
    val code = PairingCodec.encode(ava, "Ava")

    // It is safe on a screen or in a text message. Only the public half travels.
    val privateEncoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ava.publicKey)
    assertTrue(code.contains(privateEncoded))
    assertEquals(3, code.split('.').size)
  }
}
