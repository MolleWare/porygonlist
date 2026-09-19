package io.github.molleware.porygonlist.data.crypto

import io.github.molleware.porygonlist.data.sync.DeviceId
import java.util.Base64

/**
 * What one phone shows another in order to be trusted by it.
 *
 * Note what is *not* here: the device id. It is derived from [publicKey] when the invite is read,
 * so there is nothing to assert and nothing to lie about. A phone cannot present itself as someone
 * else's id without holding that id's private key — which is exactly the property a random id could
 * not give.
 *
 * Nor is there a secret. This is a public key; it is safe on a screen, in a photo, or in a text
 * message. What makes pairing trustworthy is not that the payload is confidential but that it
 * travelled a channel the owner can vouch for — a phone held up in front of them.
 */
data class PairingInvite(val deviceId: DeviceId, val publicKey: ByteArray, val displayName: String) {

  // ByteArray needs these written out; the data class default compares by reference.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PairingInvite) return false
    return deviceId == other.deviceId &&
      publicKey.contentEquals(other.publicKey) &&
      displayName == other.displayName
  }

  override fun hashCode(): Int = 31 * (31 * deviceId.hashCode() + publicKey.contentHashCode()) + displayName.hashCode()
}

/**
 * The text form of an invite, for a QR code or a message.
 *
 * `PLPAIR1.<base64url public key>.<base64url name>` — a couple of hundred characters, well inside
 * what a QR code holds comfortably, and printable enough to survive being sent as a text.
 */
object PairingCodec {

  private const val PREFIX = "PLPAIR1"
  private const val SEPARATOR = '.'

  /** The scheme the manifest registers, so a camera app can hand a scanned code straight over. */
  const val LINK_SCHEME = "porygonlist"

  private const val LINK_PREFIX = "$LINK_SCHEME://pair?c="

  private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
  private val decoder: Base64.Decoder = Base64.getUrlDecoder()

  fun encode(identity: LocalIdentity, displayName: String): String =
    listOf(
        PREFIX,
        encoder.encodeToString(identity.publicKey),
        encoder.encodeToString(displayName.toByteArray()),
      )
      .joinToString(SEPARATOR.toString())

  /**
   * The same invite as something a phone will act on when it is pointed at or tapped.
   *
   * This is what the QR code carries and what the copy button puts on the clipboard, because it
   * works in both directions at once: an ordinary camera app reading the QR offers to open it, and
   * the same string in a message is a link the recipient taps. The bare [encode] form stays
   * readable by [decode] so that codes already sent as plain text keep working.
   *
   * Opening one of these never pairs anything. It carries the code to the pairing screen and stops
   * there, in front of the same "is this someone new?" question a pasted code faces — otherwise any
   * app or web page able to fire an intent could add itself as a trusted peer.
   */
  fun link(identity: LocalIdentity, displayName: String): String = LINK_PREFIX + encode(identity, displayName)

  /**
   * Reads an invite, deriving the device id from the key it carries.
   *
   * Returns null for anything malformed rather than throwing: this parses whatever a camera or a
   * paste buffer happened to contain, and most of that is not an invite.
   */
  fun decode(text: String): PairingInvite? {
    val parts = codeIn(text.trim()).split(SEPARATOR)
    if (parts.size != 3 || parts[0] != PREFIX) return null

    return runCatching {
        val publicKey = decoder.decode(parts[1])
        if (publicKey.isEmpty()) return null
        val name = String(decoder.decode(parts[2]))
        PairingInvite(deviceId = DeviceIdentity.deviceIdFor(publicKey), publicKey = publicKey, displayName = name)
      }
      .getOrNull()
  }

  /**
   * The code inside a link, or [text] unchanged when it is already a bare code.
   *
   * Written out rather than going through `android.net.Uri` so that this file stays testable on the
   * JVM, and because the shape being parsed is fixed and ours. Anything after a further `&` is a
   * parameter this app does not define and has no business reading.
   */
  private fun codeIn(text: String): String =
    if (text.startsWith(LINK_PREFIX, ignoreCase = true)) {
      text.substring(LINK_PREFIX.length).substringBefore('&')
    } else {
      text
    }
}
