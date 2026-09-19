package io.github.molleware.porygonlist.data.crypto

/**
 * A QR code, as a square grid of dark and light modules.
 *
 * Written out here rather than pulled in, for the same reason [Base32] is: the thing we need to
 * produce is one fixed shape — a pairing invite, a couple of hundred ASCII characters, byte mode,
 * one error-correction level. A general encoder is a much larger tool than that, and the part of it
 * we would use is the part below.
 *
 * Nothing in this file touches Android, so the whole encoder is testable on the JVM. Drawing is
 * somebody else's job; this hands back a grid and stops.
 *
 * Deliberately encode-only. Reading a QR code back is a different and far larger problem — finding
 * the symbol in a camera frame, correcting its perspective, and running Reed–Solomon *correction*
 * rather than generation — and hand-writing that would be a bad trade.
 */
class QrCode internal constructor(
  /** Width and height in modules, not pixels. The quiet zone is not included. */
  val size: Int,
  private val modules: BooleanArray,
) {

  /** True where the module is dark. [x] runs left to right, [y] top to bottom. */
  operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

  companion object {

    /**
     * Encodes [text] at error-correction level M, or returns null if it will not fit.
     *
     * Null rather than an exception because the caller has somewhere to go: the pairing screen
     * already shows the invite as text, so a code too long to draw degrades to the text it was
     * drawn from instead of taking the screen down. Level M corrects ~15% of the symbol, which is
     * the usual default and leaves margin for glare and a fingerprint on the other phone's screen.
     */
    fun encode(text: String): QrCode? {
      // Invites are base64url and ASCII punctuation, so this is a straight byte-per-character map.
      // ISO-8859-1 is what byte mode is defined against; anything outside it would still encode,
      // and would still come back out of a reader unchanged, as bytes.
      val data = text.toByteArray(Charsets.ISO_8859_1)
      val version = smallestVersionFor(data.size) ?: return null
      val block = BLOCKS[version - 1]

      val codewords = interleave(toCodewords(data, version, block.dataCodewords), block)
      return draw(version, codewords)
    }
  }
}

// ── The version tables ──────────────────────────────────────────────────────
//
// Versions 1–12 only. Version 12 holds 287 bytes at level M, and an invite is a P-256 public key
// plus a name — around 140 characters, comfortably inside version 7. The cutoff is where the table
// stops being worth carrying, not where the format does; a longer name than version 12 can hold
// falls back to text, which is the same code and equally valid.

/**
 * How one version splits its data into Reed–Solomon blocks.
 *
 * Larger symbols are not protected as one long block: the data is divided, each block gets its own
 * error-correction codewords, and the results are interleaved so that a smudge which destroys a run
 * of the symbol lands a few bytes on each block rather than wiping one out entirely.
 *
 * Two group sizes because the data rarely divides evenly. [shortBlocks] blocks hold
 * [shortLength] codewords and [longBlocks] hold one more each.
 */
private class BlockLayout(
  val ecPerBlock: Int,
  val shortBlocks: Int,
  val shortLength: Int,
  val longBlocks: Int,
) {
  val dataCodewords: Int
    get() = shortBlocks * shortLength + longBlocks * (shortLength + 1)
}

// Level M, versions 1 through 12, straight from the specification's table.
private val BLOCKS =
  arrayOf(
    BlockLayout(10, 1, 16, 0), // 1
    BlockLayout(16, 1, 28, 0), // 2
    BlockLayout(26, 1, 44, 0), // 3
    BlockLayout(18, 2, 32, 0), // 4
    BlockLayout(24, 2, 43, 0), // 5
    BlockLayout(16, 4, 27, 0), // 6
    BlockLayout(18, 4, 31, 0), // 7
    BlockLayout(22, 2, 38, 2), // 8
    BlockLayout(22, 3, 36, 2), // 9
    BlockLayout(26, 4, 43, 1), // 10
    BlockLayout(30, 1, 50, 4), // 11
    BlockLayout(22, 6, 36, 2), // 12
  )

