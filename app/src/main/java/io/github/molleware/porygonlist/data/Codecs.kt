package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.DeviceIdentity
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.Records
import java.util.Base64
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.IdFactory
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId

/**
 * The shareable text format, as the design defines it:
 *
 *     PL1 · Weekly shop · sourdough, 2 oat milk, coffee beans
 *
 * One message, small enough to send anywhere, and readable by a person who has never used the app.
 * This is the fallback for when two phones are nowhere near a shared network.
 *
 * Note it deliberately carries no identity or clock: it is a message for a human, not a sync
 * payload. Items pasted in are created fresh on the receiving device, authored by whoever pasted
 * them, and marked [Origin.IMPORTED].
 */
object ShareCodec {
  private const val PREFIX = "PL1"
  private const val SEP = " · "

  /** Ticked-off items are left out — the point of the message is what is still to get. */
  fun encode(list: GroceryList): String {
    val body =
      list.liveItems.filterNot { it.checked }.joinToString(", ") { item ->
        val qty = if (item.qty.value > 1) "${item.qty.value} " else ""
        qty + item.name.value.lowercase()
      }
    return PREFIX + SEP + list.name + SEP + body
  }

  data class ParsedItem(val name: String, val qty: Int)

  /**
   * Reads a message back. The header is optional: someone may paste only the item run, or a plain
   * list typed by hand, and both should work.
   */
  fun decode(raw: String): List<ParsedItem> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()

    // Drop "PL1 · <list name> · " when it is there, keeping any interpuncts inside the body.
    val body = if (trimmed.contains('·')) trimmed.split('·').drop(2).joinToString("·") else trimmed

    return body
      .split(',', '\n')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { token ->
        val match = LEADING_QTY.find(token)
        if (match != null) {
          ParsedItem(match.groupValues[2].capitalizeFirst(), match.groupValues[1].toInt())
        } else {
          ParsedItem(token.capitalizeFirst(), 1)
        }
      }
      .filter { it.name.isNotEmpty() }
  }

  private val LEADING_QTY = Regex("""^(\d+)\s+(.*)$""")

  private fun String.capitalizeFirst() = replaceFirstChar { it.uppercase() }
}

/**
 * On-disk format for the whole app state.
 *
 * Deliberately hand-rolled and line-based: the app carries no serialization dependency, which keeps
 * the F-Droid build simple and the startup cost near zero. The volume here is a few dozen items, so
 * a parser this small is the right size of tool.
 *
 * Every record is `type|field|field|…`, one per line. Fields are escaped so a list called
 * "Bread | Milk" survives the round trip.
 *
 * Version 4 carries sync state: this device's [DeviceId], the id counter, the head of its hybrid
 * logical clock, per-item [Field]s with their stamps and causal `basedOn`, and removal tombstones.
 * Earlier versions lacked identity entirely and cannot be upgraded
 * without inventing authorship, so they are rejected rather than half-read.
 *
 * Version 7 adds the owner's name to the `meta` record. Version 6 is still read; see [READABLE].
 */
object StateCodec {
  private const val VERSION = "PLSTATE10"

  /**
   * Formats this build can read.
   *
   * Versions 6 to 8 are accepted rather than rejected because what separates them from this one —
   * the owner's name, the staples grid, two sentences about a clash — is not the same kind of hole
   * as a missing identity. Nothing has to be invented: the name comes back blank and first run asks
   * once, the staples come back as the default set, and the clash sentences are written afresh from
   * the items. Everything they carry — authorship, stamps, tombstones — is read as before.
   */
  private val READABLE = setOf("PLSTATE6", "PLSTATE7", "PLSTATE8", "PLSTATE9", VERSION)

  /** Versions that write a staples record, so an older file can be given the defaults instead. */
  private val WITH_STAPLES = setOf("PLSTATE8", "PLSTATE9", VERSION)

