package io.github.molleware.porygonlist.data.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The conversation two paired phones have once a connection is open.
 *
 * Deliberately knows nothing about sockets or TLS. It reads and writes streams, which is what lets
 * the whole protocol be tested on the JVM — the thing that actually goes wrong here is sequencing
 * and framing, and neither needs a radio to get wrong.
 *
 * ```
 * caller                                   listener
 *   ── payload ──────────────────────────────►
 *                                            applies it, keeps the sender's stamp
 *   ◄────────────────────── ack + payload ───
 *   applies theirs, banks the ack
 *   ── ack ──────────────────────────────────►
 *                                            banks the ack
 * ```
 *
 * **Three legs, not two, and the third is not optional.** An ack is a peer saying "everything you
 * knew at that stamp is now here too", which is what lets tombstones be collected. The caller can
 * only learn that about its own payload on leg two, and the listener only on leg three. Dropping
 * the last leg would leave the listener unable to ever prune, because nothing would ever confirm
 * what it sent.
 *
 * **Whole payloads, so a lost exchange costs nothing.** There is no resumption and no "since when":
 * if any leg fails, both ends keep what they had and the next exchange carries the same thing. That
 * is why every failure here is simply a null rather than an error worth showing anybody.
 */
object SyncExchange {

  /**
   * A frame header, so the reader knows where a payload ends.
   *
   * A payload is many lines and may contain blank ones, so no sentinel line is safe. The length
   * prefix is the only framing that cannot be confused by its own contents.
   */
  private const val FRAME = "PLX1"

  /** An acknowledgement of a payload, carrying the stamp being confirmed. */
  private const val ACK = "PLXACK1"

  /**
   * A cap, so a peer cannot make this phone allocate without limit.
   *
   * A few dozen lists of a few dozen items is tens of kilobytes. A megabyte is room for far more
   * than anyone has and still nothing like room for an attack.
   */
  private const val MAX_FRAME = 1 shl 20

  /** What one end has to apply, and what it must bank once the other confirms. */
  data class Exchange(val theirs: SyncPayload, val ackOfMine: Hlc?)

  /**
   * The caller's half: hand over [mine], read back their payload and their ack of ours.
   *
   * Null when the conversation did not complete. Nothing is applied on a null — a half-read
   * exchange is indistinguishable from a peer that hung up, and guessing is how one end ends up
   * believing a delivery that never landed.
   */
  fun call(mine: SyncPayload, out: OutputStream, inp: InputStream): Exchange? =
    try {
      writeFrame(out, SyncCodec.encode(mine))

      val ackOfMine = readFrame(inp)?.let { ackIn(it) }
      val theirs = readFrame(inp)?.let { SyncCodec.decode(it) } ?: return null

      // Leg three. Sent before anything is applied locally, because the listener is waiting on it
      // and a slow merge here would read to them as a peer that vanished.
      writeFrame(out, ackLine(theirs.at))
      out.flush()

      Exchange(theirs = theirs, ackOfMine = ackOfMine)
    } catch (e: IOException) {
      null
    }

  /**
   * The listener's half: read their payload, answer with an ack and [mine], then read their ack.
   *
   * [mine] is a function rather than a value because what this phone owes a peer depends on who the
   * peer turns out to be, and that is only known once their payload has been read.
   */
  fun answer(inp: InputStream, out: OutputStream, mine: (DeviceId) -> SyncPayload?): Exchange? =
    try {
      val theirs = readFrame(inp)?.let { SyncCodec.decode(it) } ?: return null

      // Their stamp goes back first, so a peer that only wanted to push and leave still gets its
      // confirmation even if what we owe them is nothing.
      writeFrame(out, ackLine(theirs.at))

      val ours = mine(theirs.from)
      writeFrame(out, ours?.let { SyncCodec.encode(it) } ?: SyncCodec.encode(empty(theirs.from)))
      out.flush()

      // Null when they hung up after taking ours: they have our changes but we cannot prove it, so
      // nothing is banked and the next exchange will carry the same payload again.
      val ackOfMine = readFrame(inp)?.let { ackIn(it) }

      Exchange(theirs = theirs, ackOfMine = ackOfMine)
    } catch (e: IOException) {
      null
    }

  /**
   * A payload with nothing in it, for a peer we share no list with.
   *
   * Sent rather than closing the connection so the shape of the conversation never changes: the
   * caller always reads a payload, and "we have nothing for you" is a thing worth saying rather
   * than a silence they have to time out on. [from] is the peer's own id so the caller discards it
   * as self-addressed rather than trying to merge it.
   */
  private fun empty(from: DeviceId) =
    SyncPayload(from = from, at = Hlc(wall = 0, counter = 0, device = from), lists = emptyList())

  private fun ackLine(stamp: Hlc) = "$ACK|${stamp.encode()}"

  private fun ackIn(frame: String): Hlc? {
    val fields = frame.trim().split('|')
    if (fields.getOrNull(0) != ACK) return null
    return runCatching { Hlc.decode(fields[1]) }.getOrNull()
  }

  private fun writeFrame(out: OutputStream, body: String) {
    val bytes = body.toByteArray()
    out.write("$FRAME ${bytes.size}\n".toByteArray())
    out.write(bytes)
    out.flush()
  }

  /** One frame, or null at a clean end of stream or anything malformed. */
  private fun readFrame(inp: InputStream): String? {
    val header = readHeader(inp) ?: return null
    val parts = header.split(' ')
    if (parts.getOrNull(0) != FRAME) return null
    val size = parts.getOrNull(1)?.toIntOrNull() ?: return null
    if (size !in 0..MAX_FRAME) return null

    val body = ByteArray(size)
    var read = 0
    while (read < size) {
      val n = inp.read(body, read, size - read)
      if (n < 0) return null
      read += n
    }
    return String(body)
  }

  /** The header line, read a byte at a time so no payload bytes are swallowed by a buffer. */
  private fun readHeader(inp: InputStream): String? {
    val out = ByteArrayOutputStream()
    while (out.size() <= 64) {
      val c = inp.read()
      if (c < 0) return null
      if (c == '\n'.code) return out.toString()
      out.write(c)
    }
    return null
  }
}
