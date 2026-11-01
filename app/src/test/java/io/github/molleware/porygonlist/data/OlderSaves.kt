package io.github.molleware.porygonlist.data

import java.util.zip.CRC32

/**
 * A save as 0.1.0 (`ef88918`) wrote it: version 11 and sealed, but its `list` records stop at
 * `arrivedFrom`, before a kept copy noted who it had been shared with, and its `meta` and `person`
 * records stop before the avatar and the stamp of a rename.
 *
 * Re-sealed with a fresh checksum, so it reads as the undamaged file that build wrote rather than as
 * a damaged new one. The writer was unchanged from `ef88918` until those fields, so this is its output.
 */
fun as010(encoded: String): String {
  val body =
    encoded
      .lineSequence()
      .filterNot { it.startsWith("end|") || it.isEmpty() }
      .map {
        when {
          it.startsWith("list|") -> it.substringBeforeLast('|')
          it.startsWith("meta|") -> firstColumns(it, META_COLUMNS_010)
          it.startsWith("person|") -> firstColumns(it, PERSON_COLUMNS_010)
          else -> it
        }
      }
      .joinToString("\n", postfix = "\n")
  val checksum = CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value.toString(16)
  return body + "end|$checksum\n"
}

/**
 * A sync payload as 0.1.0 (`ef88918`) sends it: `PLSYNC2`, with `person` records that stop at the
 * stamp of their leaving, before the avatar and the name's stamp. Everything else that build sent
 * is unchanged since.
 */
fun payloadAs010(encoded: String): String =
  encoded.lineSequence().joinToString("\n") { if (it.startsWith("person|")) firstColumns(it, PERSON_COLUMNS_010) else it }

/** `meta` up to the owner's name, and `person` up to the stamp of their leaving, as 0.1.0 wrote them. */
private const val META_COLUMNS_010 = 7
private const val PERSON_COLUMNS_010 = 8

// A bar inside a field is written escaped, so every bar in a line is a column boundary.
private fun firstColumns(line: String, count: Int): String = line.split('|').take(count).joinToString("|")

/**
 * A save as the build before the checksum wrote it: version 10, no closing `end` record.
 *
 * Tests that stand in for a file from an older build edit its lines, and a version 11 file edited
 * that way is refused as damaged. Version 10 and earlier carry no checksum, so they are read line by
 * line as they always were.
 */
fun asVersion10(encoded: String): String =
  encoded
    .lineSequence()
    .filterNot { it.startsWith("end|") }
    .joinToString("\n")
    .replaceFirst("PLSTATE11", "PLSTATE10")
