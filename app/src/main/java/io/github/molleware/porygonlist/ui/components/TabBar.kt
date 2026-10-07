package io.github.molleware.porygonlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Divider
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Surface

/** The four destinations in the bottom bar. */
enum class Tab(val label: String, val iconPath: String) {
  LISTS("Lists", IconPaths.TAB_LISTS),
  SHOP("Shop", IconPaths.TAB_SHOP),
  STAPLES("Staples", IconPaths.TAB_PANTRY),
  SHARE("Share", IconPaths.TAB_INVITE),
}

/**
 * The persistent bottom bar.
 *
 * One ground everywhere. It used to take a dark variant, because shopping mode inverted and the
 * bar followed it down; that screen is now on the same cream as the rest and the second set of
 * label colours went with it.
 */
@Composable
fun TabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth().background(Surface)) {
    Row(Modifier.fillMaxWidth().height(1.dp).background(Divider)) {}
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
      Tab.entries.forEach { tab ->
        val selected = tab == current
        val tint = if (selected) Accent700 else Neutral700
        Column(
          Modifier.weight(1f)
            .defaultMinSize(minHeight = 48.dp)
            // A tab to a screen reader, so it says which one you are on.
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
            .padding(top = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          StrokeIcon(tab.iconPath, contentDescription = null, size = 21.dp, strokeWidth = 2.75f, tint = tint)
          Text(tab.label, style = PorygonType.TabLabel, color = tint)
        }
      }
    }
  }
}
