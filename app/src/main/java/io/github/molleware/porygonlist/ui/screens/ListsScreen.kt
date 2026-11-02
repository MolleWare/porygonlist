package io.github.molleware.porygonlist.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.lifecycleScope
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.net.BackgroundSync
import io.github.molleware.porygonlist.data.GroceryList
import io.github.molleware.porygonlist.data.ListAccent
import io.github.molleware.porygonlist.data.TrustedPeer
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
import io.github.molleware.porygonlist.theme.Neutral600
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShadowInk
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.Dot
import io.github.molleware.porygonlist.ui.components.GripMark
import io.github.molleware.porygonlist.ui.components.ReorderableColumn
import io.github.molleware.porygonlist.ui.components.IconActionButton
import io.github.molleware.porygonlist.ui.components.IconPaths
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.tabBarClearance
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.SectionLabel
import io.github.molleware.porygonlist.ui.components.StrokeIcon
import io.github.molleware.porygonlist.ui.clockLine
import io.github.molleware.porygonlist.ui.itemCountLabel
import io.github.molleware.porygonlist.data.sync.DeliveryLog
import io.github.molleware.porygonlist.ui.listCardMeta
import io.github.molleware.porygonlist.ui.mergeConsequence
import io.github.molleware.porygonlist.ui.mergeQuestion
import io.github.molleware.porygonlist.ui.millisToNextMinute
import io.github.molleware.porygonlist.ui.syncDetail
import io.github.molleware.porygonlist.ui.syncHeadline
import io.github.molleware.porygonlist.ui.others
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The home screen: who you share with, whether you are in step, and every list you keep. */
@Composable
fun ListsScreen(
  state: AppState,
  networkLabel: String,
  onOpenList: (ListId) -> Unit,
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
  /** The list whose card is asking which list to merge it into, and the one picked, if any. */
  mergingList: ListId?,
  mergeTarget: ListId?,
  onStartMerge: (ListId) -> Unit,
  onChooseMergeTarget: (ListId) -> Unit,
  onCancelMerge: () -> Unit,
  /** Merges the first list into the second. The second then opens. */
  onMerge: (ListId, ListId) -> Unit,
  /** Arranges this phone's lists. Never sent anywhere. */
  onMoveList: (ListId, Int) -> Unit,
  onSetPinned: (ListId, Boolean) -> Unit,
  /** A VPN is holding sync back. The banner says so, and opens [onExplainVpn] when tapped. */
  vpnOn: Boolean = false,
  onExplainVpn: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val scrollState = rememberScrollState()

  Column(
    modifier.verticalScroll(scrollState).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = tabBarClearance())
  ) {
    TodayLine(Modifier.padding(bottom = 2.dp))
    Row(
      Modifier.fillMaxWidth().padding(bottom = 16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("porygonlist", style = PorygonType.Wordmark, color = TextInk)
      // Your own initial or avatar, and the way in to everything about this phone rather than about
      // a list. Already labelled, so the avatar itself goes unsaid.
      Avatar(
        initial = state.displayName.take(1).uppercase(),
        background = Accent,
        contentColor = Accent100,
        size = 38.dp,
        fontSize = PorygonType.BodyLarge.fontSize,
        avatar = state.avatar.value,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenSettings).semantics { contentDescription = "You and this phone" },
      )
    }

    SyncPill(state, networkLabel, vpnOn, onExplainVpn)

    // Once there is somebody to sync with, and only where Android asks at all.
    if (state.peers.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      AllowAlarmsCard(Modifier.padding(top = 12.dp))
    }

    SectionLabel("Lists", Modifier.padding(top = 24.dp, bottom = 10.dp))

    // Pinned and unpinned lists are arranged as two groups. A list cannot be dragged from one to the
    // other — it would only snap back — because the pin, in its card, is what moves it between them.
    val (pinned, rest) = state.orderedVisibleLists.partition { it.pinned }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      listOf(pinned to 0, rest to pinned.size).forEach { (group, offset) ->
        if (group.isEmpty()) return@forEach
        // Indexes count from the top of the whole screen, so the unpinned group starts after the pins.
        ReorderableColumn(
          group,
          keyOf = { it.id.value },
          onMove = { list, to -> onMoveList(list.id, offset + to) },
          spacing = 12.dp,
          scrollState = scrollState,
        ) { list, handle, dragging ->
          if (renamingList == list.id) {
            val mergeTargets = state.mergeTargetsFor(list.id)
            val into = mergeTargets.firstOrNull { mergingList == list.id && it.id == mergeTarget }
            ListEditCard(
              name = renameDraft,
              onNameChange = onRenameDraftChange,
              onSave = onSaveRename,
              onCancel = onCancelRename,
              confirmingDelete = confirmingDelete == list.id,
              sharedWith = list.others(state.localDevice).size,
              onShare = { onShareList(list.id) },
              pinned = list.pinned,
              onTogglePin = { onSetPinned(list.id, !list.pinned) },
              onAskDelete = { onAskDelete(list.id) },
              onCancelDelete = onCancelDelete,
              onDelete = { onDelete(list.id) },
              mergeTargets = mergeTargets,
              choosingMergeTarget = mergingList == list.id,
              confirmingMerge = into?.let { mergeQuestion(list, it) to mergeConsequence(list, it, state.localDevice) },
              onStartMerge = { onStartMerge(list.id) },
              onChooseMergeTarget = onChooseMergeTarget,
              onCancelMerge = onCancelMerge,
              onMerge = { into?.let { onMerge(list.id, it.id) } },
            )
          } else {
            ListCard(
              list = list,
              localDevice = state.localDevice,
              delivered = state.deliveredTo,
              paired = state.peers,
              needsAnswer = state.openConflictsOn(list.id).isNotEmpty(),
              onClick = { onOpenList(list.id) },
              onLongClick = { onStartRename(list) },
              handle = handle,
              lifted = dragging,
            )
          }
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
      "Hold a list on the left to rename, pin, merge or delete it, on the right to move it. The order is yours " +
        "alone — it doesn't change anyone else's phone.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(top = 10.dp),
    )

    ShareCallToAction(onGoShare, Modifier.padding(top = 22.dp))
  }
}

