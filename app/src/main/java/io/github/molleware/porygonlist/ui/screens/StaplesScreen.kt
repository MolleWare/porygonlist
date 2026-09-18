package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent300
import io.github.molleware.porygonlist.theme.Elevation
import io.github.molleware.porygonlist.theme.Neutral300
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShadowInk
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk

/** The things you buy over and over, with how often you tend to need them. */
private val STAPLES =
  listOf(
    "Oat milk" to "every week",
    "Eggs" to "every week",
    "Sourdough" to "twice a week",
    "Coffee beans" to "monthly",
    "Olive oil" to "monthly",
    "Rice" to "rarely",
    "Bin bags" to "monthly",
    "Yoghurt" to "every week",
    "Tinned beans" to "rarely",
  )

/** One tap drops a staple onto the list you are working on — no typing, no searching. */
@Composable
fun StaplesScreen(activeListName: String, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 88.dp)
  ) {
    Text("Staples", style = PorygonType.ScreenTitle, color = TextInk, modifier = Modifier.padding(bottom = 5.dp))
    Text(
      "Tap to drop something onto $activeListName.",
      style = PorygonType.BodyLarge,
      color = Neutral700,
      modifier = Modifier.padding(bottom = 20.dp),
    )

    // A fixed three-column grid. The set is small and known, so plain rows beat a lazy grid here —
    // and they nest inside the screen's scroll without fighting it.
    STAPLES.chunked(COLUMNS).forEachIndexed { rowIndex, rowItems ->
      if (rowIndex > 0) Spacer(Modifier.padding(top = 11.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        rowItems.forEachIndexed { columnIndex, (name, meta) ->
          StapleTile(
            name = name,
            meta = meta,
            index = rowIndex * COLUMNS + columnIndex,
            onClick = { onAdd(name) },
            modifier = Modifier.weight(1f),
          )
        }
        // Keeps a short final row aligned with the columns above it.
        repeat(COLUMNS - rowItems.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

private const val COLUMNS = 3

@Composable
private fun StapleTile(name: String, meta: String, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier
      .shadow(Elevation.Sm, Shapes.Row, ambientColor = ShadowInk, spotColor = ShadowInk)
      .clip(Shapes.Row)
      .background(Surface)
      .clickable(onClick = onClick)
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
      name,
      style = PorygonType.MetaBold.copy(lineHeight = PorygonType.Meta.fontSize * 1.25),
      color = TextInk,
      textAlign = TextAlign.Center,
    )
    Text(meta, style = PorygonType.Tiny.copy(fontWeight = PorygonType.Meta.fontWeight), color = Neutral700)
  }
}
