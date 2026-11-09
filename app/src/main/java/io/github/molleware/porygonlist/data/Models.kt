package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.OrderKey

/** The device that minted an id, read back out of it. Ids are `deviceId:counter`. */
val ItemId.device: DeviceId
  get() = DeviceId(value.substringBefore(':'))

/** How an item arrived on a list. Drives the sub-label under its name. */
enum class Origin {
  /** Typed in on a phone, or picked from Staples/suggestions. */
  LOCAL,

  /** Pasted in from a shared message. */
  IMPORTED,

  /** The result of merging a conflict — created on behalf of two people at once. */
  MERGED,
}

/** The coloured dot on a list card. */
enum class ListAccent {
  ACCENT,
  ACCENT_2,
  NEUTRAL,
}

/**
 * Someone who holds a copy of a list.
 *
 * A person outlives their phone. Identity is tied to an installation, so replacing a handset means
 * a new [DeviceId] — and the old one lingers in the history of everything it ever authored. Keeping
 * [formerDevices] lets that history stay attributed to the right human, while [device] alone is
 * what anyone still waits on.
 */
data class Person(
  val device: DeviceId,
  val name: String,
  val initial: String,
  /** Phones this person used to hold. Never waited on; only used to attribute what they wrote. */
  val formerDevices: Set<DeviceId> = emptySet(),
  /**
   * Whether they have left the list, and when that was decided.
   *
   * Stamped for the same reason an item's removal is: the two phones merge by unioning their
   * people, so without a stamp a peer whose copy still lists someone would add them straight back
   * on the next exchange and leaving would never stick. With one, the later decision wins.
   *
   * A left person stays on the list as a tombstone rather than vanishing, so the news can reach
   * everyone. The default stamp is the beginning of time, so any real decision outranks it.
   */
  val removed: Field<Boolean> = Field(false, Hlc(0, 0, device)),
  /**
   * The avatar they chose, by its [Avatars] id, or [Avatars.NONE] for their initial.
   *
   * Stamped, unlike the name, because not every phone sends it: one from before avatars leaves it
   * out, and that reads as the beginning of time, so it can never undo a choice another phone has
   * already passed on.
   */
  val avatar: Field<String> = Field(Avatars.NONE, Hlc(0, 0, device)),
  /**
   * When [name] was last set by its owner, so a rename reaches everyone else on the list.
   *
   * Kept beside the name, as a list's own name keeps its stamp, rather than wrapping it in a [Field]:
   * only the owner ever renames themselves, so there is never a clash to detect, only an order. A
   * phone from before this leaves it out, which reads as the beginning of time — its copy of an old
   * name can never undo a rename. [initial] always comes with the name it was taken from.
   */
  val nameAt: Hlc = Hlc(0, 0, device),
  /**
   * When this phone first held [removed] as it is now, for a leaving or removal it heard of from
   * another phone — as for an item; see [GroceryItem.removalHeardAt]. Kept in the save, never sent.
   */
  val removalHeardAt: Hlc? = null,
) {
  /** From when this phone held their leaving, by its own clock; see [GroceryItem.removalKnownAt]. */
  val removalKnownAt: Hlc
    get() = removalHeardAt?.let { maxOf(it, removed.at) } ?: removed.at

  fun wasEver(candidate: DeviceId): Boolean = candidate == device || candidate in formerDevices

  /** On the list now, as opposed to remembered only so their leaving can be passed on. */
  val present: Boolean
    get() = !removed.value
}

/**
 * The letter in someone's avatar.
 *
 * Derived rather than asked for: one field on first run is enough, and a letter that disagrees with
 * the name it stands for is only ever a mistake.
 */
fun initialOf(name: String): String = name.trim().firstOrNull()?.uppercase().orEmpty()

/**
 * One line on a list.
 *
 * [name] and [qty] are [Field]s rather than plain values: those are the two things a person types,
 * and the two where a silent overwrite would throw away real work. If Ava renames an item while
 * Hugo changes how many, both edits are right and both survive. [checked] is deliberately not a
 * field — ticking the same thing off twice is harmless, so last writer wins is the honest model.
 */
data class GroceryItem(
  /** `deviceId:counter`, so the creating phone is recoverable and two phones can never collide. */
  val id: ItemId,
  val name: Field<String>,
  val qty: Field<Int>,
  val checked: Boolean = false,
  /** When the tick last changed, for ordering against another phone's tick. */
  val checkedAt: Hlc,
  val origin: Origin = Origin.LOCAL,
  /**
   * Whether the item has been removed.
   *
   * A tombstone rather than a deletion: dropping the row outright is invisible to the other phone,
   * which would hand the item straight back on the next handover. It is kept until every peer has
   * confirmed receiving it — see [GroceryList.pruneDelivered]. A [Field] so that a removal and a
   * re-add of the same item merge like any other edit.
   */
  val removed: Field<Boolean>,
  /** Someone else has this item open right now. Live presence, never written to disk. */
  val editing: Boolean = false,
  /**
   * Where it sits in the list: an [io.github.molleware.porygonlist.data.sync.OrderKey], shared by
   * everyone on the list. Empty means never placed — an item from before every add was given one —
   * and those keep the order they were stored in.
   */
  val position: Field<String> = Field("", Hlc(0, 0, id.device)),
  /**
   * The recipes it was added for, by name: "for Lasagne & Pancakes". Empty for anything put on the
   * list by hand.
   *
   * Names rather than recipes, because a recipe stays on the phone that has it and the others on the
   * list only ever see what it was called. Stamped, because not every phone sends it: one from before
   * recipes leaves it out, and that reads as the beginning of time, so its copy of the item can never
   * wipe the names off.
   */
  val recipes: Field<List<String>> = Field(emptyList(), Hlc(0, 0, id.device)),
  /**
   * Who added it, where that is not the phone that minted [id]. Null for nearly everything.
   *
   * Set only on an item a merge of two lists brought across: it has a new id from the phone that
   * merged, because the old one still names the original on the other list, but it is still the
   * errand of whoever put it there first. A fact about creation, not an edit, so not stamped — it
   * never changes once set. A phone from before it leaves it out, and reads the merger as the adder.
   */
  val addedBy: DeviceId? = null,
  /**
   * When this phone first held [removed] as it is now, by this phone's own clock, for a removal it
   * heard of from another phone. Null for one made here, where the removal's own stamp already is
   * this phone's clock. This phone's own business, like [editing]: kept in the save, never sent.
   * See [removalKnownAt].
   */
  val removalHeardAt: Hlc? = null,
) {
  /**
   * The point in this phone's own clock from which it held this removal: what a peer's receipt has
   * to reach before the removal can be counted as delivered to that peer.
   *
   * Not simply [removed]'s stamp. A receipt is this phone's clock when it put a payload together,
   * and a removal heard of from a third phone carries that phone's stamp, which can be older than a
   * receipt for a payload put together before this phone had heard. Counted by its own stamp, it
   * was collected the moment it arrived, and the peer behind that receipt never heard of it.
   */
  val removalKnownAt: Hlc
    get() = removalHeardAt?.let { maxOf(it, removed.at) } ?: removed.at

  /** The phone that first added this item: [addedBy] when a merge kept it, otherwise the id's own. */
  val createdBy: DeviceId
    get() = addedBy ?: id.device

  /** The newest stamp across everything mutable on this item. */
  val touchedAt: Hlc
    get() = maxOf(name.at, qty.at, checkedAt, removed.at, position.at, recipes.at)

  /**
   * The same item, also for [recipe] as of [at]. Already for it — under any capitalisation — it is
   * left exactly as it was, so the same recipe used twice adds to the count but not to the names.
   */
  fun forRecipe(recipe: String, at: Hlc): GroceryItem =
    if (recipes.value.any { it.equals(recipe, ignoreCase = true) }) this
    else copy(recipes = recipes.set(recipes.value + recipe, at))

  /**
   * The newest change to the item itself. Position is left out: a tick can place every item in the
   * list, and that must not make whoever ticked the author of all of them.
   */
  private val editedAt: Hlc
    get() = maxOf(name.at, qty.at, checkedAt, removed.at)

  /** Whoever made the most recent change, which is not always the creator. */
  val lastWriter: DeviceId
    get() = editedAt.device

  /** Wall-clock reading of the last change, for the human-readable sub-label. */
  val at: Long
    get() = editedAt.wall

  /** "Sourdough", or "Oat milk ×2" once there is more than one. */
  val label: String
    get() = if (qty.value > 1) "${name.value} ×${qty.value}" else name.value
}

