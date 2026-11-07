package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ListId

/** How many runs a list keeps. The oldest drops off when one more is recorded. */
const val HISTORY_RUNS = 50

/**
 * One press of Clear ticked on this phone: when, and what it took off the list.
 *
 * This phone's own, like a [Recipe]: it hangs off [AppState] by list id rather than off the
 * [GroceryList], which is what travels, so nothing here can reach another phone. [at] is the stamp
 * the press gave the items it removed, and being this phone's own clock, no two runs share one;
 * their wall time alone can, since a clock following a peer's that runs ahead does not move on.
 */
data class ClearedRun(val at: Hlc, val items: List<Ingredient>)

/** [list]'s runs, newest first. */
fun AppState.historyOf(list: ListId): List<ClearedRun> = history[list].orEmpty()

/**
 * The press of Clear ticked on [list] as of [at], recorded before the items go: the ticked ones still
 * on it, in the order they are shown. Comes back as it was when there are none, since a press that
 * clears nothing is not a run. A quantity of nothing, which a pasted "0 eggs" can make, is kept as
 * one, as a recipe reads it: put back, it would otherwise ask for none.
 */
fun AppState.withClearRecorded(list: ListId, at: Hlc): AppState {
  val cleared = lists.firstOrNull { it.id == list }?.orderedItems.orEmpty().filter { it.checked }
  if (cleared.isEmpty()) return this
  val run = ClearedRun(at, cleared.map { Ingredient(it.name.value, it.qty.value.coerceAtLeast(1)) })
  return copy(history = history + (list to (historyOf(list) + run).asKept()))
}

/** [from]'s runs moved onto [into]'s, for a list that now goes by another id or has been merged. */
fun Map<ListId, List<ClearedRun>>.movedTo(from: ListId, into: ListId): Map<ListId, List<ClearedRun>> {
  val moving = this[from] ?: return this
  return this - from + (into to (this[into].orEmpty() + moving).asKept())
}

/** These runs as a list keeps them: newest first, each once, at most [HISTORY_RUNS]. */
internal fun List<ClearedRun>.asKept(): List<ClearedRun> = distinctBy { it.at }.sortedByDescending { it.at }.take(HISTORY_RUNS)

/** One item of one run on the history page, by where it is: a run can hold the same name twice. */
data class ClearedPick(val run: Hlc, val index: Int)

/** Whether every item of [run] is selected. */
fun Set<ClearedPick>.hasAllOf(run: ClearedRun): Boolean = run.items.indices.all { ClearedPick(run.at, it) in this }

/** The selection with [pick] changed: selected if it was not, unselected if it was. */
fun Set<ClearedPick>.toggling(pick: ClearedPick): Set<ClearedPick> = if (pick in this) this - pick else this + pick

/** The selection with all of [run] selected, or none of it if all of it already was. */
fun Set<ClearedPick>.togglingRun(run: ClearedRun): Set<ClearedPick> {
  val whole = run.items.indices.map { ClearedPick(run.at, it) }
  return if (hasAllOf(run)) this - whole.toSet() else this + whole
}

/**
 * What a selection comes to, given these runs newest first. Things that fold to the same name count
 * once — as the newest run has it, name and quantity, and within one run as it comes first — so the
 * same thing bought every week is not asked for fifty times over.
 */
fun List<ClearedRun>.picked(selection: Set<ClearedPick>): List<Ingredient> =
  flatMap { run -> run.items.filterIndexed { index, _ -> ClearedPick(run.at, index) in selection } }
    .distinctBy { WordTrie.fold(it.name) }

/** This recipe with [more] added last. Anything it already has, by name, keeps its own quantity. */
fun Recipe.withIngredients(more: List<Ingredient>): Recipe {
  val has = ingredients.map { WordTrie.fold(it.name) }.toSet()
  return copy(ingredients = ingredients + more.filterNot { WordTrie.fold(it.name) in has })
}
