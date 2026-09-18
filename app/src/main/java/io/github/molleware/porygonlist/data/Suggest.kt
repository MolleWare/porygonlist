package io.github.molleware.porygonlist.data

import java.text.Normalizer

/**
 * Prefix lookup over the things people put on a grocery list.
 *
 * The point is not speed. A few hundred phrases would scan fast enough with a loop, and pretending
 * otherwise would be dressing up a decision. The point is that the work per keystroke is
 * proportional to what has been *typed* rather than to the size of the vocabulary, so the
 * vocabulary can grow — every name the owner has ever used goes in it — without the field getting
 * slower. Walking to a node and collecting what hangs off it is also simply the shape of the
 * problem, where repeated `startsWith` over a list is not.
 *
 * Indexed **by word, not by phrase**. "milk" has to find "Oat milk", and it would not if only the
 * first letter of the phrase led anywhere. Every word of a phrase is inserted, and the node
 * remembers which phrase it came from and whether it started it.
 */
class WordTrie private constructor(private val terms: List<String>, private val root: Node) {

  private class Node {
    val children = HashMap<Char, Node>()

    /** Phrases reachable here, as an index into [terms], and whether the word began the phrase. */
    val entries = ArrayList<Entry>()
  }

  private class Entry(val term: Int, val leading: Boolean)

  /**
   * Phrases with a word starting with [prefix], best first.
   *
   * "Best" is: a phrase the prefix starts outright beats one it only matches inside — typing "oat"
   * should offer "Oat milk" before "Rolled oats" — and after that, the order the vocabulary was
   * built in, which puts the owner's own words ahead of the built-in ones.
   */
  fun matching(prefix: String, limit: Int = 5): List<String> {
    val folded = fold(prefix)
    if (folded.isEmpty() || limit <= 0) return emptyList()

    var node = root
    for (c in folded) node = node.children[c] ?: return emptyList()

    // A phrase can be reached by more than one of its words; keep the strongest reason for each.
    val best = LinkedHashMap<Int, Boolean>()
    collect(node) { entry ->
      val leading = best[entry.term]
      if (leading == null || (entry.leading && !leading)) best[entry.term] = entry.leading
    }

    return best.entries
      .sortedWith(compareBy({ !it.value }, { it.key }))
      .take(limit)
      .map { terms[it.key] }
  }

  private fun collect(node: Node, onEntry: (Entry) -> Unit) {
    node.entries.forEach(onEntry)
    // Iterative rather than recursive: a pasted word can be arbitrarily long, and so therefore can
    // the branch hanging off it.
    val pending = ArrayDeque(node.children.values)
    while (pending.isNotEmpty()) {
      val next = pending.removeFirst()
      next.entries.forEach(onEntry)
      pending.addAll(next.children.values)
    }
  }

  companion object {

    /**
     * Builds an index over [phrases], earlier entries winning ties.
     *
     * Duplicates are dropped case- and accent-insensitively, keeping the first spelling seen. That
     * is what makes the owner's own capitalisation beat the built-in list's: pass theirs first and
     * "oat milk" stays "oat milk" rather than being corrected to "Oat milk" by a table.
     */
    fun of(phrases: List<String>): WordTrie {
      val terms = ArrayList<String>(phrases.size)
      val seen = HashSet<String>(phrases.size)
      val root = Node()

      phrases.forEach { phrase ->
        val display = phrase.trim()
        val key = fold(display)
        if (key.isEmpty() || !seen.add(key)) return@forEach

        val index = terms.size
        terms += display
        words(display).forEachIndexed { position, word ->
          var node = root
          word.forEach { c -> node = node.children.getOrPut(c) { Node() } }
          node.entries += Entry(index, leading = position == 0)
        }
      }
      return WordTrie(terms, root)
    }

    /**
     * Case and accents removed, so "Crème fraîche" is found by typing "creme".
     *
     * Accents are stripped rather than respected because this is a phone keyboard in a shop. Someone
     * hunting for jalapeños will type "jalapenos", and a match they have to spell perfectly to get
     * is not helping with spelling.
     */
    fun fold(text: String): String =
      Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    private fun words(phrase: String): List<String> =
      fold(phrase).split(NOT_WORD).filter { it.isNotEmpty() }
  }
}

/**
 * What the app knows to offer before the owner has taught it anything.
 *
 * Produce first, since that is where the spelling actually varies — nobody misspells "milk", plenty
 * of people are unsure about "courgette" — then the rest of a normal shop. British English, matching
 * the rest of the interface.
 *
 * It is deliberately a plain list rather than anything loaded or generated: it ships in the APK at
 * no cost, needs no dependency, and a list of groceries is not something that needs a format.
 */
