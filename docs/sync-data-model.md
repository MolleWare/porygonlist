# Data model contract for sync

This document is the boundary between the local data layer and the WiFi sync
layer. It describes what the stored model must provide so that two phones can
reconcile their lists without losing anyone's work.

It is a contract, not an implementation plan for the UI. Field names are
suggestions; the properties they carry are not.

The short version: the model in `data/Models.kt` today is shaped for driving one
screen, and it is a good shape for that. It cannot be reconciled across two
phones, for six specific reasons set out below. Each one has a cheap fix if it
happens before persistence is settled, and an expensive one afterwards.

## 1. Identity must be global, not local

`GroceryItem.id` and `GroceryList.id` are currently `Long`, assigned in sequence
from a seed (`1, 2, 3`, then `10, 11, 12`, then `20+`). Two phones offline from
each other will both mint item `7`, for different things. At merge, either they
collide into one item or one silently overwrites the other.

Item and list IDs must be unique without coordination:

```kotlin
/** "k3f9a2:41" — the device that created it, and its own counter. */
@JvmInline value class ItemId(val value: String)

@JvmInline value class ListId(val value: String)
```

`deviceId:counter` is preferred over a random UUID: it is unique without relying
on RNG quality, it sorts stably, and when something goes wrong in the field the
ID says which phone produced it.

Existing seeded data can migrate by prefixing the current device's ID. The seed
in `AppState.seed()` should mint IDs the same way as any other creation path, so
there is one code path rather than two.

## 2. `Author` is a viewpoint, not an identity

```kotlin
enum class Author { YOU, PARTNER }   // today
```

This is the single most important change. `YOU` and `PARTNER` are relative to
whichever phone is looking. An item Hugo creates is `YOU` on his phone and must
become `PARTNER` on Ava's. Sent over the wire as-is, every incoming item claims
to be yours. With a third person on the list, the enum has nowhere to put them.

Store the author as a stable identity, and derive the viewpoint at render time:

```kotlin
@JvmInline value class DeviceId(val value: String)

data class Person(val id: DeviceId, val name: String, val initial: String)

// In the UI layer, not the model:
val Person.isYou: Boolean get() = id == Graph.thisDevice
```

`Author` can survive as a UI-level computed value so `ListDetailScreen` and
`ShareScreen` barely change. It must not be what is stored or transmitted.

## 3. Deletion needs a tombstone

Deleting an item currently removes it from the list. Absence carries no
information, so the peer — which still has the item — hands it back on the next
sync. The item returns. It returns every time.

Deletion has to be a recorded fact:

```kotlin
data class GroceryItem(
  ...
  val deleted: Boolean = false,
  val deletedAt: Hlc? = null,
)
```

Filter `deleted` items out at the UI layer. Keep tombstones for 30 days, then
drop them; by then every peer that is ever coming back has been seen.

## 4. Wall-clock time cannot order edits

`at: Long = System.currentTimeMillis()` is right for the human-readable label
("40 minutes ago") and wrong for deciding which of two edits came later. Phone
clocks disagree by seconds, sometimes by minutes. A merge that trusts them is
not merely occasionally wrong, it is confidently wrong, and it silently discards
the loser.

Use a hybrid logical clock — a wall-clock reading kept monotonic by a counter:

```kotlin
data class Hlc(val wall: Long, val counter: Int, val device: DeviceId) : Comparable<Hlc>

// Local event:   wall = max(now, last.wall); counter = if (wall == last.wall) last.counter + 1 else 0
// On receive:    wall = max(now, last.wall, remote.wall); counter set so the result exceeds both
```

An HLC is the right tool rather than a plain Lamport counter precisely because
the wall component stays meaningful: the UI keeps showing "40 minutes ago" from
`hlc.wall`, while merges order by the whole triple. `device` breaks ties, so the
order is total and both phones compute the same one.

Keep the existing `at` field for display if that is simpler. Do not order by it.

## 5. Merge per field, not per item

If Ava ticks off "Oat milk" while Hugo changes its quantity to 2, both edits
should survive. Whole-item last-writer-wins throws one away.

Each independently editable field carries its own stamp:

```kotlin
data class Field<T>(
  val value: T,
  /** When this value was written. */
  val at: Hlc,
  /** The `at` of the value this one replaced, or null if it is the first. */
  val basedOn: Hlc?,
)

data class GroceryItem(
  val id: ItemId,
  val createdBy: DeviceId,
  val name: Field<String>,
  val qty: Field<Int>,
  val checked: Field<Boolean>,
  val deleted: Field<Boolean>,
  val origin: Origin,
)
```

Merging two versions of an item is then per field: take the value with the
higher `Hlc`. This resolves the overwhelming majority of real edits with no
human involvement, which is the point.

`basedOn` is what makes conflict detection possible, and it is worth being
precise about why. An HLC puts every edit in a total order, but an order alone
cannot distinguish "Hugo edited this after seeing Ava's version" from "they both
edited it, neither having seen the other". Those two need opposite handling and
look identical if all you have is which stamp is larger.

Recording the stamp an edit was made *against* settles it in one field:

