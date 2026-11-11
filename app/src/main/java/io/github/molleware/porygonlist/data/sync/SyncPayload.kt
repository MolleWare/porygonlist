package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person

/**
 * Everything one phone hands another in a single exchange.
 *
 * [at] is the sender's clock when the payload was assembled. The receiver echoes it back untouched,
 * and that echo is the sender's proof of delivery — which is why it must be read at assembly and
 * not at reply time. A later reading would claim delivery of changes that were never in the bundle.
 *
 * [callback] is the port the sender listens on, so the phone it called can call it back without
 * discovery: on hardware, one phone never saw the other's mDNS service at all.
 *
 * **Whole lists, unless the peer has said it takes less.** Whole lists were the rule until 0.2.0,
 * and for good reasons: there is no "since when" to get wrong, and a lost exchange costs nothing
 * because the next one carries the same thing. But "a few dozen items" turned out to be 204 on the
 * battery night, sent whole several times a quarter hour, 13 MB each way.
 *
 * So a list can now go **partial**: its own record and all its people, as before, but only the
 * items the peer has not confirmed in their current form, and in [held] the ids of every other item
 * the sender holds. See [PeerCopy] for what decides it, and why it is content and not stamps. A
 * partial list is only ever sent to a phone that says, by [run], that it understands one; every
 * 0.1.0 phone stays silent and keeps getting whole lists.
 *
 * What a partial list leaves out must mean nothing to the receiver, and today nothing does mean
 * anything by absence except two things, both handled in `receive`: a list the receiver does not
 * hold is taken as an invitation, which a partial one never is; and an item missing from the
 * sender's list reads as one the sender has not seen, for spotting the same thing added twice —
 * which is why [held] lists the ids left out. A removal was never an absence: it is a tombstone,
 * and it travels like any other change.
 *
 * [run] is set by a phone that understands partial lists, to a value fixed for the life of its
 * process. It is what tells the other side that what it confirmed before may be gone: a phone
 * restored from an older save, or killed before its last save landed, comes back as a new process
 * with a new run, and is sent whole lists again.
 */
data class SyncPayload(
  val from: DeviceId,
  val at: Hlc,
  val lists: List<GroceryList>,
  val callback: Int? = null,
  val run: String? = null,
  /** For each partial list, the ids of the items the sender holds but left out. Whole lists are absent. */
  val held: Map<ListId, Set<ItemId>> = emptyMap(),
) {
  fun isPartial(list: ListId): Boolean = list in held
}

/**
 * The wire format.
 *
 * Note what a payload does **not** carry: approved networks, paired peers, delivery receipts, or
 * the id counter. Those are this phone's own business, and some of them — the list of everyone you
 * have ever paired with, the wifi networks you frequent — would be a meaningful leak to a peer who
 * only needs to know about the groceries.
 */
object SyncCodec {

  /**
   * Bumped from `PLSYNC1` when list ids became global.
   *
   * A build still speaking 1 would send a local `max + 1` where this expects `deviceId:counter`,
   * and the ids would parse happily as strings while meaning something entirely different — two
   * phones' list `1` are not the same list. Refusing the older version outright is the only safe
   * reading; [decode] returns null and the exchange is simply dropped.
   */
  private const val VERSION = "PLSYNC2"

  fun encode(payload: SyncPayload): String = buildString {
    appendLine(VERSION)
    // The callback port goes last, so a build that reads only the first two fields still reads this.
    // The run after it, for the same reason: 0.1.0 reads the port, and an empty one as none, and
    // stops there.
    val callback = payload.callback?.toString()
    appendLine(
      Records.line(
        listOf("from", payload.from.value, payload.at.encode()) +
          if (payload.run == null) listOfNotNull(callback) else listOf(callback.orEmpty(), payload.run)
      )
    )

    payload.lists.forEach { list ->
      appendLine(listLine(list))
      list.people.forEach { appendLine(personLine(list.id, it)) }
      list.items.forEach { appendLine(itemLine(list.id, it)) }
      // Its presence is what makes the list partial, so it is written even when nothing was left out.
      payload.held[list.id]?.let { appendLine(Records.line(listOf("held", list.id.value) + HeldIds.columns(it))) }
    }
  }

  // One line each for a list, a person on it and an item on it, exactly as they go out. [PeerCopy]
  // remembers what a peer confirmed by these, so a change to what travels is a change it sees.