data class GroceryList(
  /**
   * Globally unique, because a list crosses phones: `deviceId:counter`, minted by
   * [io.github.molleware.porygonlist.data.sync.IdFactory].
   */
  val id: ListId,
  val name: String,
  val accent: ListAccent,
  val items: List<GroceryItem>,
  val people: List<Person>,
  /**
   * When [name] was last set, so a merge can tell whose rename is newer. Kept beside the name rather
   * than wrapping it in a [Field] because only the merge cares. The default loses to any rename.
   */
  val nameAt: Hlc = Hlc(0, 0, DeviceId("")),
  /** Kept at the top of this phone's Lists screen. This phone's own: never sent, kept through merges. */
  val pinned: Boolean = false,
  /**
   * Who shared this list with this phone, until it is first opened here. This phone's own note, like
   * [pinned]: never sent, kept through merges, and cleared by opening the list.
   */
  val arrivedFrom: DeviceId? = null,
  /**
   * Who this list was shared with, by name, when this phone was taken off it and kept it as its own
   * — see [keptAsPrivate]. Empty for every other list. It is what tells a kept copy apart from a
   * list of your own with the same name (session 5).
   *
   * This phone's own note, like [pinned]: never sent, kept through merges. A rename or a removed
   * item leaves it alone, since it says where the list came from rather than what is on it; sharing
   * the list again clears it, as from then on it is an ordinary shared list.
   */
  val wasSharedWith: List<String> = emptyList(),
) {
  /**
   * Items still on the list — everything anyone sees, counts or sends.
   *
   * [items] additionally holds tombstones for things that were removed. Those exist only so the
   * removal can reach the other phones; nothing in the interface renders them.
   */
  val liveItems: List<GroceryItem>
    get() = items.filterNot { it.removed.value }

  /**
   * [liveItems] in the order everyone on the list sees them.
   *
   * Never-placed items come first, in the order they are stored — that is how every list looked
   * before reordering existed, and how one looks until somebody moves something in it. Placed items
   * follow by [GroceryItem.position], with the item id breaking a tie, because two phones can mint
   * the same key at once and both must still draw the same list.
   */
  val orderedItems: List<GroceryItem>
    get() {
      val (unplaced, placed) = liveItems.partition { it.position.value.isEmpty() }
      return unplaced + placed.sortedWith(compareBy({ it.position.value }, { it.id.value }))
    }

  /**
   * Moves one item to [toIndex] of [orderedItems], stamping only what had to change.
   *
   * Normally that is the moved item alone, keyed between its new neighbours. The whole list is given
   * fresh positions instead when there is nothing to key between: the first move in a list whose
   * items were never placed, or two neighbours that ended up with the same key on two phones.
   */
  fun withItemMoved(id: ItemId, toIndex: Int, at: Hlc): GroceryList {
    val order = orderedItems.toMutableList()
    val from = order.indexOfFirst { it.id == id }
    if (from < 0) return this
    val moving = order.removeAt(from)
    val to = toIndex.coerceIn(0, order.size)
    order.add(to, moving)
    if (from == to) return this

    val before = order.getOrNull(to - 1)?.position?.value
    val after = order.getOrNull(to + 1)?.position?.value
    val placeable = order.none { it.position.value.isEmpty() } && (before == null || after == null || before < after)

    val newPositions: Map<ItemId, String> =
      if (placeable) mapOf(id to OrderKey.between(before, after))
      else order.map { it.id }.zip(OrderKey.spread(order.size)).toMap()

    return copy(
      items =
        items.map { item ->
          newPositions[item.id]?.let { key -> item.copy(position = item.position.set(key, at)) } ?: item
        }
    )
  }

  /**
   * Moves an item whose tick just changed to where that tick belongs.
   *
   * Ticked goes to the very bottom, so the trolley collects below what is still to get, newest last.
   * Unticked comes back up to just below the last thing still to get — above the trolley, but not
   * jumping ahead of anything that was already waiting.
   */
  fun withItemSettled(id: ItemId, at: Hlc): GroceryList {
    val item = liveItems.firstOrNull { it.id == id } ?: return this
    val others = orderedItems.filterNot { it.id == id }
    val to = if (item.checked) others.size else others.indexOfLast { !it.checked } + 1
    return withItemMoved(id, to, at)
  }

  /**
   * Adds new items at the bottom of what is still to get, above the trolley, each with a position.
   *
   * A new item gets a key between the last unticked item and the first ticked one below it — a key
   * after everything would put it under the trolley. Where there is no clean gap to key into, the
   * list is given fresh positions first, as [withItemMoved] does; that includes the first add to a
   * list whose items were never placed.
   *
   * Always a position, even in a list nobody has arranged (Hugo, 2026-10-25). Unplaced items sort
   * in each phone's own storage order, which is arrival order, so two phones drew the same list in
   * two orders (session 5), and one added while the other phone's first tick was on its way jumped
   * to the top once that tick arrived.
   */
  fun withItemsAppended(added: List<GroceryItem>, at: Hlc): GroceryList =
    withItemsPlaced(added, at) { order -> order.indexOfLast { !it.checked } }

  /**
   * Adds items that are already ticked at the very bottom, under the trolley, the way a tick moves
   * one there — see [withItemSettled].
   */
  fun withTrolleyAppended(added: List<GroceryItem>, at: Hlc): GroceryList =
    withItemsPlaced(added, at) { order -> order.lastIndex }

  /** Adds [added] just below the item [anchorOf] picks out of [orderedItems], keyed in order. */
  private fun withItemsPlaced(added: List<GroceryItem>, at: Hlc, anchorOf: (List<GroceryItem>) -> Int): GroceryList {
    if (added.isEmpty()) return this

    val order = orderedItems
    val anchor = anchorOf(order)
    var before = order.getOrNull(anchor)?.position?.value
    val after = order.getOrNull(anchor + 1)?.position?.value
    val keyable = order.none { it.position.value.isEmpty() } && (before == null || after == null || before < after)
    if (!keyable) return withPositionsSpread(at).withItemsPlaced(added, at, anchorOf)

    val placed =
      added.map { item ->
        val key = OrderKey.between(before, after)
        before = key
        item.copy(position = item.position.set(key, at))
      }
    return copy(items = items + placed)
  }

  /** Every live item a fresh position, in the order they are shown now. */
  private fun withPositionsSpread(at: Hlc): GroceryList {
    val order = orderedItems
    val keys = order.map { it.id }.zip(OrderKey.spread(order.size)).toMap()
    return copy(items = items.map { item -> keys[item.id]?.let { item.copy(position = item.position.set(it, at)) } ?: item })
  }

  val doneCount: Int
    get() = liveItems.count { it.checked }

  /** Matches a phone someone holds now, or one they used to hold, so old items stay attributed. */
  fun personFor(device: DeviceId): Person? = people.firstOrNull { it.wasEver(device) }

  /**
   * The phones still expected to answer, other than [localDevice].
   *
   * Retired handsets are deliberately absent: a device that will never confirm another thing would
   * otherwise hold every tombstone on this list open for ever.
   */
  fun peersOf(localDevice: DeviceId): List<DeviceId> =
    people.filter { it.present }.map { it.device }.filterNot { it == localDevice }

  /**
   * Records that [person] moved to a new phone.
   *
   * The old id moves to [Person.formerDevices] rather than being erased — everything it authored
   * keeps its name — and stops being waited on, which unblocks collection.
   */
  fun replaceDevice(oldDevice: DeviceId, newDevice: DeviceId, name: String? = null): GroceryList =
    copy(
      people =
        people.map { person ->
          if (!person.wasEver(oldDevice)) person
          else
            person.copy(
              device = newDevice,
              name = name ?: person.name,
              initial = if (name != null) initialOf(name) else person.initial,
              formerDevices = person.formerDevices + oldDevice - newDevice,
            )
        }
    )

  /**
   * Marks someone as off the list, rather than forgetting them — whether they left or were removed.
   *
   * Both are news the others need, so the person stays as a stamped tombstone until everyone who has
   * to hear it has confirmed, at which point [pruneDelivered] collects it. Who that is depends on
   * who decided: see [mustHear]. Without the stamp, the merge's union of people would add them
   * straight back on the next exchange.
   */
  fun withPersonLeft(device: DeviceId, at: Hlc): GroceryList =
    copy(people = people.map { if (it.wasEver(device)) it.copy(removed = it.removed.set(true, at)) else it })

  /**
   * Puts someone on this list, including someone who once left it.
   *
   * Stamped for the same reason leaving is. Their old "left" may still be out there — on their own
   * phone, or one that has not caught up — and a re-add stamped at the beginning of time loses to
   * it on the next exchange, taking them quietly off the list again. Stamped now, it wins.
   */
  fun withPersonAdded(device: DeviceId, name: String, at: Hlc): GroceryList =
    if (people.any { it.wasEver(device) }) {
      copy(
        people =
          people.map {
            if (it.wasEver(device) && !it.present) it.copy(removed = it.removed.set(false, at)) else it
          },
        wasSharedWith = emptyList(),
      )
    } else {
      copy(people = people + Person(device, name, initialOf(name), removed = Field(false, at)), wasSharedWith = emptyList())
    }

  /** [localDevice]'s own name on this list, set to [name] as of [at]. */
  fun withOwnName(localDevice: DeviceId, name: String, at: Hlc): GroceryList =
    copy(people = people.map { if (it.device == localDevice) it.copy(name = name, initial = initialOf(name), nameAt = at) else it })

  /** [localDevice]'s own avatar on this list, brought up to [avatar] unless what it has is newer. */
  fun withOwnAvatar(localDevice: DeviceId, avatar: Field<String>): GroceryList =
    copy(people = people.map { if (it.device == localDevice) it.copy(avatar = it.avatar.latest(avatar)) else it })

  /**
   * Drops tombstones every peer has confirmed receiving.
   *
   * Once the removal has reached all of them, no phone still holds the live item, so there is
   * nothing left for the tombstone to contradict and it can go. Until then it stays, however old
   * it is — age is not evidence of delivery. "Confirmed" is a receipt at or past the point this
   * phone itself came to hold the removal, [GroceryItem.removalKnownAt], not the removal's stamp.
   *
   * [asked] are the items "keep which?" cards are about, and their tombstones stay while the cards
   * do. Keeping the removal rewrites it as a fresh edit, and that edit is what closes the card on
   * the other phone; with the tombstone collected there would be nothing to write, and the other
   * card would stay open for good, offering to bring the item back.
   */
  fun pruneDelivered(
    log: DeliveryLog,
    localDevice: DeviceId,
    asked: Set<ItemId> = emptySet(),
  ): GroceryList {
    val peers = peersOf(localDevice)
    return copy(
      // With nobody to deliver to there is nothing to wait for, and a solo list keeps no tombstones.
      items =
        if (peers.isEmpty()) liveItems
        else items.filterNot { it.id !in asked && it.removed.value && log.deliveredToAll(it.removalKnownAt, peers) },
      // Someone off the list is collected on the same terms as a removed item: once everyone who has
      // to hear it has confirmed, nobody is left holding a copy that says otherwise.
      people =
        people.filterNot { person ->
          person.removed.value && log.deliveredToAll(person.removalKnownAt, peers + listOfNotNull(mustHear(person, localDevice)))
        },
    )
  }

  /**
   * This list after a merge, with every removal it did not hold before marked as heard of [at] —
   * this phone's clock once the merge is done, which every payload put together from now on is at
   * or past, and carries the removal. [before] is this phone's copy as it was, or null for a list
   * just arrived.
   *
   * A removal it already held keeps the mark it had. One whose stamp changed — taken off again
   * after someone put it back — is news again, and is marked afresh.
   */
  fun withRemovalsHeard(before: GroceryList?, at: Hlc): GroceryList {
    val had = before?.items?.associateBy { it.id }.orEmpty()
    return copy(
      items =
        items.map { item ->
          if (item.removed.value && had[item.id]?.removed != item.removed) item.copy(removalHeardAt = at) else item
        },
      people =
        people.map { person ->
          val was = before?.people?.firstOrNull { it.wasEver(person.device) || person.wasEver(it.device) }
          if (person.removed.value && was?.removed != person.removed) person.copy(removalHeardAt = at) else person
        },
    )
  }

  /**
   * Whether a person who is off the list has to hear about it themselves before it can be forgotten.
   *
   * Someone who left decided it on their own phone, so it already knows. Someone *removed* did not:
   * their phone still holds the list and would hand them straight back, and would go on believing
   * the list was shared. So their own confirmation is waited for too.
   *
   * That includes a phone that has been unpaired, which may never confirm: its removal is then
   * kept for good, a single line. Collected at once, as it was, pairing the same phone again
   * brought every shared list back with both people on it, because the other phone still named
   * them both and nothing here said otherwise (2026-10-24). Kept, it reaches that phone on the
   * first exchange after pairing again, and each side is left with its own private copy: pairing
   * again starts fresh (Hugo, 2026-10-25).
   *
   * Null when nothing extra is owed.
   */
  private fun mustHear(person: Person, localDevice: DeviceId): DeviceId? {
    val decidedByThemselves = person.wasEver(person.removed.at.device)
    if (decidedByThemselves || person.device == localDevice) return null
    return person.device
  }

  /**
   * What a phone keeps after being taken off this list: the same list, as its own private one.
   *
   * New ids for the list and its items, because the original is still somebody else's, and this
   * phone may one day be put back on it — two lists sharing item ids would then be merged into each
   * other. Everything else is as it stood: names, quantities, ticks, order, who added what.
   *
   * Who it was shared with is noted on the copy, as it stood at that moment, so its card can say so.
   */
  fun keptAsPrivate(id: ListId, newItemId: () -> ItemId, localDevice: DeviceId): GroceryList {
    val me = personFor(localDevice)
    return GroceryList(
      id = id,
      name = name,
      accent = accent,
      items =
        liveItems.map {
          val newId = newItemId()
          // The new id would make this phone the adder of everything; who added it is kept instead,
          // as a merge keeps it.
          it.copy(id = newId, editing = false, addedBy = it.createdBy.takeIf { adder -> adder != newId.device })
        },
      people =
        listOf(
          me?.let { Person(localDevice, it.name, it.initial, avatar = it.avatar, nameAt = it.nameAt) }
            ?: Person(localDevice, "", "")
        ),
      nameAt = nameAt,
      pinned = pinned,
      wasSharedWith = people.filter { it.present && it.device != localDevice }.map { it.name }.filter { it.isNotBlank() },
    )
  }

  /**
   * This list with everything on [other] brought across, as merging [other] into it leaves it.
   *
   * Something still to get on both — the same name by the folding adding uses, unticked on each side
   * — becomes one line: this list's, where it already sits, asking for both quantities and for every
   * recipe either was for. Anything else comes across as it is, name, quantity, tick and recipes,
   * in the order [other] had it: what is still to get where a new item goes, and what is already in
   * the trolley under this list's own trolley, where a tick sends it. Removed items stay behind.
   *
   * Each one is a new item, with an id from [newItemId]: [other]'s ids cannot be reused, because
   * [other] still exists on the phones it was shared with and carries them there. Everything else
   * about it is kept, so it reads as it did on [other] — still the errand of whoever added it, kept
   * as [GroceryItem.addedBy] since the id now says this phone, and "Ava, 9:12" under its name, from
   * the stamps it was written with. Only its place is new, stamped [at], and a place is deliberately
   * not what says who last wrote an item. That place is also what makes the list's newest change
   * this merge, so "in step" still waits for the others to hear of it.
   *
   * Old stamps on a new id are safe: a stamp is only ever compared with the same field of the same
   * item, and nothing anywhere holds an older copy of these ones to compare with.
   *
   * The people on this list are left exactly as they are.
   */
  fun withItemsMergedFrom(other: GroceryList, newItemId: () -> ItemId, at: Hlc): GroceryList {
    val waiting = liveItems.filterNot { it.checked }
    val bumped = HashMap<ItemId, GroceryItem>()
    val copied = ArrayList<GroceryItem>()

    other.orderedItems.forEach { theirs ->
      val folded = WordTrie.fold(theirs.name.value)
      val match = if (theirs.checked) null else waiting.firstOrNull { WordTrie.fold(it.name.value) == folded }
      if (match != null) {
        val ours = bumped[match.id] ?: match
        val more = ours.copy(qty = ours.qty.set(ours.qty.value + theirs.qty.value, at))
        bumped[match.id] = theirs.recipes.value.fold(more) { item, recipe -> item.forRecipe(recipe, at) }
      } else {
        val id = newItemId()
        copied +=
          theirs.copy(
            id = id,
            // Placed afresh below, as a new item is.
            position = Field("", Hlc(0, 0, id.device)),
            editing = false,
            // Kept only where the id no longer says it: something this phone added needs nothing.
            addedBy = theirs.createdBy.takeIf { it != id.device },
          )
      }
    }

    val (inTrolley, toGet) = copied.partition { it.checked }
    return copy(items = items.map { bumped[it.id] ?: it }).withItemsAppended(toGet, at).withTrolleyAppended(inTrolley, at)
  }

  companion object {
    /**
     * Stands in when there is no list to show, so a screen reached with none renders empty instead
     * of throwing. An empty id is never minted, so this can never collide with a real list.
     */
    val none =
      GroceryList(id = ListId(""), name = "", accent = ListAccent.NEUTRAL, items = emptyList(), people = emptyList())
  }
}

