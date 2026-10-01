package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person

/**
 * Everything one phone hands another in a single exchange.
 *
 * Whole lists, not deltas. A grocery list is a few dozen items, so sending all of it costs nothing
 * and removes a category of bug outright: there is no "since when" to get wrong, a lost exchange
 * costs nothing because the next one carries the same thing, and the delivery watermark already
 * means "everything I knew at that moment" rather than a range.
 *
 * [at] is the sender's clock when the payload was assembled. The receiver echoes it back untouched,
 * and that echo is the sender's proof of delivery — which is why it must be read at assembly and
 * not at reply time. A later reading would claim delivery of changes that were never in the bundle.
 */
data class SyncPayload(val from: DeviceId, val at: Hlc, val lists: List<GroceryList>)

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
    appendLine(Records.line("from", payload.from.value, payload.at.encode()))

    payload.lists.forEach { list ->
      appendLine(Records.line("list", list.id.value, list.name, list.accent.name))
      list.people.forEach {
        appendLine(
          Records.line(
            "person",
            list.id.value,
            it.device.value,
            it.name,
            it.initial,
            it.formerDevices.joinToString(",") { former -> former.value },
            // Leaving is the one piece of membership a peer has to be told about, so it travels.
            if (it.removed.value) "1" else "0",
            it.removed.at.encode(),
          )
        )
      }
      list.items.forEach { appendLine(Records.line(listOf("item", list.id.value) + Records.itemFields(it))) }
    }
  }

  /** Null for anything this build cannot read. A malformed payload is discarded, never half-applied. */
  fun decode(text: String): SyncPayload? {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.firstOrNull()?.trim() != VERSION) return null

    var from: DeviceId? = null
    var at: Hlc? = null

    val order = mutableListOf<ListId>()
    val names = mutableMapOf<ListId, String>()
    val accents = mutableMapOf<ListId, ListAccent>()
    val people = mutableMapOf<ListId, MutableList<Person>>()
    val items = mutableMapOf<ListId, MutableList<GroceryItem>>()

    for (line in lines.drop(1)) {
      val f = Records.split(line)
      runCatching {
          when (f.getOrNull(0)) {
            "from" -> {
              from = DeviceId(f[1])
              at = Hlc.decode(f[2])
            }
            "list" -> {
              val id = ListId(f[1])
              order += id
              names[id] = f[2]
              accents[id] = ListAccent.valueOf(f[3])
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
          )
        },
    )
  }
}