  // The rename stamp travels, or the receiver cannot tell whose name is newer and keeps its own.
  internal fun listLine(list: GroceryList): String =
    Records.line("list", list.id.value, list.name, list.accent.name, list.nameAt.encode())

  internal fun personLine(list: ListId, person: Person): String =
    Records.line(
      listOf(
        "person",
        list.value,
        person.device.value,
        person.name,
        person.initial,
        person.formerDevices.joinToString(",") { former -> former.value },
        // Leaving is the one piece of membership a peer has to be told about, so it travels.
        if (person.removed.value) "1" else "0",
        person.removed.at.encode(),
      ) +
        // The avatar goes last, so a build from before avatars, which stops at the column above,
        // still reads the rest.
        Records.field(person.avatar) { id -> id } +
        // When they last renamed themselves, after the avatar for the same reason.
        person.nameAt.encode()
    )

  internal fun itemLine(list: ListId, item: GroceryItem): String = Records.line(listOf("item", list.value) + Records.itemFields(item))

  /** Null for anything this build cannot read. A malformed payload is discarded, never half-applied. */
  fun decode(text: String): SyncPayload? {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.firstOrNull()?.trim() != VERSION) return null

    var from: DeviceId? = null
    var at: Hlc? = null
    var callback: Int? = null
    var run: String? = null

    val order = mutableListOf<ListId>()
    val names = mutableMapOf<ListId, String>()
    val accents = mutableMapOf<ListId, ListAccent>()
    val nameStamps = mutableMapOf<ListId, Hlc>()
    val people = mutableMapOf<ListId, MutableList<Person>>()
    val items = mutableMapOf<ListId, MutableList<GroceryItem>>()
    // Kept as read and expanded only after every line, once it is known which lists the payload
    // carries: a run is a few bytes that become thousands of ids, so none is expanded for nothing.
    val heldLines = mutableListOf<Pair<ListId, List<String>>>()

    for (line in lines.drop(1)) {
      val f = Records.split(line)
      if (f.getOrNull(0) == "held") {
        heldLines += ListId(f.getOrNull(1) ?: return null) to f
        continue
      }
      runCatching {
          when (f.getOrNull(0)) {
            "from" -> {
              from = DeviceId(f[1])
              at = Hlc.decode(f[2])
              callback = f.getOrNull(3)?.toIntOrNull()?.takeIf { it in 1..65_535 }
              run = f.getOrNull(4)?.takeIf { it.isNotBlank() }
            }
            "list" -> {
              val id = ListId(f[1])
              order += id
              names[id] = f[2]
              accents[id] = ListAccent.valueOf(f[3])
              f.getOrNull(4)?.let { Hlc.decode(it) }?.let { nameStamps[id] = it }
            }
            "person" -> {
              val device = DeviceId(f[2])
              people.getOrPut(ListId(f[1])) { mutableListOf() } +=
                Person(
                  device = device,
                  name = f[3],
                  initial = f[4],
                  formerDevices =
                    f.getOrNull(5).orEmpty().split(',').filter { it.isNotBlank() }.map { DeviceId(it) }.toSet(),
                  removed =
                    Field(
                      f.getOrNull(6) == "1",
                      f.getOrNull(7)?.let { Hlc.decode(it) } ?: Hlc(0, 0, device),
                    ),
                  avatar = Records.parseAvatar(f, from = 8, device),
                  nameAt = Records.parseStamp(f, at = 11, device),
                )
            }
            "item" ->
              items.getOrPut(ListId(f[1])) { mutableListOf() } +=
                (Records.parseItem(f, from = 2) ?: return@runCatching)
          }
        }
        .getOrNull()
    }

    // Without a sender and a stamp there is nobody to answer and nothing to confirm.
    val sender = from ?: return null
    val stamp = at ?: return null

    // Any other damaged line is skipped, as it always was. A held one cannot be: skipped, it would
    // turn a partial list into a whole one, and what that one leaves out would read as never seen.
    // One for a list the payload does not carry makes nothing whole, so it is dropped unread. Every
    // held line draws on the same room, so many small ones cannot add up to what one may not hold.
    val known = order.toSet()
    val room = HeldIds.Room()
    val held = mutableMapOf<ListId, Set<ItemId>>()
    for ((list, f) in heldLines) {
      if (list !in known) continue
      val ids = HeldIds.parse(f, from = 2, room) ?: return null
      held[list] = held[list].orEmpty() + ids
    }