/**
 * A list card turned into its own rename-and-delete form, so the answer is where the question was.
 *
 * Delete is always offered, the last list included (see PorygonViewModel.deleteList). The buttons
 * wrap: four in a row overran the card at a large font size, and the one pushed off was Delete.
 *
 * Merge is offered only when there is another list to merge into. Picking one asks once, saying
 * what will happen, and pressing Merge does it — there is no undo to fall back on, so the question
 * carries the weight.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ListEditCard(
  name: String,
  onNameChange: (String) -> Unit,
  onSave: () -> Unit,
  onCancel: () -> Unit,
  confirmingDelete: Boolean,
  sharedWith: Int,
  onShare: () -> Unit,
  /** Pinned lists stay at the top. The pin lives here now that there is no edit-order mode. */
  pinned: Boolean,
  onTogglePin: () -> Unit,
  onAskDelete: () -> Unit,
  onCancelDelete: () -> Unit,
  onDelete: () -> Unit,
  /** The lists this one could be merged into. Empty, and Merge is not offered. */
  mergeTargets: List<GroceryList>,
  /** The card is asking which list to merge into. */
  choosingMergeTarget: Boolean,
  /** The question and its consequence, once a list to merge into has been picked. */
  confirmingMerge: Pair<String, String>?,
  onStartMerge: () -> Unit,
  onChooseMergeTarget: (ListId) -> Unit,
  onCancelMerge: () -> Unit,
  onMerge: () -> Unit,
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      PrimaryButton("Save", onSave, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
      SecondaryButton("Cancel", onCancel, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
      if (!confirmingDelete && !choosingMergeTarget && confirmingMerge == null) {
        SecondaryButton(
          if (sharedWith > 0) "Sharing" else "Share",
          onShare,
          style = PorygonType.Meta,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        )
        SecondaryButton(
          if (pinned) "Unpin" else "Pin to top",
          onTogglePin,
          style = PorygonType.Meta,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        )
        if (mergeTargets.isNotEmpty()) {
          SecondaryButton(
            "Merge into…",
            onStartMerge,
            style = PorygonType.Meta,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
          )
        }
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

    if (choosingMergeTarget && confirmingMerge == null) {
      Text("Merge into which list?", style = PorygonType.Meta, color = TextInk)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        mergeTargets.forEach { target ->
          SecondaryButton(
            target.name,
            { onChooseMergeTarget(target.id) },
            style = PorygonType.Meta,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
          )
        }
      }
    }

    if (confirmingMerge != null) {
      Text(confirmingMerge.first, style = PorygonType.Meta, color = TextInk)
      Text(
        confirmingMerge.second,
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Neutral700,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton("Merge", onMerge, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
        SecondaryButton("Keep both", onCancelMerge, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp))
      }
    }
  }
}

/**
 * Today's date and the time, at the top of the Lists screen.
 *
 * Its own composable so that each new minute redraws this line and nothing else: the time is read
 * here and only here, never by the screen around it. It wakes on the minute, and only while the app
 * is in sight; back in sight, it reads the clock at once rather than at the next minute.
 */
@Composable
private fun TodayLine(modifier: Modifier = Modifier) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LifecycleStartEffect(Unit) {
    val ticking =
      lifecycleScope.launch {
        while (true) {
          now = System.currentTimeMillis()
          delay(millisToNextMinute(now))
        }
      }
    onStopOrDispose { ticking.cancel() }
  }
  Text(clockLine(now), style = PorygonType.Meta, color = Neutral700, modifier = modifier)
}

