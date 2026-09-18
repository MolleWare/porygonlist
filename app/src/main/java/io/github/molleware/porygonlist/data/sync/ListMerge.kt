package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.Person

/** A part of an item that two phones can disagree about. */
enum class ItemField {
  NAME,
  QUANTITY,

  /** Whether the item is on the list at all — one removed it while the other put it back. */
  PRESENCE,
}

/** One item after merging, and whatever the two sides settled blind. */
data class ItemMerge(val item: GroceryItem, val concurrent: Set<ItemField>)

/** A list after merging, with the items that needed a coin toss. */
data class ListMerge(val list: GroceryList, val clashes: List<ItemMerge>)

/**
 * Merges two views of the same item, field by field.
 *
 * Per-field rather than whole-item on purpose: if Ava renames something while Hugo changes how
 * many, both edits are right and both survive. Taking the whole of whichever item was written last
 * would keep one and silently drop the other.
 *
 * [ItemMerge.concurrent] names the fields where neither side had seen the other. A value still
 * comes out — both phones have to agree on what is on screen — but the caller is told it was a
 * blind pick rather than a sequence, and can surface it.
 */
fun merge(mine: GroceryItem, theirs: GroceryItem): ItemMerge {
  require(mine.id == theirs.id) { "cannot merge different items: ${mine.id} and ${theirs.id}" }

  val name = merge(mine.name, theirs.name)
  val qty = merge(mine.qty, theirs.qty)
  val removed = merge(mine.removed, theirs.removed)

  // The tick is not a Field: ticking the same thing off twice is harmless, so the later stamp
  // simply wins and there is nothing to report.
  val minesTickIsNewer = mine.checkedAt >= theirs.checkedAt

  val concurrent = buildSet {
    if (name.wasConcurrent) add(ItemField.NAME)
    if (qty.wasConcurrent) add(ItemField.QUANTITY)
    if (removed.wasConcurrent) add(ItemField.PRESENCE)
  }

  return ItemMerge(
    item =
      mine.copy(
        name = name.field,
        qty = qty.field,
        removed = removed.field,
        checked = if (minesTickIsNewer) mine.checked else theirs.checked,
        checkedAt = maxOf(mine.checkedAt, theirs.checkedAt),
        // `origin` is a fact about creation and is the same on both sides. `pending` and `editing`
        // describe this phone's own situation — whether it still owes the change, and who has the
        // item open here — so neither is taken from a peer.
        pending = mine.pending,
        editing = mine.editing,
      ),
    concurrent = concurrent,
  )
}

/**
 * Merges two views of the same list.
 *
 * Items are matched by id, which is safe because an id carries the phone that minted it — two
 * phones adding milk while apart produce two different ids and therefore two items, not one
 * mangled one. That is the case the conflict card exists for, and it is deliberately *not*
 * resolved here.
 *
 * An item present on only one side is taken as it stands. That is what makes a removal a tombstone
 * rather than an absence: if it simply vanished from the sender, the receiver would read its own
 * live copy as the only truth and hand it back.
 */
fun merge(mine: GroceryList, theirs: GroceryList): ListMerge {
  val merged = LinkedHashMap<String, GroceryItem>(mine.items.size + theirs.items.size)
  val clashes = mutableListOf<ItemMerge>()

  // This phone's order is kept, with anything new from the peer appended.
  mine.items.forEach { merged[it.id.value] = it }

  theirs.items.forEach { incoming ->
    val here = merged[incoming.id.value]
    if (here == null) {
      // Arriving from elsewhere: it is not this phone's to hand over, and nobody here has it open.
      merged[incoming.id.value] = incoming.copy(pending = false, editing = false)
    } else {
      val result = merge(here, incoming)
      merged[incoming.id.value] = result.item
      if (result.concurrent.isNotEmpty()) clashes += result
    }
  }

  return ListMerge(
    list = mine.copy(items = merged.values.toList(), people = mergePeople(mine.people, theirs.people)),
    clashes = clashes,
  )
}

/**
 * Unions the people on a list, reconciling anyone who has changed phone.
 *
 * Matching is by any device someone has ever held, not just their current one. Without that, a
 * peer who replaced their handset would arrive as a second, unrelated person — and the retired id
 * would keep being waited on for delivery receipts it can never send.
 */
private fun mergePeople(mine: List<Person>, theirs: List<Person>): List<Person> {
  val people = mine.toMutableList()

  theirs.forEach { incoming ->
    val index = people.indexOfFirst { it.wasEver(incoming.device) || incoming.wasEver(it.device) }
    if (index < 0) {
      people += incoming
      return@forEach
    }

    val here = people[index]
    people[index] =
      when {
        // The peer knows about a phone this one had already retired: nothing new.
        here.formerDevices.contains(incoming.device) -> here.copy(formerDevices = here.formerDevices + incoming.formerDevices)
        // The peer has moved on and this phone had not heard yet — adopt the newer handset.
        incoming.formerDevices.contains(here.device) ->
          incoming.copy(formerDevices = incoming.formerDevices + here.formerDevices - incoming.device)
        // Same current device on both sides; just pool what each knows of their past.
        else -> here.copy(formerDevices = here.formerDevices + incoming.formerDevices - here.device)
      }
  }

  return people
}

/** The newest stamp anywhere on a list, for folding a peer's clock into this one after a merge. */
fun GroceryList.newestStamp(): Hlc? = items.map { it.touchedAt }.maxOrNull()