/**
 * A phone this one has paired with, and the key it is trusted by.
 *
 * [publicKey] is the whole point of the entry: the id alone proves nothing, and every connection is
 * checked against this key. Pairing happened over a channel the owner could vouch for — a QR code
 * held up in front of them — which is what makes the key trustworthy in the first place.
 */
data class TrustedPeer(val deviceId: DeviceId, val publicKey: ByteArray, val name: String, val pairedAt: Long) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TrustedPeer) return false
    return deviceId == other.deviceId &&
      publicKey.contentEquals(other.publicKey) &&
      name == other.name &&
      pairedAt == other.pairedAt
  }

  override fun hashCode(): Int =
    31 * (31 * (31 * deviceId.hashCode() + publicKey.contentHashCode()) + name.hashCode()) + pairedAt.hashCode()
}

/**
 * A network the owner has opted into looking for peers on.
 *
 * Keyed by [NetworkFingerprint], which is a discovery hint and nothing more: matching it says only
 * that it is worth listening here, never that the place is safe or that anyone found is who they
 * claim. [name] is the SSID when the phone will tell it, or whatever the owner typed.
 */
data class ApprovedNetwork(
  val fingerprint: NetworkFingerprint,
  val name: String,
  val detail: String,
  val approved: Boolean,
) {
  /** What to show when the owner has not named it. */
  val label: String
    get() = name.ifBlank { "Network ${fingerprint.short}" }
}

