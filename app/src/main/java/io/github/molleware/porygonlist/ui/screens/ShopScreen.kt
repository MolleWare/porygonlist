package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent2900
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Neutral300
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.StrikeThrough
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.BackLink
import io.github.molleware.porygonlist.ui.components.CheckCircle
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.tabBarClearance
import io.github.molleware.porygonlist.ui.others
import io.github.molleware.porygonlist.ui.names
import io.github.molleware.porygonlist.ui.shopSubLabel

/**
 * Shopping mode — one list, one job, nothing that can be mis-tapped.
 *
 * Bigger targets and bigger type, for reading at arm's length with a trolley in the other hand.
 * What it leaves out is the point: no add field, no suggestions, no edit pencil, no conflict card.
 * The whole row is the tick target, so hitting it needs no aim.
 *
 * It used to invert onto a dark ground, which was the loudest signal that this was a different
 * mode — and the only one. Now it sits on the same cream as everything else and earns the
 * distinction from its shape instead: the progress bar at the top, and the fact that everything
 * here is one of two verbs, tick and clear.
 *
 * **Clearing lives here too, and that is not a convenience.** Ticking says you have picked
 * something up; clearing is what ends the shop. With clearing only on the list screen, shopping
 * mode was a place you could not finish in — tick everything, leave, then clear. The job now
 * completes where it is done.
 */
@Composable
fun ShopScreen(
  state: AppState,
  onToggleChecked: (ItemId) -> Unit,
  confirmingClear: Boolean,
  onAskClear: () -> Unit,
  onCancelClear: () -> Unit,
  onClearChecked: () -> Unit,
  onLeave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val list = state.activeList
  val done = list.doneCount
  val total = list.liveItems.size

  Column(modifier.verticalScroll(rememberScrollState()).padding(top = 18.dp, bottom = tabBarClearance())) {
    Column(Modifier.padding(horizontal = 22.dp)) {
      BackLink("Leave shopping mode", onLeave, tint = Accent700)
      Text(
        "In the shop",
        style = PorygonType.ScreenTitle,
        color = TextInk,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
      )
      Text(
        "$done of $total in the trolley",
        style = PorygonType.ShopMeta,
        color = Neutral700,
        modifier = Modifier.padding(bottom = 12.dp),
      )
      val progress = if (total == 0) 0f else done.toFloat() / total
      Box(Modifier.fillMaxWidth().height(8.dp).clip(Shapes.Pill).background(Neutral300)) {
        Box(Modifier.fillMaxWidth(progress).height(8.dp).clip(Shapes.Pill).background(Accent))
      }
    }

    Column(
      Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // The list's shared order, so a list sorted by aisle is walked in that order.
      list.orderedItems.forEach { item ->
        ShopItemRow(
          item = item,
          subLabel = shopSubLabel(item, list, state.localDevice),
          partnerInitial = list.others(state.localDevice).firstOrNull()?.initial ?: "?",
          createdByOther = item.createdBy != state.localDevice,
          onToggle = { onToggleChecked(item.id) },
        )
      }
    }

    // The end of the shop, in the place the shop happens. Same wording and the same ask-first
    // shape as the list screen's, because it is the same act and should not read as a new one.
    if (done > 0) {
      ClearTrolley(
        count = done,
        confirming = confirmingClear,
        onAsk = onAskClear,
        onCancel = onCancelClear,
        onConfirm = onClearChecked,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
      )
    }

    // Only worth saying when there is actually someone else on the list. With nobody sharing it,
    // nobody is crossing anything off, and the fallback name turns the sentence into "them is".
    // Said as what will happen rather than what is happening: nothing here knows whether anyone else
    // is in a shop right now, and "Ava is crossing things off too" claimed that she was.
    val others = list.others(state.localDevice)
    if (others.isNotEmpty()) {
      Text(
        "Ticks from ${names(others)} show up here too.",
        style = PorygonType.ShopMeta.copy(lineHeight = PorygonType.ShopMeta.fontSize * 1.5),
        color = Neutral700,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp),
      )
    }
  }
}

/**
 * Takes everything in the trolley off the list.
 *
 * Bigger than the list screen's version of the same control, for the same reason everything else
 * here is: one hand, moving, not looking closely. It still asks first — several items at once is
 * worth a moment's pause, and the count is the whole of what needs saying.
 */
@Composable
private fun ClearTrolley(
  count: Int,
  confirming: Boolean,
  onAsk: () -> Unit,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth()) {
    Text(
      if (confirming) "Take $count off the list?" else "$count in the trolley",
      style = PorygonType.ShopMeta,
      color = Neutral700,
      modifier = Modifier.padding(bottom = 10.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      if (confirming) {
        PrimaryButton(
          "Clear them",
          onConfirm,
          modifier = Modifier.heightIn(min = 48.dp),
          style = PorygonType.ShopMeta,
        )
        SecondaryButton("Keep", onCancel, modifier = Modifier.heightIn(min = 48.dp), style = PorygonType.ShopMeta)
      } else {
        SecondaryButton(
          "Clear the trolley",
          onAsk,
          modifier = Modifier.heightIn(min = 48.dp),
          style = PorygonType.ShopMeta,
        )
      }
    }
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
      // Surface either way. A checked row is marked by the strikethrough and the fade below; a
      // second, quieter background as well made it read as disabled rather than done.
      .background(Surface)
      // A checkbox to a screen reader, so it says whether the item is in the trolley, not only its name.
      .toggleable(value = item.checked, role = Role.Checkbox, onValueChange = { onToggle() })
      .alpha(if (item.checked) 0.55f else 1f)
      .padding(horizontal = 18.dp, vertical = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    CheckCircle(item.checked, size = 38.dp, iconSize = 23.dp)
    Column(Modifier.weight(1f)) {
      Text(
        item.label,
        style = PorygonType.ShopItemName,
        color = TextInk,
        textDecoration = if (item.checked) StrikeThrough else null,
      )
      Text(subLabel, style = PorygonType.ShopMeta, color = Neutral700, modifier = Modifier.padding(top = 3.dp))
    }
    // Marks something the other person put on the list, so you know whose errand it is.
    if (createdByOther && !item.checked) {
      Avatar(partnerInitial, Accent2300, Accent2900)
    }
  }
}
