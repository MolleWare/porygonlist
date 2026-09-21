package io.github.molleware.porygonlist.ui.screens

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.data.Person
import io.github.molleware.porygonlist.data.sync.DeviceId
import io.github.molleware.porygonlist.theme.Accent100
import io.github.molleware.porygonlist.theme.Accent2100
import io.github.molleware.porygonlist.theme.Accent700
import io.github.molleware.porygonlist.theme.Accent800
import io.github.molleware.porygonlist.theme.Accent900
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.BackLink
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.PrimaryButton
import io.github.molleware.porygonlist.ui.components.QrCodeImage
import io.github.molleware.porygonlist.ui.components.SecondaryButton
import io.github.molleware.porygonlist.ui.components.SectionLabel
import io.github.molleware.porygonlist.ui.components.rememberQrCode
import kotlinx.coroutines.launch

/**
 * How large the invite's QR code is drawn.
 *
 * Sized so that a version-7 symbol's modules land around five pixels each on a typical phone,
 * which is comfortably above what a camera held at arm's length needs to resolve.
 */
private val QR_SIZE = 232.dp

/**
 * Two phones agreeing to recognise each other.
 *
 * Pairing is symmetric — each phone has to end up holding the other's key — but that no longer
 * means two scans. While this screen is open the phone is listening, and the QR says where. So the
 * phone that scans can hand its own key straight back over that connection, and both halves are
 * done by one person pointing a camera once. The screen says so only when it is actually true:
 * with no wifi, or a socket that would not bind, it goes back to asking for both.
 *
 * Nobody is asked to approve the phone that scans. It proved it read this screen — the QR carries
 * a one-time token, and the handshake has to say it back — and the screen is only up because its
 * owner put it there and held it out. So the pairing happens and the screen reports it, with Undo
 * beside it. That is where the weight belongs: on something visible that can be taken back, not on
 * a dialog in front of a thing the person already decided.
 *
 * Both halves are still on one screen, because both are still sometimes needed — when the other
 * phone is the one showing a code, or when a code arrived as a text.
 *
 * There is no camera in this app and no camera permission. That is the point rather than a gap:
 * what makes pairing trustworthy is the channel the code travelled, not the format it travelled in.
 *
 * **The written code and the QR are not the same string, and that is deliberate.** The written one
 * is a public key and a name, carries no secret, and is safe in a message. The QR adds an address
 * and a token good for one handshake — live things, true for as long as this screen is open, which
 * is exactly why they stay on the screen and out of the clipboard.
 */