/**
 * Something bought often enough to be worth one tap instead of typing.
 *
 * [uses] counts how many times it has actually been dropped onto a list from the staples grid. The
 * design writes a cadence under each tile ("every week"); that was a fixture, and asking someone to
 * type one would be asking them to maintain a second fact about their shopping. A count is the same
 * shape on screen and is simply true.
 *
 * Staples are this phone's, not the list's. They are a habit rather than an agreement, they are not
 * attributed to anyone, and nothing about them goes to a peer.
 */
data class Staple(val name: String, val uses: Int = 0) {

  /** The line under the name on a tile. */
  val note: String
    get() =
      when (uses) {
        0 -> "not used yet"
        1 -> "used once"
        else -> "used $uses times"
      }
}

/** One thing a recipe needs, and how many. Also what is kept of an item once it is cleared. */
data class Ingredient(val name: String, val qty: Int = 1) {
  /** "Sourdough", or "Oat milk ×2" once there is more than one, as an item on a list reads. */
  val label: String
    get() = if (qty > 1) "$name ×$qty" else name
}

/**
 * A dish and what goes into it, or a group of things always needed, put on a list together, less
 * whatever is unticked that time.
 *
 * This phone's own, like a [Staple]: nothing about a recipe goes to a peer. What does travel is its
 * name, on each item it adds — see [GroceryItem.recipes].
 */
