package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.Origin

/**
 * The line-based record format shared by the state file and the sync payload.
 *
 * One definition rather than two so the two cannot drift: an item written to disk and an item put
 * on the wire are the same bytes, and a change to what an item carries is a change in one place.
 *
 * Every record is `type|field|field|…`. Fields are escaped so a list called "Bread | Milk" survives.
 */
internal object Records {

  fun line(vararg fields: String): String = fields.joinToString("|") { escape(it) }

  fun line(fields: List<String>): String = fields.joinToString("|") { escape(it) }

  fun split(text: String): List<String> = text.split('|').map { unescape(it) }

  /** The wire form of one item, used by list items and the two sides of a conflict alike. */
  fun itemFields(item: GroceryItem): List<String> =
    listOf(item.id.value) +
      field(item.name) { it } +
      field(item.qty) { it.toString() } +
      field(item.removed) { bool(it) } +
      listOf(bool(item.checked), item.checkedAt.encode(), item.origin.name, bool(item.pending))

  /** A field is three columns: the value, the stamp it was written at, and what it was written against. */
  fun <T> field(field: Field<T>, encodeValue: (T) -> String): List<String> =
    listOf(encodeValue(field.value), field.at.encode(), field.basedOn?.encode().orEmpty())

  /** Reads [itemFields] back, starting at [from]. Null if any part is missing or malformed. */
  fun parseItem(f: List<String>, from: Int): GroceryItem? {
    if (f.size < from + 14) return null
    val id = f[from]
    if (!id.contains(':')) return null
    val name = parseField(f, from + 1) { it } ?: return null
    val qty = parseField(f, from + 4) { it.toIntOrNull() } ?: return null
    val removed = parseField(f, from + 7) { it == "1" } ?: return null
    return GroceryItem(
      id = ItemId(id),
      name = name,
      qty = qty,
      removed = removed,
      checked = f[from + 10] == "1",
      checkedAt = Hlc.decode(f[from + 11]) ?: return null,
      origin = runCatching { Origin.valueOf(f[from + 12]) }.getOrNull() ?: return null,
      pending = f[from + 13] == "1",
    )
  }

  fun <T> parseField(f: List<String>, from: Int, decodeValue: (String) -> T?): Field<T>? {
    val value = decodeValue(f[from]) ?: return null
    val at = Hlc.decode(f[from + 1]) ?: return null
    val basedOn = f[from + 2].takeIf { it.isNotEmpty() }?.let { Hlc.decode(it) ?: return null }
    return Field(value, at, basedOn)
  }

  fun bool(value: Boolean): String = if (value) "1" else "0"

  private fun escape(s: String) = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

  private fun unescape(s: String): String {
    if (!s.contains('\\')) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        when (s[i + 1]) {
          '\\' -> out.append('\\')
          'p' -> out.append('|')
          'n' -> out.append('\n')
          else -> out.append(s[i + 1])
        }
        i += 2
      } else {
        out.append(c)
        i++
      }
    }
    return out.toString()
  }
}
