package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.DeviceIdentity
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.sync.Records
import java.util.Base64
import java.util.zip.CRC32
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.IdFactory
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
      .map { plainName(it) }
      .filter { it.isNotEmpty() }
      .map { token ->
        val match = LEADING_QTY.find(token)
        // A number too long to be a quantity is read as part of the name rather than thrown on.
        val qty = match?.groupValues?.get(1)?.toIntOrNull()
        if (match != null && qty != null) {
          ParsedItem(match.groupValues[2].capitalizeFirst(), qty)
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
 *
 * Version 11 ends with an `end` record holding a checksum of everything before it, and a version 11
 * file without a matching one is refused. The reader skips a malformed line rather than lose the
 * file, and a state with no lists is real, so without it a save cut short after its `meta` line read
 * as a phone with no lists, and the earlier saves that still held them were never tried.
 *
 * Avatars came within version 11, as three columns at the end of `meta` and of `person`, and the
 * stamp of a name as one more after them: a save from 0.1.0 reads as nobody having chosen an avatar
 * or renamed since, and 0.1.0 reads one of these by ignoring them.
 *
 * Recipes came the same way: a `recipe` record, which 0.1.0 skips as a type it does not know, and
 * three columns at the end of `item` saying which recipes it is for, which it never reads. So did
 * the background sync switch, as a `background` record. And when this phone heard of a removal: one
 * stamp at the very end of `item` and of `person`, never sent, which 0.1.0 does not read either.
 * And what this phone cleared from a list, as a `history` record per run, skipped the same way, as
 * are the staples learned from those runs, `learned`, and the names unwanted among them, `unwanted`.
 */
object StateCodec {
  private const val VERSION = "PLSTATE11"

  /**
   * Formats this build can read.
   *
   * Versions 6 to 8 are accepted rather than rejected because what separates them from this one —
   * the owner's name, the staples grid, two sentences about a clash — is not the same kind of hole
   * as a missing identity. Nothing has to be invented: the name comes back blank and first run asks
   * once, the staples grid comes back empty, and the clash sentences are written afresh from the
   * items. Everything they carry — authorship, stamps, tombstones — is read as before.
   */
  private val READABLE = setOf("PLSTATE6", "PLSTATE7", "PLSTATE8", "PLSTATE9", "PLSTATE10", VERSION)

  /** Versions that end with an `end` record, and are refused without a matching one. */
  private val SEALED = setOf(VERSION)

  /**
   * Versions whose list ids are already global.
   *
   * Before this, a list id was a local `max + 1`, so both phones called their first list `1`. Those
   * files are migrated on read: see [migrateListIds]. Nothing is invented and nothing is lost — the
   * ids are internal, never shown, and every reference to them is rewritten in the same pass.
   */
  private val WITH_GLOBAL_LIST_IDS = setOf("PLSTATE10", VERSION)

  fun encode(state: AppState): String {
    val body = encodeBody(state)
    return body + record("end", checksum(body)) + "\n"
  }

  private fun checksum(text: String): String =
    CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }.value.toString(16)

  /** Whether [text] ends with an `end` record whose checksum matches everything before it. */
  private fun sealed(text: String): Boolean {
    val withoutLast = text.removeSuffix("\n")
    val cut = withoutLast.lastIndexOf('\n') + 1
    if (cut == 0) return false
    return withoutLast.substring(cut) == record("end", checksum(text.substring(0, cut)))
  }

  private fun encodeBody(state: AppState): String = buildString {
    appendLine(VERSION)
    appendLine(
      record(
        "meta",
        state.localDevice.value,
        state.idCounter.toString(),
        state.clockHead.encode(),
        state.activeListId.value,
        // A retired `online` flag, kept as "1" so the name stays in its column for any older build.
        "1",
        state.displayName,
        // Last, so a build from before avatars, which stops at the name, still reads this record.
        *Records.field(state.avatar) { it }.toTypedArray(),
        // When the name was last set, after the avatar for the same reason.
        state.displayNameAt.encode(),
      )
    )

    state.conflicts.forEach { c ->
      // Which kind of question, and on which list. The two sides follow on their own lines, and what
      // the card says about them is written at display time rather than stored. One card after
      // another: a file with a single card is exactly what builds that held only one wrote, so
      // several cards needed no new version and no migration.
      appendLine(record("conflict", c.kind.name, c.listId?.value.orEmpty()))
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

    state.staples.forEach { appendLine(record("staple", it.name, it.uses.toString())) }

    // The background sync switch, once the owner or the first start has set it. A record type 0.1.0
    // never wrote, and skips; absent, it reads as never touched.
    state.backgroundSync?.let { appendLine(record("background", it.bool())) }

    // How long a removed learned staple stays away, once chosen other than the default. Another
    // record type 0.1.0 skips; absent, or a choice this build does not know, it reads as the default.
    if (state.stapleComeback != StapleComeback.DEFAULT) appendLine(record("comeback", state.stapleComeback.key))

    // One line per recipe, its ingredients in pairs after the name, so a damaged line costs one
    // recipe and can never hand its ingredients to the one before. A record type 0.1.0 never wrote,
    // and skips.
    state.recipes.forEach { recipe ->
      val ingredients = recipe.ingredients.flatMap { listOf(it.name, it.qty.toString()) }
      appendLine(record(*(listOf("recipe", recipe.name) + ingredients).toTypedArray()))
    }

    state.lists.forEach { list ->
      // Pinned is this phone's own and lives only here; the sync payload's list record has no such
      // field, so it never reaches anyone else.
      appendLine(
        record(
          "list",
          list.id.value,
          list.name,
          list.accent.name,
          list.nameAt.encode(),
          list.pinned.bool(),
          list.arrivedFrom?.value.orEmpty(),
          // Several names in one field: written as a record of their own, so a name holding a comma
          // or a bar comes back whole. Empty for a list that is not a kept copy.
          if (list.wasSharedWith.isEmpty()) "" else Records.line(list.wasSharedWith),
        )
      )
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
            // Their avatar and its stamp, last for the same reason the name is last on `meta`.
            *Records.field(it.avatar) { id -> id }.toTypedArray(),
            it.nameAt.encode(),
            // When this phone heard of their leaving, after everything a payload carries too, and only
            // when there is such a thing. Only here: the sync payload's person record stops above.
            *listOfNotNull(it.removalHeardAt?.encode()).toTypedArray(),
          )
        )
      }
      list.items.forEach { item ->
        // `editing` is presence, not state — it is never written. When this phone heard of the
        // item's removal goes after everything the payload's item record carries, here only, and
        // only for a removal heard of from elsewhere.
        appendLine(record(*(listOf("item", list.id.value) + itemFields(item) + listOfNotNull(item.removalHeardAt?.encode())).toTypedArray()))
      }
      // One line per run, as a recipe has one, its items in pairs after the stamp: a damaged line
      // costs one run. A record type 0.1.0 never wrote, and skips.
      state.historyOf(list.id).forEach { run ->
        val cleared = run.items.flatMap { listOf(it.name, it.qty.toString()) }
        appendLine(record(*(listOf("history", list.id.value, run.at.encode()) + cleared).toTypedArray()))
      }
      // The staples learned for the list, one line each, and the names unwanted on it: two more
      // record types 0.1.0 never wrote, and skips.
      state.learnedOn(list.id).forEach {
        appendLine(record("learned", list.id.value, it.name, it.lastAdded.toString(), it.uses.toString()))
      }
      state.unwantedSince(list.id).forEach { (name, since) -> appendLine(record("unwanted", list.id.value, name, since.encode())) }
    }
  }

  /** Returns null when the text is absent, truncated or from a format this build does not know. */
  fun decode(text: String): AppState? {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    val version = lines.firstOrNull()?.trim()
    if (version !in READABLE) return null
    if (version in SEALED && !sealed(text)) return null

    var localDevice: DeviceId? = null
    var idCounter = 0L
    var clockHead: Hlc? = null
    // Raw, as written. Old files hold a number and new ones hold `deviceId:counter`; which it is
    // only matters once, in migrateListIds, so everything up to there treats it as opaque text.
    var activeListId = ""
    var displayName = ""
    var avatar: Field<String>? = null
    var displayNameAt: Hlc? = null
    // Each `conflict` line opens a card, and the `conflictitem` lines after it fill it in.
    val cards = mutableListOf<RawCard>()
    val networks = mutableListOf<ApprovedNetwork>()
    val peers = mutableListOf<TrustedPeer>()
    val staples = mutableListOf<Staple>()
    val recipes = mutableListOf<Recipe>()
    var backgroundSync: Boolean? = null
    var stapleComeback = StapleComeback.DEFAULT
    val receipts = mutableMapOf<DeviceId, Hlc>()

    // Lists are rebuilt in file order; their people and items arrive on following lines.
    val listOrder = mutableListOf<String>()
    val names = mutableMapOf<String, String>()
    val accents = mutableMapOf<String, ListAccent>()
    val nameStamps = mutableMapOf<String, Hlc>()
    val pins = mutableSetOf<String>()
    val arrivals = mutableMapOf<String, DeviceId>()
    val sharedWith = mutableMapOf<String, List<String>>()
    val people = mutableMapOf<String, MutableList<Person>>()
    val items = mutableMapOf<String, MutableList<GroceryItem>>()
    val runs = mutableMapOf<String, MutableList<ClearedRun>>()
    val learned = mutableMapOf<String, MutableList<LearnedStaple>>()
    val unwanted = mutableMapOf<String, MutableMap<String, Hlc>>()

    for (line in lines.drop(1)) {
      val f = split(line)
      runCatching {
          when (f.getOrNull(0)) {
            "meta" -> {
              localDevice = DeviceId(f[1])
              idCounter = f[2].toLong()
              clockHead = Hlc.decode(f[3])
              activeListId = f[4]
              // f[5] is the retired `online` flag; ignored.
              // Absent in a version 6 file, which simply means this phone has yet to be told.
              displayName = f.getOrNull(6).orEmpty()
              // Absent before avatars, including in every 0.1.0 save: the initial, as it was.
              avatar = Records.parseAvatar(f, from = 7, DeviceId(f[1]))
              // Absent before names were stamped: never renamed since, so any rename outranks it.
              displayNameAt = Records.parseStamp(f, at = 10, DeviceId(f[1]))
            }
            // Versions up to 8 wrote two ready-made sentences here. They are ignored rather than
            // read: the card works them out from the items now, and a stored one was already stale.
            "conflict" ->
              cards +=
                RawCard(
                  // Both absent in files from before edit clashes were asked about: a duplicate, on
                  // whichever list is open.
                  kind = f.getOrNull(1)?.let { runCatching { ConflictKind.valueOf(it) }.getOrNull() } ?: ConflictKind.DUPLICATE,
                  listRaw = f.getOrNull(2)?.takeIf { it.isNotEmpty() },
                )
            "conflictitem" -> {
              val card = cards.lastOrNull() ?: return@runCatching
              val item = parseItem(f, from = 2) ?: return@runCatching
              if (f[1] == "yours") card.yours = item else card.theirs = item
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
            // "Approved just now" was written down at approval and then read for good; the date is
            // lost, so it becomes plain "Approved".
            "net" ->
              networks +=
                ApprovedNetwork(NetworkFingerprint(f[1]), f[2], f[3].takeUnless { it == "Approved just now" } ?: "Approved", f[4] == "1")
            "staple" -> staples += Staple(f[1], f[2].toIntOrNull() ?: 0)
            "background" -> backgroundSync = f[1] == "1"
            "comeback" -> stapleComeback = StapleComeback.ofKey(f[1]) ?: StapleComeback.DEFAULT
            "recipe" -> {
              val ingredients =
                f.drop(2).chunked(2).map { (name, qty) -> Ingredient(name, qty.toIntOrNull() ?: return@runCatching) }
              if (f[1].isNotBlank() && ingredients.isNotEmpty()) recipes += Recipe(f[1], ingredients)
            }
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
              // Absent before shared lists said who they came from: read as already seen.
              f.getOrNull(6)?.takeIf { it.isNotEmpty() }?.let { arrivals[id] = DeviceId(it) }
              // Absent before kept copies said who they were shared with: read as not a kept copy.
              f.getOrNull(7)?.takeIf { it.isNotEmpty() }?.let { sharedWith[id] = Records.split(it) }
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
                  // Absent before avatars, which reads as none chosen.
                  avatar = Records.parseAvatar(f, from = 8, device),
                  nameAt = Records.parseStamp(f, at = 11, device),
                  // Absent before it was kept: settled below, once the clock is known.
                  removalHeardAt = f.getOrNull(PERSON_HEARD)?.let { Hlc.decode(it) },
                )
            }
            "item" -> {
              val item = parseItem(f, from = 2) ?: return@runCatching
              items.getOrPut(f[1]) { mutableListOf() } += item.copy(removalHeardAt = f.getOrNull(ITEM_HEARD)?.let { Hlc.decode(it) })
            }
            "history" -> {
              val at = Hlc.decode(f[2]) ?: return@runCatching
              val cleared =
                f.drop(3).chunked(2).map { (name, qty) -> Ingredient(name, qty.toIntOrNull() ?: return@runCatching) }
              if (cleared.isNotEmpty()) runs.getOrPut(f[1]) { mutableListOf() } += ClearedRun(at, cleared)
            }
            "learned" -> {
              val staple = LearnedStaple(f[2], lastAdded = f[3].toLong(), uses = f[4].toInt())
              if (staple.name.isNotBlank()) learned.getOrPut(f[1]) { mutableListOf() } += staple
            }
            // Absent in the test builds that wrote these before they lapsed: removed before any run.
            "unwanted" ->
              if (f[2].isNotBlank())
                unwanted.getOrPut(f[1]) { mutableMapOf() }[f[2]] =
                  f.getOrNull(3)?.let { Hlc.decode(it) } ?: Hlc(0, 0, DeviceId(f[1].substringBefore(':')))
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
      (everyItemByRawList + cards.flatMap { listOfNotNull(it.yours, it.theirs) })
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
          arrivedFrom = arrivals[raw],
          wasSharedWith = sharedWith[raw].orEmpty(),
        )
      }

    // Both sides must be present, or there is no clash to show.
    val conflicts =
      cards.mapNotNull { card ->
        val yours = card.yours ?: return@mapNotNull null
        val theirs = card.theirs ?: return@mapNotNull null
        Conflict(yours, theirs, card.kind, card.listRaw?.let { renamed.ids[it] ?: ListId(it) })
      }

    val everyItem = lists.flatMap { it.items } + conflicts.flatMap { listOf(it.yours, it.theirs) }

    // A damaged counter must never hand out an id already in use, and the clock must never resume
    // behind a stamp already on disk — either would let a new edit lose to an old one.
    val highestLocal =
      everyItem.filter { it.id.device == device }.mapNotNull { it.id.value.substringAfter(':').toLongOrNull() }.maxOrNull()
        ?: 0L
    val newestStamp = everyItem.map { it.touchedAt }.maxOrNull()
    val resumeAt = if (newestStamp != null && newestStamp > head) newestStamp else head

    /*
     * A removal heard of from another phone, in a save from before that was kept — 0.1.0's, version
     * 10, or this build's own before it — has no mark, and when it arrived cannot be known. It is
     * read as heard just now, at the clock this phone resumes from: it is then collected only once
     * every peer confirms a payload put together after this load, which certainly carries it. The
     * cost is that it waits for one more exchange with each of them than it might have; read by its
     * own stamp instead, it could be collected before a peer behind an old receipt had ever heard.
     * A removal made here needs no mark — its stamp is this phone's own clock — and gets none.
     */
    val settled =
      lists.map { list ->
        list.copy(
          items = list.items.map { if (it.removed.value && it.removalHeardAt == null && it.removed.at.device != device) it.copy(removalHeardAt = resumeAt) else it },
          people = list.people.map { if (it.removed.value && it.removalHeardAt == null && it.removed.at.device != device) it.copy(removalHeardAt = resumeAt) else it },
        )
      }

    return AppState(
      localDevice = device,
      displayName = displayName,
      avatar = avatar ?: Field(Avatars.NONE, Hlc(0, 0, device)),
      displayNameAt = displayNameAt ?: Hlc(0, 0, device),
      deliveredTo = DeliveryLog(receipts),
      // renamed.counter already covers the file's highest, and is ahead of it when a migration
      // minted new list ids.
      idCounter = maxOf(renamed.counter, highestLocal),
      clockHead = resumeAt,
      lists = settled,
      // Through the rename, so a migrated file still opens on the list its owner left open. A file
      // can legitimately hold no lists at all, in which case nothing is active and an empty id —
      // never minted — says so.
      activeListId =
        renamed.ids[activeListId]?.takeIf { active -> lists.any { it.id == active } }
          ?: lists.firstOrNull()?.id
          ?: ListId(""),
      networks = networks,
      // A peer whose key does not hash to its id is not that peer; drop it rather than trust it.
      peers = peers.filter { DeviceIdentity.matches(it.deviceId, it.publicKey) },
      staples = staples,
      recipes = recipes,
      conflicts = conflicts,
      backgroundSync = backgroundSync,
      stapleComeback = stapleComeback,
      // Through the rename like everything else about a list, and only for a list the file holds.
      history = runs.entries.mapNotNull { (raw, kept) -> renamed.ids[raw]?.let { it to kept.asKept() } }.toMap(),
      learnedStaples = learned.entries.mapNotNull { (raw, kept) -> renamed.ids[raw]?.let { it to kept.asKept() } }.toMap(),
      unwantedStaples = unwanted.entries.mapNotNull { (raw, names) -> renamed.ids[raw]?.let { it to names.toMap() } }.toMap(),
    )
  }

  /** A card as read, before its list id is known and while its two sides may still be arriving. */
  private class RawCard(val kind: ConflictKind, val listRaw: String?) {
    var yours: GroceryItem? = null
    var theirs: GroceryItem? = null
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

  /**
   * Where this phone's mark of a heard removal sits: after `item|list|` and the 21 columns the
   * payload's item record also carries, and after `person`'s twelve. 0.1.0 reads an item's first
   * nineteen columns and a person's first eight, and ignores the rest of the line.
   */
  private const val ITEM_HEARD = 23
  private const val PERSON_HEARD = 12

  // The record shape lives in Records, shared with the sync payload, so an item on disk and an
  // item on the wire can never drift apart.
  private fun itemFields(item: GroceryItem): List<String> = Records.itemFields(item)

  private fun parseItem(f: List<String>, from: Int): GroceryItem? = Records.parseItem(f, from)

  private fun record(vararg fields: String): String = Records.line(fields.toList())

  private fun split(line: String): List<String> = Records.split(line)

  private fun Boolean.bool(): String = Records.bool(this)
}