data class Recipe(val name: String, val ingredients: List<Ingredient>) {

  /**
   * "2 Eggs, Flour, Milk": the line under its name, and what its form starts from when it is edited.
   * Written the way a pasted list is read, so editing a recipe and saving it untouched changes nothing.
   */
  val ingredientsText: String
    get() = ingredients.joinToString(", ") { if (it.qty > 1) "${it.qty} ${it.name}" else it.name }

  companion object {
    /**
     * A recipe from what was typed into its form: a name, and its ingredients as a pasted list is
     * read — "2 eggs, flour, milk", by commas or by lines.
     *
     * Null without a name or without anything in it. The same thing typed twice is one ingredient
     * with both quantities, matched the way an item already on a list is. "0 eggs" still means eggs.
     */
    fun of(name: String, ingredients: String): Recipe? {
      val trimmed = name.trim()
      if (trimmed.isEmpty()) return null
      val parsed =
        ShareCodec.decode(ingredients)
          .groupBy { WordTrie.fold(it.name) }
          .values
          .map { same -> Ingredient(same.first().name, same.sumOf { it.qty.coerceAtLeast(1) }) }
      return if (parsed.isEmpty()) null else Recipe(trimmed, parsed)
    }
  }
}

/**
 * Two people changed the same thing without either having seen the other's version.
 *
 * This is what [io.github.molleware.porygonlist.data.sync.merge] reports as `wasConcurrent`: not
 * simply two edits, but two edits neither of which was made in knowledge of the other. The merge is
 * automatic everywhere it can be; this is the residue that needs a person.
 *
 * It holds the two competing items themselves and nothing else. Each already carries its own creator
 * and stamps, so "keep both" can put them on the list exactly as they are — and the sentence
 * explaining the clash is worked out from them when it is shown, never stored. A stored sentence
 * goes stale: it would still read "just now" under something added last week.
 */
data class Conflict(
  val yours: GroceryItem,
  val theirs: GroceryItem,
  val kind: ConflictKind = ConflictKind.DUPLICATE,
  /**
   * The list both sides are on. Null only in files written before it was recorded, which read as the
   * open list — what every conflict was taken to be about then, rightly or not.
   */
  val listId: ListId? = null,
) {
  val itemName: String
    get() = yours.name.value

  /**
   * What the card asks about, so the same question arriving twice is one card rather than two: an
   * edit clash is about one item, a duplicate about a pair.
   */
  val question: Any
    get() = Triple(kind, listId, if (kind == ConflictKind.EDITED) setOf(yours.id) else setOf(yours.id, theirs.id))

  /** The newest edit either side made to what can clash: name, quantity, being on the list. */
  private val sidesTouched: Hlc
    get() = listOf(yours, theirs).maxOf { maxOf(it.name.at, it.qty.at, it.removed.at) }

  /**
   * Whether the question has been overtaken: somebody has since edited the item knowing about it —
   * answered the same card on the other phone, or simply changed it again. Asking then would be
   * about a choice that no longer exists.
   */
  fun isStaleOn(list: GroceryList?): Boolean {
    if (kind != ConflictKind.EDITED) return false
    if (list == null) return true
    // Gone altogether means a removal was collected once everyone had heard it — which says nothing
    // about whether the person wanted it kept. The question still stands; see withVersionKept.
    val now = list.items.firstOrNull { it.id == yours.id } ?: return false
    return maxOf(now.name.at, now.qty.at, now.removed.at) > sidesTouched
  }
}

enum class ConflictKind {
  /** Two different items that are the same thing, added on two phones: merge, or keep both. */
  DUPLICATE,

  /** One item changed on two phones before either had seen the other's change: keep which? */
  EDITED,
}

