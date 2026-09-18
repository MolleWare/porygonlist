package io.github.molleware.porygonlist.data.sync

/**
 * A hybrid logical clock: a wall-clock reading kept monotonic by a counter, tagged with the device
 * that produced it.
 *
 * Two phones cannot agree on the time. They disagree by seconds, and a phone that has been off for a
 * week can disagree by minutes. Ordering edits by `System.currentTimeMillis()` is therefore not
 * occasionally wrong but confidently wrong, and the edit it discards is a real one somebody typed.
 *
 * A plain Lamport counter would order edits correctly and destroy the one useful property of a
 * timestamp: that a person can read it. The UI needs "40 minutes ago". Keeping the wall reading and
 * repairing it with a counter gives both — [wall] stays close to real time, while the whole triple
 * gives a total order that both phones compute identically.
 */
data class Hlc(val wall: Long, val counter: Int, val device: DeviceId) : Comparable<Hlc> {

  override fun compareTo(other: Hlc): Int {
    val byWall = wall.compareTo(other.wall)
    if (byWall != 0) return byWall
    val byCounter = counter.compareTo(other.counter)
    if (byCounter != 0) return byCounter
    // The device id is the tie-break of last resort. It carries no meaning; it exists so that the
    // order is total and every phone agrees on it.
    return device.value.compareTo(other.device.value)
  }

  /** `1737030000000-3-k3f9a2`, which survives the line-based codecs unescaped. */
  fun encode(): String = "$wall-$counter-${device.value}"

  companion object {
    /** Returns null for anything this build cannot read, which the codecs treat as a skipped line. */
    fun decode(text: String): Hlc? {
      val wallEnd = text.indexOf('-')
      if (wallEnd <= 0) return null
      val counterEnd = text.indexOf('-', wallEnd + 1)
      if (counterEnd < 0) return null

      val wall = text.substring(0, wallEnd).toLongOrNull() ?: return null
      val counter = text.substring(wallEnd + 1, counterEnd).toIntOrNull() ?: return null
      val device = text.substring(counterEnd + 1)
      if (device.isEmpty()) return null

      return Hlc(wall, counter, DeviceId(device))
    }
  }
}

/**
 * Issues stamps for this device.
 *
 * Every local edit calls [tick]. Every stamp arriving from a peer is passed to [observe] before it is
 * used, which is what keeps this phone's clock ahead of anything it has seen — without it, a peer
 * whose clock runs fast would make all of its edits permanently win.
 */
class HybridClock(
  private val device: DeviceId,
  private val now: () -> Long = System::currentTimeMillis,
  start: Hlc? = null,
) {
  private var last: Hlc = start ?: Hlc(0, 0, device)

  /** The newest stamp this clock has issued or seen. Persisted, and restored via `start`. */
  @Synchronized fun head(): Hlc = last

  /** A stamp for an edit made on this phone. Strictly greater than [head]. */
  @Synchronized
  fun tick(): Hlc {
    val wall = maxOf(now(), last.wall)
    val counter = if (wall == last.wall) last.counter + 1 else 0
    return Hlc(wall, counter, device).also { last = it }
  }

  /**
   * Folds a peer's stamp in and returns a stamp that exceeds both it and everything local.
   *
   * The counter arithmetic looks fussy but each branch is doing one thing: when the winning wall
   * reading ties with a side, that side's counter has to be beaten rather than reset.
   */
  @Synchronized
  fun observe(remote: Hlc): Hlc {
    val wall = maxOf(now(), last.wall, remote.wall)
    val counter =
      when {
        wall == last.wall && wall == remote.wall -> maxOf(last.counter, remote.counter) + 1
        wall == last.wall -> last.counter + 1
        wall == remote.wall -> remote.counter + 1
        else -> 0
      }
    return Hlc(wall, counter, device).also { last = it }
  }
}
