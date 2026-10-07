package io.github.molleware.porygonlist.data.sync

/**
 * Positions that sort as text, with room between any two of them.
 *
 * Moving an item rewrites one key, its own, to something between its new neighbours. That is what
 * lets an order sync like any other field: an index would have to renumber everything below the
 * move, and two phones each moving a different item would then fight over every row in between.
 * With a key per item, two moves of two items are two unrelated writes and both survive. Two moves
 * of the *same* item are one field written twice, and the later one wins.
 *
 * Keys are base-62 digits in ASCII order, compared as plain strings, and never end in the lowest
 * digit — which is what guarantees there is always another key between two neighbours.
 *
 * The scheme is the usual fractional-indexing midpoint. Two phones inserting at the same spot can
 * mint the same key; [GroceryList.orderedItems] breaks that tie by item id, so both agree anyway.
 */
object OrderKey {
  private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
  private const val BASE = 62

  /**
   * A key strictly between [before] and [after]. Null means unbounded on that side.
   *
   * Requires `before < after` when both are given. Equal neighbours — two phones having minted the
   * same key — have no key between them; callers re-spread the list instead. See [spread].
   */
  fun between(before: String?, after: String?): String {
    require(before == null || after == null || before < after) { "no key between $before and $after" }
    return midpoint(before.orEmpty(), after)
  }

  /** [count] keys in ascending order, for giving a whole list positions at once. */
  fun spread(count: Int): List<String> {
    val out = ArrayList<String>(count)
    var previous: String? = null
    repeat(count) {
      val next = between(previous, null)
      out += next
      previous = next
    }
    return out
  }

  private fun midpoint(a: String, b: String?): String {
    if (b != null) {
      // Skip the prefix the two share, treating a missing digit in `a` as the lowest one.
      var n = 0
      while (n < b.length && (a.getOrNull(n) ?: DIGITS[0]) == b[n]) n++
      if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.substring(n))
    }
    val low = a.firstOrNull()?.let { DIGITS.indexOf(it) } ?: 0
    val high = b?.firstOrNull()?.let { DIGITS.indexOf(it) } ?: BASE
    if (high - low > 1) return DIGITS[(low + high) / 2].toString()
    // Adjacent first digits: anything starting with b's first digit but shorter than b sorts below it.
    if (b != null && b.length > 1) return b.substring(0, 1)
    return DIGITS[low] + midpoint(a.drop(1), null)
  }
}
