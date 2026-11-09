package io.github.molleware.porygonlist.data

import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.sync.ListId
import java.time.Instant
import java.time.ZoneId

/** How many of a list's newest runs are looked at, and how many of them a name must be in. */
const val STAPLE_RUNS = 4
const val STAPLE_RUNS_NEEDED = 3

/** How long a learned staple keeps without being added, in calendar months. */
const val STAPLE_MONTHS = 3L

/**
 * How long a learned staple removed by hand stays away from its list before it can be learned there
 * again, as chosen in Settings: a number of calendar weeks, or never. Saved by [key].
 */
enum class StapleComeback(val weeks: Long?, val label: String, val key: String) {
  ONE_WEEK(1, "1 week", "1"),
  FIVE_WEEKS(5, "5 weeks", "5"),
  TEN_WEEKS(10, "10 weeks", "10"),
  TWENTY_FIVE_WEEKS(25, "25 weeks", "25"),
  NEVER(null, "Never", "never");

  companion object {
    /** Until the owner chooses (Hugo, 2026-11-09). */
    val DEFAULT = FIVE_WEEKS

    /** The choice saved as [key], or null for one this build does not know. */
    fun ofKey(key: String): StapleComeback? = entries.firstOrNull { it.key == key }
  }
}

/** Whether a staple removed [since] may come back by [now] in [zone] under [comeback]. */
private fun lapsed(since: Hlc, now: Long, zone: ZoneId, comeback: StapleComeback): Boolean {
  val weeks = comeback.weeks ?: return false
  return !Instant.ofEpochMilli(now).isBefore(Instant.ofEpochMilli(since.wall).atZone(zone).plusWeeks(weeks).toInstant())
}

/**
 * A staple this phone learned for one list, by that list being cleared with it again and again.
 *
 * Kept by list id on [AppState], like the list's history, and never sent. [lastAdded] is the wall
 * time this phone last added its name to that list, by any way of adding; it starts as the newest run
 * that held it. [uses] counts taps on its tile, as a hand-made [Staple]'s does, from 0.
 */
data class LearnedStaple(val name: String, val lastAdded: Long, val uses: Int = 0)

/**
 * Whether [STAPLE_MONTHS] calendar months have passed since it was last added, at [now] in [zone]:
 * a staple last added at noon on 10 January is gone from noon on 10 April.
 */
fun LearnedStaple.forgottenBy(now: Long, zone: ZoneId): Boolean =
  !Instant.ofEpochMilli(now).isBefore(Instant.ofEpochMilli(lastAdded).atZone(zone).plusMonths(STAPLE_MONTHS).toInstant())

/** [list]'s learned staples as kept, forgotten ones included until its next Clear ticked. */
fun AppState.learnedOn(list: ListId): List<LearnedStaple> = learnedStaples[list].orEmpty()

/** The folded names removed from [list]'s staples by hand, lapsed ones included until its next Clear ticked. */
fun AppState.unwantedOn(list: ListId): Set<String> = unwantedStaples[list].orEmpty().keys

/** [list]'s unwanted names, each with when it was removed. */
fun AppState.unwantedSince(list: ListId): Map<String, Hlc> = unwantedStaples[list].orEmpty()

/** The ones of [list]'s unwanted names still kept away at [now], by the [StapleComeback] chosen. */
private fun AppState.stillUnwanted(list: ListId, now: Long, zone: ZoneId): Map<String, Hlc> =
  unwantedSince(list).filterValues { since -> !lapsed(since, now, zone, stapleComeback) }

/**
 * The Staples tab on [list] at [now]: the hand-made ones, in the order they were made, then the
 * list's learned ones not yet forgotten, the most recently added first. One kind of tile, so a
 * learned one is a [Staple] here too.
 */
fun AppState.staplesShownOn(list: ListId, now: Long, zone: ZoneId): List<Staple> =
  staples +
    learnedOn(list).filterNot { it.forgottenBy(now, zone) }.sortedByDescending { it.lastAdded }.map { Staple(it.name, it.uses) }

/**
 * What a press of Clear ticked on [list] at [now] teaches, once its run is recorded.
 *
 * First the list's forgotten staples go from the save, and so do the unwanted names removed long
 * enough ago for the [StapleComeback] chosen. Then anything in at least [STAPLE_RUNS_NEEDED] of its last
 * [STAPLE_RUNS] runs, by folded name, is learned, as the newest run that holds it spells it and as of
 * that run — unless it is a hand-made staple, already learned here, or still unwanted here, and unless
 * that run is itself long enough ago to be forgotten already. Several at once keep the order the
 * newest run has them. Comes back as it was when nothing changes.
 */
