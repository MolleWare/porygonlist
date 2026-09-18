package io.github.molleware.porygonlist.data.crypto

import io.github.molleware.porygonlist.data.sync.DeviceId
import java.security.MessageDigest

/**
 * Turns a public key into the id this phone is known by.
 *
 * The id is *derived* from the key, never chosen. That is the whole point: a device cannot claim to
 * be `avaphone01` unless it holds the private key whose public half hashes to `avaphone01`. A random
 * id — which is what this replaced — is only a label, and a peer can put any label it likes on the
 * wire.
 *
 * Truncated to 80 bits. Forging an id means finding a second key whose fingerprint collides on
 * those bits, which is 2^80 work; the full key is what actually authenticates a connection, so this
 * only has to be strong enough that two honest phones never clash and a dishonest one cannot cheaply
 * impersonate a known id.
 */
object DeviceIdentity {

  private const val ID_BITS = 80

  /** [publicKey] is the X.509 SubjectPublicKeyInfo encoding, as `PublicKey.getEncoded` returns. */
  fun deviceIdFor(publicKey: ByteArray): DeviceId {
    require(publicKey.isNotEmpty()) { "a public key is required to derive a device id" }
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
    return DeviceId(Base32.encode(digest, ID_BITS))
  }

  /** True when [publicKey] really is the key behind [deviceId]. */
  fun matches(deviceId: DeviceId, publicKey: ByteArray): Boolean =
    runCatching { deviceIdFor(publicKey) == deviceId }.getOrDefault(false)
}

/**
 * RFC 4648 base32, without padding.
 *
 * Chosen over hex because the alphabet happens to leave out `0`, `1`, `8` and `9`, so there is no
 * `O`/`0` or `l`/`1` confusion if a person ever has to read an id aloud or compare two on screen.
 */
internal object Base32 {
  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

  /** Encodes the first [bits] bits of [bytes]; [bits] must be a multiple of five. */
  fun encode(bytes: ByteArray, bits: Int): String {
    require(bits % 5 == 0) { "base32 emits five bits per character" }
    require(bytes.size * 8 >= bits) { "not enough input for $bits bits" }

    val out = StringBuilder(bits / 5)
    var buffer = 0
    var bitsInBuffer = 0
    var index = 0

    while (out.length < bits / 5) {
      if (bitsInBuffer < 5) {
        buffer = (buffer shl 8) or (bytes[index++].toInt() and 0xFF)
        bitsInBuffer += 8
      }
      bitsInBuffer -= 5
      out.append(ALPHABET[(buffer ushr bitsInBuffer) and 0x1F])
    }
    return out.toString()
  }
}
