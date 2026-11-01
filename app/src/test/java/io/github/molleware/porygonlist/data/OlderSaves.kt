package io.github.molleware.porygonlist.data

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
