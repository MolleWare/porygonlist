package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.AppState
import io.github.molleware.porygonlist.data.ApprovedNetwork
import io.github.molleware.porygonlist.data.TrustedPeer
import io.github.molleware.porygonlist.data.net.DiscoveryDecision
import io.github.molleware.porygonlist.data.net.HoldReason
import io.github.molleware.porygonlist.data.net.NetworkFingerprint
import io.github.molleware.porygonlist.data.net.NetworkSnapshot
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent100
import io.github.molleware.porygonlist.theme.Accent2
import io.github.molleware.porygonlist.theme.Accent2100
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent2800
import io.github.molleware.porygonlist.theme.Accent2800
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Accent800
import io.github.molleware.porygonlist.theme.Accent900
import io.github.molleware.porygonlist.theme.Neutral100
import io.github.molleware.porygonlist.theme.Neutral300
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.Avatar
import io.github.molleware.porygonlist.ui.components.BackLink
import io.github.molleware.porygonlist.ui.components.Dot
import io.github.molleware.porygonlist.ui.components.IconPaths
import io.github.molleware.porygonlist.ui.components.NetworkSwitch
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.SectionLabel
import io.github.molleware.porygonlist.ui.components.StrokeIcon
import io.github.molleware.porygonlist.ui.others
import io.github.molleware.porygonlist.ui.partnerName

/**
 * Where sharing is explained and controlled.
 *
 * The whole model is here in plain words: lists move between phones on networks you have named, and
 * off those networks nothing leaves the device.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShareScreen(
  state: AppState,
  network: NetworkSnapshot,
  discovery: DiscoveryDecision,
  onApproveCurrent: () -> Unit,
  onToggleNetwork: (NetworkFingerprint) -> Unit,
  onExport: () -> Unit,
  onImport: () -> Unit,
  namingNetwork: NetworkFingerprint?,
  networkNameDraft: String,
  onNetworkNameDraftChange: (String) -> Unit,
  onStartNamingNetwork: (ApprovedNetwork) -> Unit,
  onSaveNetworkName: () -> Unit,
  onCancelNamingNetwork: () -> Unit,
  pairablePeers: List<TrustedPeer>,
  onAddPerson: (DeviceId) -> Unit,
  onGoPair: () -> Unit,
  confirmingRemovalOf: DeviceId?,
  onAskRemovePerson: (DeviceId) -> Unit,
  onCancelRemovePerson: () -> Unit,
  onRemovePerson: (DeviceId) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val list = state.activeList
  val partner = list.partnerName(state.localDevice)
  val discovering = discovery is DiscoveryDecision.Discover
  val here = state.networks.firstOrNull { it.fingerprint == network.fingerprint }

  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 88.dp)
  ) {
    BackLink("All lists", onBack, tint = Accent700)
    // Names the list rather than saying "Sharing", because this screen acts on one list and which
    // one is not guessable from a heading that does not say.
    Text(
      "Sharing ${list.name}",
      style = PorygonType.ScreenTitle,
      color = TextInk,
      modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
    Text(
      if (list.others(state.localDevice).isEmpty())
        "Nobody else has this list yet. Add someone below, and it syncs on its own whenever you are both " +
          "on a network you have approved here."
      else "Lists sync on their own whenever you and $partner are both on a network you have approved here.",
      style = PorygonType.BodyLarge.copy(lineHeight = PorygonType.BodyLarge.fontSize * 1.55),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 20.dp),
    )

    CurrentNetworkPill(
      label = here?.label ?: network.fingerprint?.let { "Network ${it.short}" } ?: "This network",
      discovery = discovery,
      canApprove = network.fingerprint != null && !discovering,
      onApprove = onApproveCurrent,
    )

    SectionLabel("Approved networks", Modifier.padding(top = 22.dp, bottom = 10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
      state.networks.forEach { entry ->
        NetworkRow(
          network = entry,
          isCurrent = entry.fingerprint == network.fingerprint,
          naming = namingNetwork == entry.fingerprint,
          nameDraft = networkNameDraft,
          onNameDraftChange = onNetworkNameDraftChange,
          onStartNaming = { onStartNamingNetwork(entry) },
          onSaveName = onSaveNetworkName,
          onCancelNaming = onCancelNamingNetwork,
          onToggle = { onToggleNetwork(entry.fingerprint) },
        )
      }
    }
    Text(
      "Tap a network's name to call it something you will recognise. Off a listed network nothing leaves the " +
        "phone — your edits wait, then hand over the next time you meet on one of these.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.55),
      color = Neutral700,
      modifier = Modifier.padding(top = 14.dp, bottom = 26.dp),
    )

    SectionLabel("Away from wifi", Modifier.padding(bottom = 10.dp))
    Column(
      Modifier.fillMaxWidth()
        .clip(Shapes.Card)
        .background(Accent100)
        .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp)
    ) {
      Text(
        "Send ${list.name} as a text",
        style = PorygonType.CardHeading,
        color = TextInk,
        modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
        "For when you are in the shop and nowhere near a shared network. It is one message, and whoever gets " +
          "it can paste it straight back in.",
        style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
        color = Accent800,
        modifier = Modifier.padding(bottom = 14.dp),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton("Export list", onExport, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
        SecondaryButton("Paste a list", onImport, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
      }
    }

    SectionLabel("People", Modifier.padding(top = 26.dp, bottom = 10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
      list.people.forEach { person ->
        PersonRow(
          person = person,
          isYou = person.device == state.localDevice,
          discovering = discovering,
          networkLabel = here?.label ?: "this network",
          confirming = confirmingRemovalOf == person.device,
          onAskRemove = { onAskRemovePerson(person.device) },
          onCancelRemove = onCancelRemovePerson,
          onConfirmRemove = { onRemovePerson(person.device) },
        )
      }
    }

    // Pairing and sharing are two decisions. A paired phone is one this one will talk to; putting
    // that person on a list is a separate act, which is what makes a list you keep to yourself
    // possible at all.
    if (pairablePeers.isNotEmpty()) {
      SectionLabel("Add to ${list.name}", Modifier.padding(top = 26.dp, bottom = 10.dp))
      Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        pairablePeers.forEach { peer ->
          Row(
            Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Avatar(
              initial = peer.name.take(1).uppercase(),
              background = Accent2,
              contentColor = Neutral100,
              size = 34.dp,
              fontSize = PorygonType.BodyLarge.fontSize,
            )
            Column(Modifier.weight(1f)) {
              Text(peer.name, style = PorygonType.RowName, color = TextInk)
              Text("Paired, not on this list", style = PorygonType.Fine, color = Neutral700)
            }
            PrimaryButton(
              "Add",
              onClick = { onAddPerson(peer.deviceId) },
              style = PorygonType.Meta,
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }
      }
    }

    SectionLabel("Another phone", Modifier.padding(top = 26.dp, bottom = 10.dp))
    Text(
      if (state.peers.isEmpty())
        "No phone is paired with this one yet. Until one is, nothing syncs however many networks you approve."
      else "Pair another phone to share lists with somebody else.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 12.dp),
    )
    SecondaryButton("Pair a phone", onGoPair, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
  }
}

/**
 * The escape hatch for removals that cannot be handed over.
 *
 * States the cost rather than burying it: clearing these gives up the guarantee that a deleted item
 * stays deleted, because a phone that never received the removal will offer the item back. Worth it
 * when the phone in question is switched off in a drawer, and not otherwise.
 */