/**
 * The sync banner: a status readout, worked out from delivery receipts.
 *
 * The detail drops under the headline when the two do not fit on one line — several names, a large
 * font — instead of being squeezed into a sliver beside it and broken mid-word. The corners stop at
 * the Panel radius for the same reason: on one line that is still a full pill, but a pill radius on
 * a banner two lines tall makes an oval that clips its own text.
 *
 * With a VPN in the way it says so, and becomes the way to the fix: a VPN that leaves this app out
 * of its tunnel lets it sync again, which is nothing the app can do for you, but easy to miss.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SyncPill(state: AppState, networkLabel: String, vpnOn: Boolean, onExplainVpn: () -> Unit) {
  val headline = syncHeadline(state, vpnOn)
  val explainsVpn = headline == "VPN is on"
  FlowRow(
    Modifier.fillMaxWidth()
      .clip(Shapes.Panel)
      .background(Accent2100)
      .then(if (explainsVpn) Modifier.clickable(onClickLabel = "See how to sync with it on", onClick = onExplainVpn) else Modifier)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(9.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      Dot(Accent2)
      Text(headline, style = PorygonType.MetaBold, color = TextInk)
    }
    Text(
      if (explainsVpn) "Nothing syncs while it is on · tap to keep both" else syncDetail(state, networkLabel),
      style = PorygonType.Meta,
      color = TextInk.copy(alpha = 0.72f),
    )
  }
}

/**
 * Asks for alarms, on the home screen, while they are not allowed.
 *
 * Settings already offered them, and nobody went looking: without them a closed phone syncs on
 * Android's schedule, and on the Pixel that left a ready window unstarted for 35 minutes (22:30,
 * 2026-10-24). A closed app is meant to catch up within a quarter of an hour or so (Hugo,
 * 2026-10-25), which only alarms can promise. "Not now" puts it away until the app is next started
 * — a reminder, not a wall. Read from Android on every return, since the switch is flipped there.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun AllowAlarmsCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var allowed by remember { mutableStateOf(BackgroundSync.canAlign(context)) }
  var notNow by remember { mutableStateOf(false) }
  LifecycleResumeEffect(Unit) {
    allowed = BackgroundSync.canAlign(context)
    onPauseOrDispose {}
  }
  if (allowed || notNow) return

  Column(modifier.fillMaxWidth().clip(Shapes.Panel).background(Surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
    Text("Allow alarms to sync while closed", style = PorygonType.RowName, color = TextInk)
    Text(
      "Closed, this phone waits for Android to wake it, which can be half an hour late or more. " +
        "With alarms it wakes at :00, :15, :30 and :45, when your paired phones do.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(top = 4.dp),
    )
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      PrimaryButton(
        "Allow alarms",
        { askForAlarms(context) },
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
      )
      SecondaryButton(
        "Not now",
        { notNow = true },
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
      )
    }
  }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ListCard(
  list: GroceryList,
  localDevice: DeviceId,
  delivered: DeliveryLog,
  paired: List<TrustedPeer>,
  needsAnswer: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  /** Held to move the card; goes on its right side, where the avatars and the grip are. */
  handle: Modifier,
  lifted: Boolean,
) {
  // Two halves with one tap between them: either half opens the list, the left one held opens its
  // card, the right one held picks it up to move.
  Row(
    Modifier.fillMaxWidth()
      .height(IntrinsicSize.Min)
      .shadow(if (lifted) Elevation.Sm * 2 else Elevation.Sm, Shapes.Card, ambientColor = ShadowInk, spotColor = ShadowInk)
      .clip(Shapes.Card)
      .background(Surface),
  ) {
    Column(
      Modifier.weight(1f)
        .fillMaxHeight()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(start = 18.dp, top = 17.dp, bottom = 17.dp, end = 8.dp),
      verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(list.accent.color(), size = 11.dp)
        Text(
          list.name,
          style = PorygonType.ListName,
          color = TextInk,
          modifier = Modifier.padding(start = 11.dp).weight(1f, fill = false),
        )
        if (list.pinned) {
          StrokeIcon(IconPaths.PIN, contentDescription = "Pinned", size = 14.dp, tint = Neutral600, modifier = Modifier.padding(start = 6.dp))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(itemCountLabel(list), style = PorygonType.Meta, color = Neutral700)
        Text("·", style = PorygonType.Meta, color = Neutral700.copy(alpha = 0.4f))
        Text(listCardMeta(list, localDevice, delivered, needsAnswer, paired), style = PorygonType.Meta, color = Neutral700)
      }
    }
    // At least a thumb's width, so a list shared with nobody — one avatar — is still easy to hold.
    Row(
      Modifier.fillMaxHeight()
        .widthIn(min = 104.dp)
        .clickable(onClick = onClick)
        .then(handle)
        .padding(end = 10.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Negative spacing gives the same tucked-under stack as `margin-left:-7px` in the design.
      Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
        list.people.filter { it.present }.forEach { person ->
          val isYou = person.device == localDevice
          Avatar(
            initial = person.initial,
            background = if (isYou) Accent else Accent2,
            contentColor = if (isYou) Accent100 else Accent2100,
            ringColor = Bg,
            avatar = person.avatar.value,
            // The stack says who is on the list, which the line beside it does not always.
            name = person.name.ifBlank { null },
          )
        }
      }
      GripMark(Modifier.padding(start = 4.dp))
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
