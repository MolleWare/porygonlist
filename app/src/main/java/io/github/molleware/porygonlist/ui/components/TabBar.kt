package io.github.molleware.porygonlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.theme.Accent400
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Divider
import io.github.molleware.porygonlist.theme.Neutral400
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.Neutral900
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShopHairline
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
 * It follows shopping mode onto the dark ground, which is the one place the app inverts.
 *
 * The design keeps the same amber-700 and neutral-700 labels on both grounds, but that leaves the
 * bar close to illegible in shopping mode — roughly 2:1 against neutral-900. On the dark ground the
 * labels step up the ramp instead, to the same amber-400 the "Leave shopping mode" link uses and
 * the same neutral-400 the design already uses for secondary text there.
 */
@Composable
fun TabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, dark: Boolean = false) {
  Column(modifier.fillMaxWidth().background(if (dark) Neutral900 else Surface)) {
    Row(Modifier.fillMaxWidth().height(1.dp).background(if (dark) ShopHairline else Divider)) {}
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
      Tab.entries.forEach { tab ->
        val selected = tab == current
        val tint =
          when {
            selected && dark -> Accent400
            selected -> Accent700
            dark -> Neutral400
            else -> Neutral700
          }
        Column(
          Modifier.weight(1f)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = { onSelect(tab) })
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
