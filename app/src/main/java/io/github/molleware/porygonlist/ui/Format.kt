package io.github.molleware.porygonlist.ui

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.ApprovedNetwork
import io.github.molleware.porygonlist.data.Conflict
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.net.NetworkSnapshot
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The design writes provenance as ready-made strings ("you, 9:12"). Those are stored structured and
 * rendered here instead, so a restart does not leave "just now" frozen under an item added days ago.
 */
private val TIME = DateTimeFormatter.ofPattern("H:mm")
private val DATE = DateTimeFormatter.ofPattern("d MMM")

fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
  if (now - epochMillis < 60_000) return "just now"
  val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
  val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
  return when (then.toLocalDate()) {
    today -> then.format(TIME)
    today.minusDays(1) -> "yesterday"
    else -> then.format(DATE)
  }
}

/**
 * A time with the preposition that makes it read as part of a sentence.
 *
 * [relativeTime] returns the bare label because most of the interface sets it beside a name in a
 * sub-label, where "you, 9:12" is right. In running prose it needs "at 9:12" — and "at just now" or
 * "at yesterday" do not exist, which is the whole reason this is a separate function.
 */
private fun whenPhrase(epochMillis: Long, now: Long, zone: ZoneId): String {
  val label = relativeTime(epochMillis, now, zone)
  if (label == "just now" || label == "yesterday") return label
  val sameDay =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
  return if (sameDay) "at $label" else "on $label"
}

/**
 * When a paired phone last confirmed a handover, in words — or that it never has.
 *
 * Replaces a line the design shipped as a fixed string, "Last handover this morning", which said
 * that at any hour of any day and whether or not anything had ever been handed over. [confirmed] is
 * the peer's delivery receipt: the stamp of the newest payload of ours it has acknowledged. So this
 * is when this phone's changes last reached them, which is the half of a handover this phone can
 * actually vouch for.
 */
