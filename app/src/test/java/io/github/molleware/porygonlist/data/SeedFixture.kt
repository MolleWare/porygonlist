package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.ItemId

/**
 * A populated [AppState] for tests to work against.
 *
 * This used to be `AppState.seed` in production, shipped as the content a new install opened onto.
 * It is test-only now: the app starts empty ([AppState.empty]), because content nobody entered is
 * the app claiming a history that never happened. The data itself is still worth keeping here —
 * three lists of different shapes, two people, a genuine concurrent clash and a mix of approved and
 * unapproved networks exercise far more of the codecs and merge than a hand-built fixture would.
 *
 * It stays an extension on the companion so call sites read `AppState.seed(...)` exactly as before.
 *
 * Nothing in the app may call this. If a screen needs something to show, it needs an empty state,
 * not invented content.
 */
fun AppState.Companion.seed(
  localDevice: DeviceId,
  partner: DeviceId = DeviceId("avademo01"),
  now: Long = System.currentTimeMillis(),
): AppState {
  val you = Person(localDevice, "", "")
  val ava = Person(partner, "Ava", "A")
  val minute = 60_000L

  // The shape IdFactory mints, so a fixture list is indistinguishable from a real one. Tests that
  // care about two phones disagreeing need ids that carry the phone they came from.
  fun listId(n: Int) = ListId("${localDevice.value}:$n")

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
      id = listId(1),
      name = "Weekly shop",
      accent = ListAccent.ACCENT,
      people = listOf(you, ava),
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

  // Both phones added eggs a minute apart, neither having seen the other. Both stamps carry no
  // `basedOn`, which is exactly what makes them concurrent.
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
        GroceryList(listId(2), "Corner shop", ListAccent.ACCENT_2, corner, listOf(you)),
        GroceryList(listId(3), "Party, Saturday", ListAccent.NEUTRAL, party, listOf(you, ava)),
      ),
    activeListId = listId(1),
    online = true,
    networks =
      listOf(
        ApprovedNetwork(NetworkFingerprint("seed01"), "Home", "Ava is approved here too", approved = true),
        ApprovedNetwork(NetworkFingerprint("seed02"), "Ava's hotspot", "Used in the car", approved = true),
        ApprovedNetwork(NetworkFingerprint("seed03"), "Mum-and-Dad", "Approved, seen in June", approved = false),
      ),
    staples = SEED_STAPLES,
    conflict = conflict,
  )
}

/**
 * The starter shortlist the app used to ship. Kept here so the staples tests still have a populated
 * grid to encode, decode and tick; [Staple.defaults] is empty now.
 */
val SEED_STAPLES: List<Staple> =
  listOf("Oat milk", "Eggs", "Sourdough", "Coffee beans", "Olive oil", "Rice", "Bin bags", "Yoghurt", "Tinned beans")
    .map { Staple(it) }