  /**
   * Versions whose list ids are already global.
   *
   * Before this, a list id was a local `max + 1`, so both phones called their first list `1`. Those
   * files are migrated on read: see [migrateListIds]. Nothing is invented and nothing is lost — the
   * ids are internal, never shown, and every reference to them is rewritten in the same pass.
   */
  private val WITH_GLOBAL_LIST_IDS = setOf(VERSION)

  fun encode(state: AppState): String = buildString {
    appendLine(VERSION)
    appendLine(
      record(
        "meta",
        state.localDevice.value,
        state.idCounter.toString(),
        state.clockHead.encode(),
        state.activeListId.value,
        state.online.bool(),
        state.displayName,
      )
    )

    state.conflict?.let { c ->
      // A marker only. The two sides follow on their own lines, and what the card says about them
      // is written at display time rather than stored.
      appendLine(record("conflict"))
      appendLine(record(*(listOf("conflictitem", "yours") + itemFields(c.yours)).toTypedArray()))
      appendLine(record(*(listOf("conflictitem", "theirs") + itemFields(c.theirs)).toTypedArray()))
    }

    // Proof of receipt, one line per peer. Losing these is safe: tombstones simply stay longer.
    state.deliveredTo.receipts.forEach { (peer, upTo) -> appendLine(record("receipt", peer.value, upTo.encode())) }

    state.peers.forEach {
      appendLine(
        record(
          "peer",
          it.deviceId.value,
          Base64.getUrlEncoder().withoutPadding().encodeToString(it.publicKey),
          it.name,
          it.pairedAt.toString(),
        )
      )
    }

    state.networks.forEach {
      appendLine(record("net", it.fingerprint.value, it.name, it.detail, it.approved.bool()))
    }

    // Written even when empty is impossible to express here, so an absent record is read as "this
    // file predates staples" and the version is what tells the two apart — see FIRST_WITH_STAPLES.
    state.staples.forEach { appendLine(record("staple", it.name, it.uses.toString())) }

    state.lists.forEach { list ->
      // Pinned is this phone's own and lives only here; the sync payload's list record has no such
      // field, so it never reaches anyone else.
      appendLine(record("list", list.id.value, list.name, list.accent.name, list.nameAt.encode(), list.pinned.bool()))
      list.people.forEach {
        appendLine(
          record(
            "person",
            list.id.value,
            it.device.value,
            it.name,
            it.initial,
            // Base32 ids carry no commas, so a plain join is unambiguous.
            it.formerDevices.joinToString(",") { former -> former.value },
            // Whether they have left, and when it was decided. Both are needed: the stamp is what
            // lets a later decision at either end win, and without it a leaving never sticks.
            it.removed.value.bool(),
            it.removed.at.encode(),
          )
        )
      }
      list.items.forEach { item ->
        // `editing` is presence, not state — it is never written.
        appendLine(record(*(listOf("item", list.id.value) + itemFields(item)).toTypedArray()))
      }
    }
  }

