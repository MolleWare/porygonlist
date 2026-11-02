package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.Field
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.HybridClock
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.data.sync.Records
import io.github.molleware.porygonlist.data.sync.SyncCodec
import io.github.molleware.porygonlist.data.sync.SyncPayload
import io.github.molleware.porygonlist.data.sync.SyncResult
import io.github.molleware.porygonlist.data.sync.merge
import io.github.molleware.porygonlist.data.sync.payloadFor
import io.github.molleware.porygonlist.data.sync.receive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private val HUGO = DeviceId("HUGOAAAAAAAAAAAA")
private val AVA = DeviceId("AVAAAAAAAAAAAAAA")

private val WEEKLY = ListId("${HUGO.value}:1")

private fun at(wall: Long, device: DeviceId = HUGO) = Hlc(wall, 0, device)

private val lasagne = Recipe("Lasagne", listOf(Ingredient("Pasta sheets"), Ingredient("Tomatoes", 2)))
private val pancakes = Recipe("Pancakes", listOf(Ingredient("Eggs", 2), Ingredient("Flour"), Ingredient("Milk")))

private fun eggs(id: String = "${HUGO.value}:5", stamp: Hlc = at(1_000)) =
  GroceryItem(
    id = ItemId(id),
    name = Field("Eggs", stamp),
    qty = Field(2, stamp),
    checkedAt = stamp,
    removed = Field(false, stamp),
  )

private fun weekly(vararg items: GroceryItem) =
  GroceryList(WEEKLY, "Weekly shop", ListAccent.ACCENT, items.toList(), listOf(Person(HUGO, "Hugo", "H"), Person(AVA, "Ava", "A")))

private fun hugos(vararg items: GroceryItem) =
  AppState(
    localDevice = HUGO,
    displayName = "Hugo",
    idCounter = 10,
    clockHead = at(1_000),
    lists = listOf(weekly(*items)),
    activeListId = WEEKLY,
    networks = emptyList(),
    peers = listOf(TrustedPeer(AVA, byteArrayOf(1), "Ava", 0)),
  )

/** What Hugo's phone has once [payload] from Ava has arrived, over the wire as [wire] puts it. */
private fun AppState.receiving(payload: SyncPayload, wire: (String) -> String = { it }): AppState {
  val decoded = SyncCodec.decode(wire(SyncCodec.encode(payload)))!!
  val result = receive(decoded, HybridClock(HUGO, now = { 9_000 }))
  assertTrue("the payload should apply, got $result", result is SyncResult.Merged)
  return (result as SyncResult.Merged).state
}

private fun AppState.item(id: ItemId) = lists.single().items.single { it.id == id }

/**
 * Recipes are this phone's own, like staples; the names of the ones an item was added for travel
 * with the item, to everyone on the list, including phones from before recipes.
 */
class RecipeTest {

  // ── Typing one ────────────────────────────────────────────────────────────

  @Test
  fun `ingredients are read the way a pasted list is`() {
    val recipe = Recipe.of("  Pancakes ", "2 eggs, flour\nmilk")

    assertEquals(pancakes, recipe)
  }

  @Test
  fun `a recipe needs a name and something in it`() {
    assertNull(Recipe.of(" ", "2 eggs, flour"))
    assertNull(Recipe.of("Pancakes", " , \n "))
  }

  @Test
  fun `the same thing typed twice is one ingredient with both quantities`() {
    val recipe = Recipe.of("Omelette", "2 eggs, cheese, Eggs, 0 chives")!!

    assertEquals(listOf(Ingredient("Eggs", 3), Ingredient("Cheese"), Ingredient("Chives")), recipe.ingredients)
  }

  @Test
  fun `a number too long to be a quantity is part of the name`() {
    assertEquals(listOf(Ingredient("99999999999 apples")), Recipe.of("Pie", "99999999999 apples")!!.ingredients)
  }

  @Test
  fun `what goes in it reads back as it was typed, and saving that unchanged changes nothing`() {
    assertEquals("2 Eggs, Flour, Milk", pancakes.ingredientsText)
    assertEquals(pancakes, Recipe.of(pancakes.name, pancakes.ingredientsText))
  }

  // ── Keeping them ──────────────────────────────────────────────────────────

  @Test
  fun `a new recipe goes last, and an edited one stays where it was`() {
    val two = hugos().withRecipeSaved(lasagne).withRecipeSaved(pancakes)
    assertEquals(listOf(lasagne, pancakes), two.recipes)

    val renamed = Recipe("Veggie lasagne", lasagne.ingredients + Ingredient("Spinach"))
    assertEquals(listOf(renamed, pancakes), two.withRecipeSaved(renamed, replacing = "Lasagne").recipes)
  }

