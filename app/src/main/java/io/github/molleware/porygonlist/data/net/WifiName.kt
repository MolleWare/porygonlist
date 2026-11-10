package io.github.molleware.porygonlist.data.net

import android.Manifest
import io.github.molleware.porygonlist.data.plainName

/**
 * The network's own name, when the phone will tell us it.
 *
 * **This is a label and nothing else.** An SSID is free for anyone to claim — a phone in a car park
 * can advertise "Home" — so it never decides anything. Identity stays with [NetworkFingerprint],
 * which is what [discoveryDecision] matches on, and the trust that matters stays with the keys. All
 * this does is save someone from having to work out which of two networks called `Network a3f91c`
 * is the one they are standing on.
 *
 * Reading it costs `ACCESS_FINE_LOCATION`, which is why the app went without it for so long and why
 * the permission is optional: declined, everything works exactly as it did, and the fingerprint's
 * short form is the name. See `docs/ARCHITECTURE.md`.
 */
object WifiName {

  /** What the framework hands back instead of a name when it will not tell us. */
  private const val UNKNOWN = "<unknown ssid>"

  /**
   * What to ask for, at the moment the name would help: precise and approximate location together.
   *
   * Only precise location reads the name, but from Android 12 an app targeting 31 or later that
   * asks for it alone is refused without a dialog — the button did nothing. Both are declared in
   * the manifest for the same reason.
   */
  val permissions: Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

  /**
   * Whether the answer to asking for [permissions] lets the name be read: precise location granted.
   * "Approximate" does not, so the offer stays, and the next tap can ask to make it precise.
   */
  fun readable(answer: Map<String, Boolean>): Boolean = answer[Manifest.permission.ACCESS_FINE_LOCATION] == true

  /**
   * Cleans up what `WifiInfo.getSSID` returns, or null if it is not a usable name.
   *
   * There is more to reject here than there looks. The value arrives wrapped in double quotes when
   * it is UTF-8 and *unquoted hex* when it is not; it is the literal string `<unknown ssid>` when
   * the permission is missing **or when location services are switched off**, which is a separate
   * condition that catches people out; and it can be blank on a hidden network. Every one of those
   * has to come back as null so the caller falls back to the fingerprint rather than putting
   * `<unknown ssid>` on screen as though it were somebody's wifi.
   */
  fun clean(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == UNKNOWN) return null

    // Hex-encoded, for an SSID that is not valid UTF-8. Showing the hex would be worse than showing
    // the fingerprint, which is at least short and meant to be opaque.
    if (value.startsWith("0x")) return null

    val unquoted = if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value.substring(1, value.length - 1)
    } else {
      value
    }

    // An SSID is any 32 bytes, line breaks included, and this one becomes a stored name.
    return plainName(unquoted).ifBlank { null }
  }
}
