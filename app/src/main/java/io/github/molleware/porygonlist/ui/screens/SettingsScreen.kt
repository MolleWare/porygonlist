package io.github.molleware.porygonlist.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.Avatars
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.initialOf
import io.github.molleware.porygonlist.data.net.BackgroundSync
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent100
import io.github.molleware.porygonlist.theme.Accent2
import io.github.molleware.porygonlist.theme.Accent2100
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Accent800
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.systemGlyphs
import io.github.molleware.porygonlist.ui.components.BackLink
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.SectionLabel

/**
 * Everything about this phone rather than about a list: who it says you are, what it is known as,
 * which phones it trusts, and the one way to stop being any of that.
 */
@Composable
fun SettingsScreen(
  state: AppState,
  deviceId: DeviceId,
  nameDraft: String,
  onNameDraftChange: (String) -> Unit,
  onSaveName: () -> Unit,
  onChooseAvatar: (String) -> Unit,
  onPair: () -> Unit,
  onUnpair: (DeviceId) -> Unit,
  confirmingDelete: Boolean,
  onAskDelete: () -> Unit,
  onCancelDelete: () -> Unit,
  onConfirmDelete: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 88.dp)
  ) {
    BackLink("All lists", onBack, tint = Accent700)
    Text("You", style = PorygonType.ScreenTitle, color = TextInk, modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Avatar(
        initial = state.displayName.take(1).uppercase(),
        background = Accent,
        contentColor = Accent100,
        size = 46.dp,
        fontSize = PorygonType.CardHeading.fontSize,
        avatar = state.avatar.value,
      )
      Column(Modifier.weight(1f)) {
        Text(state.displayName, style = PorygonType.CardHeading, color = TextInk)
        Text(
          "On ${state.visibleLists.size} ${if (state.visibleLists.size == 1) "list" else "lists"}",
          style = PorygonType.Fine,
          color = Neutral700,
        )
      }
    }

    SectionLabel("Your name", Modifier.padding(top = 26.dp, bottom = 10.dp))
    Text(
      "Shows on your lists too.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 10.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
      PorygonTextField(
        value = nameDraft,
        onValueChange = onNameDraftChange,
        placeholder = state.displayName,
        modifier = Modifier.weight(1f),
        imeAction = ImeAction.Done,
        onSubmit = onSaveName,
      )
      PrimaryButton("Save", onSaveName, modifier = Modifier.heightIn(min = 46.dp), style = PorygonType.BodyLarge)
    }

    SectionLabel("Your avatar", Modifier.padding(top = 26.dp, bottom = 10.dp))
    Text(
      "Shows instead of your initial, on your lists and on theirs.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 10.dp),
    )
    AvatarChoices(
      initial = state.displayName.take(1).uppercase(),
      chosen = state.avatar.value,
      onChoose = onChooseAvatar,
    )

    SectionLabel("This phone", Modifier.padding(top = 26.dp, bottom = 10.dp))
    Column(Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
      Text(deviceId.value, style = PorygonType.RowName, color = TextInk)
      Text(
        "Whoever pairs with you sees this too.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Neutral700,
        modifier = Modifier.padding(top = 4.dp),
      )
    }

    SectionLabel("Paired phones", Modifier.padding(top = 26.dp, bottom = 10.dp))
    if (state.peers.isEmpty()) {
      Text(
        "None yet. Nothing syncs until you pair.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Neutral700,
        modifier = Modifier.padding(bottom = 12.dp),
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        // Named as they call themselves now, which a rename since pairing has changed.
        state.peers.forEach { PeerRow(it, state.nameOf(it), state.avatarOf(it.deviceId)) { onUnpair(it.deviceId) } }
      }
    }
    SecondaryButton("Pair a phone", onPair, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)

    // Only asked for once there is somebody to sync with, and only where Android asks at all.
    if (state.peers.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      SectionLabel("With the app closed", Modifier.padding(top = 26.dp, bottom = 10.dp))
      QuarterHourCard()
    }

    SectionLabel("Starting over", Modifier.padding(top = 30.dp, bottom = 10.dp))
    DeleteIdentityCard(
      peerCount = state.peers.size,
      confirming = confirmingDelete,
      onAsk = onAskDelete,
      onCancel = onCancelDelete,
      onConfirm = onConfirmDelete,
    )

    SectionLabel("About", Modifier.padding(top = 30.dp, bottom = 10.dp))
    AboutCard()
  }
}

/**
 * Whether closed phones wake together on the quarter hour, and the way to let them.
 *
 * Read from Android on every return to the screen rather than held in the state: the switch lives
 * in system settings, and the owner flips it there, not here. The schedule itself moves across on
 * its own when they do — see SyncAlarm.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun QuarterHourCard() {
  val context = LocalContext.current
  var aligned by remember { mutableStateOf(BackgroundSync.canAlign(context)) }
  LifecycleResumeEffect(Unit) {
    aligned = BackgroundSync.canAlign(context)
    onPauseOrDispose {}
  }

  Column(Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
    Text(if (aligned) "Syncing on the quarter hour" else "Syncing around the quarter hour", style = PorygonType.RowName, color = TextInk)
    Text(
      if (aligned) {
        "Wakes at :00, :15, :30 and :45 for half a minute, when your paired phones do too. " +
          "A quiet notification shows while it runs."
      } else {
        "Aims for :00, :15, :30 and :45, but a phone left idle may wake late and miss the others. " +
          "Allow alarms and it wakes on time."
      },
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(top = 4.dp),
    )
    if (!aligned) {
      SecondaryButton(
        "Allow alarms",
        { askForAlarms(context) },
        modifier = Modifier.padding(top = 12.dp),
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
      )
    }
  }
}

/** The system page where the owner allows this app's alarms. Android 12 and later only. */
@RequiresApi(Build.VERSION_CODES.S)
internal fun askForAlarms(context: Context) {
  runCatching {
    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
  }
}

