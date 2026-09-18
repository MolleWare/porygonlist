package io.github.molleware.porygonlist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTrieTest {

  private val groceries = WordTrie.of(listOf("Oat milk", "Milk", "Tomatoes", "Cherry tomatoes", "Tinned tomatoes"))

  @Test
  fun `a prefix finds what starts with it`() {
    assertEquals(listOf("Tomatoes"), groceries.matching("toma", limit = 1))
  }

  @Test
  fun `a word inside a phrase is findable`() {
    // The reason this is indexed by word: nobody types "oat" looking for milk.
    assertTrue("Oat milk" in groceries.matching("milk"))
  }

  @Test
  fun `a phrase the prefix starts outright comes first`() {
    val matches = groceries.matching("milk")

    assertEquals("Milk", matches.first())
  }

  @Test
  fun `every phrase containing the word is offered`() {
    val matches = groceries.matching("tomato")

    assertEquals(setOf("Tomatoes", "Cherry tomatoes", "Tinned tomatoes"), matches.toSet())
  }

  @Test
  fun `a phrase is offered once however many of its words match`() {
    val matches = WordTrie.of(listOf("Tomato tomato")).matching("tomato")

    assertEquals(listOf("Tomato tomato"), matches)
  }

  @Test
  fun `case does not matter`() {
    assertEquals(groceries.matching("TOMA"), groceries.matching("toma"))
  }

  @Test
  fun `accents do not have to be typed`() {
    val fancy = WordTrie.of(listOf("Crème fraîche", "Jalapeños"))

    assertEquals(listOf("Crème fraîche"), fancy.matching("creme"))
    assertEquals(listOf("Jalapeños"), fancy.matching("jalapen"))
  }

  @Test
  fun `accents can also be typed`() {
    assertEquals(listOf("Crème fraîche"), WordTrie.of(listOf("Crème fraîche")).matching("crème"))
  }

  @Test
  fun `a prefix nothing starts with finds nothing`() {
    assertEquals(emptyList<String>(), groceries.matching("zzz"))
  }

  @Test
  fun `an empty prefix offers nothing rather than everything`() {
    assertEquals(emptyList<String>(), groceries.matching(""))
    assertEquals(emptyList<String>(), groceries.matching("   "))
  }

  @Test
  fun `the limit is respected`() {
    assertEquals(2, groceries.matching("tomato", limit = 2).size)
    assertEquals(emptyList<String>(), groceries.matching("tomato", limit = 0))
  }

  @Test
  fun `earlier entries win ties, so the owner's own words come first`() {
    val mine = WordTrie.of(listOf("Sourdough loaf", "Sourdough"))

    assertEquals("Sourdough loaf", mine.matching("sour").first())
  }

  @Test
  fun `the same name twice is one entry, spelled the way it was first seen`() {
    val trie = WordTrie.of(listOf("oat milk", "Oat Milk", "OAT MILK"))

    assertEquals(listOf("oat milk"), trie.matching("oat"))
  }

  @Test
  fun `punctuation and blanks do not become entries`() {
    val trie = WordTrie.of(listOf("", "   ", "Goat's cheese"))

    assertEquals(listOf("Goat's cheese"), trie.matching("cheese"))
    assertEquals(listOf("Goat's cheese"), trie.matching("goat"))
  }

  @Test
  fun `a long prefix does not overflow the stack`() {
    val long = "a".repeat(10_000)

    assertEquals(listOf(long), WordTrie.of(listOf(long)).matching(long))
  }

  @Test
  fun `the built-in list is sane`() {
    val builtIn = WordTrie.of(CommonGroceries.names)

    assertEquals(listOf("Courgette"), builtIn.matching("courg"))
    assertTrue("Aubergine" in builtIn.matching("auber"))
    assertTrue("Chopped tomatoes" in builtIn.matching("chopped"))
    // Spelled once each, so the vocabulary cannot itself be a source of two spellings.
    val folded = CommonGroceries.names.map { WordTrie.fold(it) }
    assertEquals(folded.size, folded.toSet().size)
  }
}

class SuggestionVocabularyTest {

  private val phone = io.github.molleware.porygonlist.data.sync.DeviceId("avaphone01")

  private fun state() = AppState.seed(localDevice = phone, now = 1_700_000_000_000)

  @Test
  fun `the owner's own words outrank the built-in list`() {
    val words = state().copy(staples = listOf(Staple("Oat milk"))).suggestionVocabulary()

    assertEquals("Oat milk", words.first())
  }

  @Test
  fun `everything on every list is vocabulary`() {
    val words = state().suggestionVocabulary()

    // Including ticked-off things: how the owner spells it is the point, not whether they need it.
    assertTrue("Tomatoes" in words)
    assertTrue("Sourdough" in words)
  }

  @Test
  fun `what is already on the list is known, folded`() {
    val list = state().activeList

    val here = alreadyOn(list)

    assertTrue("sourdough" in here)
    assertTrue("oat milk" in here)
  }
}