/** Centre coordinates of the alignment patterns, per version. Version 1 has none. */
private val ALIGNMENT =
  arrayOf(
    intArrayOf(),
    intArrayOf(6, 18),
    intArrayOf(6, 22),
    intArrayOf(6, 26),
    intArrayOf(6, 30),
    intArrayOf(6, 34),
    intArrayOf(6, 22, 38),
    intArrayOf(6, 24, 42),
    intArrayOf(6, 26, 46),
    intArrayOf(6, 28, 50),
    intArrayOf(6, 30, 54),
    intArrayOf(6, 32, 58),
  )

/** Versions 1–9 count characters in 8 bits; 10 and up need 16. */
private fun countBits(version: Int): Int = if (version < 10) 8 else 16

/** Bytes of payload a version holds, after the mode indicator and character count. */
private fun capacityFor(version: Int): Int =
  BLOCKS[version - 1].dataCodewords - 2 - (countBits(version) - 8) / 8

private fun smallestVersionFor(byteCount: Int): Int? = (1..BLOCKS.size).firstOrNull { byteCount <= capacityFor(it) }

// ── Data into codewords ─────────────────────────────────────────────────────

/**
 * Byte-mode segment, padded out to exactly fill the version's data capacity.
 *
 * The padding is not arbitrary: a terminator, zeros up to the next byte boundary, then 0xEC and
 * 0x11 alternating. Those two bytes are specified, and they alternate rather than repeat because a
 * long run of one value produces large uniform areas that the masking step then has to work harder
 * to break up.
 */
private fun toCodewords(data: ByteArray, version: Int, capacity: Int): ByteArray {
  val bits = BitBuffer()
  bits.append(0b0100, 4) // byte mode
  bits.append(data.size, countBits(version))
  data.forEach { bits.append(it.toInt() and 0xFF, 8) }

  bits.append(0, minOf(4, capacity * 8 - bits.length)) // terminator, truncated if it will not fit
  bits.append(0, (8 - bits.length % 8) % 8) // up to the byte boundary

  val out = bits.toBytes().copyOf(capacity)
  var pad = bits.length / 8
  var alternate = true
  while (pad < capacity) {
    out[pad++] = if (alternate) 0xEC.toByte() else 0x11.toByte()
    alternate = !alternate
  }
  return out
}

/** Splits into blocks, adds error correction to each, and interleaves the lot. */
private fun interleave(data: ByteArray, layout: BlockLayout): ByteArray {
  val dataBlocks = ArrayList<ByteArray>(layout.shortBlocks + layout.longBlocks)
  val ecBlocks = ArrayList<ByteArray>(dataBlocks.size)

  var offset = 0
  repeat(layout.shortBlocks + layout.longBlocks) { index ->
    val length = layout.shortLength + if (index >= layout.shortBlocks) 1 else 0
    val block = data.copyOfRange(offset, offset + length)
    offset += length
    dataBlocks += block
    ecBlocks += errorCorrectionFor(block, layout.ecPerBlock)
  }

  val out = ByteArray(data.size + ecBlocks.size * layout.ecPerBlock)
  var at = 0
  // Column-wise across the blocks: byte 0 of every block, then byte 1, and so on. The short blocks
  // simply have nothing to contribute on the final pass.
  for (i in 0 until layout.shortLength + 1) {
    dataBlocks.forEach { if (i < it.size) out[at++] = it[i] }
  }
  for (i in 0 until layout.ecPerBlock) {
    ecBlocks.forEach { out[at++] = it[i] }
  }
  return out
}

// ── Reed–Solomon ────────────────────────────────────────────────────────────

/**
 * Arithmetic in GF(256), the field QR codes do error correction in.
 *
 * Multiplication is done by adding logarithms, which is why the tables exist: every non-zero value
 * is some power of 2 under the field's own multiplication, so a log and an antilog table turn a
 * multiply into an add and two lookups.
 */
