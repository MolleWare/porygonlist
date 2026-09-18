package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId

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
) {
  fun wasEver(candidate: DeviceId): Boolean = candidate == device || candidate in formerDevices
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
  val id: Long,
  val name: String,
  val accent: ListAccent,
  val items: List<GroceryItem>,
  val people: List<Person>,
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
  fun peersOf(localDevice: DeviceId): List<DeviceId> = people.map { it.device }.filterNot { it == localDevice }

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
   * Drops tombstones every peer has confirmed receiving.
   *
   * Once the removal has reached all of them, no phone still holds the live item, so there is
   * nothing left for the tombstone to contradict and it can go. Until then it stays, however old
   * it is — age is not evidence of delivery.
   */
  fun pruneDelivered(log: DeliveryLog, localDevice: DeviceId): GroceryList {
    val peers = peersOf(localDevice)
    // With nobody to deliver to there is nothing to wait for, and a solo list keeps no tombstones.
    if (peers.isEmpty()) return copy(items = liveItems)
    return copy(items = items.filterNot { it.removed.value && log.deliveredToAll(it.removed.at, peers) })
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
     * What the grid starts out holding, so the screen opens onto something usable rather than an
     * empty shell. Every one of them can be removed.
     */
    val defaults: List<Staple> =
      listOf("Oat milk", "Eggs", "Sourdough", "Coffee beans", "Olive oil", "Rice", "Bin bags", "Yoghurt", "Tinned beans")
        .map { Staple(it) }
  }
}

/**
 * Two people changed the same thing without either having seen the other's version.
 *
 * This is what [io.github.molleware.porygonlist.data.sync.merge] reports as `wasConcurrent`: not
 * simply two edits, but two edits neither of which was made in knowledge of the other. The merge is
 * automatic everywhere it can be; this is the residue that needs a person.
 *
 * It holds the two competing items themselves. Each already carries its own creator and stamps, so
 * "keep both" can put them on the list exactly as they are.
 */
