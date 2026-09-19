package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.Conflict
import io.github.molleware.porygonlist.data.GroceryItem
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.sync.ItemId
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent100
import io.github.molleware.porygonlist.theme.Accent2
import io.github.molleware.porygonlist.theme.Accent2100
import io.github.molleware.porygonlist.theme.Accent2200
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent2800
import io.github.molleware.porygonlist.theme.Accent300
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Accent800
import io.github.molleware.porygonlist.theme.Accent900
import io.github.molleware.porygonlist.theme.Bg
import io.github.molleware.porygonlist.theme.Neutral600
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.StrikeThrough
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.BackLink
import io.github.molleware.porygonlist.ui.components.CheckCircle
import io.github.molleware.porygonlist.ui.components.Dot
import io.github.molleware.porygonlist.ui.components.IconActionButton
import io.github.molleware.porygonlist.ui.components.IconPaths
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.tabBarClearance
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.StrokeIcon
import io.github.molleware.porygonlist.ui.conflictSide
import io.github.molleware.porygonlist.ui.itemSubLabel
import io.github.molleware.porygonlist.ui.others

/**
 * Things worth offering before you have typed anything.
 *
 * A fixed three, from the design. Once there is a prefix to go on these give way to real matches —
 * see [ListDetailScreen]'s `suggestions`.
 */
