package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.crypto.LocalIdentity
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Hlc
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val ME = DeviceId("avaphone01")
private val SOMEONE_ELSE = DeviceId("oldphone01")

private class FixedIdentity(override val deviceId: DeviceId) : LocalIdentity {
  override val publicKey = ByteArray(0)

  override fun sign(data: ByteArray) = data

  override fun keyEntry() = null
}

/**
 * The save file on disk: what a crash just after a save costs, and what happens when an update
 * cannot read what an earlier build wrote.
 *
 * Both used to end the same way — a blank state written over the file 300 ms later, every list gone
 * without a word. Now a save cut short costs only the last change, and saves no build can read are
 * set aside until one can.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveFileTest {

  private val dir: File = Files.createTempDirectory("porygonlist").toFile()
  private val file = File(dir, "porygonlist.state")
  private fun saved(n: Int) = File(dir, "porygonlist.state.$n")
  private val setAside = File(dir, "porygonlist.state.unreadable")

  private fun TestScope.repository(device: DeviceId = ME) =
    FileListRepository(
      file = file,
      scope = this,
      io = UnconfinedTestDispatcher(testScheduler),
      writeDelayMs = 0,
      identity = { FixedIdentity(device) },
    )

  /** A repository opened on whatever is on disk now, as on the next start of the app. */
  private suspend fun TestScope.reopened(): AppState {
    val repo = repository()
    repo.load()
    advanceUntilIdle()
    return checkNotNull(repo.state.value) { "nothing loaded" }
  }

  /** Three saves in a row, each with its own name, so each can be told apart on disk. */
  private suspend fun TestScope.saveThree() {
    val repo = repository()
    repo.load()
    advanceUntilIdle()
    for (name in listOf("One", "Two", "Three")) {
      repo.update { state, node -> state.withDisplayName(name, node.clock.tick()) }
      advanceUntilIdle()
    }
  }

  @Test
  fun `each save keeps the two before it`() = runTest {
    saveThree()

    assertEquals("Three", StateCodec.decode(file.readText())?.displayName)
    assertEquals("Two", StateCodec.decode(saved(1).readText())?.displayName)
    assertEquals("One", StateCodec.decode(saved(2).readText())?.displayName)
    assertFalse("nothing left half-written beside them", File(dir, "porygonlist.state.tmp").exists())
  }

  @Test
  fun `a save cut short costs only the last change`() = runTest {
    saveThree()
    file.writeText(file.readText().take(40))

    assertEquals("Two", reopened().displayName)
    assertFalse("a cut save is not a fault in the reader: nothing is set aside", setAside.exists())
  }

  @Test
  fun `ids handed out in a save that is passed over never come round again`() = runTest {
    saveThree()
    file.writeText(file.readText().take(40))

    val state = reopened()

    assertTrue("above anything the cut save can have minted", state.idCounter > 1_000_000_000L)
    assertEquals("and kept so on disk", state.idCounter, StateCodec.decode(file.readText())?.idCounter)
  }

  @Test
  fun `a save passed over does not take a backup's place`() = runTest {
    saveThree()
    file.writeText(file.readText().take(40))

    reopened()

    val onDisk = listOf(file, saved(1), saved(2)).filter { it.exists() }
    assertTrue("every save left on disk reads", onDisk.all { runCatching { StateCodec.decode(it.readText()) }.getOrNull() != null })
    assertTrue("with a backup behind the current one", saved(1).exists() || saved(2).exists())
  }

  @Test
  fun `a phone that died between the two renames opens the save before`() = runTest {
    saveThree()
    val before = StateCodec.decode(saved(1).readText())!!.idCounter
    file.delete()

    val state = reopened()

    assertEquals("Two", state.displayName)
    assertEquals("nothing newer was passed over, so ids carry on", before, state.idCounter)
  }

  @Test
  fun `with no save readable, the newest is set aside and ids start above the old ones`() = runTest {
    saveThree()
    file.writeText("PLSTATE99\nwritten by a build this one cannot read")
    saved(1).writeText("PLSTATE99\nolder")
    saved(2).writeText("PLSTATE99\noldest")

    val state = reopened()

    assertTrue("started blank", state.lists.isEmpty())
    assertEquals("PLSTATE99\nwritten by a build this one cannot read", setAside.readText())
    assertTrue("no id the lost state handed out can come round again", state.idCounter > 1_000_000_000L)
  }

  @Test
  fun `a second failure does not replace the file set aside by the first`() = runTest {
    setAside.writeText("PLSTATE99\nthe one with the lists in it")
    file.writeText("PLSTATE99\nthe blank state, unreadable in turn")

    reopened()

    assertEquals("PLSTATE99\nthe one with the lists in it", setAside.readText())
  }

  @Test
  fun `a later build that reads the set-aside save gets its lists back`() = runTest {
    val lost = AppState.seed(localDevice = ME).withDisplayName("Ava", Hlc(1_000, 0, ME))
    setAside.writeText(StateCodec.encode(lost))
    val sinceThen = AppState.empty(localDevice = ME).let { it.copy(idCounter = it.clockHead.wall) }
    file.writeText(StateCodec.encode(sinceThen))

    val state = reopened()

    assertEquals(lost.lists.map { it.id }, state.lists.map { it.id })
    assertEquals("the name comes back with them", "Ava", state.displayName)
    assertEquals("and so does when it was set, so it still travels as a rename", lost.displayNameAt, state.displayNameAt)
    assertEquals("the phones it was paired with", lost.peers, state.peers)
    assertEquals("ids stay above both", sinceThen.idCounter, state.idCounter)
    assertFalse("gone once the lists are saved again", setAside.exists())
    assertEquals(lost.lists.map { it.id }, StateCodec.decode(file.readText())?.lists?.map { it.id })
  }

  @Test
  fun `what the phone has now wins over the set-aside copy`() {
    val lost = AppState.seed(localDevice = ME)
    val renamed = lost.lists.first().copy(name = "Came back from a peer")
    val now = AppState.empty(localDevice = ME).copy(lists = listOf(renamed), displayName = "Ava")

    val merged = now.withRecovered(lost.withDisplayName("Old name", Hlc(1_000, 0, ME)))

    assertEquals("Came back from a peer", merged.lists.first { it.id == renamed.id }.name)
    assertEquals(lost.lists.size, merged.lists.size)
    assertEquals("Ava", merged.displayName)
  }

  @Test
  fun `a set-aside save from another identity is dropped, not brought back`() = runTest {
    setAside.writeText(StateCodec.encode(AppState.seed(localDevice = SOMEONE_ELSE)))
    file.writeText(StateCodec.encode(AppState.empty(localDevice = ME)))

    val state = reopened()

    assertTrue(state.lists.isEmpty())
    assertFalse(setAside.exists())
  }

  @Test
  fun `deleting the identity takes the earlier saves and the set-aside file with it`() = runTest {
    saveThree()
    setAside.writeText("PLSTATE99\nanything")
    val repo = repository()
    repo.load()
    advanceUntilIdle()

    repo.reset()
    advanceUntilIdle()

    assertFalse(saved(1).exists())
    assertFalse(saved(2).exists())
    assertFalse(setAside.exists())
    assertTrue("only the fresh state is left", StateCodec.decode(file.readText())!!.lists.isEmpty())
  }
}