data class Conflict(
  val yours: GroceryItem,
  val theirs: GroceryItem,
  /** How each side is explained to the person resolving it — "you added it at 9:02". */
  val yourStory: String,
  val theirStory: String,
) {
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
  val activeListId: Long,
  val online: Boolean,
  val networks: List<ApprovedNetwork>,
  /** Phones paired with this one, by key. Empty until someone scans a code. */
  val peers: List<TrustedPeer> = emptyList(),
  /** This phone's one-tap shortlist. Local to the install; never sent anywhere. */
  val staples: List<Staple> = Staple.defaults,
  val conflict: Conflict?,
) {
  val activeList: GroceryList
    get() = lists.firstOrNull { it.id == activeListId } ?: lists.first()

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
  fun removePersonFrom(listId: Long, device: DeviceId): AppState =
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
      lists = lists.map { it.pruneDelivered(deliveredTo, localDevice) },
      deliveredTo = deliveredTo.retaining(knownPeers()),
    )

  companion object {
    /**
     * The content the design ships with. Seeded on first launch so the app opens onto something,
     * exactly as the mockup does, rather than an empty shell.
     *
     * [partner] stands in for the second phone until real pairing exists — the design is drawn with
     * Hugo already there.
     *
     * The owner is seeded nameless on purpose. First run asks before any of this is reachable, and
     * shipping a placeholder name would mean the app addressing someone as a person they are not.
     */
    fun seed(
      localDevice: DeviceId,
      partner: DeviceId = DeviceId("hugodemo01"),
      now: Long = System.currentTimeMillis(),
    ): AppState {
      val you = Person(localDevice, "", "")
      val hugo = Person(partner, "Hugo", "H")
      val minute = 60_000L

      var counter = 0L
      var tick = 0
      fun id() = ItemId("${localDevice.value}:${++counter}")
      fun stamp(device: DeviceId, at: Long) = Hlc(at, tick++, device)

      fun item(name: String, writer: DeviceId, at: Long, qty: Int = 1, checked: Boolean = false, editing: Boolean = false) =
        GroceryItem(
          id = id(),
          name = Field(name, stamp(writer, at)),
          qty = Field(qty, stamp(writer, at)),
          checked = checked,
          checkedAt = stamp(writer, at),
          removed = Field(false, stamp(writer, at)),
          editing = editing,
        )

      val weekly =
        GroceryList(
          id = 1,
          name = "Weekly shop",
          accent = ListAccent.ACCENT,
          people = listOf(you, hugo),
          items =
            listOf(
              item("Sourdough", localDevice, now - 40 * minute),
              item("Oat milk", partner, now - 38 * minute, qty = 2),
              item("Tomatoes", localDevice, now - 26 * 60 * minute, checked = true),
              item("Coffee beans", partner, now - 35 * minute, editing = true),
              item("Butter", localDevice, now - 37 * minute),
              item("Dish soap", partner, now - 72 * minute),
            ),
        )

      val corner =
        listOf("Stamps", "Milk", "Newspaper").mapIndexed { i, n ->
          item(n, localDevice, now - (i + 1) * 3_600_000L)
        }

      val party =
        listOf(
            "Crisps",
            "Olives",
            "Sparkling water",
            "Paper cups",
            "Napkins",
            "Ice",
            "Lemons",
            "Cheese",
            "Crackers",
            "Grapes",
            "Candles",
          )
          .mapIndexed { i, n -> item(n, if (i % 3 == 0) partner else localDevice, now - (i + 1) * 900_000L) }

      // The clash the design opens on: both phones added eggs a minute apart, neither having seen
      // the other. Both stamps carry no `basedOn`, which is exactly what makes them concurrent.
      val clashAt = now - 5 * 60 * minute
      val conflict =
        Conflict(
          yours =
            GroceryItem(
              id = id(),
              name = Field("Eggs", Hlc(clashAt, 0, localDevice)),
              qty = Field(1, Hlc(clashAt, 0, localDevice)),
              checkedAt = Hlc(clashAt, 0, localDevice),
              removed = Field(false, Hlc(clashAt, 0, localDevice)),
            ),
          theirs =
            GroceryItem(
              id = ItemId("${partner.value}:1"),
              name = Field("Eggs", Hlc(clashAt + 60_000, 0, partner)),
              qty = Field(1, Hlc(clashAt + 60_000, 0, partner)),
              checkedAt = Hlc(clashAt + 60_000, 0, partner),
              removed = Field(false, Hlc(clashAt + 60_000, 0, partner)),
            ),
          yourStory = "you added it at 9:02",
          theirStory = "Hugo added it at 9:03",
        )

      return AppState(
        localDevice = localDevice,
        idCounter = counter,
        clockHead = Hlc(now, tick, localDevice),
        // Nothing has been handed over yet, so nothing is collectable yet.
        deliveredTo = DeliveryLog(),
        lists =
          listOf(
            weekly,
            GroceryList(2, "Corner shop", ListAccent.ACCENT_2, corner, listOf(you)),
            GroceryList(3, "Party, Saturday", ListAccent.NEUTRAL, party, listOf(you, hugo)),
          ),
        activeListId = 1,
        online = true,
        // Seeded so the screen has something to show. Real entries arrive when the owner approves
        // a network they are actually standing on, and carry its real fingerprint.
        networks =
          listOf(
            ApprovedNetwork(NetworkFingerprint("seed01"), "Home", "Hugo is approved here too", approved = true),
            ApprovedNetwork(NetworkFingerprint("seed02"), "Hugo's hotspot", "Used in the car", approved = true),
            ApprovedNetwork(NetworkFingerprint("seed03"), "Mum-and-Dad", "Approved, seen in June", approved = false),
          ),
        conflict = conflict,
      )
    }
  }
}