private val SUGGESTIONS = listOf("Parmesan", "Spinach", "Lemons")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ListDetailScreen(
  state: AppState,
  draft: String,
  onDraftChange: (String) -> Unit,
  onSubmitDraft: () -> Unit,
  /** Names matching what is being typed. Empty when there is nothing to go on. */
  suggestions: List<String>,
  onAddItem: (String) -> Unit,
  onToggleChecked: (ItemId) -> Unit,
  onEditItem: (GroceryItem) -> Unit,
  onMerge: () -> Unit,
  onKeepBoth: () -> Unit,
  /** Opens sharing for this list — who has it, and who else could. */
  onShare: () -> Unit,
  confirmingClear: Boolean,
  onAskClear: () -> Unit,
  onCancelClear: () -> Unit,
  onClearChecked: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val list = state.activeList
  val scrollState = rememberScrollState()

  // A new item lands at the end of the list, which is below the fold once the list is long enough.
  // Follow it down, so you can see what you just added and the field is still under your thumb for
  // the next one.
  //
  // Only your own adds do this. Items also arrive from the other phone, and moving the view while
  // someone is reading it would be the wrong kind of helpful.
  var followAdd by remember { mutableStateOf(false) }
  LaunchedEffect(list.liveItems.size) {
    if (!followAdd) return@LaunchedEffect
    followAdd = false
    // The new row has to be measured before maxValue means anything.
    withFrameNanos {}
    scrollState.animateScrollTo(scrollState.maxValue)
  }
  val addAndFollow = { name: String ->
    followAdd = true
    onAddItem(name)
  }
  // A blank draft adds nothing, so there would be no new row to follow.
  val submitAndFollow = {
    if (draft.isNotBlank()) followAdd = true
    onSubmitDraft()
  }

  Column(modifier.verticalScroll(scrollState).padding(top = 18.dp, bottom = tabBarClearance())) {
    Column(Modifier.padding(horizontal = 20.dp)) {
      BackLink("All lists", onBack, tint = Accent700)
      Text(
        list.name,
        style = PorygonType.ScreenTitle,
        color = TextInk,
        modifier = Modifier.padding(top = 9.dp, bottom = 5.dp),
      )
      // Who has this list is also the way to change who has it: the status line is the question,
      // so it is also the door.
      Row(
        Modifier.clip(Shapes.Pill)
          .clickable(onClick = onShare)
          .padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
          .then(Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        Dot(if (state.online) Accent2 else Accent)
        Text(listStatus(list, state.online, state.localDevice), style = PorygonType.Meta, color = Neutral700)
        Text(
          if (list.others(state.localDevice).isEmpty()) "Share it" else "Change",
          style = PorygonType.Tiny.copy(fontSize = PorygonType.TabLabel.fontSize * 1.2),
          color = Accent900,
          modifier = Modifier.clip(Shapes.Pill).background(Accent300).padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
      Spacer(Modifier.padding(bottom = 16.dp))
      Text(
        "Hold an item to edit it.",
        style = PorygonType.Fine,
        color = Neutral700,
        modifier = Modifier.padding(bottom = 14.dp),
      )
    }

    state.conflict?.let { conflict ->
      ConflictCard(
        conflict = conflict,
        yourSide = conflictSide(conflict.yours, list, state.localDevice),
        theirSide = conflictSide(conflict.theirs, list, state.localDevice),
        onMerge = onMerge,
        onKeepBoth = onKeepBoth,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
      )
    }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      list.liveItems.forEach { item ->
        ItemRow(
          item = item,
          subLabel = itemSubLabel(item, list, state.localDevice),
          partnerInitial = list.others(state.localDevice).firstOrNull()?.initial ?: "?",
          onToggle = { onToggleChecked(item.id) },
          onEdit = { onEditItem(item) },
        )
      }
    }

    // Only once there is something in the trolley. Ticking says you have it and the row stays put
    // so you can see what you have got; this is the thing that ends the shop.
    val ticked = list.liveItems.count { it.checked }
    if (ticked > 0) {
      ClearTicked(
        count = ticked,
        confirming = confirmingClear,
        onAsk = onAskClear,
        onCancel = onCancelClear,
        onConfirm = onClearChecked,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
      )
    }

    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
      FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        // Matches on what is being typed, or the design's standing three when nothing is. The two
        // behave identically on tap, so the row does not change meaning under the finger.
        val matching = suggestions.isNotEmpty()
        val offered = if (matching) suggestions else if (draft.isBlank()) SUGGESTIONS else emptyList()
        offered.forEach { name ->
          Text(
            name,
            style = PorygonType.Meta,
            color = Accent800,
            modifier =
              Modifier.clip(Shapes.Pill)
                .background(if (matching) Accent300 else Accent100)
                .clickable { addAndFollow(name) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
          )
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PorygonTextField(
          value = draft,
          onValueChange = onDraftChange,
          placeholder = "Add something…",
          modifier = Modifier.weight(1f),
          onSubmit = submitAndFollow,
        )
        IconActionButton(IconPaths.PLUS, contentDescription = "Add item", onClick = submitAndFollow)
      }
    }
  }
}

private fun listStatus(list: GroceryList, online: Boolean, localDevice: DeviceId): String {
  val others = list.others(localDevice)
  if (others.isEmpty()) return "Just you"
  val names = others.joinToString(" & ") { it.name }
  return if (online) "Shared with $names · in step" else "Shared with $names · will hand over on wifi"
}

/**
 * Surfaced when the same thing was added on both phones while neither could see the other.
 *
 * Merging is automatic everywhere else; this is the case where a person has to say what they meant,
 * so it is shown rather than resolved quietly.
 */
@Composable
private fun ConflictCard(
  conflict: Conflict,
  yourSide: String,
  theirSide: String,
  onMerge: () -> Unit,
  onKeepBoth: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier
      .fillMaxWidth()
      .clip(Shapes.Row)
      .background(Accent100)
      .border(1.dp, Accent300, Shapes.Row)
      .padding(horizontal = 17.dp, vertical = 15.dp)
  ) {
    Row(
      Modifier.padding(bottom = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(Modifier.size(24.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
        StrokeIcon(IconPaths.MERGE_LINES, contentDescription = null, size = 14.dp, strokeWidth = 2.9f, tint = Bg)
      }
      Text("You both added ${conflict.itemName}", style = PorygonType.InlineHeading, color = TextInk)
    }
    Text(
      "$yourSide, $theirSide — neither of you could see the other's copy at the time.",
      style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
      color = Accent800,
      modifier = Modifier.padding(bottom = 11.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PrimaryButton(
        "Merge",
        onMerge,
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      )
      SecondaryButton(
        "Keep both",
        onKeepBoth,
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      )
    }
  }
}

/**
 * What to do with everything already in the trolley.
 *
 * Asks before it acts, in the same place rather than in a dialogue: several items at once is worth
 * a moment's pause, and the count is the whole of what needs saying.
 */
@Composable
private fun ClearTicked(
  count: Int,
  confirming: Boolean,
  onAsk: () -> Unit,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Text(
      if (confirming) "Take $count off the list?" else "$count in the trolley",
      style = PorygonType.Meta,
      color = Neutral700,
      modifier = Modifier.weight(1f),
    )
    if (confirming) {
      PrimaryButton(
        "Clear them",
        onConfirm,
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
      )
      SecondaryButton(
        "Keep",
        onCancel,
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
      )
    } else {
      SecondaryButton(
        "Clear ticked",
        onAsk,
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
  item: GroceryItem,
  subLabel: String,
  partnerInitial: String,
  onToggle: () -> Unit,
  onEdit: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth()
      .clip(Shapes.Row)
      .background(Surface)
      // The ring marks an item the other person has open right now.
      .then(if (item.editing) Modifier.border(2.dp, Accent2300, Shapes.Row) else Modifier)
      .alpha(if (item.checked) 0.55f else 1f)
      .padding(start = 5.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // A 44dp target around a 26dp circle. The row's reduced start padding is the design's
    // `margin-left:-9px`, which keeps the circle itself aligned to the 14dp gutter.
    Box(
      Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onToggle),
      contentAlignment = Alignment.Center,
    ) {
      CheckCircle(item.checked)
    }

    Column(
      Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onEdit),
    ) {
      Text(
        item.label,
        style = PorygonType.ItemName,
        color = TextInk,
        textDecoration = if (item.checked) StrikeThrough else null,
      )
      Text(subLabel, style = PorygonType.Fine, color = Neutral700, modifier = Modifier.padding(top = 1.dp))
    }

    if (item.editing) {
      Row(
        Modifier.clip(Shapes.Pill).background(Accent2200).padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Avatar(partnerInitial, Accent2, Accent2100, size = 22.dp, fontSize = PorygonType.Tiny.fontSize)
        Text("editing", style = PorygonType.Tiny.copy(fontSize = PorygonType.TabLabel.fontSize * 1.1), color = Accent2800)
      }
    }

    if (item.pending) {
      Text("waiting", style = PorygonType.Tiny, color = Accent700)
    }

    Box(
      Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onEdit),
      contentAlignment = Alignment.Center,
    ) {
      StrokeIcon(IconPaths.PENCIL, contentDescription = "Edit item", size = 17.dp, tint = Neutral600)
    }
  }
}
