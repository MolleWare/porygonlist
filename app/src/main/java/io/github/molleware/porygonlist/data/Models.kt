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
) {
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
) {
  /** The phone that first added this item. */
  val createdBy: DeviceId
    get() = id.device

  /** The newest stamp across everything mutable on this item. */
  val touchedAt: Hlc
    get() = maxOf(name.at, qty.at, checkedAt, removed.at, position.at)

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
  fun withItemsAppended(added: List<GroceryItem>, at: Hlc): GroceryList {
    if (added.isEmpty()) return this

    val order = orderedItems
    val anchor = order.indexOfLast { !it.checked }
    var before = order.getOrNull(anchor)?.position?.value
    val after = order.getOrNull(anchor + 1)?.position?.value
    val keyable = order.none { it.position.value.isEmpty() } && (before == null || after == null || before < after)
    if (!keyable) return withPositionsSpread(at).withItemsAppended(added, at)

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
          }
      )
    } else {
      copy(people = people + Person(device, name, initialOf(name), removed = Field(false, at)))
    }

  /**
   * Drops tombstones every peer has confirmed receiving.
   *
   * Once the removal has reached all of them, no phone still holds the live item, so there is
   * nothing left for the tombstone to contradict and it can go. Until then it stays, however old
   * it is — age is not evidence of delivery.
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
        else items.filterNot { it.id !in asked && it.removed.value && log.deliveredToAll(it.removed.at, peers) },
      // Someone off the list is collected on the same terms as a removed item: once everyone who has
      // to hear it has confirmed, nobody is left holding a copy that says otherwise.
      people =
        people.filterNot { person ->
          person.removed.value && log.deliveredToAll(person.removed.at, peers + listOfNotNull(mustHear(person, localDevice)))
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
   */
  fun keptAsPrivate(id: ListId, newItemId: () -> ItemId, localDevice: DeviceId): GroceryList {
    val me = personFor(localDevice)
    return GroceryList(
      id = id,
      name = name,
      accent = accent,
      items = liveItems.map { it.copy(id = newItemId(), editing = false) },
      people = listOf(Person(localDevice, me?.name.orEmpty(), me?.initial.orEmpty())),
      nameAt = nameAt,
      pinned = pinned,
    )
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
  /**
   * Every question waiting for a person, oldest first, each on its own list.
   *
   * Several, not one: with a single slot an unanswered card stopped a second clash being asked at
   * all, and an edit clash, already merged by clock, never came back — the Pixel dropped Milk
   * without a word while Parmesan's card waited (2026-10-24). Hugo, 2026-10-25: every clash its
   * own card.
   */
  val conflicts: List<Conflict> = emptyList(),
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
   */
  fun withDisplayName(name: String): AppState {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return this
    return copy(
      displayName = trimmed,
      lists =
        lists.map { list ->
          list.copy(
            people =
              list.people.map {
                if (it.device == localDevice) it.copy(name = trimmed, initial = initialOf(trimmed)) else it
              }
          )
        },
    )
  }

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