  /** Returns null when the text is absent, truncated or from a format this build does not know. */
  fun decode(text: String): AppState? {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    val version = lines.firstOrNull()?.trim()
    if (version !in READABLE) return null

    var localDevice: DeviceId? = null
    var idCounter = 0L
    var clockHead: Hlc? = null
    // Raw, as written. Old files hold a number and new ones hold `deviceId:counter`; which it is
    // only matters once, in migrateListIds, so everything up to there treats it as opaque text.
    var activeListId = ""
    var online = true
    var displayName = ""
    var sawConflict = false
    var conflictYours: GroceryItem? = null
    var conflictTheirs: GroceryItem? = null
    val networks = mutableListOf<ApprovedNetwork>()
    val peers = mutableListOf<TrustedPeer>()
    val staples = mutableListOf<Staple>()
    val receipts = mutableMapOf<DeviceId, Hlc>()

    // Lists are rebuilt in file order; their people and items arrive on following lines.
    val listOrder = mutableListOf<String>()
    val names = mutableMapOf<String, String>()
    val accents = mutableMapOf<String, ListAccent>()
    val nameStamps = mutableMapOf<String, Hlc>()
    val pins = mutableSetOf<String>()
    val people = mutableMapOf<String, MutableList<Person>>()
    val items = mutableMapOf<String, MutableList<GroceryItem>>()

    for (line in lines.drop(1)) {
      val f = split(line)
      runCatching {
          when (f.getOrNull(0)) {
            "meta" -> {
              localDevice = DeviceId(f[1])
              idCounter = f[2].toLong()
              clockHead = Hlc.decode(f[3])
              activeListId = f[4]
              online = f[5] == "1"
              // Absent in a version 6 file, which simply means this phone has yet to be told.
              displayName = f.getOrNull(6).orEmpty()
            }
            // Versions up to 8 wrote two ready-made sentences here. They are ignored rather than
            // read: the card works them out from the items now, and a stored one was already stale.
            "conflict" -> sawConflict = true
            "conflictitem" -> {
              val item = parseItem(f, from = 2) ?: return@runCatching
              if (f[1] == "yours") conflictYours = item else conflictTheirs = item
            }
            "receipt" -> receipts[DeviceId(f[1])] = Hlc.decode(f[2]) ?: return@runCatching
            "peer" ->
              peers +=
                TrustedPeer(
                  deviceId = DeviceId(f[1]),
                  publicKey = Base64.getUrlDecoder().decode(f[2]),
                  name = f[3],
                  pairedAt = f[4].toLong(),
                )
            "net" -> networks += ApprovedNetwork(NetworkFingerprint(f[1]), f[2], f[3], f[4] == "1")
            "staple" -> staples += Staple(f[1], f[2].toIntOrNull() ?: 0)
            "list" -> {
              val id = f[1]
              listOrder += id
              names[id] = f[2]
              accents[id] = ListAccent.valueOf(f[3])
              // Absent before renames were stamped — including in version 10 files already on a
              // phone — which reads as never renamed: any rename from elsewhere then wins.
              f.getOrNull(4)?.let { Hlc.decode(it) }?.let { nameStamps[id] = it }
              // Absent before pinning existed, which reads as not pinned.
              if (f.getOrNull(5) == "1") pins += id
            }
            "person" -> {
              val device = DeviceId(f[2])
              people.getOrPut(f[1]) { mutableListOf() } +=
                Person(
                  device = device,
                  name = f[3],
                  initial = f[4],
                  formerDevices =
                    f.getOrNull(5).orEmpty().split(',').filter { it.isNotBlank() }.map { DeviceId(it) }.toSet(),
                  // Absent in a file written before leaving was expressible, which simply means
                  // nobody has left: everyone recorded there is still on the list.
                  removed =
                    Field(
                      f.getOrNull(6) == "1",
                      f.getOrNull(7)?.let { Hlc.decode(it) } ?: Hlc(0, 0, device),
                    ),
                )
            }
            "item" ->
              items.getOrPut(f[1]) { mutableListOf() } += (parseItem(f, from = 2) ?: return@runCatching)
          }
        }
        // One malformed line should not cost the whole file — skip it and keep the rest.
        .getOrNull()
    }

    // Without an identity and a clock head there is no way to say who authored anything, or to
    // resume ordering safely; treat the file as unreadable rather than guessing.
    val device = localDevice ?: return null
    val head = clockHead ?: return null
    // No check on the lists: a file holding none is a real state, not a truncated one. Every install
    // starts there and stays there until someone makes a list, and rejecting it would throw away the
    // identity and clock in the `meta` record and start the phone over as if it were new.

    // Everything below refers to lists by their new ids, so the rename happens before the lists are
    // built rather than being threaded through afterwards.
    val everyItemByRawList = items.values.flatten()
    val highestBefore =
      (everyItemByRawList + listOfNotNull(conflictYours, conflictTheirs))
        .filter { it.id.device == device }
        .mapNotNull { it.id.value.substringAfter(':').toLongOrNull() }
        .maxOrNull() ?: 0L

    val renamed = migrateListIds(version, listOrder, device = device, from = maxOf(idCounter, highestBefore))

    val lists =
      listOrder.map { raw ->
        val id = renamed.ids.getValue(raw)
        GroceryList(
          id = id,
          name = names[raw].orEmpty(),
          accent = accents[raw] ?: ListAccent.ACCENT,
          items = items[raw].orEmpty(),
          people = people[raw].orEmpty(),
          nameAt = nameStamps[raw] ?: GroceryList.none.nameAt,
          pinned = raw in pins,
        )
      }

    // Both sides must be present, or there is no clash to show.
    val conflict =
      if (sawConflict && conflictYours != null && conflictTheirs != null) Conflict(conflictYours, conflictTheirs)
      else null

    val everyItem = lists.flatMap { it.items } + listOfNotNull(conflict?.yours, conflict?.theirs)

    // A damaged counter must never hand out an id already in use, and the clock must never resume
    // behind a stamp already on disk — either would let a new edit lose to an old one.
    val highestLocal =
      everyItem.filter { it.id.device == device }.mapNotNull { it.id.value.substringAfter(':').toLongOrNull() }.maxOrNull()
        ?: 0L
    val newestStamp = everyItem.map { it.touchedAt }.maxOrNull()

    return AppState(
      localDevice = device,
      displayName = displayName,
      deliveredTo = DeliveryLog(receipts),
      // renamed.counter already covers the file's highest, and is ahead of it when a migration
      // minted new list ids.
      idCounter = maxOf(renamed.counter, highestLocal),
      clockHead = if (newestStamp != null && newestStamp > head) newestStamp else head,
      lists = lists,
      // Through the rename, so a migrated file still opens on the list its owner left open. A file
      // can legitimately hold no lists at all, in which case nothing is active and an empty id —
      // never minted — says so.
      activeListId =
        renamed.ids[activeListId]?.takeIf { active -> lists.any { it.id == active } }
          ?: lists.firstOrNull()?.id
          ?: ListId(""),
      online = online,
      networks = networks,
      // A peer whose key does not hash to its id is not that peer; drop it rather than trust it.
      peers = peers.filter { DeviceIdentity.matches(it.deviceId, it.publicKey) },
      // An empty grid is a real state someone can reach by removing every tile, so a stored one is
      // taken at its word. A file written before staples were storable has none to restore.
      staples = if (version in WITH_STAPLES) staples else Staple.defaults,
      conflict = conflict,
    )
  }

