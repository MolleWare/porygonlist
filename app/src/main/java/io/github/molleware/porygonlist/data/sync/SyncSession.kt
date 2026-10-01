package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.Conflict

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
   * field without either having seen the other. [duplicates] are the pairs that turned out to be the
   * same thing added twice; the first of them is put on [state] as the card to answer.
   */
  data class Merged(
    val state: AppState,
    val clashes: List<ItemMerge>,
    val duplicates: List<Conflict>,
    val receipt: Hlc,
  ) : SyncResult

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
 *
 * Two kinds of list arrive here and they are handled differently: one both phones already hold,
 * which merges, and one this phone is being invited onto, which is taken whole. See the comment on
 * `invitations` below for what makes an invitation recognisable and what it still does not permit.
 */
fun AppState.receive(payload: SyncPayload, clock: HybridClock): SyncResult {
  if (payload.from == localDevice) return SyncResult.Rejected(RejectReason.SELF)
  if (peers.none { it.deviceId == payload.from }) return SyncResult.Rejected(RejectReason.UNKNOWN_PEER)

  val known = lists.mapTo(mutableSetOf()) { it.id }

  // Lists both phones hold. These merge field by field.
  val applicable = payload.lists.filter { it.id in known }

  /*
   * Lists this phone has never seen, which it is being invited onto.
   *
   * Joining still happens by invitation rather than by assertion — what changed is that the
   * invitation is now something the receiver can recognise. It is a list from a phone this one has
   * paired with, naming this phone among its people. Both halves matter: pairing is the owner
   * saying yes to the sender, and being named is that sender saying this list is meant for them.
   *
   * What a peer still cannot do is hand over a list that has nothing to do with this phone. A
   * bundle naming only other people is dropped rather than quietly stored, which is what stops a
   * paired phone using this as somewhere to put its own lists.
   *
   * There is no second question on arrival, deliberately. The weight sits at the moment of pairing,
   * where somebody held a phone up and someone else agreed to it; asking again here would be
   * ceremony about a decision already made.
   */
  val invitations = payload.lists.filter { it.id !in known && it.people.any { who -> who.wasEver(localDevice) } }

  if (applicable.isEmpty() && invitations.isEmpty()) return SyncResult.Rejected(RejectReason.NOTHING_SHARED)

  val clashes = mutableListOf<ItemMerge>()
  val duplicates = mutableListOf<Conflict>()
  val mergedLists =
    lists.map { mine ->
      val theirs = applicable.firstOrNull { it.id == mine.id } ?: return@map mine
      if (theirs.people.none { it.wasEver(payload.from) }) return@map mine
      val result = merge(mine, theirs)
      clashes += result.clashes
      duplicates += result.duplicates
      result.list
    } + invitations

  clock.observe(payload.at)
  mergedLists.mapNotNull { it.newestStamp() }.maxOrNull()?.let { clock.observe(it) }

  // One card at a time, and an unanswered one is not thrown away for a newer one: the items behind
  // it are both on the list, so the question keeps until it is asked again on the next handover.
  val surfaced = conflict ?: duplicates.firstOrNull()

  return SyncResult.Merged(
    state = copy(lists = mergedLists, clockHead = clock.head(), conflict = surfaced).pruneDeliveredTombstones(),
    clashes = clashes,
    duplicates = duplicates,
    receipt = payload.at,
  )
}

/**
 * Applies everything one exchange with [peer] produced.
 *
 * [peer] is who the connection **authenticated** as — the key TLS pinned — not who the payload
 * claims to be from. They have to agree. A paired phone that sent a payload naming a different
 * paired phone would otherwise be able to speak for it, and [receive]'s own check would not catch
 * that, because the name it was given is one it trusts.
 *
 * The ack is honoured only if it echoes exactly the stamp that went out. Anything else is not a
 * confirmation of what was sent — and a peer able to confirm an arbitrary stamp could get this phone
 * to collect tombstones it has never actually delivered.
 *
 * [theirs] with no lists is how the other end says "nothing for you", and applying it would only
 * fold an empty stamp into the clock, so it is skipped.
 */
fun AppState.afterExchange(
  peer: DeviceId,
  theirs: SyncPayload?,
  ackOfMine: Hlc?,
  sent: SyncPayload?,
  clock: HybridClock,
): AppState {
  var next = this

  if (theirs != null && theirs.from == peer && theirs.lists.isNotEmpty()) {
    val result = next.receive(theirs, clock)
    if (result is SyncResult.Merged) next = result.state
  }

  if (sent != null && ackOfMine != null && ackOfMine == sent.at) {
    next = next.confirmDelivery(peer, ackOfMine)
  }

  return next
}

/**
 * Records a peer's echo of the stamp we sent, then collects whatever that makes collectable.
 *
 * [receipt] must be the stamp from the payload that peer is confirming — never a fresh reading.
 */
fun AppState.confirmDelivery(peer: DeviceId, receipt: Hlc): AppState =
  copy(deliveredTo = deliveredTo.record(peer, receipt)).pruneDeliveredTombstones()
