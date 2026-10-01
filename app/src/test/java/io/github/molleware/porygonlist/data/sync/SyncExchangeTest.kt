package io.github.molleware.porygonlist.data.sync

import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.Person
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-legged conversation, run for real on two threads over a pair of pipes.
 *
 * Pipes rather than one buffered stream because the failure this protocol can have is a deadlock
 * or a short read — each end waiting for something the other has not sent — and a single buffer
 * would hide exactly that by letting one side write everything before the other reads anything.
 */
class SyncExchangeTest {

  private val ava = DeviceId("AVAAAAAAAAAAAAAA")
  private val hugo = DeviceId("HUGOOOOOOOOOOOOO")

  private val pool = Executors.newFixedThreadPool(2)

  @After fun stop() = pool.shutdownNow().let {}

  private fun item(id: String, name: String, at: Hlc) =
    GroceryItem(
      id = ItemId(id),
      name = Field(name, at),
      qty = Field(1, at),
      checked = false,
      checkedAt = at,
      removed = Field(false, at),
    )

  private fun payload(from: DeviceId, wall: Long, vararg names: String) =
    SyncPayload(
      from = from,
      at = Hlc(wall, 0, from),
      lists =
        listOf(
          GroceryList(
            ListId("${ava.value}:1"),
            "Weekly shop",
            ListAccent.ACCENT,
            names.mapIndexed { i, n -> item("${from.value}:${i + 10}", n, Hlc(wall, 0, from)) },
            listOf(Person(ava, "Ava", "A"), Person(hugo, "Hugo", "H")),
          )
        ),
    )

  /** Two connected ends: what one writes, the other reads. */
  private class Wire {
    private val toListener = PipedInputStream(1 shl 16)
    private val fromCaller = PipedOutputStream(toListener)
    private val toCaller = PipedInputStream(1 shl 16)
    private val fromListener = PipedOutputStream(toCaller)

    val callerOut: OutputStream = fromCaller
    val callerIn: InputStream = toCaller
    val listenerOut: OutputStream = fromListener
    val listenerIn: InputStream = toListener
  }

  private fun <T> run(task: () -> T) = pool.submit(Callable(task))

  @Test
  fun `both ends get the other's payload and an ack of their own`() {
    val wire = Wire()
    val mine = payload(hugo, 2_000, "Ice")
    val theirs = payload(ava, 3_000, "Lemons")

    val caller = run { SyncExchange.call(mine, wire.callerOut, wire.callerIn) }
    val listener = run { SyncExchange.answer(wire.listenerIn, wire.listenerOut) { theirs } }

    val called = caller.get(5, TimeUnit.SECONDS)
    val answered = listener.get(5, TimeUnit.SECONDS)

    assertNotNull("the caller never finished", called)
    assertNotNull("the listener never finished", answered)

    assertEquals(listOf("Lemons"), called!!.theirs.lists.single().items.map { it.name.value })
    assertEquals(listOf("Ice"), answered!!.theirs.lists.single().items.map { it.name.value })

    // Each ack is the stamp that end sent, echoed back untouched — never a fresh reading, or it
    // would claim delivery of changes that were not in the bundle.
    assertEquals(mine.at, called.ackOfMine)
    assertEquals(theirs.at, answered.ackOfMine)
  }

  @Test
  fun `the listener learns who called before deciding what to send`() {
    val wire = Wire()
    var askedFor: DeviceId? = null

    val caller = run { SyncExchange.call(payload(hugo, 2_000, "Ice"), wire.callerOut, wire.callerIn) }
    val listener =
      run {
        SyncExchange.answer(wire.listenerIn, wire.listenerOut) { who ->
          askedFor = who
          payload(ava, 3_000)
        }
      }

    caller.get(5, TimeUnit.SECONDS)
    listener.get(5, TimeUnit.SECONDS)

    assertEquals(hugo, askedFor)
  }

  @Test
  fun `owing a caller nothing still completes the conversation`() {
    val wire = Wire()

    val caller = run { SyncExchange.call(payload(hugo, 2_000, "Ice"), wire.callerOut, wire.callerIn) }
    val listener = run { SyncExchange.answer(wire.listenerIn, wire.listenerOut) { null } }

    val called = caller.get(5, TimeUnit.SECONDS)
    val answered = listener.get(5, TimeUnit.SECONDS)

    // "Nothing for you" is said rather than left as a silence the caller has to time out on.
    assertNotNull(called)
    assertTrue(called!!.theirs.lists.isEmpty())
    assertNotNull("the caller's payload still arrived", answered)
    assertEquals(listOf("Ice"), answered!!.theirs.lists.single().items.map { it.name.value })
  }

  @Test
  fun `a caller that hangs up after taking ours confirms nothing`() {
    val wire = Wire()
    val ours = payload(ava, 3_000, "Lemons")

    // Plays the caller by hand: sends a payload, reads the reply, and goes without leg three.
    val caller =
      run {
        val body = SyncCodec.encode(payload(hugo, 2_000, "Ice")).toByteArray()
        wire.callerOut.write("PLX1 ${body.size}\n".toByteArray())
        wire.callerOut.write(body)
        wire.callerOut.flush()
        wire.callerIn.readNBytes(1) // something arrived
        wire.callerOut.close()
      }
    val listener = run { SyncExchange.answer(wire.listenerIn, wire.listenerOut) { ours } }

    caller.get(5, TimeUnit.SECONDS)
    val answered = listener.get(5, TimeUnit.SECONDS)

    // Their payload is real and should be applied; ours may or may not have landed, and claiming
    // it did is how a tombstone gets collected before the other phone has heard.
    assertNotNull(answered)
    assertNull("no leg three, so no proof of delivery", answered!!.ackOfMine)
  }

  @Test
  fun `a payload in the old wire format is not read`() {
    val legacy = SyncCodec.encode(payload(hugo, 2_000, "Ice")).replaceFirst("PLSYNC2", "PLSYNC1").toByteArray()
    val inp = ByteArrayInputStream("PLX1 ${legacy.size}\n".toByteArray() + legacy)

    assertNull(SyncExchange.answer(inp, ByteArrayOutputStream()) { null })
  }

  @Test
  fun `an oversized frame is refused before it is allocated`() {
    val inp = ByteArrayInputStream("PLX1 999999999\n".toByteArray())

    assertNull(SyncExchange.answer(inp, ByteArrayOutputStream()) { null })
  }

  @Test
  fun `something that is not a frame at all is refused`() {
    val inp = ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())

    assertNull(SyncExchange.answer(inp, ByteArrayOutputStream()) { null })
  }
}
