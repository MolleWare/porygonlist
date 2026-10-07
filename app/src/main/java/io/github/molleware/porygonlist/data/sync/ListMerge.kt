package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.Conflict
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.WordTrie

/** A part of an item that two phones can disagree about. */
enum class ItemField {
  NAME,
  QUANTITY,

  /** Whether the item is on the list at all — one removed it while the other put it back. */
  PRESENCE,
}

/** One item after merging, and whatever the two sides settled blind. */
data class ItemMerge(val item: GroceryItem, val concurrent: Set<ItemField>)

/**
 * A list after merging, with everything that needed a person.
 *
 * [clashes] are single items whose fields were settled blind. [duplicates] are two *different*
 * items that turn out to be the same thing — both phones added rice while apart.
 */
data class ListMerge(val list: GroceryList, val clashes: List<ItemMerge>, val duplicates: List<Conflict>)

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
        // Where it sits. Two people moving the same item at once is not worth a question: the later
        // move wins, the way a tick does, and nothing is reported.
        position = mine.position.latest(theirs.position),
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
 * Items are matched by id, which is what keeps an edit from being mistaken for an addition: an id
 * carries the phone that minted it, so two phones editing the same item agree about which item they
 * are editing, whatever either has renamed it to.
 *
 * Two phones *adding* the same thing while apart is the other case, and ids cannot see it — nobody
 * types an id when they put rice on a list, so independently added rice is two ids and one
 * groceries. [duplicatesBetween] catches that by name, and it is reported rather than resolved:
 * whether two people wanting rice means one bag or two is not something this can know.
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

  // The later rename wins. Two people renaming at once settle silently by the total order; a
  // title is not worth stopping somebody to ask about, unlike two different things to buy.
  val named = if (theirs.nameAt > mine.nameAt) theirs else mine

  return ListMerge(
    list =
      mine.copy(
        name = named.name,
        nameAt = named.nameAt,
        items = merged.values.toList(),
        people = mergePeople(mine.people, theirs.people),
      ),
    clashes = clashes,
    duplicates = duplicatesBetween(mine, theirs),
  )
}

/**
 * The same thing added twice, once on each phone, without either having seen the other.
 *
 * Matching is by folded name — case and accents removed, the same folding the add field's
 * suggestions use, which is the point of having them: two phones that both write "Tomatoes" can be
 * told they meant one thing, and "tomatos" against "Tomatoes" can only ever be two.
 *
 * "Without either having seen the other" is what the id check does, and it is what stops this
 * nagging. If their rice is already on my list, my second rice is a deliberate second bag, not a
 * clash. It also means a pair is reported on the one merge where the two first meet: afterwards
 * each list holds both ids and the condition cannot hold again.
 *
 * Tombstones are skipped. A removal is not an addition, and pairing one with a live item would
 * offer to merge something that is deliberately gone.
 */
internal fun duplicatesBetween(mine: GroceryList, theirs: GroceryList): List<Conflict> {
  val myIds = mine.items.mapTo(HashSet(mine.items.size)) { it.id.value }
  val theirIds = theirs.items.mapTo(HashSet(theirs.items.size)) { it.id.value }

  val unseenByThem = mine.liveItems.filterNot { it.id.value in theirIds }
  if (unseenByThem.isEmpty()) return emptyList()

  val unseenByMe = theirs.liveItems.filterNot { it.id.value in myIds }
  if (unseenByMe.isEmpty()) return emptyList()

  val byName = unseenByMe.groupBy { WordTrie.fold(it.name.value) }
  // One pairing per name, so three phones adding rice do not produce a combinatorial pile of cards.
  val paired = HashSet<String>()

  return unseenByThem.mapNotNull { ours ->
    val key = WordTrie.fold(ours.name.value)
    if (!paired.add(key)) return@mapNotNull null
    byName[key]?.firstOrNull()?.let { Conflict(yours = ours, theirs = it) }
  }
}

/**
 * Unions the people on a list, reconciling anyone who has changed phone.
 *
 * Matching is by any device someone has ever held, not just their current one. Without that, a
 * peer who replaced their handset would arrive as a second, unrelated person — and the retired id
 * would keep being waited on for delivery receipts it can never send.
 *
 * Membership itself is last-writer-wins on [Person.removed]. A plain union would mean leaving a
 * list never stuck: the other phone's copy still lists you, so the next exchange would put you
 * back. Comparing the stamps makes the later decision the one that holds, whichever end made it.
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
    val reconciled =
      when {
        // The peer knows about a phone this one had already retired: nothing new.
        here.formerDevices.contains(incoming.device) -> here.copy(formerDevices = here.formerDevices + incoming.formerDevices)
        // The peer has moved on and this phone had not heard yet — adopt the newer handset.
        incoming.formerDevices.contains(here.device) ->
          incoming.copy(formerDevices = incoming.formerDevices + here.formerDevices - incoming.device)
        // Same current device on both sides; just pool what each knows of their past.
        else -> here.copy(formerDevices = here.formerDevices + incoming.formerDevices - here.device)
      }

    // Whichever end decided most recently owns whether they are still on the list. Taken after the
    // handset reconciliation above so that adopting a newer phone cannot quietly drop a leaving.
    people[index] = reconciled.copy(removed = here.removed.latest(incoming.removed))
  }

  return people
}

/** The newest stamp anywhere on a list, for folding a peer's clock into this one after a merge. */
fun GroceryList.newestStamp(): Hlc? = items.map { it.touchedAt }.maxOrNull()
