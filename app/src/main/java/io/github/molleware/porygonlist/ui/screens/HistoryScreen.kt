package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.ClearedPick
import io.github.molleware.porygonlist.data.ClearedRun
import io.github.molleware.porygonlist.data.Ingredient
import io.github.molleware.porygonlist.data.hasAllOf
import io.github.molleware.porygonlist.data.picked
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Divider
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.clockLine
import io.github.molleware.porygonlist.ui.components.BackLink
import io.github.molleware.porygonlist.ui.components.CheckCircle
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.SectionLabel
import io.github.molleware.porygonlist.ui.components.tabBarClearance

/**
 * What this phone has cleared from one list, newest first, to bring some of it back.
 *
 * Lazy, and a row of its own for every item, because fifty runs of thirty items is 1500 rows. The
 * selection comes in as a function, read only by the rows and the actions through a derived state,
 * so this body and the list's contents are not run again by a tap, and a tap redraws only the row it
 * changed, its run's title if that changed too, and the count. Nothing here is editable.
 */
@Composable
fun HistoryScreen(
  listName: String,
  runs: List<ClearedRun>,
  selection: () -> Set<ClearedPick>,
  onToggleItem: (ClearedPick) -> Unit,
  onToggleRun: (ClearedRun) -> Unit,
  onSaveAsRecipe: () -> Unit,
  onAddToRecipe: () -> Unit,
  onPutBack: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    LazyColumn(
      Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = tabBarClearance()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(key = "heading", contentType = "heading") {
        Column(Modifier.padding(bottom = 4.dp)) {
          BackLink(listName, onBack, tint = Accent700)
          Text("History", style = PorygonType.ScreenTitle, color = TextInk, modifier = Modifier.padding(top = 9.dp, bottom = 5.dp))
          Text(
            if (runs.isEmpty()) "Nothing yet. Each time you clear ticked items from this list on this phone, they appear here."
            else "What you cleared from this list on this phone. Tap to choose things to use again.",
            style = PorygonType.BodyLarge.copy(lineHeight = PorygonType.BodyLarge.fontSize * 1.5),
            color = Neutral700,
          )
        }
      }
      runs.forEach { run ->
        // A stamp, unlike a time, is one run's alone, and a key has to be something a Bundle can hold.
        val id = run.at.encode()
        item(key = id, contentType = "title") { RunTitle(run, selection, onToggleRun) }
        itemsIndexed(run.items, key = { index, _ -> "$id/$index" }, contentType = { _, _ -> "item" }) { index, item ->
          ClearedRow(item, ClearedPick(run.at, index), selection, onToggleItem)
        }
      }
    }
    HistoryActions(runs, selection, onSaveAsRecipe, onAddToRecipe, onPutBack)
  }
}

/** A run's date and time, and the way to select or unselect all of it. */
@Composable
private fun RunTitle(run: ClearedRun, selection: () -> Set<ClearedPick>, onToggle: (ClearedRun) -> Unit) {
  val whole by remember(run) { derivedStateOf { selection().hasAllOf(run) } }
  Row(
    Modifier.fillMaxWidth()
      .padding(top = 10.dp)
      .clip(Shapes.Row)
      .toggleable(value = whole, role = Role.Checkbox, onValueChange = { onToggle(run) })
      .defaultMinSize(minHeight = 44.dp)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    CheckCircle(whole)
    SectionLabel(clockLine(now = run.at.wall), Modifier.weight(1f))
  }
}

/** One thing a run cleared. The name is read out with the tick, so it needs no label of its own. */
@Composable
private fun ClearedRow(item: Ingredient, pick: ClearedPick, selection: () -> Set<ClearedPick>, onToggle: (ClearedPick) -> Unit) {
  val selected by remember(pick) { derivedStateOf { pick in selection() } }
  Row(
    Modifier.fillMaxWidth()
      .clip(Shapes.Row)
      .background(Surface)
      .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle(pick) })
      .defaultMinSize(minHeight = 48.dp)
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    CheckCircle(selected)
    Text(item.label, style = PorygonType.ItemName, color = TextInk, modifier = Modifier.weight(1f))
  }
}

/** The three things to do with a selection, and how many it comes to. Only while there is one. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryActions(
  runs: List<ClearedRun>,
  selection: () -> Set<ClearedPick>,
  onSaveAsRecipe: () -> Unit,
  onAddToRecipe: () -> Unit,
  onPutBack: () -> Unit,
) {
  val count by remember(runs) { derivedStateOf { runs.picked(selection()).size } }
  if (count == 0) return
  val buttonPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
  Column(Modifier.fillMaxWidth().background(Surface)) {
    Row(Modifier.fillMaxWidth().height(1.dp).background(Divider)) {}
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      Text("$count selected", style = PorygonType.Meta, color = Neutral700)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Save as recipe", onSaveAsRecipe, style = PorygonType.Meta, contentPadding = buttonPadding)
        SecondaryButton("Add to recipe", onAddToRecipe, style = PorygonType.Meta, contentPadding = buttonPadding)
        PrimaryButton("Put back on the list", onPutBack, style = PorygonType.Meta, contentPadding = buttonPadding)
      }
    }
  }
}
