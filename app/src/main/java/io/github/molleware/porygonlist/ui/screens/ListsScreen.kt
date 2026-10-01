package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.sync.ListId
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent100
import io.github.molleware.porygonlist.theme.Accent2
import io.github.molleware.porygonlist.theme.Accent2100
import io.github.molleware.porygonlist.theme.Accent2800
import io.github.molleware.porygonlist.theme.Bg
import io.github.molleware.porygonlist.theme.Elevation
import io.github.molleware.porygonlist.theme.Neutral500
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShadowInk
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.Dot
import io.github.molleware.porygonlist.ui.components.IconActionButton
import io.github.molleware.porygonlist.ui.components.IconPaths
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.tabBarClearance
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.SectionLabel
import io.github.molleware.porygonlist.ui.components.StrokeIcon
import io.github.molleware.porygonlist.ui.itemCountLabel
import io.github.molleware.porygonlist.ui.listCardMeta

/** The home screen: who you share with, whether you are in step, and every list you keep. */
@Composable
fun ListsScreen(
  state: AppState,
  networkLabel: String,
  onOpenList: (ListId) -> Unit,
  onToggleOnline: () -> Unit,
  onGoShare: () -> Unit,
  onOpenSettings: () -> Unit,
  draft: String,
  onDraftChange: (String) -> Unit,
  onCreateList: () -> Unit,
  /** Opens sharing for one list, without having to open the list first. */
  onShareList: (ListId) -> Unit,
  renamingList: ListId?,
  renameDraft: String,
  onStartRename: (GroceryList) -> Unit,
  onRenameDraftChange: (String) -> Unit,
  onSaveRename: () -> Unit,
  onCancelRename: () -> Unit,
  confirmingDelete: ListId?,
  onAskDelete: (ListId) -> Unit,
  onCancelDelete: () -> Unit,
  onDelete: (ListId) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = tabBarClearance())
  ) {
    Row(
      Modifier.fillMaxWidth().padding(bottom = 16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("porygonlist", style = PorygonType.Wordmark, color = TextInk)
      // Your own initial, and the way in to everything about this phone rather than about a list.
      Avatar(
        initial = state.displayName.take(1).uppercase(),
        background = Accent,
        contentColor = Accent100,
        size = 38.dp,
        fontSize = PorygonType.BodyLarge.fontSize,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenSettings).semantics { contentDescription = "You and this phone" },
      )
    }

    SyncPill(state, networkLabel, onToggleOnline)

    SectionLabel("Lists", Modifier.padding(top = 24.dp, bottom = 10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      state.visibleLists.forEach { list ->
        if (renamingList == list.id) {
          ListEditCard(
            name = renameDraft,
            onNameChange = onRenameDraftChange,
            onSave = onSaveRename,
            onCancel = onCancelRename,
            canDelete = state.visibleLists.size > 1,
            confirmingDelete = confirmingDelete == list.id,
            sharedWith = list.people.count { it.device != state.localDevice },
            onShare = { onShareList(list.id) },
            onAskDelete = { onAskDelete(list.id) },
            onCancelDelete = onCancelDelete,
            onDelete = { onDelete(list.id) },
          )
        } else {
          ListCard(
            list = list,
            online = state.online,
            localDevice = state.localDevice,
            onClick = { onOpenList(list.id) },
            onLongClick = { onStartRename(list) },
          )
        }
      }
    }

    Row(
      Modifier.fillMaxWidth().padding(top = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(9.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PorygonTextField(
        value = draft,
        onValueChange = onDraftChange,
        placeholder = "New list…",
        modifier = Modifier.weight(1f),
        imeAction = ImeAction.Done,
        onSubmit = onCreateList,
      )
      IconActionButton(IconPaths.PLUS, contentDescription = "Make the list", onClick = onCreateList)
    }
    Text(
      "Press and hold a list to rename or delete it.",
      style = PorygonType.Fine,
      color = Neutral700,
      modifier = Modifier.padding(top = 10.dp),
    )

    ShareCallToAction(onGoShare, Modifier.padding(top = 22.dp))
  }
}

/** A list card turned into its own rename-and-delete form, so the answer is where the question was. */
@Composable
private fun ListEditCard(
  name: String,
  onNameChange: (String) -> Unit,
  onSave: () -> Unit,
  onCancel: () -> Unit,
  canDelete: Boolean,
  confirmingDelete: Boolean,
  sharedWith: Int,
  onShare: () -> Unit,
  onAskDelete: () -> Unit,
  onCancelDelete: () -> Unit,
  onDelete: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth()
      .shadow(Elevation.Sm, Shapes.Card, ambientColor = ShadowInk, spotColor = ShadowInk)
      .clip(Shapes.Card)
      .background(Surface)
      .padding(horizontal = 18.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(11.dp),
  ) {
    PorygonTextField(
      value = name,
      onValueChange = onNameChange,
      placeholder = "List name",
      modifier = Modifier.fillMaxWidth(),
      imeAction = ImeAction.Done,
      onSubmit = onSave,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
      PrimaryButton("Save", onSave, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
      SecondaryButton("Cancel", onCancel, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
      if (!confirmingDelete) {
        SecondaryButton(
          if (sharedWith > 0) "Sharing" else "Share",
          onShare,
          style = PorygonType.Meta,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        )
      }
      if (canDelete && !confirmingDelete) {
        SecondaryButton(
          "Delete",
          onAskDelete,
          style = PorygonType.Meta,
          contentColor = Accent2800,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        )
      }
    }

    if (confirmingDelete) {
      Text("Delete this list from this phone?", style = PorygonType.Meta, color = TextInk)
      Text(
        if (sharedWith > 0) "From this phone only — they keep their copy."
        else "Nobody else has this one.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Neutral700,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton("Delete", onDelete, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
        SecondaryButton("Keep it", onCancelDelete, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
      }
    }
  }
}

/**
 * The sync banner. Tapping it flips between in-step and off-the-network — in the design this is the
 * control that lets you see both states; with real sync it would be a status readout.
 */
@Composable
private fun SyncPill(state: AppState, networkLabel: String, onToggle: () -> Unit) {
  // Null, not "them", when nobody else is on any list. "In step with them" beside a card reading
  // "just you" is the app claiming to have synced with somebody who does not exist.
  val partner =
    state.visibleLists.flatMap { it.people }.firstOrNull { it.present && it.device != state.localDevice }?.name
  val waiting = state.visibleLists.sumOf { list -> list.items.count { it.pending } }

  Row(
    Modifier.fillMaxWidth()
      .clip(Shapes.Pill)
      .background(if (state.online) Accent2100 else Accent100)
      .clickable(onClick = onToggle)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Dot(if (state.online) Accent2 else Accent)
    Text(
      when {
        !state.online -> "Off the network"
        partner != null -> "In step with $partner"
        else -> "Nobody to sync with yet"
      },
      style = PorygonType.MetaBold,
      color = TextInk,
    )
    Text(
      if (state.online) "$networkLabel · a moment ago"
      else "$waiting ${if (waiting == 1) "change" else "changes"} waiting to hand over",
      style = PorygonType.Meta,
      color = TextInk.copy(alpha = 0.72f),
    )
  }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ListCard(
  list: GroceryList,
  online: Boolean,
  localDevice: DeviceId,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth()
      .shadow(Elevation.Sm, Shapes.Card, ambientColor = ShadowInk, spotColor = ShadowInk)
      .clip(Shapes.Card)
      .background(Surface)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = 18.dp, vertical = 17.dp),
    verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Dot(list.accent.color(), size = 11.dp)
      Text(
        list.name,
        style = PorygonType.ListName,
        color = TextInk,
        modifier = Modifier.padding(start = 11.dp).weight(1f),
      )
      // Negative spacing gives the same tucked-under stack as `margin-left:-7px` in the design.
      Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
        list.people.forEach { person ->
          val isYou = person.device == localDevice
          Avatar(
            initial = person.initial,
            background = if (isYou) Accent else Accent2,
            contentColor = if (isYou) Accent100 else Accent2100,
            ringColor = Bg,
          )
        }
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
      Text(itemCountLabel(list), style = PorygonType.Meta, color = Neutral700)
      Text("·", style = PorygonType.Meta, color = Neutral700.copy(alpha = 0.4f))
      Text(listCardMeta(list, online, localDevice), style = PorygonType.Meta, color = Neutral700)
    }
  }
}

/** The invitation to share, explaining in plain words where the data actually goes. */
@Composable
private fun ShareCallToAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier
      .fillMaxWidth()
      .clip(Shapes.Card)
      .background(Accent2100)
      .clickable(onClick = onClick)
      .padding(horizontal = 18.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Accent2), contentAlignment = Alignment.Center) {
      StrokeIcon(IconPaths.WIFI, contentDescription = null, size = 18.dp, tint = Accent2100)
    }
    Column {
      Text(
        "Share a list with someone",
        style = PorygonType.InlineHeading,
        color = TextInk,
        modifier = Modifier.padding(bottom = 3.dp),
      )
      Text(
        "Straight between phones. No account, no server.",
        style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5, fontWeight = FontWeight.Normal),
        color = Accent2800,
      )
    }
  }
}

private fun ListAccent.color(): Color =
  when (this) {
    ListAccent.ACCENT -> Accent
    ListAccent.ACCENT_2 -> Accent2
    ListAccent.NEUTRAL -> Neutral500
  }
