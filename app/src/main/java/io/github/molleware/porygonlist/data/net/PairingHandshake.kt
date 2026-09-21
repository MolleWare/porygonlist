package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.crypto.PairingCodec
import io.github.molleware.porygonlist.data.crypto.PairingInvite
import io.github.molleware.porygonlist.data.crypto.PairingToken
import io.github.molleware.porygonlist.data.crypto.PinnedTls
import java.io.BufferedReader
import java.io.IOException
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

/**
 * The half of pairing that used to need a second camera.
 *
 * Pairing is symmetric — each phone has to end up holding the other's key — but only the first
 * leg has to travel a channel a person can vouch for. Once the scanner has read the code off the
 * screen it can authenticate the phone that showed it, and that is enough to carry its own key
 * back over the network. So one scan does what two used to.
 *
 * ```
 * she shows a code ──► he scans it ──► he has her key
 *                                        │
 *                      she has his key ◄─┘ this handshake
 * ```
 *
 * **Both ends get an answer, and they are different answers.**
 *
 * The scanner knows who it is calling, because the invite carried the key and the connection is
 * pinned to it. An impostor on the same wifi cannot be the far end, whatever address the code
 * pointed at.
 *
 * The phone being called cannot know that the same way — a phone it has never met is precisely
 * what it is waiting for. What it gets instead is the [PairingToken]: a fresh random value that
 * was only ever on its own screen, said back inside a channel already encrypted to its own key. A
 * caller that can produce it read the screen. One that merely found the open port cannot, and is
 * refused before its invite is so much as parsed.
 *
 * What remains, and is not solved by cryptography because it is not a cryptographic problem: a
 * token proves somebody *looked*, not that it was the right somebody. A photograph of the screen
 * is as good as standing in front of it. The token is minted per screen and spent once, so the
 * window is a minute and the race is visible — the wrong name appears — but the owner is still
 * the one who says yes.
 */
object PairingHandshake {

  /**
   * Long enough for a phone to answer on a local network, short enough not to sit there.
   *
   * The fallback when it lapses is the flow that already worked, so the cost of giving up early is
   * a line on screen rather than a failure.
   */
  const val TIMEOUT_MS = 4_000

  /**
   * A line cap, so a peer cannot make this phone read forever.
   *
   * An invite is a P-256 key and a name, a couple of hundred characters. This is room for a long
   * name and nothing like room for an attack.
   */
  private const val MAX_LINE = 4_096

  private const val ACCEPTED = "PLPAIRGOT1"

  private const val REFUSED = "PLPAIRNO1"

  /** What a caller sends when the code it read carried no token. Never matches a real one. */
  private const val NO_TOKEN = "-"

  /** What became of an attempt to hand a key over. */
  sealed interface Outcome {
    /** The other phone has our invite and is asking its owner about it. */
    data object Delivered : Outcome

    /**
     * Nothing was handed over, and the invite carried no address to try.
     *
     * The ordinary case for a code that arrived as a text rather than off a screen.
     */
    data object NoAddress : Outcome

    /** There was an address and it did not work out. The caller says so and offers the old way. */
    data class Failed(val reason: String) : Outcome
  }

