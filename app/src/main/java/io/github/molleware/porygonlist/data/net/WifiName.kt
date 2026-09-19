package io.github.molleware.porygonlist.data.net

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

    return unquoted.trim().ifBlank { null }
  }
}