@Composable
fun PairScreen(
  /** What the QR carries: the code, plus where to call back and the one-time token. */
  invite: String,
  /** What the text and the copy button carry: the code alone, safe to send anywhere. */
  textInvite: String,
  code: String,
  onCodeChange: (String) -> Unit,
  note: String,
  /** The list this pairing was started in order to share, if it was started that way. */
  sharingList: String?,
  pendingName: String?,
  /** Somebody scanned the code above and is already paired. Shown as done, not as a question. */
  justPairedName: String?,
  onUndoJustPaired: () -> Unit,
  onJustPairedIsReplacementFor: (DeviceId) -> Unit,
  /** The socket behind the code is open, so scanning it is enough. */
  listening: Boolean,
  /** This phone is handing its own key back to one that just scanned it. */
  handingBack: Boolean,
  replaceCandidates: List<Person>,
  onPairAsNew: () -> Unit,
  onReplace: (DeviceId) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()

  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 88.dp)
  ) {
    BackLink("Back", onBack, tint = Accent700)
    Text(
      if (sharingList != null) "Share $sharingList" else "Pair a phone",
      style = PorygonType.ScreenTitle,
      color = TextInk,
      modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
    // Two different promises, and the screen must not make the wrong one. While the socket is
    // open, one scan finishes the job in both directions and saying "swap codes" would send
    // somebody looking for a second step that is not there. Off the air — no wifi, or the socket
    // would not bind — it really is both ways, and pretending otherwise strands them.
    Text(
      when {
        listening && sharingList != null -> "They scan this once, and they're on $sharingList."
        listening -> "They scan this once. That's both of you."
        sharingList != null -> "Swap codes once, and they're on $sharingList."
        else -> "Swap codes once, both ways."
      },
      style = PorygonType.BodyLarge.copy(lineHeight = PorygonType.BodyLarge.fontSize * 1.55),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 22.dp),
    )

    SectionLabel("Your code", Modifier.padding(bottom = 10.dp))
    Column(
      Modifier.fillMaxWidth()
        .clip(Shapes.Card)
        .background(Accent100)
        .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)
    ) {
      // The QR and the text below it deliberately differ, which they did not used to. The QR
      // carries where to call back and a token good for one handshake; the text carries neither,
      // because it is the form that gets sent as a message and outlives this screen. Both contain
      // the same code, so scanning and pasting reach the same place — one just gets there without
      // a second scan.
      val qr = rememberQrCode(invite)
      if (qr != null) {
        QrCodeImage(
          code = qr,
          contentDescription =
            if (listening) "Your pairing code as a QR code. Scanning it pairs both phones at once."
            else "Your pairing code as a QR code. The text below says the same thing.",
          modifier = Modifier.align(Alignment.CenterHorizontally).size(QR_SIZE).padding(bottom = 12.dp),
          dark = Accent900,
          light = Accent100,
        )
        Text(
          if (listening) "Point their camera here. One scan does both." else "Point their camera here.",
          style = PorygonType.Meta,
          color = Accent800,
          modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
        )
      }
      Text(
        textInvite,
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.45),
        color = Accent800,
        modifier = Modifier.padding(bottom = 12.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton(
          "Copy your code",
          onClick = {
            // The durable form, never the one in the QR. A copied code goes into a message, and a
            // message keeps — a one-time token has no business in one.
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Porygonlist", textInvite))) }
          },
          modifier = Modifier.heightIn(min = 44.dp),
          style = PorygonType.BodyLarge,
        )
      }
      // No line here about it carrying no secret. It is true, and it is the reason this is safe to
      // send, but a person deciding whether to send it does not read a paragraph first — the one at
      // the foot of the screen covers it once, for anyone who wonders.
    }

    SectionLabel("Their code", Modifier.padding(top = 26.dp, bottom = 6.dp))
    Text(
      // Still here, and still the way in when the other phone is the one showing a code, when
      // there is no wifi between them, or when a code arrived as a text. It is no longer the
      // second half of a chore, which is why it no longer says "and then they do the same".
      if (listening) "Or scan theirs instead — either direction works." else "Point your camera at theirs.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(bottom = 12.dp),
    )
    PorygonTextField(
      value = code,
      onValueChange = onCodeChange,
      placeholder = "Or paste their code",
      modifier = Modifier.fillMaxWidth(),
      shape = Shapes.Row,
      singleLine = false,
      minHeight = 76.dp,
      imeAction = ImeAction.Done,
      onSubmit = onPairAsNew,
    )

    // "Handing your code back" rather than a spinner: the wait is a second at most, and what is
    // happening is the part somebody would otherwise assume they still had to do themselves.
    if (handingBack) {
      Text(
        "Handing your code back…",
        style = PorygonType.Fine,
        color = Accent800,
        modifier = Modifier.padding(top = 8.dp),
      )
    } else if (note.isNotEmpty()) {
      Text(note, style = PorygonType.Fine, color = Neutral700, modifier = Modifier.padding(top = 8.dp))
    }

    // Somebody scanned the code and paired themselves. Reported, not asked — they proved they
    // read this screen, and the screen is only up because its owner put it there. What is offered
    // instead is the two corrections: the wrong phone got there first, or this is a handset
    // replacing one already on a list.
    if (justPairedName != null) {
      Column(
        Modifier.fillMaxWidth()
          .padding(top = 14.dp)
          .clip(Shapes.Card)
          .background(Accent2100)
          .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          if (sharingList != null) "$justPairedName is on $sharingList" else "$justPairedName is paired",
          style = PorygonType.CardHeading,
          color = TextInk,
        )
        Text(
          "They scanned your code, so you both have each other now.",
          style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
          color = Neutral700,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
          SecondaryButton(
            "Undo",
            onUndoJustPaired,
            style = PorygonType.Meta,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
          )
        }
        replaceCandidates.forEach { person ->
          SecondaryButton(
            "This is ${person.name}'s new phone",
            onClick = { onJustPairedIsReplacementFor(person.device) },
            style = PorygonType.Meta,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
          )
        }
      }
    }

    // A valid code is not acted on until it is clear *who* it is. Adding a stranger and recording
    // that someone replaced their handset look identical on the wire and are entirely different
    // things: the second retires an id that is still owed delivery receipts.
    if (pendingName != null) {
      Column(
        Modifier.fillMaxWidth()
          .padding(top = 14.dp)
          .clip(Shapes.Card)
          .background(Accent2100)
          .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("That code is $pendingName's phone", style = PorygonType.CardHeading, color = TextInk)
        Text(
          "Someone new, or a phone replacing one?",
          style = PorygonType.Meta.copy(lineHeight = PorygonType.Meta.fontSize * 1.5),
          color = Neutral700,
        )
        PrimaryButton(
          if (sharingList != null) "Add $pendingName to $sharingList" else "Add $pendingName",
          onPairAsNew,
          modifier = Modifier.heightIn(min = 44.dp),
          style = PorygonType.BodyLarge,
        )
        replaceCandidates.forEach { person ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
              "This is ${person.name}'s new phone",
              onClick = { onReplace(person.device) },
              style = PorygonType.Meta,
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
            )
          }
        }
        if (replaceCandidates.isNotEmpty()) {
          Text(
            "Replacing keeps what they've written, and stops waiting on the old phone.",
            style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
            color = Neutral700,
          )
        }
      }
    }

    // The whole justification — no camera permission, nothing secret in a code, trust coming from
    // the channel rather than the payload — lives in the KDoc above and in docs/ARCHITECTURE.md.
    // On screen it is one line, for the person wondering where the scan button went.
    Text(
      if (listening)
        "No camera permission. The written code is just a public key and a name — the QR adds where to reach this phone, for as long as this screen is open."
      else "No camera permission. A code is just a public key and a name.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(top = 26.dp),
    )
  }
}
