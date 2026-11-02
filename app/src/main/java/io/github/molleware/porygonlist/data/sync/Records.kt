package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.Avatars
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.device

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
      // The "0" is a retired `pending` column, still written so readers that count columns stay aligned.
      listOf(bool(item.checked), item.checkedAt.encode(), item.origin.name, "0") +
      // Last, so a reader from before reordering, which stops at the column above, still reads the rest.
      field(item.position) { it } +
      // After the position for the same reason: 0.1.0 stops there. Several names in one column, written
      // as a record of their own so a name holding a bar comes back whole.
      field(item.recipes) { if (it.isEmpty()) "" else line(it) } +
      // After the recipes, so a reader from before it, which stops there, still reads the rest. Empty
      // for the usual case: whoever added it is the phone in the id.
      listOf(item.addedBy?.value.orEmpty())

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
      // f[from + 13] is the retired `pending` column; ignored.
      // Absent from anything written before reordering, which reads as never placed.
      position =
        (if (f.size >= from + 17) parseField(f, from + 14) { it } else null) ?: Field("", Hlc(0, 0, ItemId(id).device)),
      // Absent from anything written before recipes — a 0.1.0 phone's payload, or its save — which reads
      // as for no recipe, stamped at the beginning of time: any recipe's name outranks it. A damaged
      // one reads the same way rather than costing the item.
      recipes =
        (if (f.size >= from + 20) parseField(f, from + 17) { if (it.isEmpty()) emptyList() else split(it) } else null)
          ?: Field(emptyList(), Hlc(0, 0, ItemId(id).device)),
      // Absent from anything written before it, and empty for nearly everything since: the id's own.
      addedBy = f.getOrNull(from + 20)?.takeIf { it.isNotEmpty() }?.let { DeviceId(it) },
    )
  }

  fun <T> parseField(f: List<String>, from: Int, decodeValue: (String) -> T?): Field<T>? {
    val value = decodeValue(f[from]) ?: return null
    val at = Hlc.decode(f[from + 1]) ?: return null
    val basedOn = f[from + 2].takeIf { it.isNotEmpty() }?.let { Hlc.decode(it) ?: return null }
    return Field(value, at, basedOn)
  }

  /**
   * Reads an avatar written by [field] at [from], for a person or the owner.
   *
   * Absent from anything written before avatars — a 0.1.0 phone's payload, or its save — which reads
   * as no avatar, stamped at the beginning of time by [device]: any avatar chosen anywhere outranks
   * it. A damaged one reads the same way rather than costing the record.
   */
  fun parseAvatar(f: List<String>, from: Int, device: DeviceId): Field<String> =
    (if (f.size >= from + 3) parseField(f, from) { it } else null) ?: Field(Avatars.NONE, Hlc(0, 0, device))

  /**
   * Reads a stamp at [at], such as when a name was last set. Absent from anything written before it
   * was, which reads as the beginning of time by [device], so any real stamp outranks it.
   */
  fun parseStamp(f: List<String>, at: Int, device: DeviceId): Hlc = f.getOrNull(at)?.let { Hlc.decode(it) } ?: Hlc(0, 0, device)

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
