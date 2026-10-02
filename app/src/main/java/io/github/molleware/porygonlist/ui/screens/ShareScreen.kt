package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import io.github.molleware.porygonlist.data.sync.Hlc
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent100
import io.github.molleware.porygonlist.theme.Accent2
import io.github.molleware.porygonlist.theme.Accent2100
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent2800
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Accent800
import io.github.molleware.porygonlist.theme.Neutral300
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.handoverLabel
import io.github.molleware.porygonlist.ui.networkLabel
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
  /** The name the wifi gives itself, when the optional location permission allows reading it. */
  wifiName: String?,
  /** False once the permission is granted, or when there is no wifi to name. */
  canAskForWifiName: Boolean,
  onAskForWifiName: () -> Unit,
  namingNetwork: NetworkFingerprint?,
  networkNameDraft: String,
  onNetworkNameDraftChange: (String) -> Unit,
  onStartNamingNetwork: (ApprovedNetwork) -> Unit,
  onSaveNetworkName: () -> Unit,
  onCancelNamingNetwork: () -> Unit,
  confirmingNetworkRemoval: NetworkFingerprint?,
  onAskForgetNetwork: (NetworkFingerprint) -> Unit,
  onCancelForgetNetwork: () -> Unit,
  onForgetNetwork: (NetworkFingerprint) -> Unit,
  pairablePeers: List<TrustedPeer>,
  onAddPerson: (DeviceId) -> Unit,
  onGoPair: () -> Unit,
  /** Opens the one screen that unpairs a phone. See the section at the foot of this one. */
  onOpenPairedPhones: () -> Unit,
  confirmingRemovalOf: DeviceId?,
  onAskRemovePerson: (DeviceId) -> Unit,
  onCancelRemovePerson: () -> Unit,
  onRemovePerson: (DeviceId) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val list = state.activeList
  // Null when nobody else is on this list. "looking for them here" with no "them" is the screen
  // inventing a person, which is the same thing the Lists banner used to do.
  val partner = list.others(state.localDevice).firstOrNull()?.name
  val discovering = discovery is DiscoveryDecision.Discover
  val here = state.networks.firstOrNull { it.fingerprint == network.fingerprint }

  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 88.dp)
  ) {
    BackLink("All lists", onBack, tint = Accent700)
    // Names the list rather than saying "Sharing", because this screen acts on one list and which
    // one is not guessable from a heading that does not say. With no list at all there is no name
    // to give, and the bare word is honest rather than trailing an empty space.
    Text(
      if (list.name.isBlank()) "Sharing" else "Sharing ${list.name}",
      style = PorygonType.ScreenTitle,
      color = TextInk,
      modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
    Text(
      if (partner == null) "Nobody else has this list yet."
      else "Syncs with $partner on an approved network.",
      style = PorygonType.BodyLarge.copy(lineHeight = PorygonType.BodyLarge.fontSize * 1.55),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 20.dp),
    )

    CurrentNetworkPill(
      label = networkLabel(here, network, wifiName, unknown = "This network"),
      partner = partner,
      discovery = discovery,
      // Not offered under a VPN: what would be approved is the tunnel, not the wifi.
      canApprove = network.fingerprint != null && !discovering && !network.isVpn,
      onApprove = onApproveCurrent,
    )

    SectionLabel("Approved networks", Modifier.padding(top = 22.dp, bottom = 10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
      state.networks.forEach { entry ->
        NetworkRow(
          network = entry,
          isCurrent = entry.fingerprint == network.fingerprint,
          naming = namingNetwork == entry.fingerprint,
          confirmingForget = confirmingNetworkRemoval == entry.fingerprint,
          nameDraft = networkNameDraft,
          onNameDraftChange = onNetworkNameDraftChange,
          onStartNaming = { onStartNamingNetwork(entry) },
          onSaveName = onSaveNetworkName,
          onCancelNaming = onCancelNamingNetwork,
          onAskForget = { onAskForgetNetwork(entry.fingerprint) },
          onCancelForget = onCancelForgetNetwork,
          onConfirmForget = { onForgetNetwork(entry.fingerprint) },
          onToggle = { onToggleNetwork(entry.fingerprint) },
        )
      }
    }
    Text(
      "Tap a name to rename it. Press and hold to forget one.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.55),
      color = Neutral700,
      modifier = Modifier.padding(top = 14.dp, bottom = if (canAskForWifiName) 10.dp else 26.dp),
    )

    // Offered here, beside the names, rather than thrown up on arrival. The permission buys one
    // thing — the wifi's own name instead of "Network a3f91c" — so it is asked for at the moment
    // that is visibly what you want, and never again once it has been granted or the networks are
    // already named.
    if (canAskForWifiName) {
      SecondaryButton(
        "Use the wifi's own name",
        onAskForWifiName,
        modifier = Modifier.padding(bottom = 6.dp),
        style = PorygonType.Meta,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
      )
      Text(
        "Android only gives out the wifi name with location permission. It stays a label — what syncs is still decided by the code you swapped.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.55),
        color = Neutral700,
        modifier = Modifier.padding(bottom = 26.dp),
      )
    }

    SectionLabel("Away from wifi", Modifier.padding(bottom = 10.dp))
    Column(
      Modifier.fillMaxWidth()
        .clip(Shapes.Card)
        .background(Accent100)
        .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp)
    ) {
      Text(
        if (list.name.isBlank()) "Send a list as a text" else "Send ${list.name} as a text",
        style = PorygonType.CardHeading,
        color = TextInk,
        modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
        "One message. They paste it straight back in.",
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
      // Present people only. Someone who has left stays on the list as a tombstone until everyone has
      // heard, and listing them under People would show them as still sharing it.
      list.people.filter { it.present }.forEach { person ->
        PersonRow(
          person = person,
          isYou = person.device == state.localDevice,
          discovering = discovering,
          lastHandover = state.deliveredTo.confirmedBy(person.device),
          networkLabel = networkLabel(here, network, wifiName),
          confirming = confirmingRemovalOf == person.device,
          onAskRemove = { onAskRemovePerson(person.device) },
          onCancelRemove = onCancelRemovePerson,
          onConfirmRemove = { onRemovePerson(person.device) },
        )
      }
    }

    // Pairing and sharing are two decisions in the model, and should be: trusting a phone is about
    // the phone, which is what makes a list you keep to yourself possible. But "give this list to
    // somebody" is one intention, so it is one section — an already-paired person is one tap, and
    // somebody new goes through pairing and lands on this list at the end of it.
    SectionLabel(
      if (list.name.isBlank()) "Give a list to someone" else "Give ${list.name} to someone",
      Modifier.padding(top = 26.dp, bottom = 10.dp),
    )
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
            contentColor = Accent2100,
            size = 34.dp,
            fontSize = PorygonType.BodyLarge.fontSize,
          )
          Column(Modifier.weight(1f)) {
            Text(peer.name, style = PorygonType.RowName, color = TextInk)
            Text("Paired with this phone, not on this list", style = PorygonType.Fine, color = Neutral700)
          }
          PrimaryButton(
            "Add",
            onClick = { onAddPerson(peer.deviceId) },
            style = PorygonType.Meta,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          )
        }
      }

      Column(
        Modifier.fillMaxWidth()
          .clip(Shapes.Card)
          .background(Accent2100)
          .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)
      ) {
        Text("Somebody new", style = PorygonType.CardHeading, color = TextInk, modifier = Modifier.padding(bottom = 4.dp))
        Text(
          if (list.name.isBlank()) "Swap codes once, and they're on your lists."
          else "Swap codes once, and they're on ${list.name}.",
          style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
          color = Neutral700,
          modifier = Modifier.padding(bottom = 14.dp),
        )
        PrimaryButton(
          if (list.name.isBlank()) "Share" else "Share ${list.name}",
          onGoPair,
          modifier = Modifier.heightIn(min = 44.dp),
          style = PorygonType.BodyLarge,
        )
      }
    }

    // The counterpart to everything above it, and the reason it is here rather than only on the
    // "You" screen: this is the screen somebody arrives at wanting to stop sharing with a phone,
    // and until now every control on it added. Unpairing stays in one place — it is about the
    // phone, not this list, and a second copy of the list would be two places to get right — so
    // this is a signpost that says what the other screen does and what this one cannot.
    if (state.peers.isNotEmpty()) {
      SectionLabel("Paired phones", Modifier.padding(top = 26.dp, bottom = 10.dp))
      Column(
        Modifier.fillMaxWidth()
          .clip(Shapes.Card)
          .background(Surface)
          .clickable(onClick = onOpenPairedPhones)
          .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)
      ) {
        Text(
          if (state.peers.size == 1) "One phone paired with this one"
          else "${state.peers.size} phones paired with this one",
          style = PorygonType.CardHeading,
          color = TextInk,
          modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
          // Says the distinction out loud, because it is the one that makes the removal above look
          // like it did nothing: taking Ava off this list does not take her phone off the others.
          "Taking someone off a list leaves their phone paired. Unpairing drops the phone, and takes them off every list.",
          style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
          color = Neutral700,
          modifier = Modifier.padding(bottom = 14.dp),
        )
        SecondaryButton(
          "Unpair a phone",
          onOpenPairedPhones,
          modifier = Modifier.heightIn(min = 44.dp),
          style = PorygonType.BodyLarge,
        )
      }
    }
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
      if (waitingOn.isEmpty()) "Waiting for another phone."
      else "Waiting for ${waitingOn.joinToString(" and ")}.",
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
        "They may come back when you next meet.",
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
  partner: String?,
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
            discovering && partner != null -> "looking for $partner here"
            discovering -> "looking here"
            discovery is DiscoveryDecision.Hold ->
              when (discovery.reason) {
                // The dot beside this is already the "nothing is leaving" signal; five variants
                // spelling it out in words made the banner the longest line on the screen.
                HoldReason.OFFLINE -> "no network"
                HoldReason.NOT_WIFI -> "not wifi"
                // Names the VPN rather than saying "not recognised", because this one is a switch
                // the owner can flip — and would not think to, if the banner blamed the network.
                HoldReason.VPN -> "VPN is on"
                HoldReason.UNIDENTIFIABLE -> "not recognised"
                HoldReason.NOT_APPROVED -> "not approved"
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
        color = Accent100,
        modifier =
          Modifier.clip(Shapes.Pill).background(Accent).clickable(onClick = onApprove).padding(horizontal = 14.dp, vertical = 7.dp),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkRow(
  network: ApprovedNetwork,
  isCurrent: Boolean,
  naming: Boolean,
  confirmingForget: Boolean,
  nameDraft: String,
  onNameDraftChange: (String) -> Unit,
  onStartNaming: () -> Unit,
  onSaveName: () -> Unit,
  onCancelNaming: () -> Unit,
  onAskForget: () -> Unit,
  onCancelForget: () -> Unit,
  onConfirmForget: () -> Unit,
  onToggle: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().clip(Shapes.Row).background(Surface).padding(horizontal = 16.dp, vertical = 13.dp)
  ) {
    Row(
      Modifier.fillMaxWidth(),
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
          tint = if (network.approved && isCurrent) Accent2100 else Accent2800,
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
          color = Accent100,
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
        // No clip here. Shapes.Row is a generous corner radius, and clipping a column this short
        // with it bites the first character off both lines — which is what "Home" rendering as
        // "dome" was.
        Column(Modifier.weight(1f).combinedClickable(onClick = onStartNaming, onLongClick = onAskForget)) {
          Text(network.label, style = PorygonType.RowName, color = TextInk)
          Text(network.detail, style = PorygonType.Fine, color = Neutral700, modifier = Modifier.padding(top = 1.dp))
        }
        NetworkSwitch(network.approved, onToggle, contentDescription = "Approve ${network.label}")
      }
    }

    if (confirmingForget) {
      Text(
        "Forget ${network.label}?",
        style = PorygonType.Meta,
        color = TextInk,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
      )
      Text(
        // The reassuring half: a network says where these phones may look for each other, never
        // which phones are trusted. Forgetting one unpairs nobody.
        "Nothing moves here until you approve it again. Nobody is unpaired.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Neutral700,
        modifier = Modifier.padding(bottom = 12.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton(
          "Forget",
          onConfirmForget,
          style = PorygonType.Meta,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        )
        SecondaryButton(
          "Keep it",
          onCancelForget,
          style = PorygonType.Meta,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun PersonRow(
  person: Person,
  isYou: Boolean,
  discovering: Boolean,
  lastHandover: Hlc?,
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
        contentColor = if (isYou) Accent100 else Accent2100,
        size = 34.dp,
        fontSize = PorygonType.BodyLarge.fontSize,
      )
      Column(Modifier.weight(1f)) {
        Text(if (isYou) "You" else person.name, style = PorygonType.RowName, color = TextInk)
        Text(
          when {
            isYou -> "This phone"
            discovering -> "Looked for on $networkLabel"
            else -> handoverLabel(lastHandover)
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
        "They keep the copy they have.",
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