  /**
   * Connects to the phone that showed [invite] and hands it this phone's own invite.
   *
   * [expecting] is the public key from the scanned code. It is pinned, so this will not speak to
   * anything else at that address — which is the property that makes sending a key over the
   * network acceptable at all.
   *
   * Blocking, and meant for a background dispatcher. Every failure is an [Outcome] rather than an
   * exception: not reaching the other phone is an ordinary thing that happens on a different wifi
   * or behind client isolation, and the answer to it is the manual flow, not a crash.
   */
  fun deliver(link: String, me: LocalIdentity, myName: String, expecting: ByteArray): Outcome {
    val address = PairingCodec.addressIn(link) ?: return Outcome.NoAddress
    val factory = PinnedTls.clientFactory(expecting) ?: return Outcome.Failed("no TLS on this phone")

    return try {
      factory.createSocket().use { plain ->
        val socket = plain as SSLSocket
        PinnedTls.harden(socket)
        socket.soTimeout = TIMEOUT_MS
        socket.connect(java.net.InetSocketAddress(address.host, address.port), TIMEOUT_MS)
        // Explicit, so a pinning failure surfaces here rather than on the first read.
        socket.startHandshake()

        // The token first, then who we are. Both inside the handshake, so neither is on the wire
        // in the clear — the token in particular is the one thing here worth keeping off it.
        val token = PairingCodec.tokenIn(link)?.value ?: NO_TOKEN
        socket.outputStream.write("$token\n${PairingCodec.encode(me, myName)}\n".toByteArray())
        socket.outputStream.flush()

        when (socket.inputStream.bufferedReader().readLineCapped()) {
          ACCEPTED -> Outcome.Delivered
          // The code on their screen has moved on: they closed the screen, or somebody else got
          // there first and used the token. Showing the code again mints a new one.
          REFUSED -> Outcome.Failed("their code has expired — ask them to show it again")
          else -> Outcome.Failed("the other phone did not take it")
        }
      }
    } catch (e: javax.net.ssl.SSLException) {
      // Only a *pinning* failure means somebody else answered, and it is the one worth naming.
      // Every other TLS failure is a broken connection wearing the same exception type, and
      // reporting those as an impostor sends the reader hunting for an attacker that is not
      // there — which is exactly what this said before it was corrected.
      if (e.isPinFailure()) Outcome.Failed("that address answered with a different phone's key")
      else Outcome.Failed("the secure connection to them failed")
    } catch (e: IOException) {
      Outcome.Failed("could not reach them on this network")
    }
  }

  /**
   * Reads an invite from a phone that has just scanned this one's code.
   *
   * [expecting] is the token minted when the code went on screen. A caller that cannot say it back
   * is refused and nothing is returned, so a port-scanner never reaches the owner's attention.
   *
   * Returns what the caller claims to be, or null. Nothing is stored here: having read the screen
   * earns a phone a question, not a pairing, and the caller of this puts it in front of a person.
   *
   * The acknowledgement says only "received", never "accepted". The owner has not been asked yet
   * and may be about to say no; claiming otherwise would have the far end report a pairing that
   * never happened.
   */
  fun serve(socket: SSLSocket, expecting: PairingToken): PairingInvite? =
    try {
      socket.soTimeout = TIMEOUT_MS
      val reader = socket.inputStream.bufferedReader()
      val offered = reader.readLineCapped()
      val invite = reader.readLineCapped()?.let { PairingCodec.decode(it) }

      // Constant-time, and the token is checked before the invite is looked at. A caller that did
      // not read the screen learns only that it was refused — not whether its invite parsed, and
      // not how much of the token it got right.
      val vouched =
        offered != null &&
          MessageDigest.isEqual(offered.toByteArray(Charsets.UTF_8), expecting.value.toByteArray(Charsets.UTF_8))

      val reply = if (vouched && invite != null) ACCEPTED else REFUSED
      socket.outputStream.write((reply + "\n").toByteArray())
      socket.outputStream.flush()

      if (vouched) invite else null
    } catch (e: IOException) {
      null
    }

  /**
   * True when this failure was the pin rejecting the far end, rather than the connection breaking.
   *
   * The pin is enforced by throwing `CertificateException` from the trust manager, and that is the
   * only thing in this app that throws one, so finding it anywhere in the cause chain identifies
   * the case exactly. TLS wraps it a couple of layers deep, which is why this walks rather than
   * looking at the top.
   */
  private fun Throwable.isPinFailure(): Boolean {
    var cause: Throwable? = this
    // Bounded: a cause chain can be circular, and this runs on a failure path.
    repeat(8) {
      if (cause is java.security.cert.CertificateException) return true
      cause = cause?.cause ?: return false
    }
    return false
  }

  /**
   * One line, or null.
   *
   * `readLine` on its own will read to the end of memory if the far end never sends a newline.
   * This stops at [MAX_LINE] and gives up, which is the difference between a peer that is confused
   * and a peer that can take this process down.
   */
  private fun BufferedReader.readLineCapped(): String? {
    val out = StringBuilder()
    while (out.length <= MAX_LINE) {
      val c = read()
      if (c < 0) return out.toString().ifEmpty { null }
      if (c == '\n'.code) return out.toString()
      if (c != '\r'.code) out.append(c.toChar())
    }
    return null
  }
}
