package io.github.molleware.porygonlist.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the encoder.
 *
 * These verify the frame — that the symbol is the right size, that the patterns a reader looks for
 * are where it looks for them. They do *not* verify the payload: nothing here proves the data and
 * error-correction codewords are right, because proving that needs a decoder, and the whole reason
 * this encoder is hand-written is that we did not want to carry one. See the note in
 * `docs/ARCHITECTURE.md`; a test-only reference decoder is the way to close that gap.
 */
class QrCodeTest {

  private fun encode(text: String) = requireNotNull(QrCode.encode(text)) { "did not fit: ${text.length} chars" }

  @Test
  fun `symbol is four times the version plus seventeen`() {
    // Version 1 holds 14 bytes at level M, so 14 characters must still be the smallest symbol.
    assertEquals(21, encode("A".repeat(14)).size)
    // One more forces version 2.
    assertEquals(25, encode("A".repeat(15)).size)
  }

  @Test
  fun `versions grow only as far as the table goes`() {
    // Version 12 at level M holds 287 bytes. One past that has no version left to use.
    assertNotNull(QrCode.encode("A".repeat(287)))
    assertNull(QrCode.encode("A".repeat(288)))
  }

  @Test
  fun `finder patterns sit in three corners and not the fourth`() {
    val qr = encode(SAMPLE_INVITE)

    listOf(0 to 0, qr.size - 7 to 0, 0 to qr.size - 7).forEach { (cornerX, cornerY) ->
      for (dy in 0..6) {
        for (dx in 0..6) {
          val ring = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
          assertEquals(
            "finder at ($cornerX,$cornerY) module ($dx,$dy)",
            ring != 2,
            qr[cornerX + dx, cornerY + dy],
          )
        }
      }
    }
  }

  @Test
  fun `separators around the finders are light`() {
    val qr = encode(SAMPLE_INVITE)
    for (i in 0..7) {
      assertFalse("below top-left finder", qr[i, 7])
      assertFalse("right of top-left finder", qr[7, i])
      assertFalse("below top-right finder", qr[qr.size - 1 - i, 7])
      assertFalse("right of bottom-left finder", qr[7, qr.size - 1 - i])
    }
  }

  @Test
  fun `timing patterns alternate along row and column six`() {
    val qr = encode(SAMPLE_INVITE)
    for (i in 8 until qr.size - 8) {
      assertEquals("row six at $i", i % 2 == 0, qr[i, 6])
      assertEquals("column six at $i", i % 2 == 0, qr[6, i])
    }
  }

  @Test
  fun `the dark module is dark`() {
    val qr = encode(SAMPLE_INVITE)
    assertTrue(qr[8, qr.size - 8])
  }

  @Test
  fun `masking leaves the symbol close to an even split of dark and light`() {
    // Not a spec requirement in itself, but the mask is chosen to make it true, so a badly broken
    // mask step shows up here as a lopsided symbol.
    val qr = encode(SAMPLE_INVITE)
    var dark = 0
    for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) dark++

    val percent = dark * 100 / (qr.size * qr.size)
    assertTrue("dark modules were $percent%", percent in 40..60)
  }

  @Test
  fun `a real invite fits well inside the table`() {
    val identity = InMemoryIdentityStore().identity()
    val invite = PairingCodec.encode(identity, "Hugo")

    val qr = requireNotNull(QrCode.encode(invite))
    // A P-256 key and a short name land in version 7 (45 modules). The assertion is deliberately
    // loose: what matters is that an ordinary invite is nowhere near the version-12 ceiling.
    assertTrue("invite needed a ${qr.size}-module symbol", qr.size <= 57)
  }

  @Test
  fun `an empty string still produces a valid symbol`() {
    val qr = encode("")
    assertEquals(21, qr.size)
    assertTrue(qr[8, qr.size - 8])
  }

  private companion object {
    // Shaped like a real invite: prefix, base64url key, base64url name.
    const val SAMPLE_INVITE =
      "PLPAIR1.MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfake_key_material_here_for_testing_only_padding_x." +
        "SHVnbw"
  }
}
