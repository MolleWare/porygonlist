package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.sync.DeviceId
import java.io.File

/**
 * Where each paired phone was last reached, and the port this phone last listened on, kept across
 * process death.
 *
 * **Why it exists.** A closed phone wakes into a fresh process for every window. With nothing kept,
 * each of two phones in Doze had to find the other by mDNS inside the same 30 seconds, and on
 * 2026-10-24 one of them never did (Pixel and S24 FE, 18:45). Kept here, a window calls the
 * address it already knows at once and lets discovery correct it (Hugo, 2026-10-25).
 *
 * **The port is half of it.** A phone opened a new socket on a new port for every window — the
 * Pixel listened on 41389 at 21:04 and 37667 at 21:15 — so a remembered address was refused
 * however recent. The endpoint now asks for [port] again first.
 *
 * Not part of the state file on purpose: an address is a fact about the network this phone is on,
 * not about the lists, it changes far more often than they do, and nothing about it is synced. A
 * stale one costs a refused call, until the next address that works replaces it.
 *
 * Read on first use, written on every change, both small. [file] null keeps it in memory, for tests
 * and the fake phone.
 */
class PeerAddressBook(private val file: File? = null) {

  /** One remembered address, and when it was last good. */
  data class Entry(val record: ServiceRecord, val at: Long)

  private val lock = Any()
  private var loaded = false
  private var peers = mutableMapOf<DeviceId, Entry>()
  private var lastPort: Int? = null

  /** The port this phone last listened on, to ask for again. */
  val port: Int?
    get() = synchronized(lock) { loadLocked(); lastPort }

  fun rememberPort(port: Int) =
    synchronized(lock) {
      loadLocked()
      if (lastPort == port) return
      lastPort = port
      saveLocked()
    }

  fun entries(): List<Entry> = synchronized(lock) { loadLocked(); peers.values.toList() }

  fun remember(record: ServiceRecord, at: Long) =
    synchronized(lock) {
      loadLocked()
      // Only a move is worth a write. The time matters only to tell an old address from a fresh one,
      // so a peer found again where it was does not rewrite the file every minute.
      val was = peers[record.deviceId]
      if (was?.record == record && at - was.at < REFRESH_MS) return
      peers[record.deviceId] = Entry(record, at)
      saveLocked()
    }

  /** Everything, file included. For when this phone's identity goes, and its peers with it. */
  fun forgetAll() =
    synchronized(lock) {
      loaded = true
      peers.clear()
      lastPort = null
      runCatching { file?.delete() }
    }

  private fun loadLocked() {
    if (loaded) return
    loaded = true
    val lines = file?.takeIf { it.exists() }?.let { runCatching { it.readLines() }.getOrNull() } ?: return
    for (line in lines) {
      val parts = line.split(' ')
      when {
        parts.size == 2 && parts[0] == "port" -> lastPort = parts[1].toIntOrNull()
        parts.size == 5 && parts[0] == "peer" -> {
          val port = parts[3].toIntOrNull() ?: continue
          val at = parts[4].toLongOrNull() ?: continue
          val id = DeviceId(parts[1])
          peers[id] = Entry(ServiceRecord(id, parts[2], port), at)
        }
        // Anything else is from a version that wrote more, or a torn write: skipped, not fatal.
      }
    }
  }

  private fun saveLocked() {
    val target = file ?: return
    val text = buildString {
      lastPort?.let { appendLine("port $it") }
      peers.values.forEach { (r, at) -> appendLine("peer ${r.deviceId.value} ${r.host} ${r.port} $at") }
    }
    // Written beside and renamed over, so a process killed mid-write leaves the old file whole.
    runCatching {
      val temp = File(target.path + ".tmp")
      temp.writeText(text)
      temp.renameTo(target)
    }
  }

  private companion object {
    /** How stale a held time may get before finding the same address again rewrites it. */
    const val REFRESH_MS = 10 * 60_000L
  }
}
