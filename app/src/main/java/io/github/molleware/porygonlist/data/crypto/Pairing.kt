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
 * Where a phone showing an invite can be reached, while it is showing it.
 *
 * Not part of the invite and never stored. A pairing screen is up for a minute on one wifi; the
 * address is true for exactly that long, which is why it rides in the link rather than in the code
 * — see [PairingCodec.link].
 */
data class PeerAddress(val host: String, val port: Int)

/**
 * A one-time proof that someone actually looked at the screen.
 *
 * The problem it solves is the one thing pinning cannot. A scanner can be sure who it is calling,
 * because the invite carried the key it pins. The phone *receiving* that call has no such luxury:
 * the phone introducing itself is by definition one it has never met, so a connection alone says
 * nothing about whether the caller read the code or merely found the port.
 *
 * So the code carries a fresh random value, and the handshake has to say it back. Only something
 * that read the screen can, and it is said inside a channel already encrypted to the key from that
 * same screen — so it is never exposed on the wire to anyone watching.
 *
 * **This is the one secret in the system, and it is deliberately the shortest-lived thing in it.**
 * Minted when the pairing screen opens, gone when it closes, and good for one handshake. It never
 * touches disk, never appears in the bare code, and so never travels in a text message — which is
 * what keeps "a pairing code is safe to send" true, because the code in a message still is one.
 */
@JvmInline
value class PairingToken(val value: String) {
  companion object {
    /**
     * 128 bits from the system's secure source.
     *
     * Far more than a value that lives for a minute and admits one guess needs, which is the right
     * direction to overshoot in: the cost is twenty characters of QR capacity.
     */
    fun mint(): PairingToken {
      val bytes = ByteArray(16)
      java.security.SecureRandom().nextBytes(bytes)
      return PairingToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }
  }
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

  /** The link parameter carrying [PeerAddress]. Outside the code, so the code never changes. */
  private const val ADDRESS_PARAM = "a"

  /** The link parameter carrying [PairingToken]. Outside the code for the same reason, and one more. */
  private const val TOKEN_PARAM = "t"

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
   *
   * [at] is where this phone is listening right now, and it is what turns one scan into a pairing
   * instead of two. The scanner reads the code, connects straight back, and hands over its own key
   * — so the phone that showed the code never has to scan anything.
   *
   * It is a **hint, and only in the link**. Three consequences, all wanted:
   *
   * - The bare code is unchanged, so a code sent as a text is the same string it always was. An
   *   address in a message would be stale by the time it was read, and wrong in a way nobody could
   *   see.
   * - It carries no secret, so it is still safe on a screen. Knowing where a phone listens is not
   *   permission to pair with it: the phone that showed the code still asks its owner, because a
   *   connection from an address is not proof of who is on it. See [PeerAddress] and the handshake.
   * - A reader that does not know the parameter drops it, which is what [codeIn] already did.
   */
  fun link(
    identity: LocalIdentity,
    displayName: String,
    at: PeerAddress? = null,
    token: PairingToken? = null,
  ): String =
    buildString {
      append(LINK_PREFIX).append(encode(identity, displayName))
      if (at != null) append('&').append(ADDRESS_PARAM).append('=').append(at.host).append(':').append(at.port)
      if (token != null) append('&').append(TOKEN_PARAM).append('=').append(token.value)
    }

  /**
   * The address hint in a link, when there is one this app is willing to act on.
   *
   * Only literal IPv4 on a private or link-local range. The point is not tidiness: this address
   * arrives from a QR code, and a QR code is something a stranger can print on a poster. Without
   * this check, scanning one would make the phone open a connection to whatever host the poster
   * named — an arbitrary address on the internet, chosen by whoever put it there. Restricting it
   * to the ranges a phone's own wifi actually hands out keeps a scan incapable of reaching off the
   * local link, which is the only place a pairing partner can be.
   *
   * A name is refused rather than resolved, for the same reason: `pair.example.com` is an
   * off-network lookup dressed as a local address.
   *
   * Null for anything else, and the caller falls back to the manual flow. There is no error to
   * report — an invite without a usable address is simply the older kind.
   */
  fun addressIn(text: String): PeerAddress? {
    val raw = paramIn(text, ADDRESS_PARAM) ?: return null

    val host = raw.substringBeforeLast(':', missingDelimiterValue = "")
    val port = raw.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    if (!isLocalIpv4(host)) return null

    return PeerAddress(host, port)
  }

  /**
   * The one-time token in a link, if it has one.
   *
   * Only ever read from a link that was scanned or tapped. A code arriving as text has none, and
   * that is the whole distinction: the durable form carries no secret and gets the manual flow,
   * the live form carries one and gets the handshake.
   */
  fun tokenIn(text: String): PairingToken? = paramIn(text, TOKEN_PARAM)?.let { PairingToken(it) }

  /**
   * One parameter out of a pairing link.
   *
   * Hand-parsed rather than going through `android.net.Uri`, like [codeIn] above, so this file
   * stays testable on the JVM and the shape being read stays fixed and ours. `c` is skipped by
   * construction: it is the first parameter and everything here reads the ones after it.
   */
  private fun paramIn(text: String, name: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(LINK_PREFIX, ignoreCase = true)) return null
    return trimmed
      .substring(LINK_PREFIX.length)
      .split('&')
      .drop(1)
      .firstOrNull { it.startsWith("$name=") }
      ?.substringAfter('=')
      ?.takeIf { it.isNotEmpty() }
  }

  /**
   * True for a dotted-quad on a range a local network actually uses.
   *
   * Loopback is allowed alongside the private ranges. It is not reachable from another phone, so
   * it grants nothing, and it is what lets the handshake be exercised end to end on one device.
   */
  private fun isLocalIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    // Rejects "01.2.3.4" and the like: a leading zero is read as octal by some resolvers and as
    // decimal by others, which is a disagreement worth never having.
    if (parts.any { it.length > 1 && it.startsWith("0") }) return false

    val (a, b) = octets
    return when {
      a == 10 -> true
      a == 127 -> true
      a == 172 && b in 16..31 -> true
      a == 192 && b == 168 -> true
      // Link-local, which is what two phones fall back to with no DHCP between them.
      a == 169 && b == 254 -> true
      else -> false
    }
  }

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
