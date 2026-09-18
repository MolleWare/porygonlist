package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent100
import io.github.molleware.porygonlist.theme.Accent2
import io.github.molleware.porygonlist.theme.Accent2100
import io.github.molleware.porygonlist.theme.Accent2800
import io.github.molleware.porygonlist.theme.Accent900
import io.github.molleware.porygonlist.theme.Bg
import io.github.molleware.porygonlist.theme.Elevation
import io.github.molleware.porygonlist.theme.Neutral100
import io.github.molleware.porygonlist.theme.Neutral500
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShadowInk
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.Dot
import io.github.molleware.porygonlist.ui.components.IconPaths
import io.github.molleware.porygonlist.ui.components.SectionLabel
import io.github.molleware.porygonlist.ui.components.StrokeIcon
import io.github.molleware.porygonlist.ui.itemCountLabel
import io.github.molleware.porygonlist.ui.listCardMeta

/** The home screen: who you share with, whether you are in step, and every list you keep. */
@Composable
fun ListsScreen(
  state: AppState,
  networkLabel: String,
  onOpenList: (Long) -> Unit,
  onToggleOnline: () -> Unit,
  onGoShare: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 88.dp)
  ) {
    Row(
      Modifier.fillMaxWidth().padding(bottom = 16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Bottom,
    ) {
      Text("porygonlist", style = PorygonType.Wordmark, color = TextInk)
      Text(
        state.lists.flatMap { it.people }.distinctBy { it.device }.joinToString(" & ") { it.name },
        style = PorygonType.Meta.copy(fontSize = PorygonType.TabLabel.fontSize * 1.2),
        color = Neutral700,
      )
    }

    SyncPill(state, networkLabel, onToggleOnline)

    SectionLabel("Lists", Modifier.padding(top = 24.dp, bottom = 10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      state.lists.forEach { list -> ListCard(list, state.online, state.localDevice) { onOpenList(list.id) } }
    }

    ShareCallToAction(onGoShare, Modifier.padding(top = 22.dp))
  }
}

/**
 * The sync banner. Tapping it flips between in-step and off-the-network — in the design this is the
 * control that lets you see both states; with real sync it would be a status readout.
 */
@Composable
private fun SyncPill(state: AppState, networkLabel: String, onToggle: () -> Unit) {
  val partner = state.lists.flatMap { it.people }.firstOrNull { it.device != state.localDevice }?.name ?: "them"
  val waiting = state.lists.sumOf { list -> list.items.count { it.pending } }

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
      if (state.online) "In step with $partner" else "Off the network",
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
private fun ListCard(list: GroceryList, online: Boolean, localDevice: DeviceId, onClick: () -> Unit) {
  Column(
    Modifier.fillMaxWidth()
      .shadow(Elevation.Sm, Shapes.Card, ambientColor = ShadowInk, spotColor = ShadowInk)
      .clip(Shapes.Card)
      .background(Surface)
      .clickable(onClick = onClick)
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
            contentColor = if (isYou) Accent900 else Neutral100,
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
      StrokeIcon(IconPaths.WIFI, contentDescription = null, size = 18.dp, tint = Color.White)
    }
    Column {
      Text(
        "Share a list with someone",
        style = PorygonType.InlineHeading,
        color = TextInk,
        modifier = Modifier.padding(bottom = 3.dp),
      )
      Text(
        "Lists hop straight between phones on the same wifi. No account, nothing on a server.",
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
