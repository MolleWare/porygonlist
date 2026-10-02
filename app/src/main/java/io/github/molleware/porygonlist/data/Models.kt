package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId

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
   * A tombstone rather than a deletion, and this is not a UI choice: dropping the row outright is
   * invisible to the other phone, which would hand the item straight back on the next handover. The
   * tombstone is what says "this was deliberately taken off the list" as opposed to "this phone has
   * not heard about it yet".
   *
   * Nobody ever sees it. Removal is confirmed at the moment it happens, so the other phone simply
   * stops showing the item — a grocery list does not warrant being told about every deletion.
   *
   * It is kept only until every peer has confirmed receiving it, then collected — see
   * [GroceryList.pruneDelivered].
   *
   * It is a [Field] so that a removal and a re-add of the same item merge like any other edit.
   */
  val removed: Field<Boolean>,
  /** The change has not been handed to the other phones yet. */
  val pending: Boolean = false,
  /**
   * Someone else has this item open right now. Live presence, never written to disk — it is
   * meaningless once the app is closed.
   */
  val editing: Boolean = false,
) {
  /** The phone that first added this item. */
  val createdBy: DeviceId
    get() = id.device

  /** The newest stamp across everything mutable on this item. */
  val touchedAt: Hlc
    get() = maxOf(name.at, qty.at, checkedAt, removed.at)

  /** Whoever made the most recent change, which is not always the creator. */
  val lastWriter: DeviceId
    get() = touchedAt.device

  /** Wall-clock reading of the last change, for the human-readable sub-label. */
  val at: Long
    get() = touchedAt.wall

  /** "Sourdough", or "Oat milk ×2" once there is more than one. */
  val label: String
    get() = if (qty.value > 1) "${name.value} ×${qty.value}" else name.value
}

data class GroceryList(
  /**
   * Globally unique, because a list crosses phones.
   *
   * `deviceId:counter`, minted by [io.github.molleware.porygonlist.data.sync.IdFactory]. It used to
   * be a local `max + 1`, which meant both phones called their first list `1` — so two unrelated
   * lists could not be told apart on the wire, and merging them into each other was a data loss
   * waiting for the transport to land. Derived from a key the way every other id here is.
   */
  val id: ListId,
  val name: String,
  val accent: ListAccent,
  val items: List<GroceryItem>,
  val people: List<Person>,
  /**
   * When [name] was last set.
   *
   * Without it a rename travelled but never landed: the merge has no way to tell whose name is
   * newer, so it kept its own and the other phone's rename was silently discarded. Kept beside the
   * name rather than wrapping it, because the name is read all over the interface and only the
   * merge cares when it was chosen. The default is the beginning of time, so a list nobody has
   * renamed loses to any rename at all.
   */
  val nameAt: Hlc = Hlc(0, 0, DeviceId("")),
) {
  /**
   * Items still on the list — everything anyone sees, counts or sends.
   *
   * [items] additionally holds tombstones for things that were removed. Those exist only so the
   * removal can reach the other phones; nothing in the interface renders them.
   */
  val liveItems: List<GroceryItem>
    get() = items.filterNot { it.removed.value }

  val doneCount: Int
    get() = liveItems.count { it.checked }

  /** Matches a phone someone holds now, or one they used to hold, so old items stay attributed. */
  fun personFor(device: DeviceId): Person? = people.firstOrNull { it.wasEver(device) }

  /** Everyone on this list except the phone asking. */
  /**
   * The phones still expected to answer.
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

  /** Removes someone from this list entirely, past devices and all. */
  fun withoutPerson(device: DeviceId): GroceryList = copy(people = people.filterNot { it.wasEver(device) })

  /**
   * Marks someone as having left, rather than forgetting them.
   *
   * The difference from [withoutPerson] is who decided. Taking someone off a list is this phone's
   * business and theirs alone — see [AppState.removePersonFrom]. *Leaving* is news the others need,
   * so the person stays as a stamped tombstone until every remaining peer has confirmed hearing it,
   * at which point [pruneDelivered] collects it.
   *
   * Without the stamp this would not survive a single exchange: the other phone's copy still lists
   * the leaver, and [mergePeople] unions, so they would be added straight back.
   */
  fun withPersonLeft(device: DeviceId, at: Hlc): GroceryList =
    copy(people = people.map { if (it.wasEver(device)) it.copy(removed = it.removed.set(true, at)) else it })

  /**
   * Drops tombstones every peer has confirmed receiving.
   *
   * Once the removal has reached all of them, no phone still holds the live item, so there is
   * nothing left for the tombstone to contradict and it can go. Until then it stays, however old
   * it is — age is not evidence of delivery.
   */
  fun pruneDelivered(log: DeliveryLog, localDevice: DeviceId): GroceryList {
    val peers = peersOf(localDevice)
    // With nobody to deliver to there is nothing to wait for, and a solo list keeps no tombstones.
    if (peers.isEmpty()) return copy(items = liveItems, people = people.filter { it.present })
    return copy(
      items = items.filterNot { it.removed.value && log.deliveredToAll(it.removed.at, peers) },
      // Someone who has left is collected on the same terms as a removed item: once everyone still
      // on the list has confirmed hearing it, nobody is left holding a copy that says otherwise.
      people = people.filterNot { it.removed.value && log.deliveredToAll(it.removed.at, peers) },
    )
  }

  companion object {
    /**
     * Stands in when there is no list to show, so a screen reached with none renders empty instead
     * of throwing. Id 0 is never handed out, so this can never collide with a real list.
     */
    val none =
      GroceryList(id = ListId(""), name = "", accent = ListAccent.NEUTRAL, items = emptyList(), people = emptyList())
  }
}

