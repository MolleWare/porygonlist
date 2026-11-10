package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.sync.DeviceId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private val ME = DeviceId("AVAAAAAAAAAAAAAA")

private class OneIdentity(override val deviceId: DeviceId) : LocalIdentity {
  override val publicKey = ByteArray(0)

  override fun sign(data: ByteArray) = data

  override fun keyEntry() = null
}

/**
 * Two changes made at the same moment from two threads: a tap on the main thread and a payload being
 * merged on a sync thread.
 *
 * Both used to read the state, change it and write it back with nothing between them, so one of the
 * two was lost. The worst of it was a payload whose merge was lost after the ack had gone: the peer
 * then collected its tombstones, and the items it had removed came back here for good.
 */
class SimultaneousChangesTest {

  private val dir: File = Files.createTempDirectory("porygonlist").toFile()
  private val file = File(dir, "porygonlist.state")

  @Test
  fun `every change made at once from two threads is kept, in memory and on disk`() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repo = FileListRepository(file, scope, io = Dispatchers.IO, writeDelayMs = 1, identity = { OneIdentity(ME) })
    runBlocking { repo.load() }

    // A count kept in the name: each change reads the one before it, so a lost change shows.
    val start = CountDownLatch(1)
    val threads =
      List(2) {
        thread {
          start.await()
          repeat(EACH) {
            repo.update { s, node ->
              node.clock.tick()
              s.withDisplayName(((s.displayName.toIntOrNull() ?: 0) + 1).toString())
            }
          }
        }
      }
    start.countDown()
    threads.forEach { it.join() }

    assertEquals((2 * EACH).toString(), repo.state.value?.displayName)

    // The newest change is the one that lands, however the saves were interleaved.
    val deadline = System.currentTimeMillis() + 5_000
    var saved: String? = null
    while (System.currentTimeMillis() < deadline) {
      saved = runCatching { StateCodec.decode(file.readText())?.displayName }.getOrNull()
      if (saved == (2 * EACH).toString()) break
      Thread.sleep(20)
    }
    scope.cancel()
    assertEquals((2 * EACH).toString(), saved)
  }

  private companion object {
    const val EACH = 20_000
  }
}