    return SyncPayload(
      from = sender,
      at = stamp,
      lists =
        order.map { id ->
          GroceryList(
            id = id,
            name = names[id].orEmpty(),
            accent = accents[id] ?: ListAccent.ACCENT,
            items = items[id].orEmpty(),
            people = people[id].orEmpty(),
            nameAt = nameStamps[id] ?: GroceryList.none.nameAt,
          )
        },
      callback = callback,
      run = run,
      held = held,
    )
  }
}

/**
 * The ids a partial list leaves out, written small.
 *
 * Every id is `deviceId:counter`, and a phone's counters run in order, so one list's items from one
 * phone are mostly runs: `DEVICE|1-40,42,45-60`. Two columns per phone that minted any, the prefix
 * then its runs. An id that is not of that shape — none are, but the reader must not depend on it —
 * goes as a prefix of its own with nothing after it.
 */
internal object HeldIds {

  /** Less than half what an item with nothing on it takes, with the 16 characters of a real device id. */
  const val SHORTEST_ITEM_LINE = 64

  /**
   * The most ids one payload's held records may name between them, so a damaged or hostile one
   * cannot fill memory: a line of twenty bytes can name a hundred thousand.
   *
   * What a sender holds it must also be able to send whole, which it does with every new process
   * and every tenth payload, and a whole payload has to fit [SyncExchange.MAX_FRAME]. A line for an
   * item is never shorter than [SHORTEST_ITEM_LINE] (a test holds it to that), so no honest sender
   * can hold more items than this across every list it sends: 16,384, where 204 was a lot.
   */
  const val MOST = SyncExchange.MAX_FRAME / SHORTEST_ITEM_LINE

  /**
   * What is left of [MOST] while a payload is read, shared by all its held records.
   *
   * Each id named counts, once per time it is named, and before a run is expanded: a run repeated
   * many times adds nothing to the set but would still cost the time to walk it.
   */
  class Room(var left: Long = MOST.toLong())

  fun columns(ids: Set<ItemId>): List<String> {
    val counted = mutableMapOf<String, MutableList<Long>>()
    val odd = mutableListOf<String>()
    ids.forEach { id ->
      val prefix = id.value.substringBeforeLast(':', missingDelimiterValue = "")
      val tail = id.value.substringAfterLast(':')
      // Only a number that writes back exactly as it was read; "007" would come back as "7".
      val n = tail.toLongOrNull()?.takeIf { it >= 0 && it.toString() == tail }
      if (prefix.isEmpty() || n == null) odd += id.value else counted.getOrPut(prefix) { mutableListOf() } += n
    }
    return counted.toSortedMap().flatMap { (prefix, numbers) -> listOf(prefix, runs(numbers.sorted())) } +
      odd.sorted().flatMap { listOf(it, "") }
  }

  /**
   * Reads [columns] back from [from], drawing on [room]. Null if anything is malformed, or if it
   * names more ids than [room] has left: see `decode`.
   */
  fun parse(f: List<String>, from: Int, room: Room = Room()): Set<ItemId>? {
    val ids = mutableSetOf<ItemId>()
    var i = from
    while (i < f.size) {
      val prefix = f[i]
      val spec = f.getOrNull(i + 1) ?: return null
      if (prefix.isEmpty()) return null
      if (spec.isEmpty()) {
        if (room.left < 1) return null
        room.left -= 1
        ids += ItemId(prefix)
      } else {
        for (run in spec.split(',')) {
          val first = run.substringBefore('-').toLongOrNull() ?: return null
          val last = if ('-' in run) run.substringAfter('-').toLongOrNull() ?: return null else first
          // Never `last - first + 1`, which overflows for a run to Long.MAX_VALUE.
          if (first < 0 || last < first || last - first >= room.left) return null
          room.left -= last - first + 1
          for (n in first..last) ids += ItemId("$prefix:$n")
        }
      }
      i += 2
    }
    return ids
  }

  private fun runs(sorted: List<Long>): String = buildString {
    var start = sorted.first()
    var end = start
    fun close() {
      if (isNotEmpty()) append(',')
      append(if (start == end) "$start" else "$start-$end")
    }
    sorted.drop(1).forEach { n ->
      if (n == end + 1) end = n
      else {
        close()
        start = n
        end = n
      }
    }
    close()
  }
}