data class AppState(
  /** This installation's identity. Fixed for the life of the install. */
  val localDevice: DeviceId,
  /**
   * What the owner calls themselves, and the one thing first run asks for.
   *
   * Blank until they answer — [named] is what decides whether to ask. It is deliberately not part of
   * identity: a name is a label, changeable at any time, and it travels to other phones inside a
   * pairing invite where the *key* is what settles who anyone actually is. Two people called Sam are
   * not a problem this has to solve.
   */
  val displayName: String = "",
  /** When the owner last set [displayName]: the stamp their name travels with. See [Person.nameAt]. */
  val displayNameAt: Hlc = Hlc(0, 0, localDevice),
  /**
   * The owner's avatar, by its [Avatars] id: [Avatars.NONE] until they choose one.
   *
   * Kept here as well as on each list, like [displayName], so a list made or joined later starts
   * with it. The stamp is the moment it was chosen, and travels with it.
   */
  val avatar: Field<String> = Field(Avatars.NONE, Hlc(0, 0, localDevice)),
  /** Highest id counter handed out here. Persisted so an id is never reused after a restart. */
  val idCounter: Long,
  /** Newest stamp this device has issued or seen. Persisted so the clock never goes backwards. */
  val clockHead: Hlc,
  /** Proof of what each peer has received from here. Governs when tombstones can be collected. */
  val deliveredTo: DeliveryLog = DeliveryLog(),
  val lists: List<GroceryList>,
  val activeListId: ListId,
  val networks: List<ApprovedNetwork>,
  /** Phones paired with this one, by key. Empty until someone scans a code. */
  val peers: List<TrustedPeer> = emptyList(),
  /** This phone's one-tap shortlist. Starts empty; local to the install; never sent anywhere. */
  val staples: List<Staple> = emptyList(),
  /** This phone's recipes, in the order they were made. Local, like [staples]; never sent anywhere. */
  val recipes: List<Recipe> = emptyList(),
  /**
   * Every question waiting for a person, oldest first, each on its own list.
   *
   * Several, not one: with a single slot an unanswered card stopped a second clash being asked at
   * all, and an edit clash, already merged by clock, never came back — the Pixel dropped Milk
   * without a word while Parmesan's card waited (2026-10-24). Hugo, 2026-10-25: every clash its
   * own card.
   */
  val conflicts: List<Conflict> = emptyList(),
  /**
   * The "Background sync" switch in Settings, as the owner last set it: null until they touch it.
   *
   * Only what was chosen. What it reads as also depends on whether Android allows this app's alarms,
   * which changes in system settings and is asked of Android, never stored — see
   * [io.github.molleware.porygonlist.data.net.backgroundSwitch]. This phone's own; never sent anywhere.
   */
  val backgroundSync: Boolean? = null,
  /**
   * What this phone has cleared from each list, newest first: see [ClearedRun]. A list with none has
   * no entry. Here rather than on the list, because a [GroceryList] is what goes to other phones and
   * this never does.
   */
  val history: Map<ListId, List<ClearedRun>> = emptyMap(),
  /**
   * Staples this phone learned for each list from its runs, in the order they were learned: see
   * [LearnedStaple]. Here for the same reason as [history], and never sent either.
   */
  val learnedStaples: Map<ListId, List<LearnedStaple>> = emptyMap(),
  /** For each list, the folded names of learned staples removed by hand, never to be learned there again. */
  val unwantedStaples: Map<ListId, Set<String>> = emptyMap(),
) {
  /**
   * With no lists at all — a fresh install, or the last one deleted — this is [GroceryList.none]: a
   * nameless empty list that renders as nothing rather than throwing. Screens reached by opening a
   * list cannot be reached in that state; the tabs can, and they show an empty shop.
   */
  val activeList: GroceryList
    get() = visibleLists.firstOrNull { it.id == activeListId } ?: visibleLists.firstOrNull() ?: GroceryList.none

  /**
   * The lists this phone is on, which is everything anyone should ever see.
   *
   * [lists] can additionally hold one this phone has left, kept only until the other people on it
   * have heard — the same arrangement items use for their tombstones. Showing it would mean a list
   * the owner deleted sitting there until a sync that may be days away.
   */
  val visibleLists: List<GroceryList>
    get() = lists.filter { it.personFor(localDevice)?.present == true }

  /**
   * [visibleLists] as the Lists screen draws them: pinned first, then the rest, each group in the
   * order the owner arranged.
   *
   * The arrangement is simply the order of [lists], which is the order the file stores them in. It
   * is this phone's own: an exchange keeps the local order and adds newcomers at the end.
   */
  val orderedVisibleLists: List<GroceryList>
    get() = visibleLists.partition { it.pinned }.let { (pinned, rest) -> pinned + rest }

  /**
   * Moves a list to [toIndex] of [orderedVisibleLists], within its own group.
   *
   * Pinned and unpinned lists are arranged separately, so the target is clamped to the moved list's
   * group: dragging a list past the boundary is what pinning is for. Lists the owner cannot see —
   * ones they have left, held only until the others hear — keep their places at the end.
   */
  fun withListMoved(id: ListId, toIndex: Int): AppState {
    val order = orderedVisibleLists.toMutableList()
    val from = order.indexOfFirst { it.id == id }
    if (from < 0) return this
    val moving = order.removeAt(from)
    val pinnedCount = order.count { it.pinned }
    val to = if (moving.pinned) toIndex.coerceIn(0, pinnedCount) else toIndex.coerceIn(pinnedCount, order.size)
    order.add(to, moving)
    val shown = order.map { it.id }.toSet()
    return copy(lists = order + lists.filterNot { it.id in shown })
  }

  /** Pins or unpins a list. A newly pinned list joins the bottom of the pinned group. */
  fun withPinned(id: ListId, pinned: Boolean): AppState {
    val target = lists.firstOrNull { it.id == id } ?: return this
    if (target.pinned == pinned) return this
    // Moved to the end of the stored order, which is the end of whichever group it now belongs to.
    return copy(lists = lists.filterNot { it.id == id } + target.copy(pinned = pinned))
  }

  /**
   * Takes a list off this phone, which is what Delete does.
   *
   * Local only: the people it was shared with keep their copies. Propagating a whole-list deletion
   * would need a tombstone for the list itself, and handing one phone the power to wipe a shared list
   * off everyone else's is not obviously right.
   *
   * The last list can go too. A new install starts with none, so no-lists is a state the app already
   * has to show; refusing to delete the only one would make the bin stop working for no reason the
   * owner can see.
   */
  fun withoutList(id: ListId, at: Hlc): AppState {
    val going = lists.firstOrNull { it.id == id }
    val shared = going != null && going.peersOf(localDevice).isNotEmpty()

    val kept =
      if (shared) {
        // Someone else is on it, so this is leaving rather than deleting. The list stays, hidden,
        // until every one of them has heard — see pruneDeliveredTombstones. Dropping it outright
        // would leave them waiting for ever on a phone that had simply stopped caring.
        lists.map { if (it.id == id) it.withPersonLeft(localDevice, at) else it }
      } else {
        lists.filterNot { it.id == id }
      }

    // An empty id is never minted, so this leaves nothing active, which is what no lists means.
    val visible = kept.filter { it.personFor(localDevice)?.present == true }
    return copy(
      lists = kept,
      activeListId = if (activeListId == id) visible.firstOrNull()?.id ?: ListId("") else activeListId,
      // At once, even when the list stays hidden for the others to hear: nothing here shows it again.
      history = history - id,
      learnedStaples = learnedStaples - id,
      unwantedStaples = unwantedStaples - id,
    )
  }

  /** The lists [id] could be merged into: every other one this phone shows. None, and none is offered. */
  fun mergeTargetsFor(id: ListId): List<GroceryList> =
    if (visibleLists.none { it.id == id }) emptyList() else orderedVisibleLists.filterNot { it.id == id }

  /**
   * Merges list [from] into list [into]: everything on it brought across — see
   * [GroceryList.withItemsMergedFrom] — and then [from] taken off this phone exactly as Delete takes
   * it, so anyone it was shared with keeps their copy. [into] keeps the people it had; nobody is
   * added to a list by having been on the other one. What this phone cleared from [from] moves to
   * [into] too, since those items now live there, and so do the staples it learned there.
   *
   * Nothing happens unless both are lists this phone shows, and two different ones.
   */
  fun withListMerged(from: ListId, into: ListId, newItemId: () -> ItemId, at: Hlc): AppState {
    val source = visibleLists.firstOrNull { it.id == from } ?: return this
    if (mergeTargetsFor(from).none { it.id == into }) return this
    return copy(
        lists = lists.map { if (it.id == into) it.withItemsMergedFrom(source, newItemId, at) else it },
        history = history.movedTo(from, into),
      )
      .withStaplesMoved(from, into)
      .withoutList(from, at)
  }

  /**
   * The cards waiting for an answer that still mean anything.
   *
   * An edit clash somebody has since settled — on the other phone, or by editing the item again —
   * is no longer a question, so it is left out rather than asking about a choice already made.
   */
  val openConflicts: List<Conflict>
    get() = conflicts.filterNot { c -> c.isStaleOn(lists.firstOrNull { it.id == listIdOf(c) }) }

  /** The list a card is about — the open one, for a card from before that was recorded. */
  fun listIdOf(c: Conflict): ListId = c.listId ?: activeListId

  /** The open cards about [listId], oldest first. */
  fun openConflictsOn(listId: ListId): List<Conflict> = openConflicts.filter { listIdOf(it) == listId }

  /** [c] as it stands now, if it is still open: the card a person answers may have moved on. */
  fun openConflict(c: Conflict): Conflict? = openConflicts.firstOrNull { it.question == c.question }

  /** Every card but [c]. */
  fun withoutConflict(c: Conflict): AppState = copy(conflicts = conflicts.filterNot { it.question == c.question })

  /**
   * Answers "keep which?" for an item changed on two phones at once.
   *
   * The chosen version is written again as a fresh edit, made in knowledge of both — so it beats
   * either original everywhere it travels, and the other phone's card goes away when it arrives
   * rather than asking a question that has been answered. Only what the two versions disagree on
   * is rewritten; anything else about the item is left as the merge had it.
   */
  fun withVersionKept(card: Conflict, yours: Boolean, at: Hlc): AppState {
    val c = openConflict(card)?.takeIf { it.kind == ConflictKind.EDITED } ?: return this
    val chosen = if (yours) c.yours else c.theirs
    val target = listIdOf(c)
    return withoutConflict(c).copy(
      lists =
        lists.map { list ->
          if (list.id != target) list
          else
            list.copy(
              items =
                // The removed side's tombstone may have been collected while the card waited. Keeping
                // the other side then puts the item back from the card's own copy.
                (if (list.items.any { it.id == chosen.id } || chosen.removed.value) list.items else list.items + chosen)
                  .map { item ->
                  if (item.id != chosen.id) item
                  else
                    item.copy(
                      name = if (c.yours.name.value != c.theirs.name.value) item.name.set(chosen.name.value, at) else item.name,
                      qty = if (c.yours.qty.value != c.theirs.qty.value) item.qty.set(chosen.qty.value, at) else item.qty,
                      removed =
                        if (c.yours.removed.value != c.theirs.removed.value) item.removed.set(chosen.removed.value, at)
                        else item.removed,
                    )
                }
            )
        },
    )
  }

  /** The recipe called [name], under any capitalisation. */
  fun recipeNamed(name: String): Recipe? = recipes.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

  /**
   * Keeps [recipe]: in place of the one called [replacing] when it is an edit, last when it is new.
   *
   * Refused — this state comes back as it was — when a different recipe already has its name. The
   * name is all an item says about which recipe it is for, and two recipes sharing one would make
   * "for Lasagne" mean either.
   */
  fun withRecipeSaved(recipe: Recipe, replacing: String? = null): AppState {
    val taken = recipeNamed(recipe.name)
    if (taken != null && taken.name != replacing) return this
    val at = recipes.indexOfFirst { it.name == replacing }
    return copy(recipes = if (at < 0) recipes + recipe else recipes.toMutableList().apply { set(at, recipe) })
  }

  /** Takes a recipe off this phone. Nothing it already put on a list is touched, its names included. */
  fun withoutRecipe(name: String): AppState = copy(recipes = recipes.filterNot { it.name == name })

  /** The fingerprints discovery is allowed to run on. */
  val approvedFingerprints: Set<NetworkFingerprint>
    get() = networks.filter { it.approved }.map { it.fingerprint }.toSet()

  /** Whether the owner has told this phone their name yet. False exactly once, on first run. */
  val named: Boolean
    get() = displayName.isNotBlank()

  fun isYou(device: DeviceId): Boolean = device == localDevice

  /**
   * Records the owner's name — here, and on every list they are already on.
   *
   * Both halves matter. The top-level copy is what a pairing invite carries and what first run
   * checks; the per-list [Person] is what every "Hugo added this" line reads. Writing only one of
   * them would leave the app introducing them by one name and attributing their items to another.
   *
   * A blank name is ignored rather than stored: it would put the app back into first run and throw
   * away the name it already had.
   *
   * Stamped [at], so the rename travels: every other phone on those lists takes the newer name.
   */
  fun withDisplayName(name: String, at: Hlc): AppState {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return this
    return copy(displayName = trimmed, displayNameAt = at, lists = lists.map { it.withOwnName(localDevice, trimmed, at) })
  }

  /**
   * Records the owner's avatar — here, and on every list they are already on, as a name is.
   *
   * Stamped [at], so the choice outranks whatever another phone's copy of their record says. Only
   * [Avatars.NONE] or a free avatar this build knows can be chosen; choosing the one already worn
   * changes nothing, rather than sending the same choice round again under a new stamp.
   */
  fun withAvatar(id: String, at: Hlc): AppState {
    if (id != Avatars.NONE && Avatars.byId(id)?.free != true) return this
    if (id == avatar.value) return this
    val chosen = avatar.set(id, at)
    return copy(avatar = chosen, lists = lists.map { it.withOwnAvatar(localDevice, chosen) })
  }

  /**
   * The avatar to show for [device]: the owner's own, or the newest any list has for that person.
   *
   * For screens about a paired phone rather than a list, which have no list's copy to read.
   */
  fun avatarOf(device: DeviceId): String =
    if (device == localDevice) avatar.value
    else lists.flatMap { it.people }.filter { it.wasEver(device) }.maxByOrNull { it.avatar.at }?.avatar?.value ?: Avatars.NONE

  /**
   * What to call a paired phone's owner: the newest name any list has for them, or failing that the
   * one they gave when pairing, which a rename since leaves behind.
   */
  fun nameOf(peer: TrustedPeer): String =
    lists
      .flatMap { it.people }
      .filter { it.wasEver(peer.deviceId) && it.name.isNotBlank() }
      .maxByOrNull { it.nameAt }
      ?.name ?: peer.name

  fun peerFor(device: DeviceId): TrustedPeer? = peers.firstOrNull { it.deviceId == device }

  /** Every phone known to this state, across all lists, excluding this one. */
  fun knownPeers(): Set<DeviceId> = lists.flatMap { it.people }.map { it.device }.filterNot { it == localDevice }.toSet()

  /**
   * Stops waiting on [oldDevice] and waits on [newDevice] instead — someone got a new phone.
   *
   * Their old receipts go with the old id: a new installation has confirmed nothing, and inheriting
   * the predecessor's watermark would collect tombstones it never received.
   */
  fun replaceDevice(oldDevice: DeviceId, newDevice: DeviceId, name: String? = null): AppState =
    copy(lists = lists.map { it.replaceDevice(oldDevice, newDevice, name) })
      .let { next -> next.copy(deliveredTo = next.deliveredTo.retaining(next.knownPeers())) }
      .pruneDeliveredTombstones()

  /**
   * The people whose phone [newDevice] could be replacing, for "Someone new, or a phone replacing
   * one?" — empty when it can only be someone new, and then the question is not asked.
   *
   * A candidate's old phone is paired with this one and they are still on one of its lists: that is
   * the id still waited on for receipts, which is all a replacement changes. Someone who was
   * unpaired is left out — pairing again starts fresh (Hugo, 2026-10-25) — and with nobody paired
   * at all there is nobody to replace. The asked-about phone is never its own predecessor.
   */
  fun replaceableBy(newDevice: DeviceId): List<Person> =
    visibleLists
      .flatMap { it.people }
      .filter { it.present && it.device != localDevice && it.device != newDevice && peerFor(it.device) != null }
      .distinctBy { it.device }

  /**
   * Stops sharing one list with someone.
   *
   * A stamped tombstone, not a deletion, so the decision travels: to everyone still on the list, and
   * to the person removed, whose phone keeps the list as its own private copy.
   */
  fun removePersonFrom(listId: ListId, device: DeviceId, at: Hlc): AppState =
    copy(lists = lists.map { if (it.id == listId) it.withPersonLeft(device, at) else it }).pruneDeliveredTombstones()

  /**
   * Unpairs a phone: off every list it is on, and no longer trusted.
   *
   * Off the lists by tombstone, for the same reason as [removePersonFrom] — anyone else on a list
   * would otherwise hand them back. The tombstone is kept until that phone itself has heard, which
   * may be never; see `mustHear` for why that is what makes pairing again start fresh.
   */
  fun retireDevice(device: DeviceId, at: Hlc): AppState =
    copy(
        lists = lists.map { list -> if (list.personFor(device)?.present == true) list.withPersonLeft(device, at) else list },
        peers = peers.filterNot { it.deviceId == device },
      )
      .pruneDeliveredTombstones()

  /**
   * Collects every tombstone that all of its list's peers have confirmed receiving.
   *
   * Run after a receipt arrives, and once on load so a file written before a receipt landed still
   * settles.
   */
  fun pruneDeliveredTombstones(): AppState =
    copy(
      lists =
        lists
          .map { list ->
            val asked = openConflictsOn(list.id).filter { it.kind == ConflictKind.EDITED }.map { it.yours.id }.toSet()
            list.pruneDelivered(deliveredTo, localDevice, asked = asked)
          }
          // A list this phone has left is kept, and kept being sent, for exactly as long as its own
          // leaving tombstone survives — that is what carries the news. Once the tombstone has been
          // collected above, everyone has heard, and the list can finally go. Dropping it any
          // earlier would leave the others waiting on a phone that had quietly stopped answering.
          //
          // Guarded on there being somebody else named, because this runs on every load and a list
          // nobody is on at all is a state nothing should produce. If one ever appears, keeping it
          // is recoverable and deleting somebody's groceries is not.
          .filterNot { list -> list.people.isNotEmpty() && list.people.none { it.wasEver(localDevice) } },
      deliveredTo = deliveredTo.retaining(knownPeers()),
    )

  companion object {
    /**
     * What a phone holds before anyone has done anything with it: no lists, nobody to sync with, no
     * approved networks, no conflict.
     *
     * The owner is left nameless on purpose. First run asks before any of this is reachable, and
     * shipping a placeholder name would mean the app addressing someone as a person they are not.
     * The same reasoning applies to everything else here — an invented list, a staged clash or a
     * network nobody approved would all be the app claiming something happened that did not.
     */
    fun empty(localDevice: DeviceId, now: Long = System.currentTimeMillis()): AppState =
      AppState(
        localDevice = localDevice,
        idCounter = 0,
        clockHead = Hlc(now, 0, localDevice),
        lists = emptyList(),
        // An empty id is never minted, so [activeList] falls back to [GroceryList.none].
        activeListId = ListId(""),
        networks = emptyList(),
      )
  }
}