```kotlin
/** B was written by someone who had already seen A, so B simply wins. */
fun <T> Field<T>.supersedes(other: Field<T>) = basedOn != null && basedOn >= other.at

/** Neither editor had seen the other's value. This is a real clash. */
fun <T> Field<T>.concurrentWith(other: Field<T>) = !supersedes(other) && !other.supersedes(this)
```

This is cheaper than a version vector per item and sufficient for a
last-writer-wins register, which is all a field here is.

`editing` stays out of this entirely. It is live presence, it is never
persisted, and it never merges — the current code already says so and is right.

`pending` should become derived rather than stored. An item is pending for a
given peer when its newest stamp is above the last stamp that peer has
acknowledged. Stored as a single flag, it cannot express "Hugo has this, Mum's
phone does not".

## 6. A conflict is two values, not a sentence

```kotlin
data class Conflict(val itemName: String, val yours: String, val theirs: String)  // today
// yours = "you added it at 9:02"
```

Those strings are already formatted for display, so nothing can resolve them.
The agreed behaviour is that merging is automatic but a genuine clash is a
person's call, which means the model has to hold both candidates and an action
that picks one:

```kotlin
data class Conflict(
  val id: ConflictId,
  val listId: ListId,
  val kind: Kind,
  val mine: GroceryItem,
  val theirs: GroceryItem,
  val noticedAt: Hlc,
) {
  enum class Kind { SAME_NAME_ADDED, COMPETING_RENAME }
}

sealed interface Resolution {
  data class KeepMine(val id: ConflictId) : Resolution
  data class KeepTheirs(val id: ConflictId) : Resolution
  data class KeepBoth(val id: ConflictId) : Resolution
}
```

Conflicts are device-local and are not synced. Each phone notices the same clash
independently and each person resolves it on their own phone; the resolution
then propagates as an ordinary edit.

### What merges silently, and what surfaces

Automatic, no prompt:

- edits to different items, or to different fields of the same item
- `checked` toggled on both phones — last stamp wins, and the stakes are nil
- `qty` set concurrently to different numbers — last stamp wins. A number is
  easy to see and easy to correct, so a prompt costs more than it saves. Say so
  out loud here because it is a deliberate line, and it is the one most likely
  to want moving later.
- two phones adding genuinely different items

Surfaced as a `Conflict`:

- **`SAME_NAME_ADDED`** — both phones add an item with the same normalised name
  (trim, casefold), neither causally after the other. Auto-merging duplicates
  it; auto-deduping may silently drop a second one that was wanted. This is the
  "Eggs" case already in the seed.
- **`COMPETING_RENAME`** — the same item's `name` set to two different non-empty
  values concurrently. Last-writer-wins here throws away something a person
  typed, which is the one outcome worth interrupting for.

"Concurrently" means `concurrentWith` above: neither editor had seen the other's
value. Note that this is not about how far apart the edits were in time. Two
edits a week apart are not a conflict if the later one was made against the
earlier; two edits a second apart are a conflict if neither phone had heard from
the other.

## 7. Synced state and device state must separate

`StateCodec.encode` writes lists, `activeListId`, `online`, `currentNetwork`,
`networks` and `conflict` into one file. Sync the whole of that and each phone
overwrites the other's idea of which WiFi it is on and which networks it trusts.

```kotlin
/** Crosses the wire. */
data class SyncedState(val lists: List<GroceryList>)

/** Never leaves the phone. */
data class DeviceState(
  val activeListId: ListId,
  val online: Boolean,
  val currentNetwork: String,
  val networks: List<ApprovedNetwork>,
  val conflicts: List<Conflict>,
  val peers: Map<DeviceId, Hlc>,   // highest stamp each peer is known to hold
)
```

`AppState` can stay as the single thing the UI observes, composed of both. The
split only has to exist at the persistence and wire boundary.

Approved networks are emphatically device-local. Whether Hugo trusts his
parents' WiFi is his business and must not arrive from Ava's phone.

## What the sync layer provides

Everything below lives in `data/sync/` and is not the local layer's concern:

- peer discovery on the LAN (`NsdManager`, AOSP, no Play Services)
- pairing, trust, and the TLS transport
- computing and applying deltas
- detecting the conflict cases above and adding them to `DeviceState.conflicts`

The interface it needs from the local layer is small:

```kotlin
interface SyncableStore {
  /** Everything stamped after [since], for sending to one peer. */
  suspend fun changesSince(since: Hlc?): List<GroceryList>

  /** Merge a peer's changes in. Returns any clashes a person must settle. */
  suspend fun merge(incoming: List<GroceryList>, from: DeviceId): List<Conflict>

  /** The newest stamp this device holds. */
  suspend fun head(): Hlc?
}
```

If the local layer implements that, the two halves meet at one seam.

## Cold start

None of this may run during startup. The sync service is constructed lazily in
`Graph`, exactly as `ListRepository` is, and starts only after the first frame —
the same rule the repository already follows, for the same reason. Discovery and
TLS handshakes are especially unwelcome on that path.

## Migration

The stored format is versioned (`PLSTATE1`). The change here is large enough to
warrant `PLSTATE2`, with `decode` returning null for the old version so a first
run after upgrade reseeds rather than half-reads. There are no real users yet,
which makes this the cheapest it will ever be.
