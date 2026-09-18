package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.DeviceIdentity
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.Records
import java.util.Base64
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId

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
  private const val VERSION = "PLSTATE7"

  /**
   * Formats this build can read.
   *
   * Version 6 is accepted rather than rejected because the only thing it lacks is the owner's name,
   * and a missing name is not the same kind of hole as a missing identity: nothing has to be
   * invented, the field comes back blank, and first run asks once. Everything it does carry —
   * authorship, stamps, tombstones — is read exactly as before.
   */
  private val READABLE = setOf("PLSTATE6", VERSION)

  fun encode(state: AppState): String = buildString {
    appendLine(VERSION)
    appendLine(
      record(
        "meta",
        state.localDevice.value,
        state.idCounter.toString(),
        state.clockHead.encode(),
        state.activeListId.toString(),
        state.online.bool(),
        state.displayName,
      )
    )

    state.conflict?.let { c ->
      appendLine(record("conflict", c.yourStory, c.theirStory))
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

    state.lists.forEach { list ->
      appendLine(record("list", list.id.toString(), list.name, list.accent.name))
      list.people.forEach {
        appendLine(
          record(
            "person",
            list.id.toString(),
            it.device.value,
            it.name,
            it.initial,
            // Base32 ids carry no commas, so a plain join is unambiguous.
            it.formerDevices.joinToString(",") { former -> former.value },
          )
        )
      }
      list.items.forEach { item ->
        // `editing` is presence, not state — it is never written.
        appendLine(record(*(listOf("item", list.id.toString()) + itemFields(item)).toTypedArray()))
      }
    }
  }

  /** Returns null when the text is absent, truncated or from a format this build does not know. */
  fun decode(text: String): AppState? {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.firstOrNull()?.trim() !in READABLE) return null

    var localDevice: DeviceId? = null
    var idCounter = 0L
    var clockHead: Hlc? = null
    var activeListId = 1L
    var online = true
    var displayName = ""
    var conflictStories: Pair<String, String>? = null
    var conflictYours: GroceryItem? = null
    var conflictTheirs: GroceryItem? = null
    val networks = mutableListOf<ApprovedNetwork>()
    val peers = mutableListOf<TrustedPeer>()
    val receipts = mutableMapOf<DeviceId, Hlc>()

    // Lists are rebuilt in file order; their people and items arrive on following lines.
    val listOrder = mutableListOf<Long>()
    val names = mutableMapOf<Long, String>()
    val accents = mutableMapOf<Long, ListAccent>()
    val people = mutableMapOf<Long, MutableList<Person>>()
    val items = mutableMapOf<Long, MutableList<GroceryItem>>()

    for (line in lines.drop(1)) {
      val f = split(line)
      runCatching {
          when (f.getOrNull(0)) {
            "meta" -> {
              localDevice = DeviceId(f[1])
              idCounter = f[2].toLong()
              clockHead = Hlc.decode(f[3])
              activeListId = f[4].toLong()
              online = f[5] == "1"
              // Absent in a version 6 file, which simply means this phone has yet to be told.
              displayName = f.getOrNull(6).orEmpty()
            }
            "conflict" -> conflictStories = f[1] to f[2]
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
            "list" -> {
              val id = f[1].toLong()
              listOrder += id
              names[id] = f[2]
              accents[id] = ListAccent.valueOf(f[3])
            }
            "person" ->
              people.getOrPut(f[1].toLong()) { mutableListOf() } +=
                Person(
                  device = DeviceId(f[2]),
                  name = f[3],
                  initial = f[4],
                  formerDevices =
                    f.getOrNull(5).orEmpty().split(',').filter { it.isNotBlank() }.map { DeviceId(it) }.toSet(),
                )
            "item" ->
              items.getOrPut(f[1].toLong()) { mutableListOf() } += (parseItem(f, from = 2) ?: return@runCatching)
          }
        }
        // One malformed line should not cost the whole file — skip it and keep the rest.
        .getOrNull()
    }

    // Without an identity and a clock head there is no way to say who authored anything, or to
    // resume ordering safely; treat the file as unreadable rather than guessing.
    val device = localDevice ?: return null
    val head = clockHead ?: return null
    if (listOrder.isEmpty()) return null

    val lists =
      listOrder.map { id ->
        GroceryList(
          id = id,
          name = names[id].orEmpty(),
          accent = accents[id] ?: ListAccent.ACCENT,
          items = items[id].orEmpty(),
          people = people[id].orEmpty(),
        )
      }

    // Both sides and both stories must be present, or there is no conflict to show.
    val conflict =
      conflictStories?.let { (yourStory, theirStory) ->
        val yours = conflictYours
        val theirs = conflictTheirs
        if (yours != null && theirs != null) Conflict(yours, theirs, yourStory, theirStory) else null
      }

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
      idCounter = maxOf(idCounter, highestLocal),
      clockHead = if (newestStamp != null && newestStamp > head) newestStamp else head,
      lists = lists,
      activeListId = if (lists.any { it.id == activeListId }) activeListId else lists.first().id,
      online = online,
      networks = networks,
      // A peer whose key does not hash to its id is not that peer; drop it rather than trust it.
      peers = peers.filter { DeviceIdentity.matches(it.deviceId, it.publicKey) },
      conflict = conflict,
    )
  }

  // The record shape lives in Records, shared with the sync payload, so an item on disk and an
  // item on the wire can never drift apart.
  private fun itemFields(item: GroceryItem): List<String> = Records.itemFields(item)

  private fun parseItem(f: List<String>, from: Int): GroceryItem? = Records.parseItem(f, from)

  private fun record(vararg fields: String): String = Records.line(fields.toList())

  private fun split(line: String): List<String> = Records.split(line)

  private fun Boolean.bool(): String = Records.bool(this)
}