  /** What a file's raw list ids became, and where the id counter ended up. */
  private class Renamed(val ids: Map<String, ListId>, val counter: Long)

  /**
   * Turns the list ids in a file into global ones.
   *
   * A current file already holds `deviceId:counter` and is passed through untouched. An older one
   * holds a local `max + 1`, and those are **re-minted rather than rewritten in place**. The
   * tempting shortcut — `<device>:<old number>` — would collide with the item namespace, because
   * [IdFactory] runs a single counter for items and lists alike, so list `1` could be handed a
   * string an item already holds. Minting keeps that invariant intact.
   *
   * [from] must already account for the highest counter seen in the file, or a migrated list could
   * take an id an existing item is using.
   */
  private fun migrateListIds(version: String?, order: List<String>, device: DeviceId, from: Long): Renamed {
    if (version in WITH_GLOBAL_LIST_IDS) {
      return Renamed(ids = order.associateWith { ListId(it) }, counter = from)
    }

    val factory = IdFactory(device, start = from)
    // In file order, so a migrated phone's lists keep the order their owner put them in.
    val ids = order.associateWith { factory.nextList() }
    return Renamed(ids = ids, counter = factory.peek())
  }

  // The record shape lives in Records, shared with the sync payload, so an item on disk and an
  // item on the wire can never drift apart.
  private fun itemFields(item: GroceryItem): List<String> = Records.itemFields(item)

  private fun parseItem(f: List<String>, from: Int): GroceryItem? = Records.parseItem(f, from)

  private fun record(vararg fields: String): String = Records.line(fields.toList())

  private fun split(line: String): List<String> = Records.split(line)

  private fun Boolean.bool(): String = Records.bool(this)
}
