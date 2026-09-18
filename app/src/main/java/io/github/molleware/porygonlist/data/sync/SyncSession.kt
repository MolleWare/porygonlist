package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.AppState

/** Why a payload was not applied. */
enum class RejectReason {
  /** The sender is not paired with this phone. Nothing from a stranger is ever merged. */
  UNKNOWN_PEER,

  /** The payload claims to come from this phone. Either a loop, or someone replaying our own words. */
  SELF,

  /** Nothing in it concerns any list shared with that peer. */
  NOTHING_SHARED,
}

sealed interface SyncResult {
  /**
   * The payload was applied.
   *
   * [receipt] is the sender's own stamp, echoed back untouched — their proof that everything they
   * knew at that moment is now here too. [clashes] are the items where both phones changed the same
   * field without either having seen the other.
   */
  data class Merged(val state: AppState, val clashes: List<ItemMerge>, val receipt: Hlc) : SyncResult

  data class Rejected(val reason: RejectReason) : SyncResult
}

/**
 * Assembles what to hand [peer].
 *
 * Only lists that peer is actually on. Sending the others would be a quiet leak — the point of
 * separate lists is that "Party, Saturday" is not everyone's business — and the receiver would
 * reject them anyway for naming people it has never heard of.
 *
 * The clock is read here, at assembly, because the stamp carried is what the peer will echo back as
 * proof of delivery. Reading it later would claim delivery of changes that never left.
 */
fun AppState.payloadFor(peer: DeviceId, clock: HybridClock): SyncPayload? {
  if (peer == localDevice) return null
  val shared = lists.filter { list -> list.people.any { it.wasEver(peer) } }
  if (shared.isEmpty()) return null
  return SyncPayload(from = localDevice, at = clock.head(), lists = shared)
}

/**
 * Applies a payload from a peer.
 *
 * The pairing check is here as well as in the transport on purpose. The channel is what stops a
 * stranger speaking to us at all; this is what stops a bug in that channel turning into someone
 * else's groceries appearing on the list. Two independent reasons to refuse are cheap.
 *
 * Folding the peer's stamp into this phone's clock is the step that keeps a device with a fast
 * clock from winning every future merge: afterwards this clock is ahead of everything it has seen,
 * so the next local edit outranks what just arrived.
 */
fun AppState.receive(payload: SyncPayload, clock: HybridClock): SyncResult {
  if (payload.from == localDevice) return SyncResult.Rejected(RejectReason.SELF)
  if (peers.none { it.deviceId == payload.from }) return SyncResult.Rejected(RejectReason.UNKNOWN_PEER)

  // Only lists this phone already shares with the sender. A peer cannot introduce a list, or add
  // itself to one, by sending it — joining a list happens by invitation, not by assertion.
  val applicable = payload.lists.filter { incoming -> lists.any { it.id == incoming.id } }
  if (applicable.isEmpty()) return SyncResult.Rejected(RejectReason.NOTHING_SHARED)

  val clashes = mutableListOf<ItemMerge>()
  val mergedLists =
    lists.map { mine ->
      val theirs = applicable.firstOrNull { it.id == mine.id } ?: return@map mine
      if (theirs.people.none { it.wasEver(payload.from) }) return@map mine
      val result = merge(mine, theirs)
      clashes += result.clashes
      result.list
    }

  clock.observe(payload.at)
  mergedLists.mapNotNull { it.newestStamp() }.maxOrNull()?.let { clock.observe(it) }

  return SyncResult.Merged(
    state = copy(lists = mergedLists, clockHead = clock.head()).pruneDeliveredTombstones(),
    clashes = clashes,
    receipt = payload.at,
  )
}

/**
 * Records a peer's echo of the stamp we sent, then collects whatever that makes collectable.
 *
 * [receipt] must be the stamp from the payload that peer is confirming — never a fresh reading.
 */
fun AppState.confirmDelivery(peer: DeviceId, receipt: Hlc): AppState =
  copy(deliveredTo = deliveredTo.record(peer, receipt)).pruneDeliveredTombstones()
