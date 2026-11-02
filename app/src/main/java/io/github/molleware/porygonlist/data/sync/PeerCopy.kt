package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryList

/**
 * What one peer has confirmed holding of this phone's lists: every list, person and item, as the
 * exact line it was last sent in a payload that peer acknowledged.
 *
 * It decides what a partial payload leaves out — see [partOf] — and it is kept in memory only. A
 * new process has none, so every peer's first exchange of a run is whole, and so is the first one
 * after the peer's own [run] changes.
 *
 * **Content, not stamps.** The obvious rule — send whatever is stamped after the peer's last
 * receipt — is wrong twice over. A receipt is this phone's clock, and an item that arrived here
 * from a third phone keeps that phone's stamps, which can be older than the receipt although this
 * phone learned of it after: Ava would never hear what Hugo had from Sam. And some of what travels
 * has no stamp at all: a person's former phones. Comparing what would go out now with what the peer
 * confirmed has neither problem, and needs no clocks to agree.
 *
 * **Why a confirmed line can be left out.** The peer acknowledged a payload with that line in it, so
 * it merged it, and a merge only ever moves forward: whatever it holds now already includes it, and
 * merging the same line again would change nothing. Nothing else about the peer is assumed. If it
 * lost what it confirmed, it did so in a new process — a restored save, a save that never landed —
 * and says so with a new run, which drops this copy.
 *
 * **What it costs to be wrong is only ever size.** Two exchanges with the same peer can finish in
 * either order, and the later write may leave an older line here than the peer holds. That item then
 * goes again, unnecessarily. The opposite — a line here that the peer never confirmed — cannot
 * happen, because only an acknowledged payload is ever written in.
 */
data class PeerCopy(
  /** The peer's run when it confirmed. A different run now means it may have lost all of this. */
  val run: String,
  val lists: Map<ListId, ListCopy> = emptyMap(),
  /** Partial payloads confirmed since the last whole one, for [WHOLE_EVERY]. */
  val sinceWhole: Int = 0,
) {

  /** One list as the peer confirmed it: its own line, its people's lines together, its items' lines. */
  data class ListCopy(val header: String, val people: String, val items: Map<ItemId, String>) {
    companion object {
      fun of(list: GroceryList): ListCopy =
        ListCopy(
          header = SyncCodec.listLine(list),
          people = peopleLines(list),
          items = list.items.associate { it.id to SyncCodec.itemLine(list.id, it) },
        )
    }
  }

  /** Still fit to cut from: the peer is on the same run, and the safety net is not due. */
  fun usableFor(peerRun: String?): Boolean = peerRun == run && sinceWhole < WHOLE_EVERY

  /**
   * [whole], cut down to what this peer has not confirmed.
   *
   * - A list it has no copy of goes whole — one newly shared with it, or one it has never confirmed.
   * - So does a list whose people have changed in any way. Someone added has no copy at all, and it
   *   is simpler to send the list whole to everyone than to reason about who among them needs it;
   *   people change rarely, and a person's former phones are not stamped.
   * - Otherwise the list goes partial: its line and all its people, as always, every item whose line
   *   differs from the one confirmed, and the ids of the rest. Tombstones included — a removal is a
   *   line like any other, and goes until it is confirmed.
   * - A list with nothing changed at all is left out of the payload, which says nothing about it.
   *
   * [whole]'s stamp is kept: the peer's ack of it still means it holds everything this phone knew
   * at that moment, now that what it did not get this time it already had.
   */
  fun partOf(whole: SyncPayload): SyncPayload {
    val held = mutableMapOf<ListId, Set<ItemId>>()
    val lists =
      whole.lists.mapNotNull { list ->
        val copy = lists[list.id] ?: return@mapNotNull list
        if (copy.people != peopleLines(list)) return@mapNotNull list
        val (changed, unchanged) = list.items.partition { copy.items[it.id] != SyncCodec.itemLine(list.id, it) }
        if (changed.isEmpty() && copy.header == SyncCodec.listLine(list)) return@mapNotNull null
        held[list.id] = unchanged.mapTo(mutableSetOf()) { it.id }
        list.copy(items = changed)
      }
    return whole.copy(lists = lists, held = held)
  }

  /**
   * This copy once the peer has acknowledged [sent], a payload cut from it by [partOf].
   *
   * A list that went whole is taken as it went. A partial one takes the lines that went, and keeps
   * only the items the sender then held, so a collected tombstone does not stay here for ever. A
   * list left out is as it was.
   */
  fun confirming(sent: SyncPayload): PeerCopy {
    val next = lists.toMutableMap()
    sent.lists.forEach { list ->
      val kept = sent.held[list.id]
      if (kept == null) {
        next[list.id] = ListCopy.of(list)
      } else {
        val before = lists[list.id] ?: return@forEach
        val sentNow = list.items.associate { it.id to SyncCodec.itemLine(list.id, it) }
        next[list.id] =
          ListCopy(
            header = SyncCodec.listLine(list),
            people = peopleLines(list),
            items = before.items.filterKeys { it in kept } + sentNow,
          )
      }
    }
    return copy(lists = next, sinceWhole = sinceWhole + 1)
  }

  companion object {
    /**
     * Every this-many partial payloads to a peer, one goes whole regardless.
     *
     * The safety net, for whatever the reasoning above has missed: a peer that lost something
     * without changing run, or a line this phone compares wrongly. Neither is known to happen. The
     * net bounds how long either could last, to this many exchanges, at a tenth of what whole lists
     * cost. It rarely fires on its own: a phone woken for a background window is a new process, and
     * starts whole anyway; it is for the phone left open on the shopping list, ticking things off.
     */
    const val WHOLE_EVERY = 10

    /** A copy of everything in [whole], a payload that went out with every list whole. */
    fun of(whole: SyncPayload, run: String): PeerCopy =
      PeerCopy(run = run, lists = whole.lists.associate { it.id to ListCopy.of(it) })
  }
}

private fun peopleLines(list: GroceryList): String = list.people.joinToString("\n") { SyncCodec.personLine(list.id, it) }