fun AppState.withStaplesLearned(list: ListId, now: Long, zone: ZoneId): AppState {
  val kept = learnedOn(list).filterNot { it.forgottenBy(now, zone) }
  val unwanted = stillUnwanted(list, now, zone)
  val recent = historyOf(list).take(STAPLE_RUNS)
  val learned =
    if (recent.size < STAPLE_RUNS_NEEDED) emptyList()
    else {
      val taken = (staples.map { WordTrie.fold(it.name) } + kept.map { WordTrie.fold(it.name) } + unwanted.keys).toSet()
      val runsHolding = recent.flatMap { run -> run.items.map { WordTrie.fold(it.name) }.distinct() }.groupingBy { it }.eachCount()
      recent
        .flatMap { run -> run.items.map { it.name to run.at.wall } }
        // Newest run first, so the first of each name is the newest run that holds it.
        .distinctBy { (name, _) -> WordTrie.fold(name) }
        .filter { (name, _) -> WordTrie.fold(name).let { it !in taken && runsHolding.getValue(it) >= STAPLE_RUNS_NEEDED } }
        .map { (name, at) -> LearnedStaple(name, at) }
        .filterNot { it.forgottenBy(now, zone) }
    }
  val next = kept + learned
  if (next == learnedOn(list) && unwanted == unwantedSince(list)) return this
  return copy(
    learnedStaples = if (next.isEmpty()) learnedStaples - list else learnedStaples + (list to next),
    unwantedStaples = if (unwanted.isEmpty()) unwantedStaples - list else unwantedStaples + (list to unwanted),
  )
}

/**
 * [name] added to [list] on this phone at [at]: a learned staple of that name there, not yet
 * forgotten, keeps from then. A forgotten one is not brought back by it.
 */
fun AppState.withStapleAdded(list: ListId, name: String, at: Long, zone: ZoneId): AppState {
  val folded = WordTrie.fold(name)
  val learned = learnedOn(list)
  if (learned.none { WordTrie.fold(it.name) == folded && !it.forgottenBy(at, zone) }) return this
  return copy(
    learnedStaples =
      learnedStaples + (list to learned.map { if (WordTrie.fold(it.name) == folded && !it.forgottenBy(at, zone)) it.copy(lastAdded = at) else it })
  )
}

/** A tap on the tile of [list]'s learned staple called [name], counted on it. */
fun AppState.withStapleUsed(list: ListId, name: String): AppState {
  val learned = learnedOn(list)
  if (learned.none { it.name == name }) return this
  return copy(learnedStaples = learnedStaples + (list to learned.map { if (it.name == name) it.copy(uses = it.uses + 1) else it }))
}

/**
 * [list]'s learned staple called [name] removed by hand [at]: gone, and not learned there again for
 * as long as the [StapleComeback] chosen says, counted from [at].
 */
fun AppState.withStapleRemoved(list: ListId, name: String, at: Hlc): AppState {
  val learned = learnedOn(list)
  if (learned.none { it.name == name }) return this
  val left = learned.filterNot { it.name == name }
  return copy(
    learnedStaples = if (left.isEmpty()) learnedStaples - list else learnedStaples + (list to left),
    unwantedStaples = unwantedStaples + (list to unwantedSince(list) + (WordTrie.fold(name) to at)),
  )
}

/**
 * A hand-made staple called [name], last on the grid. It takes the place of a learned staple of the
 * same folded name on every list, so the grid never shows both, and lifts that name's being unwanted
 * on every list. One already there, under any capitals, is left as it is.
 */
fun AppState.withHandMadeStaple(name: String): AppState {
  val trimmed = name.trim()
  if (trimmed.isEmpty() || staples.any { it.name.equals(trimmed, ignoreCase = true) }) return this
  val folded = WordTrie.fold(trimmed)
  return copy(
    staples = staples + Staple(trimmed),
    learnedStaples =
      learnedStaples.mapValues { (_, learned) -> learned.filterNot { WordTrie.fold(it.name) == folded } }.filterValues { it.isNotEmpty() },
    unwantedStaples = unwantedStaples.mapValues { (_, names) -> names - folded }.filterValues { it.isNotEmpty() },
  )
}

/**
 * [from]'s learned staples and unwanted names moved onto [into], for a list merged into another or
 * kept under a new id. A name [into] already has, learned or unwanted, keeps what [into] says.
 */
fun AppState.withStaplesMoved(from: ListId, into: ListId): AppState {
  if (from !in learnedStaples && from !in unwantedStaples) return this
  val unwanted = unwantedSince(from) + unwantedSince(into)
  val has = learnedOn(into).map { WordTrie.fold(it.name) }.toSet() + unwantedOn(into)
  val learned = learnedOn(into) + learnedOn(from).filterNot { WordTrie.fold(it.name) in has }
  return copy(
    learnedStaples = (learnedStaples - from - into).let { if (learned.isEmpty()) it else it + (into to learned) },
    unwantedStaples = (unwantedStaples - from - into).let { if (unwanted.isEmpty()) it else it + (into to unwanted) },
  )
}

/** These as a save holds them: each folded name once, the first kept. */
internal fun List<LearnedStaple>.asKept(): List<LearnedStaple> = distinctBy { WordTrie.fold(it.name) }
