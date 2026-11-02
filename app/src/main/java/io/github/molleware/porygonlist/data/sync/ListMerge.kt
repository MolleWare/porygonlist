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

/**
 * One item after merging, and whatever the two sides settled blind.
 *
 * [mine] and [theirs] are the two versions that went in, kept so that a blind pick can be put to a
 * person as the choice it was.
 */
data class ItemMerge(
  val item: GroceryItem,
  val concurrent: Set<ItemField>,
  val mine: GroceryItem = item,
  val theirs: GroceryItem = item,
)

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
    if (removed.wasConcurrent || removedWhileEdited(mine, theirs) || removedWhileEdited(theirs, mine)) add(ItemField.PRESENCE)
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
        // Which recipes it is for. Taken from the copy that has them, never left as this phone's, or
        // the names would reach nobody: a phone from before recipes sends none, which is the oldest
        // stamp and loses to any. Not worth a question either; see recipesBetween.
        recipes = recipesBetween(mine.recipes, theirs.recipes),
        // Who added it, when a merge of two lists kept that. Set once and never changed, so whichever
        // copy has it is right; one from a phone from before it simply has none, and must not wipe it.
        addedBy = mine.addedBy ?: theirs.addedBy,
        // `origin` is a fact about creation and is the same on both sides. `editing` is who has the
        // item open on this phone, so it is never taken from a peer.
        editing = mine.editing,
      ),
    concurrent = concurrent,
    mine = mine,
    theirs = theirs,
  )
}

/**
 * The recipes an item is for, after merging two copies of it.
 *
 * The newer stamp wins, as for a position. Except when neither side had seen the other — two people
 * each adding a different recipe's eggs while apart — where both lists of names are kept, the
 * newer's first: names are only ever added, so keeping both is what each of them did. The newer's
 * stamp and the same order on both phones, so the two agree on what the item says.
 */
internal fun recipesBetween(mine: Field<List<String>>, theirs: Field<List<String>>): Field<List<String>> {
  val merged = merge(mine, theirs)
  if (!merged.wasConcurrent) return merged.field
  val newer = merged.field
  val older = if (newer.at == mine.at) theirs else mine
  val extra = older.value.filterNot { name -> newer.value.any { it.equals(name, ignoreCase = true) } }
  return newer.copy(value = newer.value + extra)
}

/**
 * One side took the item off the list while the other changed it, neither having seen the other.
 *
 * Field by field this is no clash at all — the removal and the rename touch different fields — so
 * the merge would quietly delete something somebody had just edited. Seen as a person sees it, it is
 * the plainest clash there is. [remover] has the removal the other has not got; [editor] has a name
 * or quantity the remover's copy never had.
 */
private fun removedWhileEdited(remover: GroceryItem, editor: GroceryItem): Boolean =
  remover.removed.value &&
    !editor.removed.value &&
    remover.removed.at > editor.removed.at &&
    (editor.name.at > remover.name.at || editor.qty.at > remover.qty.at)

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
 * live copy as the only truth and hand it back. It is also why a partial list, which leaves out
 * everything this phone already has, merges exactly as the whole one would.
 *
 * [from] is the phone [theirs] came from, when it is known: see `mergePeople` for what it settles.
 * [held] is, for a partial [theirs], the ids of the items its sender holds but left out — the one
 * thing an absence did say, for [duplicatesBetween].
 */
fun merge(mine: GroceryList, theirs: GroceryList, from: DeviceId? = null, held: Set<ItemId> = emptySet()): ListMerge {
  val merged = LinkedHashMap<String, GroceryItem>(mine.items.size + theirs.items.size)
  val clashes = mutableListOf<ItemMerge>()

  // This phone's order is kept, with anything new from the peer appended.
  mine.items.forEach { merged[it.id.value] = it }

  theirs.items.forEach { incoming ->
    val here = merged[incoming.id.value]
    if (here == null) {
      // Arriving from elsewhere: nobody here has it open.
      merged[incoming.id.value] = incoming.copy(editing = false)
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
        people = mergePeople(mine.people, theirs.people, from),
      ),
    clashes = clashes,
    duplicates = duplicatesBetween(mine, theirs, held),
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
 *
 * "Seen by them" is every id their list holds, which for a partial list is what it sent and what it
 * [held] back. Without the second, everything of mine they had long since seen would look unseen,
 * and their second bag of rice would be asked about as though it were a clash.
 */
internal fun duplicatesBetween(mine: GroceryList, theirs: GroceryList, held: Set<ItemId> = emptySet()): List<Conflict> {
  val myIds = mine.items.mapTo(HashSet(mine.items.size)) { it.id.value }
  val theirIds = theirs.items.mapTo(HashSet(theirs.items.size + held.size)) { it.id.value }.apply { held.forEach { add(it.value) } }

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
 * [Person.avatar] and [Person.name] are settled by their stamps in the same way.
 */
private fun mergePeople(mine: List<Person>, theirs: List<Person>, from: DeviceId?): List<Person> {
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
    // Their avatar the same way: a phone from before avatars sends none, stamped at the beginning of
    // time, and so never undoes one chosen since.
    // And their name, by its stamp, with the letter that goes with it.
    val named = if (namesBetter(incoming, here, from)) incoming else here
    people[index] =
      reconciled.copy(
        removed = here.removed.latest(incoming.removed),
        avatar = here.avatar.latest(incoming.avatar),
        name = named.name,
        initial = named.initial,
        nameAt = named.nameAt,
      )
  }

  return people
}

/**
 * Whether [incoming]'s name for someone should replace [here]'s.
 *
 * A newer stamp wins. With neither stamped — a name nobody has changed since names were stamped,
 * such as the one given at pairing, or one a 0.1.0 phone sends — the person's own phone, telling us
 * about themselves, wins: nobody knows their name better. That is what corrects a name that went
 * stale before names travelled. Anyone else's unstamped copy of them changes nothing, so an old
 * name passed on can never undo a rename.
 */
private fun namesBetter(incoming: Person, here: Person, from: DeviceId?): Boolean =
  if (incoming.nameAt.wall != 0L || here.nameAt.wall != 0L) incoming.nameAt > here.nameAt
  else from != null && incoming.wasEver(from) && incoming.name.isNotBlank()

/** The newest stamp anywhere on a list, for folding a peer's clock into this one after a merge. */
fun GroceryList.newestStamp(): Hlc? = items.map { it.touchedAt }.maxOrNull()
