package io.github.molleware.porygonlist.ui

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.Origin
import io.github.molleware.porygonlist.data.sync.DeviceId
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

/** The other people on a list — everyone who is not this device. */
fun GroceryList.others(localDevice: DeviceId) = people.filter { it.device != localDevice }

/** The single other person, for the many places the design assumes a pair. */
fun GroceryList.partnerName(localDevice: DeviceId): String = others(localDevice).firstOrNull()?.name ?: "them"

/**
 * The line under an item's name in the list detail.
 *
 * This tracks the *last writer*, which is what the design shows: an item Hugo added and you then
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

/** The meta line on a list card: "in step with Hugo", or what is waiting. */
fun listCardMeta(list: GroceryList, online: Boolean, localDevice: DeviceId): String {
  val waiting = list.items.count { it.pending }
  val others = list.others(localDevice)
  return when {
    others.isEmpty() -> "just you"
    !online && waiting > 0 -> "$waiting ${if (waiting == 1) "change" else "changes"} waiting"
    !online -> "will hand over on wifi"
    list.liveItems.any { it.editing } -> "${others.first().name} is adding to it"
    else -> "in step with ${others.joinToString(" & ") { it.name }}"
  }
}

fun itemCountLabel(list: GroceryList): String =
  "${list.liveItems.size} ${if (list.liveItems.size == 1) "item" else "items"}"