@Composable
private fun StrandedRemovals(
  count: Int,
  waitingOn: List<String>,
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
    Text(
      "$count ${if (count == 1) "removal" else "removals"} not handed over",
      style = PorygonType.CardHeading,
      color = TextInk,
      modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
      if (waitingOn.isEmpty()) "These are waiting for another phone to catch up."
      else "These are waiting for ${waitingOn.joinToString(" and ")} to catch up.",
      style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
      color = Accent800,
      modifier = Modifier.padding(bottom = 14.dp),
    )

    if (confirming) {
      Text(
        "Clear them anyway?",
        style = PorygonType.RowName.copy(fontSize = PorygonType.BodyLarge.fontSize),
        color = TextInk,
        modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
        "Anything that phone still has may come back the next time you meet on a network.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Accent800,
        modifier = Modifier.padding(bottom = 14.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton("Clear them", onConfirm, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
        SecondaryButton("Leave them", onCancel, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
      }
    } else {
      SecondaryButton("Clear them", onAsk, modifier = Modifier.heightIn(min = 44.dp), style = PorygonType.BodyLarge)
    }
  }
}

/**
 * What is happening on the network you are on right now, and the one-tap way to opt into it.
 *
 * The wording stays away from "safe": approving a network says only that the app may look for
 * peers here, not that anything about the place is trusted.
 */
@Composable
private fun CurrentNetworkPill(
  label: String,
  discovery: DiscoveryDecision,
  canApprove: Boolean,
  onApprove: () -> Unit,
) {
  val discovering = discovery is DiscoveryDecision.Discover
  Row(
    Modifier.fillMaxWidth()
      .clip(Shapes.Pill)
      .background(if (discovering) Accent2100 else Accent100)
      .padding(horizontal = 16.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(11.dp),
  ) {
    Dot(if (discovering) Accent2 else Accent)
    Text(
      buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
        append(" — ")
        append(
          when {
            discovering -> "looking for Hugo here"
            discovery is DiscoveryDecision.Hold ->
              when (discovery.reason) {
                HoldReason.OFFLINE -> "no network, nothing is leaving this phone"
                HoldReason.NOT_WIFI -> "not wifi, nothing is leaving this phone"
                HoldReason.UNIDENTIFIABLE -> "cannot be told apart from other networks"
                HoldReason.NOT_APPROVED -> "not approved, nothing is leaving this phone"
              }
            else -> ""
          }
        )
      },
      style = PorygonType.Meta,
      color = TextInk,
      modifier = Modifier.weight(1f),
    )
    if (canApprove) {
      Text(
        "Approve",
        style = PorygonType.Tiny.copy(fontSize = PorygonType.TabLabel.fontSize * 1.2),
        color = Accent900,
        modifier =
          Modifier.clip(Shapes.Pill).background(Accent).clickable(onClick = onApprove).padding(horizontal = 14.dp, vertical = 7.dp),
      )
    }
  }
}

@Composable
private fun NetworkRow(
  network: ApprovedNetwork,
  isCurrent: Boolean,
  naming: Boolean,
  nameDraft: String,
  onNameDraftChange: (String) -> Unit,
  onStartNaming: () -> Unit,
  onSaveName: () -> Unit,
  onCancelNaming: () -> Unit,
  onToggle: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // The network you are on right now is filled solid; other approved ones sit in a tint.
    val iconBg =
      when {
        network.approved && isCurrent -> Accent2
        network.approved -> Accent2300
        else -> Neutral300
      }
    Box(Modifier.size(34.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
      StrokeIcon(
        IconPaths.WIFI,
        contentDescription = null,
        size = 17.dp,
        tint = if (network.approved && isCurrent) Color.White else Accent2800,
      )
    }
    if (naming) {
      PorygonTextField(
        value = nameDraft,
        onValueChange = onNameDraftChange,
        placeholder = "Call it something",
        modifier = Modifier.weight(1f),
        minHeight = 40.dp,
        textStyle = PorygonType.RowName,
        imeAction = ImeAction.Done,
        onSubmit = onSaveName,
      )
      Text(
        "Save",
        style = PorygonType.Tiny.copy(fontSize = PorygonType.TabLabel.fontSize * 1.2),
        color = Accent900,
        modifier =
          Modifier.clip(Shapes.Pill).background(Accent).clickable(onClick = onSaveName).padding(horizontal = 12.dp, vertical = 7.dp),
      )
      Text(
        "Cancel",
        style = PorygonType.Tiny.copy(fontSize = PorygonType.TabLabel.fontSize * 1.2),
        color = Neutral700,
        modifier = Modifier.clip(Shapes.Pill).clickable(onClick = onCancelNaming).padding(horizontal = 8.dp, vertical = 7.dp),
      )
    } else {
      Column(Modifier.weight(1f).clip(Shapes.Row).clickable(onClick = onStartNaming)) {
        Text(network.label, style = PorygonType.RowName, color = TextInk)
        Text(network.detail, style = PorygonType.Fine, color = Neutral700, modifier = Modifier.padding(top = 1.dp))
      }
      NetworkSwitch(network.approved, onToggle, contentDescription = "Approve ${network.label}")
    }
  }
}

@Composable
private fun PersonRow(
  person: Person,
  isYou: Boolean,
  discovering: Boolean,
  networkLabel: String,
  confirming: Boolean,
  onAskRemove: () -> Unit,
  onCancelRemove: () -> Unit,
  onConfirmRemove: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Avatar(
        initial = person.initial,
        background = if (isYou) Accent else Accent2,
        contentColor = if (isYou) Accent900 else Neutral100,
        size = 34.dp,
        fontSize = PorygonType.BodyLarge.fontSize,
      )
      Column(Modifier.weight(1f)) {
        Text(if (isYou) "You" else person.name, style = PorygonType.RowName, color = TextInk)
        Text(
          when {
            isYou -> "This phone"
            discovering -> "Looked for on $networkLabel"
            else -> "Last handover this morning"
          },
          style = PorygonType.Fine,
          color = Neutral700,
        )
      }
      // No way to remove yourself: a list with nobody on it has nowhere to go.
      if (!isYou && !confirming) {
        Text(
          "Remove",
          style = PorygonType.Tiny.copy(fontSize = PorygonType.TabLabel.fontSize * 1.2),
          color = Accent2800,
          modifier =
            Modifier.clip(Shapes.Pill).clickable(onClick = onAskRemove).padding(horizontal = 12.dp, vertical = 7.dp),
        )
      }
    }

    if (confirming) {
      Text(
        "Stop sharing this list with ${person.name}?",
        style = PorygonType.Meta,
        color = TextInk,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
      )
      Text(
        "They keep the copy they already have. Nothing more passes between you on this list.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Neutral700,
        modifier = Modifier.padding(bottom = 12.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton(
          "Remove",
          onConfirmRemove,
          style = PorygonType.Meta,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        )
        SecondaryButton(
          "Keep sharing",
          onCancelRemove,
          style = PorygonType.Meta,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    }
  }
}