fun handoverLabel(confirmed: Hlc?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
  if (confirmed == null) "Nothing handed over yet" else "Last handover ${whenPhrase(confirmed.wall, now, zone)}"

/**
 * One side of a clash, in words: "you added it at 9:02".
 *
 * Worked out from the item every time it is shown rather than written down when the clash was
 * found. A stored sentence is frozen — it would still say "just now" under something added last
 * week — and this is the one card where the two times are the entire argument for reading it.
 *
 * It names the *creator*, not the last writer: a clash is about who independently added the same
 * thing, and nothing has been written since or there would be no clash to show.
 */
fun conflictSide(
  item: GroceryItem,
  list: GroceryList,
  localDevice: DeviceId,
  now: Long = System.currentTimeMillis(),
  zone: ZoneId = ZoneId.systemDefault(),
): String = "${list.nameFor(item.createdBy, localDevice)} added it ${whenPhrase(item.at, now, zone)}"

/** How someone is referred to in running text: "you" on this device, their name anywhere else. */
fun GroceryList.nameFor(peer: DeviceId, localDevice: DeviceId): String =
  if (peer == localDevice) "you" else personFor(peer)?.name ?: "someone"

/** The other people on a list — everyone who is not this device, and has not left it. */
fun GroceryList.others(localDevice: DeviceId) = people.filter { it.present && it.device != localDevice }

/** The single other person, for the many places the design assumes a pair. */
fun GroceryList.partnerName(localDevice: DeviceId): String = others(localDevice).firstOrNull()?.name ?: "them"

/**
 * Several people as running text: "Ava", "Ava & Sam", "Ava, Sam & Léa".
 *
 * The design was drawn for two people, and a list shared three ways read as though one of them did
 * not exist. Everything that names "the others" goes through here.
 */
fun names(people: List<Person>): String =
  when (people.size) {
    0 -> "nobody"
    1 -> people.single().name
    else -> people.dropLast(1).joinToString(", ") { it.name } + " & " + people.last().name
  }

/** "is" for one person, "are" for more — or whichever pair of words is given. */
fun agree(people: List<Person>, one: String, many: String): String = if (people.size == 1) one else many

/**
 * The newest change to anything on this list that travels: an item, its name, who is on it.
 *
 * What a phone has to have confirmed to be in step with it.
 */
fun GroceryList.lastChange(): Hlc =
  (items.map { it.touchedAt } + nameAt + people.map { it.removed.at }).maxOrNull() ?: nameAt

/**
 * The others on this list whose phones have not confirmed its latest change.
 *
 * Judged from receipts — the same proof of delivery tombstones are collected on — rather than from
 * being online. Online only means the two phones *could* talk; a receipt is the other phone saying
 * it did.
 */
fun GroceryList.behind(localDevice: DeviceId, delivered: DeliveryLog): List<Person> {
  val latest = lastChange()
  return others(localDevice).filterNot { person -> delivered.confirmedBy(person.device)?.let { it >= latest } == true }
}

/**
 * The line under an item's name in the list detail.
 *
 * This tracks the *last writer*, which is what the design shows: an item Ava added and you then
 * renamed reads as yours. Who originally added it stays on the item as `createdBy`.
 */
fun itemSubLabel(
  item: GroceryItem,
  list: GroceryList,
  localDevice: DeviceId,
  now: Long = System.currentTimeMillis(),
): String {
  val writer = list.nameFor(item.lastWriter, localDevice)
  return when {
    item.pending -> "$writer, not sent yet"
    item.editing && item.lastWriter != localDevice -> "$writer, editing now"
    item.origin == Origin.MERGED -> "you and ${list.partnerName(localDevice)}, ${relativeTime(item.at, now)}"
    item.origin == Origin.IMPORTED -> "from a text, ${relativeTime(item.at, now)}"
    else -> "$writer, ${relativeTime(item.at, now)}"
  }
}

/**
 * The line under an item in shopping mode.
 *
 * Unlike the list detail this names the *creator*: in the shop what matters is whose errand this
 * is, and ticking something off must not quietly make it yours.
 */
fun shopSubLabel(item: GroceryItem, list: GroceryList, localDevice: DeviceId): String =
  when {
    item.checked -> "in the trolley"
    item.createdBy == localDevice -> "you added this"
    else -> "${list.nameFor(item.createdBy, localDevice)} added this"
  }

/**
 * The meta line on a list card: "in step with Ava", or what is still to reach whom.
 *
 * "In step" is said only once every other phone has confirmed the list's latest change. It used to
 * be said whenever this phone was online, and on hardware a card read "in step with Ava" about a
 * list her phone had never received.
 */
fun listCardMeta(
  list: GroceryList,
  online: Boolean,
  localDevice: DeviceId,
  delivered: DeliveryLog,
  /** A "keep which?" or duplicate card is waiting on this list. It is asked only inside the list. */
  needsAnswer: Boolean = false,
): String {
  val waiting = list.items.count { it.pending }
  val others = list.others(localDevice)
  val behind = list.behind(localDevice, delivered)
  return when {
    needsAnswer -> "a change needs you"
    // Until it is first opened, a list someone shared says so — the one thing that tells it apart
    // from a list of your own with the same name.
    list.arrivedFrom != null -> "new, from ${list.nameFor(list.arrivedFrom, localDevice)}"
    others.isEmpty() -> "just you"
    !online && waiting > 0 -> "$waiting ${if (waiting == 1) "change" else "changes"} waiting"
    list.liveItems.any { it.editing } -> "${others.first().name} is adding to it"
    behind.isEmpty() -> "in step with ${names(others)}"
    behind.none { delivered.confirmedBy(it.device) != null } && behind.size == others.size -> "not handed over yet"
    else -> "waiting for ${names(behind)}"
  }
}

/**
 * The line under the edit sheet: where a change goes.
 *
 * "Ava sees this the moment you save" was the design's line for two people with both phones awake.
 * A change reaches the others when their phones are next on the wifi with this one, which is
 * immediately if they are there now.
 */
fun editSyncNote(state: AppState): String {
  val others = state.activeList.others(state.localDevice)
  return when {
    // Nobody on the list means nobody to see it, whatever the network is doing.
    others.isEmpty() -> "Saved on this phone."
    state.online -> "Goes to ${names(others)} as soon as ${agree(others, "their phone is", "their phones are")} around."
    else -> "Saved here now, handed over next time you share a network."
  }
}

/** Under "Take this off the list?": whose phones the item goes from. */
fun removeNote(state: AppState): String {
  val others = state.activeList.others(state.localDevice)
  return when (others.size) {
    0 -> "It goes from this phone."
    1 -> "It goes from your phone and ${others.single().name}'s."
    else -> "It goes from everyone's phone on this list."
  }
}

/** What the "keep which?" card says, worked out when it is shown rather than stored. */
data class EditClashText(val title: String, val body: String, val keepYours: String, val keepTheirs: String)

/**
 * The words for an item changed on two phones before either had seen the other's change.
 *
 * Each side is said as what that person did to it — "Ava made it “Oat milk ×3”", "you took it off
 * the list" — because the bare values alone ("Oat milk ×2 / Oat milk ×3") do not say that anything
 * happened, let alone that two people did it at once.
 */
fun editClashText(c: Conflict, list: GroceryList, localDevice: DeviceId): EditClashText {
  val yourWriter = writerOf(c.yours, c.theirs)
  val theirWriter = writerOf(c.theirs, c.yours)
  val you = list.nameFor(yourWriter, localDevice)
  val them = list.nameFor(theirWriter, localDevice)

  fun did(version: GroceryItem) = if (version.removed.value) "took it off the list" else "made it “${version.label}”"

  // Normally the first side is this phone's own. With three phones it can be somebody else's that
  // arrived here earlier, and then it is named like anyone else.
  val keepYours = if (yourWriter == localDevice) "Keep yours" else "Keep $you's"
  val keepTheirs = if (theirWriter == localDevice) "Keep yours" else "Keep $them's"
  return EditClashText(
    title = "Changed on two phones at once",
    body = "${you.replaceFirstChar { it.uppercase() }} ${did(c.yours)} and $them ${did(c.theirs)}, " +
      "each before seeing the other's change. Which should stay?",
    keepYours = keepYours,
    keepTheirs = keepTheirs,
  )
}

/** Who made [version] different from [other]: the writer of the newest field where they disagree. */
private fun writerOf(version: GroceryItem, other: GroceryItem): DeviceId {
  val differing =
    listOfNotNull(
      version.name.at.takeIf { version.name.value != other.name.value },
      version.qty.at.takeIf { version.qty.value != other.qty.value },
      version.removed.at.takeIf { version.removed.value != other.removed.value },
    )
  return (differing.maxOrNull() ?: version.touchedAt).device
}

/** The banner's headline: whether everyone you share with has caught up. */
fun syncHeadline(state: AppState): String {
  val shared = state.visibleLists.filter { it.others(state.localDevice).isNotEmpty() }
  val everyone = shared.flatMap { it.others(state.localDevice) }.distinctBy { it.device }
  val behind = shared.flatMap { it.behind(state.localDevice, state.deliveredTo) }.distinctBy { it.device }
  return when {
    !state.online -> "Off the network"
    everyone.isEmpty() -> "Nobody to sync with yet"
    behind.isEmpty() -> "In step with ${names(everyone)}"
    else -> "Waiting for ${names(behind)}"
  }
}

/**
 * The banner's detail: where, and when something last actually reached another phone.
 *
 * It said "a moment ago" whatever had happened, which was the design's placeholder.
 */
fun syncDetail(
  state: AppState,
  networkLabel: String,
  now: Long = System.currentTimeMillis(),
  zone: ZoneId = ZoneId.systemDefault(),
): String {
  if (!state.online) {
    val waiting = state.visibleLists.sumOf { list -> list.items.count { it.pending } }
    return "$waiting ${if (waiting == 1) "change" else "changes"} waiting to hand over"
  }
  val last = state.deliveredTo.receipts.values.maxOrNull()
  return "$networkLabel · " + handoverLabel(last, now, zone).replaceFirstChar { it.lowercase() }
}

fun itemCountLabel(list: GroceryList): String =
  "${list.liveItems.size} ${if (list.liveItems.size == 1) "item" else "items"}"

/**
 * What to call the network this phone is standing on.
 *
 * Three sources, in the order a person would rank them: the name they gave it, the name the network
 * gives itself, and failing both the fingerprint's short form. The middle one is null whenever the
 * optional location permission is not granted, which is why the third still exists.
 *
 * The owner's own name wins over the SSID deliberately. Someone who renamed a network to "Mum's"
 * did it because "BT-HUB-7A2X" was not helping them.
 *
 * [unknown] differs between callers only in its capital letter — it starts a sentence in one place
 * and sits mid-sentence in another.
 */
fun networkLabel(
  known: ApprovedNetwork?,
  snapshot: NetworkSnapshot,
  wifiName: String?,
  unknown: String = "this network",
): String =
  known?.name?.ifBlank { null }
    ?: wifiName
    ?: snapshot.fingerprint?.let { "Network ${it.short}" }
    ?: unknown