/**
 * A network the owner has opted into looking for peers on.
 *
 * Keyed by [NetworkFingerprint], which is a discovery hint and nothing more: matching it says only
 * that it is worth listening here, never that the place is safe or that anyone found is who they
 * claim. [name] is whatever the owner called it — the SSID is not read, because that would cost a
 * location permission for a value that is spoofable anyway.
 */
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

  companion object {
    /**
     * The grid starts empty. A shortlist of things you buy over and over is only worth anything if
     * it is yours; a shipped one is a guess about a stranger's kitchen, and the screen already says
     * what it is for while it is empty.
     */
    val defaults: List<Staple> = emptyList()
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
data class Conflict(val yours: GroceryItem, val theirs: GroceryItem) {
  val itemName: String
    get() = yours.name.value
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
  val online: Boolean,
  val networks: List<ApprovedNetwork>,
  /** Phones paired with this one, by key. Empty until someone scans a code. */
  val peers: List<TrustedPeer> = emptyList(),
  /** This phone's one-tap shortlist. Local to the install; never sent anywhere. */
  val staples: List<Staple> = Staple.defaults,
  val conflict: Conflict?,
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
   * Their tombstones on that list go with them, and safely: collection is normally held back
   * because a phone that never heard about a removal would offer the item back on the next
   * handover — but once they are off the list there is no next handover. Nothing to wait for, and
   * nothing that can come back.
   */
  fun removePersonFrom(listId: ListId, device: DeviceId): AppState =
    copy(lists = lists.map { if (it.id == listId) it.withoutPerson(device) else it })
      .let { next -> next.copy(deliveredTo = next.deliveredTo.retaining(next.knownPeers())) }
      .pruneDeliveredTombstones()

  /** Removes a person from every list, and stops waiting on their phone. */
  fun retireDevice(device: DeviceId): AppState =
    copy(lists = lists.map { it.withoutPerson(device) }, peers = peers.filterNot { it.deviceId == device })
      .let { next -> next.copy(deliveredTo = next.deliveredTo.retaining(next.knownPeers())) }
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
          .map { it.pruneDelivered(deliveredTo, localDevice) }
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
        // Nothing has been handed over yet, so nothing is collectable yet.
        deliveredTo = DeliveryLog(),
        lists = emptyList(),
        // No list to be active. An empty id is never minted, so this matches nothing until the
        // owner makes a list, and [activeList] falls back to [GroceryList.none] until they do.
        activeListId = ListId(""),
        online = true,
        // A network becomes approved when the owner approves it while standing on it, and carries
        // that network's real fingerprint. There is nothing honest to put here in advance.
        networks = emptyList(),
        conflict = null,
      )
  }
}