object CommonGroceries {

  val names: List<String> =
    listOf(
      // Vegetables
      "Asparagus", "Aubergine", "Beetroot", "Broccoli", "Brussels sprouts", "Butternut squash",
      "Cabbage", "Carrots", "Cauliflower", "Celery", "Chard", "Cherry tomatoes", "Chillies",
      "Courgette", "Cucumber", "Fennel", "Garlic", "Ginger", "Green beans", "Kale", "Leeks",
      "Lettuce", "Mushrooms", "Onions", "Pak choi", "Parsnips", "Peas", "Peppers", "Potatoes",
      "Pumpkin", "Radishes", "Red onions", "Rocket", "Shallots", "Spinach", "Spring onions",
      "Sweet potatoes", "Sweetcorn", "Tomatoes", "Turnips", "Watercress",
      // Fruit
      "Apples", "Apricots", "Avocados", "Bananas", "Blackberries", "Blueberries", "Cherries",
      "Clementines", "Figs", "Grapefruit", "Grapes", "Kiwi", "Lemons", "Limes", "Mango", "Melon",
      "Nectarines", "Oranges", "Papaya", "Peaches", "Pears", "Pineapple", "Plums", "Pomegranate",
      "Raspberries", "Rhubarb", "Strawberries", "Watermelon",
      // Herbs
      "Basil", "Bay leaves", "Chives", "Coriander", "Dill", "Mint", "Oregano", "Parsley",
      "Rosemary", "Sage", "Thyme",
      // Dairy and eggs
      "Butter", "Cheddar", "Cream", "Cream cheese", "Crème fraîche", "Double cream", "Eggs",
      "Feta", "Goat's cheese", "Halloumi", "Milk", "Mozzarella", "Oat milk", "Parmesan",
      "Soured cream", "Yoghurt",
      // Bakery
      "Bagels", "Baguette", "Brioche", "Croissants", "Crumpets", "Flatbread", "Naan", "Pitta",
      "Sourdough", "Tortillas", "Wholemeal bread",
      // Meat and fish
      "Bacon", "Beef mince", "Chicken breasts", "Chicken thighs", "Chorizo", "Cod", "Ham",
      "Lamb chops", "Mackerel", "Prawns", "Salmon", "Sardines", "Sausages", "Smoked salmon",
      "Tuna",
      // Store cupboard
      "Baking powder", "Basmati rice", "Black pepper", "Chickpeas", "Chopped tomatoes", "Cinnamon",
      "Cocoa", "Coconut milk", "Coffee beans", "Cumin", "Curry powder", "Flour", "Honey",
      "Kidney beans", "Lentils", "Mayonnaise", "Mustard", "Noodles", "Olive oil", "Olives",
      "Paprika", "Pasta", "Peanut butter", "Pesto", "Porridge oats", "Rice", "Salt", "Soy sauce",
      "Spaghetti", "Stock cubes", "Sugar", "Sunflower oil", "Tahini", "Tinned beans", "Tomato purée",
      "Tortilla chips", "Vinegar",
      // Frozen and chilled
      "Fish fingers", "Frozen peas", "Hummus", "Ice cream", "Tofu",
      // Drinks
      "Apple juice", "Beer", "Coffee", "Lemonade", "Orange juice", "Sparkling water", "Tea bags",
      "Tonic water", "Wine",
      // Household
      "Bin bags", "Cling film", "Dish soap", "Foil", "Kitchen roll", "Laundry detergent",
      "Sponges", "Toilet roll", "Washing-up liquid",
    )
}

/**
 * The vocabulary to offer on a given list, best sources first.
 *
 * The owner's own words come first and win every tie, in this order:
 *
 *  1. their staples, which they chose deliberately,
 *  2. everything on any of their lists, including things already ticked off — how they spell it is
 *     what matters, not whether they still need it,
 *  3. the built-in list.
 *
 * Things already on *this* list are left out. Offering someone a second "Tomatoes" is the duplicate
 * this feature exists to prevent.
 */
fun AppState.suggestionVocabulary(): List<String> =
  staples.map { it.name } + lists.flatMap { list -> list.items.map { it.name.value } } + CommonGroceries.names

/** Names already live on [list], folded for comparison. */
fun alreadyOn(list: GroceryList): Set<String> =
  list.liveItems.map { WordTrie.fold(it.name.value) }.toSet()
