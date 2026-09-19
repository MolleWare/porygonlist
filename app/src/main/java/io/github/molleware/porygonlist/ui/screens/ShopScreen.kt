package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent2900
import io.github.molleware.porygonlist.theme.Accent400
import io.github.molleware.porygonlist.theme.Neutral100
import io.github.molleware.porygonlist.theme.Neutral400
import io.github.molleware.porygonlist.theme.Neutral900
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.ShopRow
import io.github.molleware.porygonlist.theme.ShopRowChecked
import io.github.molleware.porygonlist.theme.ShopTrack
import io.github.molleware.porygonlist.theme.StrikeThrough
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.BackLink
import io.github.molleware.porygonlist.ui.components.CheckCircle
import io.github.molleware.porygonlist.ui.others
import io.github.molleware.porygonlist.ui.partnerName
import io.github.molleware.porygonlist.ui.shopSubLabel

/**
 * Shopping mode — the one screen that inverts.
 *
 * Bigger targets and bigger type, for reading at arm's length with a trolley in the other hand.
 */
@Composable
fun ShopScreen(state: AppState, onToggleChecked: (ItemId) -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier) {
  val list = state.activeList
  val done = list.doneCount
  val total = list.liveItems.size
  val partner = list.partnerName(state.localDevice)

  Column(
    modifier
      .background(Neutral900)
      .verticalScroll(rememberScrollState())
      .padding(top = 18.dp, bottom = 88.dp)
  ) {
    Column(Modifier.padding(horizontal = 22.dp)) {
      BackLink("Leave shopping mode", onLeave, tint = Accent400)
      Text(
        "In the shop",
        style = PorygonType.ScreenTitle,
        color = Neutral100,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
      )
      Text(
        "$done of $total in the trolley",
        style = PorygonType.BodyLarge,
        color = Neutral400,
        modifier = Modifier.padding(bottom = 12.dp),
      )
      val progress = if (total == 0) 0f else done.toFloat() / total
      Box(Modifier.fillMaxWidth().height(8.dp).clip(Shapes.Pill).background(ShopTrack)) {
        Box(Modifier.fillMaxWidth(progress).height(8.dp).clip(Shapes.Pill).background(Accent400))
      }
    }

    Column(
      Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      list.liveItems.forEach { item ->
        ShopItemRow(
          item = item,
          subLabel = shopSubLabel(item, list, state.localDevice),
          partnerInitial = list.others(state.localDevice).firstOrNull()?.initial ?: "?",
          createdByOther = item.createdBy != state.localDevice,
          onToggle = { onToggleChecked(item.id) },
        )
      }
    }

    Text(
      "$partner is crossing things off too.",
      style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.55),
      color = Neutral400,
      modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp),
    )
  }
}

@Composable
private fun ShopItemRow(
  item: GroceryItem,
  subLabel: String,
  partnerInitial: String,
  createdByOther: Boolean,
  onToggle: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth()
      .clip(Shapes.ShopRow)
      .background(if (item.checked) ShopRowChecked else ShopRow)
      .clickable(onClick = onToggle)
      .alpha(if (item.checked) 0.5f else 1f)
      .padding(horizontal = 18.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    CheckCircle(item.checked, size = 34.dp, dark = true, iconSize = 20.dp)
    Column(Modifier.weight(1f)) {
      Text(
        item.label,
        style = PorygonType.ShopItemName,
        color = Neutral100,
        textDecoration = if (item.checked) StrikeThrough else null,
      )
      Text(subLabel, style = PorygonType.Meta, color = Neutral400, modifier = Modifier.padding(top = 2.dp))
    }
    // Marks something the other person put on the list, so you know whose errand it is.
    if (createdByOther && !item.checked) {
      Avatar(partnerInitial, Accent2300, Accent2900)
    }
  }
}