private object Galois {
  private const val PRIMITIVE = 0x11D // x^8 + x^4 + x^3 + x^2 + 1

  val exp = IntArray(512)
  private val log = IntArray(256)

  init {
    var x = 1
    for (i in 0 until 255) {
      exp[i] = x
      log[x] = i
      x = x shl 1
      if (x and 0x100 != 0) x = x xor PRIMITIVE
    }
    // Doubled so that exp[log[a] + log[b]] never needs a modulo.
    for (i in 255 until 512) exp[i] = exp[i - 255]
  }

  fun multiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
}

/** The generator polynomial whose roots are α⁰ … α^([degree]-1), highest power first. */
private fun generatorPolynomial(degree: Int): IntArray {
  var poly = intArrayOf(1)
  for (i in 0 until degree) {
    // Multiply by (x + α^i).
    val next = IntArray(poly.size + 1)
    for (j in poly.indices) {
      next[j] = next[j] xor poly[j]
      next[j + 1] = next[j + 1] xor Galois.multiply(poly[j], Galois.exp[i])
    }
    poly = next
  }
  return poly
}

/** The remainder of [block] divided by the generator polynomial — the block's EC codewords. */
private fun errorCorrectionFor(block: ByteArray, count: Int): ByteArray {
  val generator = generatorPolynomial(count)
  val remainder = IntArray(count)

  for (byte in block) {
    val factor = (byte.toInt() and 0xFF) xor remainder[0]
    for (i in 0 until count - 1) remainder[i] = remainder[i + 1]
    remainder[count - 1] = 0
    for (i in 0 until count) remainder[i] = remainder[i] xor Galois.multiply(generator[i + 1], factor)
  }
  return ByteArray(count) { remainder[it].toByte() }
}

// ── Laying out the symbol ───────────────────────────────────────────────────

/**
 * Places the fixed patterns, then the data, then picks a mask.
 *
 * The order matters: the function patterns have to be in place before the data is written, because
 * the data snakes around whatever is already occupied rather than being placed at fixed offsets.
 */