  @Test
  fun `two recipes cannot share a name, whatever the capitals`() {
    val state = hugos().withRecipeSaved(lasagne).withRecipeSaved(pancakes)

    assertSame(state, state.withRecipeSaved(Recipe("lasagne", pancakes.ingredients)))
    assertSame("nor can one be renamed onto another", state, state.withRecipeSaved(pancakes.copy(name = "LASAGNE"), replacing = "Pancakes"))
    assertEquals(
      "but a recipe can change its own capitals",
      listOf("lasagne", "Pancakes"),
      state.withRecipeSaved(lasagne.copy(name = "lasagne"), replacing = "Lasagne").recipes.map { it.name },
    )
  }

  @Test
  fun `a recipe is found by name whatever the capitals, and can be deleted`() {
    val state = hugos().withRecipeSaved(lasagne).withRecipeSaved(pancakes)

    assertEquals(lasagne, state.recipeNamed(" lasagne"))
    assertEquals(listOf(pancakes), state.withoutRecipe("Lasagne").recipes)
  }

  @Test
  fun `recipes survive a save, in order, a bar in a name and all`() {
    val odd = Recipe("Salt | pepper steak", listOf(Ingredient("Steak", 2), Ingredient("Salt | pepper")))
    val state = hugos().withRecipeSaved(lasagne).withRecipeSaved(odd)

    assertEquals(state.recipes, StateCodec.decode(StateCodec.encode(state))!!.recipes)
  }

  @Test
  fun `a damaged recipe costs only itself`() {
    val encoded = StateCodec.encode(hugos().withRecipeSaved(lasagne).withRecipeSaved(pancakes))
    val damaged = asVersion10(encoded.replace("recipe|Lasagne|Pasta sheets|1|Tomatoes|2", "recipe|Lasagne|Pasta sheets|lots|Tomatoes"))

    assertEquals(listOf(pancakes), StateCodec.decode(damaged)!!.recipes)
  }

  @Test
  fun `recipes are never in a payload`() {
    val state = hugos(eggs()).withRecipeSaved(Recipe("Secret sauce", listOf(Ingredient("Mystery"))))

    val payload = SyncCodec.encode(state.payloadFor(AVA, HybridClock(HUGO, now = { 9_000 }))!!)

    assertTrue(!payload.contains("Secret sauce") && !payload.contains("Mystery"))
  }

  @Test
  fun `recipes come back from a save set aside, unless there are some again`() {
    val kept = hugos().withRecipeSaved(lasagne)

    assertEquals(listOf(lasagne), hugos().withRecovered(kept).recipes)
    assertEquals(listOf(pancakes), hugos().withRecipeSaved(pancakes).withRecovered(kept).recipes)
  }

  // ── What an item is for ───────────────────────────────────────────────────

  @Test
  fun `an item is for no recipe until one adds it`() {
    assertEquals(Field(emptyList<String>(), Hlc(0, 0, HUGO)), eggs().recipes)
  }

  @Test
  fun `an item gains a recipe stamped, and the same recipe twice only once`() {
    val forPancakes = eggs().forRecipe("Pancakes", at(2_000))
    val forBoth = forPancakes.forRecipe("Lasagne", at(3_000))

    assertEquals(Field(listOf("Pancakes"), at(2_000), basedOn = Hlc(0, 0, HUGO)), forPancakes.recipes)
    assertEquals(Field(listOf("Pancakes", "Lasagne"), at(3_000), basedOn = at(2_000)), forBoth.recipes)
    assertSame(forBoth, forBoth.forRecipe("pancakes", at(4_000)))
    assertEquals("the stamp counts as a change to the item", at(3_000), forBoth.touchedAt)
  }

  @Test
  fun `what an item is for survives a save, a bar in a name and all, on a card too`() {
    val item = eggs().forRecipe("Pancakes", at(2_000)).forRecipe("Salt | pepper", at(3_000))
    val other = eggs(id = "${AVA.value}:5", stamp = at(1_500, AVA)).forRecipe("Lasagne", at(2_500, AVA))
    val state = hugos(item).copy(conflicts = listOf(Conflict(item, other, listId = WEEKLY)))

    val restored = StateCodec.decode(StateCodec.encode(state))!!

    assertEquals(item, restored.item(item.id))
    assertEquals(listOf(item, other), restored.conflicts.single().let { listOf(it.yours, it.theirs) })
  }

  @Test
  fun `0_1_0 reads an item for a recipe exactly as it read the item before`() {
    // 0.1.0 reads an item's first seventeen columns and stops: they must be what it always wrote.
    val plain = eggs()
    val forPancakes = plain.forRecipe("Pancakes", at(2_000))

    val before = ITEM_COLUMNS_010 - 2
    assertEquals("three more, at the end", before + 3, Records.itemFields(forPancakes).size)
    assertEquals(Records.itemFields(plain).take(before), Records.itemFields(forPancakes).take(before))
  }