private const val SOURCE = "https://github.com/MolleWare/porygonlist"

/**
 * Which version this is, the licence, where the code lives and where to report a problem.
 *
 * The links open in the browser and only when tapped: the app itself still never reaches the
 * internet. The version is read from the installed package rather than a generated constant, which
 * the build deliberately does not produce (`buildConfig = false`).
 */
@Composable
private fun AboutCard() {
  val context = LocalContext.current
  val uris = LocalUriHandler.current
  val version =
    remember {
      runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
  val open = { url: String -> runCatching { uris.openUri(url) } }

  Column(Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
    Text("PorygonList $version", style = PorygonType.RowName, color = TextInk)
    Text(
      "Free software under the GPL, version 3 or later. No account, no server, nothing collected.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      SecondaryButton("Source code", { open(SOURCE) }, style = PorygonType.Meta, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp))
      SecondaryButton(
        "Report a problem",
        { open("$SOURCE/issues") },
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
      )
    }
    Text(
      "Built with Kotlin, Jetpack Compose and the AndroidX libraries, under the Apache License 2.0.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(top = 12.dp),
    )
  }
}

/**
 * Your initial, then every avatar there is to choose, each as it would look on your lists.
 *
 * One tap wears it; there is nothing to confirm, as choosing again is just as quick. An avatar this
 * build does not know — chosen on a newer one, perhaps — marks none of them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarChoices(initial: String, chosen: String, onChoose: (String) -> Unit) {
  FlowRow(
    Modifier.selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    AvatarOption(initial, Avatars.NONE, "Your initial", selected = chosen == Avatars.NONE) { onChoose(Avatars.NONE) }
    // Only what this phone can draw: an emoji its fonts lack would be an empty circle to choose.
    Avatars.choosable(systemGlyphs::canDraw).forEach { choice ->
      AvatarOption(initial, choice.id, "${choice.name} avatar", selected = chosen == choice.id) { onChoose(choice.id) }
    }
  }
}

/** One circle to choose, ringed when it is the one worn, and a whole thumb's width to hit. */
@Composable
private fun AvatarOption(initial: String, avatar: String, label: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.size(48.dp)
      .clip(CircleShape)
      .border(2.dp, if (selected) Accent800 else Color.Transparent, CircleShape)
      // Announced as a choice among several, with its name and whether it is the one worn.
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Avatar(
      initial = initial,
      background = Accent,
      contentColor = Accent100,
      size = 40.dp,
      fontSize = PorygonType.BodyLarge.fontSize,
      avatar = avatar,
      // The label above says it; the letter is not read out as well.
      modifier = Modifier.clearAndSetSemantics {},
    )
  }
}

@Composable
private fun PeerRow(peer: TrustedPeer, name: String, avatar: String, onUnpair: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Avatar(
      initial = initialOf(name),
      background = Accent2,
      contentColor = Accent2100,
      size = 34.dp,
      fontSize = PorygonType.BodyLarge.fontSize,
      avatar = avatar,
    )
    Column(Modifier.weight(1f)) {
      Text(name, style = PorygonType.RowName, color = TextInk)
      Text(peer.deviceId.value, style = PorygonType.Fine, color = Neutral700)
    }
    SecondaryButton(
      "Unpair",
      onUnpair,
      style = PorygonType.Meta,
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    )
  }
}

/**
 * The only way to change identity, and it says so.
 *
 * Deliberately one tap behind a plain statement of the cost rather than a typed confirmation: the
 * consequence belongs in the sentence next to the button, not in a ritual that trains people to
 * click through. What makes it safe is that it is never the obvious thing to press.
 */
@Composable
private fun DeleteIdentityCard(
  peerCount: Int,
  confirming: Boolean,
  onAsk: () -> Unit,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth()
      .clip(Shapes.Card)
      .background(Accent100)
      .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp)
  ) {
    Text("Delete this phone's identity", style = PorygonType.CardHeading, color = TextInk, modifier = Modifier.padding(bottom = 4.dp))
    Text(
      // Cut, but not to a nudge. This one is irreversible and takes the lists with it, so the two
      // things a person cannot undo afterwards stay on screen.
      "Your lists go with it." +
        if (peerCount > 0) {
          val phones = if (peerCount == 1) "The phone" else "All $peerCount phones"
          " $phones you paired with will need pairing again."
        } else "",
      style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
      color = Accent800,
      modifier = Modifier.padding(bottom = 14.dp),
    )

    if (confirming) {
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton(
          "Delete and start over",
          onConfirm,
          modifier = Modifier.heightIn(min = 44.dp),
          style = PorygonType.BodyLarge,
        )
        SecondaryButton("Keep it", onCancel, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
      }
    } else {
      SecondaryButton("Delete identity", onAsk, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
    }
  }
}