private fun draw(version: Int, codewords: ByteArray): QrCode {
  val size = version * 4 + 17
  val modules = BooleanArray(size * size)
  // Which modules are structure rather than payload. Masking must leave these alone, and the data
  // placement has to step over them.
  val reserved = BooleanArray(size * size)

  fun set(x: Int, y: Int, dark: Boolean, structural: Boolean = true) {
    modules[y * size + x] = dark
    if (structural) reserved[y * size + x] = true
  }

  // Finder patterns, with the light separator around each. Three corners, never the fourth — that
  // asymmetry is how a reader works out the symbol's rotation.
  for ((cornerX, cornerY) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
    for (dy in -1..7) {
      for (dx in -1..7) {
        val x = cornerX + dx
        val y = cornerY + dy
        if (x !in 0 until size || y !in 0 until size) continue
        val ring = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
        set(x, y, ring != 2 && ring <= 3)
      }
    }
  }

  // Timing patterns: alternating modules along row 6 and column 6, giving a reader a ruler to
  // measure the module pitch against.
  for (i in 8 until size - 8) {
    set(6, i, i % 2 == 0)
    set(i, 6, i % 2 == 0)
  }

  // Alignment patterns, at every intersection of the version's coordinates except the three that
  // would sit on top of a finder pattern.
  val centres = ALIGNMENT[version - 1]
  for (cy in centres) {
    for (cx in centres) {
      val onFinder =
        (cx == 6 && cy == 6) || (cx == 6 && cy == size - 7) || (cx == size - 7 && cy == 6)
      if (onFinder) continue
      for (dy in -2..2) {
        for (dx in -2..2) {
          set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
      }
    }
  }

  // The dark module. Always set, always here, no meaning beyond being a fixed reference.
  set(8, size - 8, true)

  // Reserve the format areas before any data goes down; the values are written after masking,
  // because the value depends on which mask was chosen.
  for (i in 0..8) {
    if (i != 6) set(8, i, false)
    if (i != 6) set(i, 8, false)
  }
  for (i in 0 until 8) {
    set(size - 1 - i, 8, false)
    if (size - 1 - i != size - 8) set(8, size - 1 - i, false)
  }

  if (version >= 7) {
    val bits = versionBits(version)
    for (i in 0 until 18) {
      val dark = (bits shr i) and 1 == 1
      set(i / 3, size - 11 + i % 3, dark)
      set(size - 11 + i % 3, i / 3, dark)
    }
  }

  placeData(size, codewords, modules, reserved)

  val mask = chooseMask(size, modules, reserved)
  applyMask(size, mask, modules, reserved)
  writeFormat(size, mask, ::set)

  return QrCode(size, modules)
}

/**
 * Writes the codewords into the symbol in the order a reader walks it.
 *
 * Two modules wide, bottom-right to top-left, alternating direction each column pair — the snake
 * exists so that a scratch across the symbol hits a contiguous run of *codewords*, which the
 * interleaving has already spread across blocks.
 */
private fun placeData(size: Int, codewords: ByteArray, modules: BooleanArray, reserved: BooleanArray) {
  var bit = 0
  var upward = true
  var x = size - 1

  while (x > 0) {
    if (x == 6) x = 5 // the vertical timing pattern is not a data column
    for (i in 0 until size) {
      val y = if (upward) size - 1 - i else i
      for (dx in 0..1) {
        val cx = x - dx
        if (reserved[y * size + cx]) continue
        // Past the end of the data are the format's remainder bits, which are zero.
        val dark = bit < codewords.size * 8 && (codewords[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1
        modules[y * size + cx] = dark
        bit++
      }
    }
    upward = !upward
    x -= 2
  }
}

/** The eight mask patterns, as the specification numbers them. */
private fun masked(pattern: Int, x: Int, y: Int): Boolean =
  when (pattern) {
    0 -> (x + y) % 2 == 0
    1 -> y % 2 == 0
    2 -> x % 3 == 0
    3 -> (x + y) % 3 == 0
    4 -> (y / 2 + x / 3) % 2 == 0
    5 -> (x * y) % 2 + (x * y) % 3 == 0
    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
    else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
  }

private fun applyMask(size: Int, pattern: Int, modules: BooleanArray, reserved: BooleanArray) {
  for (y in 0 until size) {
    for (x in 0 until size) {
      if (reserved[y * size + x]) continue
      if (masked(pattern, x, y)) modules[y * size + x] = !modules[y * size + x]
    }
  }
}

/**
 * Tries all eight masks and keeps the one that scores lowest.
 *
 * Masking exists because the payload is arbitrary bytes, and some byte sequences would otherwise
 * draw large blank areas or shapes that look like finder patterns — both of which make the symbol
 * harder or impossible to read. The penalty rules below are the specification's proxy for "looks
 * awkward to a scanner"; the point is not that the score means anything on its own, only that the
 * lowest of eight is reliably readable.
 */
private fun chooseMask(size: Int, modules: BooleanArray, reserved: BooleanArray): Int {
  var best = 0
  var bestPenalty = Int.MAX_VALUE

  for (pattern in 0 until 8) {
    val candidate = modules.copyOf()
    applyMask(size, pattern, candidate, reserved)
    val penalty = penaltyFor(size, candidate)
    if (penalty < bestPenalty) {
      bestPenalty = penalty
      best = pattern
    }
  }
  return best
}

private fun penaltyFor(size: Int, modules: BooleanArray): Int {
  fun dark(x: Int, y: Int) = modules[y * size + x]
  var penalty = 0

  // Rule 1: runs of five or more of the same shade, in either direction.
  for (i in 0 until size) {
    var rowRun = 1
    var colRun = 1
    for (j in 1 until size) {
      rowRun = if (dark(j, i) == dark(j - 1, i)) rowRun + 1 else 1
      if (rowRun == 5) penalty += 3 else if (rowRun > 5) penalty += 1
      colRun = if (dark(i, j) == dark(i, j - 1)) colRun + 1 else 1
      if (colRun == 5) penalty += 3 else if (colRun > 5) penalty += 1
    }
  }

  // Rule 2: every 2×2 block of one shade.
  for (y in 0 until size - 1) {
    for (x in 0 until size - 1) {
      val shade = dark(x, y)
      if (shade == dark(x + 1, y) && shade == dark(x, y + 1) && shade == dark(x + 1, y + 1)) penalty += 3
    }
  }

  // Rule 3: anything resembling a finder pattern's 1:1:3:1:1 ratio with a light run beside it.
  // These are the shapes that would send a reader looking for a corner that is not there.
  val pattern = booleanArrayOf(true, false, true, true, true, false, true)
  for (i in 0 until size) {
    for (j in 0..size - 7) {
      fun matches(read: (Int) -> Boolean): Boolean = pattern.indices.all { read(j + it) == pattern[it] }
      fun clear(read: (Int) -> Boolean, from: Int): Boolean =
        (from until from + 4).all { it !in 0 until size || !read(it) }

      if (matches { dark(it, i) } && (clear({ dark(it, i) }, j - 4) || clear({ dark(it, i) }, j + 7))) penalty += 40
      if (matches { dark(i, it) } && (clear({ dark(i, it) }, j - 4) || clear({ dark(i, it) }, j + 7))) penalty += 40
    }
  }

  // Rule 4: drifting away from an even split of dark and light.
  val darkCount = modules.count { it }
  val percent = darkCount * 100 / modules.size
  penalty += 10 * maxOf(kotlin.math.abs(percent - 50) / 5, 0)

  return penalty
}

// ── Format and version information ──────────────────────────────────────────

/**
 * The 15-bit format field: error-correction level, mask, and a BCH code over both.
 *
 * It gets its own error correction, and is written twice in different corners, because losing it
 * would make the rest unreadable however well the data itself survived.
 */
private fun writeFormat(size: Int, mask: Int, set: (Int, Int, Boolean, Boolean) -> Unit) {
  val data = (0b00 shl 3) or mask // level M is 0b00
  var remainder = data
  repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
  // The XOR stops an all-zero format field, which would otherwise be a large blank area.
  val bits = ((data shl 10) or remainder) xor 0x5412

  fun bit(i: Int) = (bits shr i) and 1 == 1

  for (i in 0..5) set(8, i, bit(i), true)
  set(8, 7, bit(6), true)
  set(8, 8, bit(7), true)
  set(7, 8, bit(8), true)
  for (i in 9..14) set(14 - i, 8, bit(i), true)

  for (i in 0..7) set(size - 1 - i, 8, bit(i), true)
  for (i in 8..14) set(8, size - 15 + i, bit(i), true)
}

/** The 18-bit version field, present from version 7, likewise BCH-protected. */
private fun versionBits(version: Int): Int {
  var remainder = version
  repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
  return (version shl 12) or remainder
}

// ── A small bit buffer ──────────────────────────────────────────────────────

private class BitBuffer {
  private val bytes = ArrayList<Byte>()
  var length = 0
    private set

  fun append(value: Int, bits: Int) {
    for (i in bits - 1 downTo 0) {
      if (length % 8 == 0) bytes.add(0)
      if ((value shr i) and 1 == 1) {
        val index = length / 8
        bytes[index] = (bytes[index].toInt() or (1 shl (7 - length % 8))).toByte()
      }
      length++
    }
  }

  fun toBytes(): ByteArray = bytes.toByteArray()
}
