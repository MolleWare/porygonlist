package io.github.molleware.porygonlist.data.sync

/**
 * This installation of the app, as the other phones know it.
 *
 * It identifies a phone, not a person: the same person on a new phone is a new device that pairs
 * again.
 *
 * **Never construct one at random.** An id is the fingerprint of a public key — see
 * [io.github.molleware.porygonlist.data.crypto.DeviceIdentity] — so that claiming an id requires
 * holding the matching private key. A drawn id is only a label, and anything on a network can put
 * any label it likes on the wire.
 */
@JvmInline value class DeviceId(val value: String)

@JvmInline value class ItemId(val value: String)

@JvmInline value class ListId(val value: String)

/**
 * Mints IDs that are unique without asking anyone.
 *
 * `deviceId:counter` is preferred over a random UUID on three counts: it cannot collide rather than
 * merely being unlikely to, it sorts into creation order within a device, and when something is
 * wrong in the field the ID says which phone produced it.
 *
 * [next] must never return a value this device has used before, so the counter is persisted
 * alongside the clock and restored through `start`.
 */
class IdFactory(private val device: DeviceId, start: Long = 0) {
  private var counter: Long = start

  @Synchronized fun peek(): Long = counter

  @Synchronized private fun next(): String = "${device.value}:${++counter}"

  @Synchronized fun nextItem(): ItemId = ItemId(next())

  @Synchronized fun nextList(): ListId = ListId(next())
}
