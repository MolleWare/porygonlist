package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a file written before list ids were global.
 *
 * Both phones in use hold such a file, with real lists on it, so this is the one upgrade path where
 * getting it wrong loses somebody's groceries rather than failing a build. What has to survive: the
 * lists themselves, their order, which items and which people belong to which list, and which list
 * was open.
 *
 * The legacy text is produced by encoding a current state and pushing the ids back down to numbers,
 * rather than by hand. A hand-written fixture drifts from the real record shape the moment a field
 * is added, and then tests a file the app never wrote.
 */
class StateMigrationTest {

  private val me = DeviceId("avaphone01")
  private val them = DeviceId("hugophone2")

  private fun stamp(wall: Long) = Hlc(wall, 0, me)

  private fun item(seq: Long, name: String) =
    GroceryItem(
      id = ItemId("${me.value}:$seq"),
      name = Field(name, stamp(1_700_000_000_000)),
      qty = Field(1, stamp(1_700_000_000_000)),
      checked = false,
      checkedAt = stamp(1_700_000_000_000),
      removed = Field(false, stamp(1_700_000_000_000)),
    )

  private val you = Person(me, "Ava", "A")
  private val partner = Person(them, "Hugo", "H")

  /** Two lists, the second one shared, with the second one open. */
  private fun current(): AppState =
    AppState(
      localDevice = me,
      displayName = "Ava",
      idCounter = 7,
      clockHead = stamp(1_700_000_000_000),
      lists =
        listOf(
          GroceryList(ListId("${me.value}:1"), "groceries", ListAccent.ACCENT, listOf(item(5, "Milk")), listOf(you)),
          GroceryList(
            ListId("${me.value}:2"),
            "to do",
            ListAccent.ACCENT_2,
            listOf(item(6, "Dentist"), item(7, "Bins")),
            listOf(you, partner),
          ),
        ),
      activeListId = ListId("${me.value}:2"),
      online = true,
      networks = emptyList(),
      conflict = null,
    )

  /**
   * The same state as a version 9 file: header rolled back, and every list id written as the local
   * `max + 1` it used to be.
   *
   * Only the list-id field is touched. Item ids are `device:counter` too and must survive intact —
   * a careless rewrite of the whole line would damage them, which is exactly the bug this guards.
   */
  private fun asLegacy(text: String): String {
    val legacy = mapOf("${me.value}:1" to "1", "${me.value}:2" to "2")
    return text
      .lineSequence()
      .filter { it.isNotBlank() }
      .map { line ->
        val f = line.split('|')
        when (f.getOrNull(0)) {
          "PLSTATE10" -> "PLSTATE9"
          // meta's fifth field is the open list.
          "meta" -> f.toMutableList().also { it[4] = legacy.getValue(it[4]) }.joinToString("|")
          "list",
          "person",
          "item" -> f.toMutableList().also { it[1] = legacy.getValue(it[1]) }.joinToString("|")
          else -> line
        }
      }
      .joinToString("\n")
  }

  /**
   * That the fixture really is an old file.
   *
   * Without this the suite has a hole: if [asLegacy] quietly stopped rolling the version back, no
   * migration would run, the ids would still carry a device prefix because they never lost one, and
   * every assertion below would pass while testing nothing at all.
   */
  @Test
  fun `the fixture really is a version 9 file with numeric list ids`() {
    val legacy = asLegacy(StateCodec.encode(current()))

    assertEquals("PLSTATE9", legacy.lineSequence().first())
    assertTrue("list ids were not pushed back down", legacy.lineSequence().any { it.startsWith("list|1|") })
    assertTrue("item lines lost their list", legacy.lineSequence().any { it.startsWith("item|2|") })
    assertTrue("item ids should be untouched", legacy.contains("${me.value}:5"))
  }

  @Test
  fun `a version 9 file keeps its lists and gains global ids`() {
    val decoded = StateCodec.decode(asLegacy(StateCodec.encode(current())))
    assertNotNull("a version 9 file should still be readable", decoded)

    val lists = decoded!!.lists
    assertEquals(listOf("groceries", "to do"), lists.map { it.name })

    // The point of the change: no list is called `1` any more, and each id names the phone that
    // minted it, so two phones' first lists can never be confused for each other.
    lists.forEach { assertTrue("not a global id: ${it.id}", it.id.value.startsWith("${me.value}:")) }
    assertEquals("ids must be distinct", 2, lists.map { it.id }.toSet().size)
  }

  @Test
  fun `items and people stay on the list they were on`() {
    val lists = StateCodec.decode(asLegacy(StateCodec.encode(current())))!!.lists

    assertEquals(listOf("Milk"), lists[0].items.map { it.name.value })
    assertEquals(listOf("Dentist", "Bins"), lists[1].items.map { it.name.value })
    assertEquals(listOf("Ava"), lists[0].people.map { it.name })
    assertEquals(listOf("Ava", "Hugo"), lists[1].people.map { it.name })
  }

  @Test
  fun `item ids are not damaged by the rename`() {
    val lists = StateCodec.decode(asLegacy(StateCodec.encode(current())))!!.lists

    // Items were already `device:counter`. Rewriting list ids must not touch them.
    assertEquals(listOf("${me.value}:5"), lists[0].items.map { it.id.value })
    assertEquals(listOf("${me.value}:6", "${me.value}:7"), lists[1].items.map { it.id.value })
  }

  @Test
  fun `the list that was open is still open`() {
    val decoded = StateCodec.decode(asLegacy(StateCodec.encode(current())))!!

    assertEquals("to do", decoded.lists.first { it.id == decoded.activeListId }.name)
  }

  @Test
  fun `a migrated list never takes an id an item already holds`() {
    val decoded = StateCodec.decode(asLegacy(StateCodec.encode(current())))!!

    val itemIds = decoded.lists.flatMap { it.items }.map { it.id.value }.toSet()
    decoded.lists.forEach { assertTrue("list ${it.id} collides with an item", it.id.value !in itemIds) }

    // And the counter has moved past everything handed out, so the next mint cannot collide either.
    val highest =
      (itemIds + decoded.lists.map { it.id.value }).mapNotNull { it.substringAfter(':').toLongOrNull() }.max()
    assertTrue("counter ${decoded.idCounter} is behind $highest", decoded.idCounter >= highest)
  }

  @Test
  fun `a current file is passed through untouched`() {
    val before = current()
    val after = StateCodec.decode(StateCodec.encode(before))!!

    // No migration should run for a file that already has global ids.
    assertEquals(before.lists.map { it.id }, after.lists.map { it.id })
    assertEquals(before.activeListId, after.activeListId)
  }
}
