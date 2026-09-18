package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.Staple
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent300
import io.github.molleware.porygonlist.theme.Accent900
import io.github.molleware.porygonlist.theme.Elevation
import io.github.molleware.porygonlist.theme.Neutral300
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShadowInk
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.IconActionButton
import io.github.molleware.porygonlist.ui.components.IconPaths
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.tabBarClearance

/**
 * One tap drops a staple onto the list you are working on — no typing, no searching.
 *
 * The grid is the owner's, not the design's: tiles are added below and removed by a long press. A
 * tile's sub-label counts how often it has actually been used, which is the one thing about a
 * staple the app can know without being told.
 */
@Composable
fun StaplesScreen(
  staples: List<Staple>,
  activeListName: String,
  draft: String,
  onDraftChange: (String) -> Unit,
  onAddStaple: () -> Unit,
  onUse: (String) -> Unit,
  confirmingRemoval: String?,
  onAskRemove: (String) -> Unit,
  onCancelRemove: () -> Unit,
  onRemove: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = tabBarClearance())
  ) {
    Text("Staples", style = PorygonType.ScreenTitle, color = TextInk, modifier = Modifier.padding(bottom = 5.dp))
    Text(
      if (staples.isEmpty()) "Add the things you buy over and over, and they are one tap from then on."
      else "Tap to drop something onto $activeListName. Press and hold a tile to take it off the grid.",
      style = PorygonType.BodyLarge.copy(lineHeight = PorygonType.BodyLarge.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 20.dp),
    )

    // A fixed three-column grid. The set is small and known, so plain rows beat a lazy grid here —
    // and they nest inside the screen's scroll without fighting it.
    staples.chunked(COLUMNS).forEachIndexed { rowIndex, rowItems ->
      if (rowIndex > 0) Spacer(Modifier.padding(top = 11.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        rowItems.forEachIndexed { columnIndex, staple ->
          StapleTile(
            staple = staple,
            index = rowIndex * COLUMNS + columnIndex,
            confirming = confirmingRemoval == staple.name,
            onClick = { if (confirmingRemoval == staple.name) onCancelRemove() else onUse(staple.name) },
            onLongClick = { onAskRemove(staple.name) },
            onRemove = { onRemove(staple.name) },
            modifier = Modifier.weight(1f),
          )
        }
        // Keeps a short final row aligned with the columns above it.
        repeat(COLUMNS - rowItems.size) { Spacer(Modifier.weight(1f)) }
      }
    }

    Row(
      Modifier.fillMaxWidth().padding(top = if (staples.isEmpty()) 0.dp else 20.dp),
      horizontalArrangement = Arrangement.spacedBy(9.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PorygonTextField(
        value = draft,
        onValueChange = onDraftChange,
        placeholder = "Add a staple…",
        modifier = Modifier.weight(1f),
        imeAction = ImeAction.Done,
        onSubmit = onAddStaple,
      )
      IconActionButton(IconPaths.PLUS, contentDescription = "Add staple", onClick = onAddStaple)
    }
  }
}

private const val COLUMNS = 3

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StapleTile(
  staple: Staple,
  index: Int,
  confirming: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier
      .shadow(Elevation.Sm, Shapes.Row, ambientColor = ShadowInk, spotColor = ShadowInk)
      .clip(Shapes.Row)
      .background(Surface)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = 10.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    // The blob colour cycles through three roles so the grid reads as a texture, not a table.
    val blob =
      when (index % 3) {
        0 -> Accent300
        1 -> Accent2300
        else -> Neutral300
      }
    Box(Modifier.size(34.dp).clip(CircleShape).background(blob))
    Text(
      staple.name,
      style = PorygonType.MetaBold.copy(lineHeight = PorygonType.Meta.fontSize * 1.25),
      color = TextInk,
      textAlign = TextAlign.Center,
    )

    // The tile itself becomes the confirmation, so the answer is where the question was asked.
    if (confirming) {
      Text(
        "Remove",
        style = PorygonType.Tiny,
        color = Accent900,
        textAlign = TextAlign.Center,
        modifier =
          Modifier.clip(Shapes.Pill)
            .background(Accent300)
            .clickable(onClick = onRemove)
            .padding(horizontal = 10.dp, vertical = 4.dp),
      )
    } else {
      Text(staple.note, style = PorygonType.Tiny.copy(fontWeight = PorygonType.Meta.fontWeight), color = Neutral700)
    }
  }
}
