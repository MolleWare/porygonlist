package io.github.molleware.porygonlist.data.sync

/**
 * What each peer has confirmed receiving from this phone.
 *
 * A tombstone cannot be dropped just because it is old. Drop it while a peer still holds the live
 * item and has never heard it was removed, and the next handover reads that item as news: it comes
 * straight back, and nobody can see why. So a removal has to be kept until there is proof it landed.
 *
 * The proof is a high-water mark rather than a per-item receipt. After an exchange that a peer
 * confirms, it has everything this phone knew at that moment — which is everything stamped at or
 * below this phone's clock head. One [Hlc] per peer therefore covers every change at once, and
 * costs nothing to keep.
 *
 * A peer that has never confirmed anything has no entry, and nothing is ever dropped on its
 * account. Forgetting is the dangerous direction, so the absent case is the safe one.
 */
@JvmInline
value class DeliveryLog(val receipts: Map<DeviceId, Hlc> = emptyMap()) {

  /** The newest point this phone's knowledge that [peer] has confirmed, or null if it never has. */
  fun confirmedBy(peer: DeviceId): Hlc? = receipts[peer]

  /**
   * Records that [peer] has confirmed everything up to [upTo].
   *
   * Only ever moves forward: a late or replayed receipt cannot walk the mark backwards and make
   * already-dropped tombstones look undelivered.
   */
  fun record(peer: DeviceId, upTo: Hlc): DeliveryLog {
    val known = receipts[peer]
    if (known != null && known >= upTo) return this
    return DeliveryLog(receipts + (peer to upTo))
  }

  /** Every peer in [peers] has confirmed a point at or beyond [stamp]. */
  fun deliveredToAll(stamp: Hlc, peers: Collection<DeviceId>): Boolean =
    peers.all { peer -> receipts[peer]?.let { it >= stamp } == true }

  /** Drops peers who are no longer on any list, so the log does not outlive its subjects. */
  fun retaining(peers: Collection<DeviceId>): DeliveryLog =
    DeliveryLog(receipts.filterKeys { it in peers })
}