  @Test
  fun `a 0_1_0 save reads as every item for no recipe and no recipes kept, and otherwise as it was`() {
    val item = eggs().forRecipe("Pancakes", at(2_000))
    val state = hugos(item).withRecipeSaved(pancakes)

    for (old in listOf(as010(StateCodec.encode(state)), asVersion10(as010(StateCodec.encode(state))))) {
      assertTrue(old.lines().filter { it.startsWith("item|") }.all { it.split('|').size == ITEM_COLUMNS_010 })
      val restored = StateCodec.decode(old)!!

      assertEquals(emptyList<Recipe>(), restored.recipes)
      assertEquals(item.copy(recipes = Field(emptyList(), Hlc(0, 0, HUGO))), restored.item(item.id))
    }
  }

  // ── Travelling ────────────────────────────────────────────────────────────

  @Test
  fun `what an item is for travels in a payload`() {
    val item = eggs().forRecipe("Pancakes", at(2_000)).forRecipe("Lasagne", at(3_000))
    val payload = SyncPayload(from = HUGO, at = at(5_000), lists = listOf(weekly(item)))

    assertEquals(item, SyncCodec.decode(SyncCodec.encode(payload))!!.lists.single().items.single())
  }

  @Test
  fun `a recipe added on the other phone reaches this one`() {
    // The trap: the merge starts from this phone's copy of the item, which knows of no recipe.
    val mine = eggs()
    val theirs = mine.copy(qty = mine.qty.set(4, at(2_000, AVA))).forRecipe("Pancakes", at(2_000, AVA))

    val after = hugos(mine).receiving(SyncPayload(AVA, at(5_000, AVA), listOf(weekly(theirs))))

    assertEquals(theirs.recipes, after.item(mine.id).recipes)
    assertEquals(theirs.recipes, merge(mine, theirs).item.recipes)
  }

  @Test
  fun `the newer recipes win, and an older copy changes nothing`() {
    val old = eggs().forRecipe("Pancakes", at(2_000))
    val newer = old.forRecipe("Lasagne", at(3_000, AVA))

    assertEquals(newer.recipes, merge(old, newer).item.recipes)
    assertEquals(newer.recipes, merge(newer, old).item.recipes)
  }

  @Test
  fun `two recipes added apart keep both, and both phones agree on the order`() {
    val start = eggs()
    val hugos = start.forRecipe("Lasagne", at(2_000))
    val avas = start.forRecipe("Pancakes", at(2_500, AVA))

    val onHugos = merge(hugos, avas).item.recipes
    val onAvas = merge(avas, hugos).item.recipes

    assertEquals(listOf("Pancakes", "Lasagne"), onHugos.value)
    assertEquals(onHugos, onAvas)
    assertEquals("and merging again settles nothing new", onHugos, merge(hugos.copy(recipes = onHugos), avas).item.recipes)
  }

  @Test
  fun `a 0_1_0 phone sending the item back never wipes what it is for`() {
    // Ava's phone is on 0.1.0: it has the item, ticked it, and sends it back with no recipe columns.
    val mine = eggs().forRecipe("Pancakes", at(2_000))
    val ticked = mine.copy(checked = true, checkedAt = at(3_000, AVA))
    val fromAva = SyncPayload(from = AVA, at = at(5_000, AVA), lists = listOf(weekly(ticked)))
    assertTrue(
      "0.1.0 sends nineteen columns per item",
      payloadAs010(SyncCodec.encode(fromAva)).lines().filter { it.startsWith("item|") }.all { it.split('|').size == ITEM_COLUMNS_010 },
    )

    val after = hugos(mine).receiving(fromAva, ::payloadAs010)

    assertEquals(mine.recipes, after.item(mine.id).recipes)
    assertTrue("the tick still arrives", after.item(mine.id).checked)
    val goingBack = after.payloadFor(AVA, HybridClock(HUGO, now = { 9_000 }))!!.lists.single().items.single()
    assertEquals("and it goes on being sent", mine.recipes, goingBack.recipes)
  }

  @Test
  fun `an item a 0_1_0 phone added reads as for no recipe`() {
    val theirs = eggs(id = "${AVA.value}:3", stamp = at(2_000, AVA))

    val after = hugos().receiving(SyncPayload(AVA, at(5_000, AVA), listOf(weekly(theirs))), ::payloadAs010)

    assertEquals(Field(emptyList<String>(), Hlc(0, 0, AVA)), after.item(theirs.id).recipes)
  }
}
