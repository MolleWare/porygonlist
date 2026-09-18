package io.github.molleware.porygonlist.data.sync

/**
 * One independently mergeable value: what it is, when it was written, and what it was written
 * against.
 *
 * Fields merge one at a time rather than whole items on purpose. If Ava ticks "Oat milk" off while
 * Hugo changes its quantity to two, both of those are right and both should survive. Merging whole
 * items would keep one edit and drop the other, and neither person would ever know.
 *
 * [basedOn] is what separates a clash from a sequence. An [Hlc] orders every edit, but an order
 * cannot tell "Hugo edited this having already seen Ava's version" from "they edited it at the same
 * time, neither having seen the other" — those want opposite handling and look identical if all you
 * have is which stamp is larger. Recording the stamp an edit was made against settles it.
 */
data class Field<T>(val value: T, val at: Hlc, val basedOn: Hlc? = null) {

  /** This value was written by someone who had already seen [other], so it simply wins. */
  fun supersedes(other: Field<T>): Boolean = basedOn != null && basedOn >= other.at

  /** Neither editor had seen the other's value. A person has to settle it. */
  fun concurrentWith(other: Field<T>): Boolean = !supersedes(other) && !other.supersedes(this)

  /** The later of the two by total order. Only meaningful once concurrency has been ruled out. */
  fun latest(other: Field<T>): Field<T> = if (at >= other.at) this else other

  /** Replaces this value with [next], recording what it was written against. */
  fun set(next: T, at: Hlc): Field<T> = Field(next, at, basedOn = this.at)
}

/**
 * Merges one field, reporting whether the result had to pick a side.
 *
 * Callers that care — a name someone typed — surface [Merged.wasConcurrent] as a conflict. Callers
 * that do not, such as a ticked checkbox, ignore it and take the value.
 */
data class Merged<T>(val field: Field<T>, val wasConcurrent: Boolean)

fun <T> merge(mine: Field<T>, theirs: Field<T>): Merged<T> =
  when {
    mine.supersedes(theirs) -> Merged(mine, wasConcurrent = false)
    theirs.supersedes(mine) -> Merged(theirs, wasConcurrent = false)
    // Concurrent: the total order still has to produce one value so that both phones agree on what
    // is on screen. Whether that silent choice is acceptable is the caller's decision, not this
    // function's — hence the flag rather than an exception.
    else -> Merged(mine.latest(theirs), wasConcurrent = mine.value != theirs.value)
  }
