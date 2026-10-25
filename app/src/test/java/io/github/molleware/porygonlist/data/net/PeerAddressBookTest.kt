package io.github.molleware.porygonlist.data.net

import io.github.molleware.porygonlist.data.sync.DeviceId
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAddressBookTest {

  private val dir = Files.createTempDirectory("peers").toFile()
  private val file = File(dir, "porygonlist.peers")

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  private val ava = ServiceRecord(DeviceId("VJPUMTYCQR4KXVCX"), "192.168.1.151", 38623)

  @Test
  fun `what one process kept, the next one reads`() {
    PeerAddressBook(file).apply {
      remember(ava, at = 1_000)
      rememberPort(41389)
    }

    val next = PeerAddressBook(file)

    assertEquals(listOf(PeerAddressBook.Entry(ava, 1_000)), next.entries())
    assertEquals(41389, next.port)
  }

  @Test
  fun `a peer that moved replaces where it was`() {
    val book = PeerAddressBook(file)
    book.remember(ava, at = 1_000)
    book.remember(ava.copy(port = 36303), at = 2_000)

    assertEquals(listOf(ava.copy(port = 36303)), PeerAddressBook(file).entries().map { it.record })
  }

  @Test
  fun `the same address found again is not written every time`() {
    val book = PeerAddressBook(file)
    book.remember(ava, at = 1_000)
    val written = file.lastModified()
    file.setLastModified(0)

    book.remember(ava, at = 2_000)

    assertEquals("unchanged on disk", 0, file.lastModified())
    assertTrue(written > 0)
  }

  @Test
  fun `lines it does not understand are skipped, not fatal`() {
    file.writeText("port 41389\npeer torn\nsomething new\npeer ${ava.deviceId.value} ${ava.host} ${ava.port} 1000\n")

    val book = PeerAddressBook(file)

    assertEquals(listOf(ava), book.entries().map { it.record })
    assertEquals(41389, book.port)
  }

  @Test
  fun `no file yet is an empty book`() {
    val book = PeerAddressBook(file)

    assertTrue(book.entries().isEmpty())
    assertNull(book.port)
  }

  @Test
  fun `forgetting everything takes the file with it`() {
    val book = PeerAddressBook(file)
    book.remember(ava, at = 1_000)

    book.forgetAll()

    assertFalse(file.exists())
    assertTrue(PeerAddressBook(file).entries().isEmpty())
  }
}
