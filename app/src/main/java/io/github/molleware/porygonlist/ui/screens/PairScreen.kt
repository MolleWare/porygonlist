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
import io.github.molleware.porygonlist.theme.Surface
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
 * Both halves are on one screen because pairing is symmetric: each phone has to end up holding the
 * other's key, so whoever is looking at this has to both show a code and take one — and the screen
 * says which is which, in those terms. Show yours: they point their camera at it. Take theirs: point
 * your own camera app at their screen, because the invite is a link and the phone already knows how
 * to open one.
 *
 * There is no camera in this app and no camera permission. That is the point rather than a gap: what
 * makes pairing trustworthy is the channel the code travelled, not the format it travelled in.
 *
 * The code is a public key and a name. It carries no secret, so it is safe in a message, and it
 * cannot be used to impersonate the phone that showed it.
 */
@Composable
fun PairScreen(
  invite: String,
  code: String,
  onCodeChange: (String) -> Unit,
  note: String,
  /** The list this pairing was started in order to share, if it was started that way. */
  sharingList: String?,
  pendingName: String?,
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
    Text(
      "Each of you needs the other's code. Show yours, take theirs, and from then on the two phones " +
        "recognise each other on any network you have both approved." +
        if (sharingList != null) " They go onto $sharingList as soon as that is done." else "",
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
      // Drawn from the same string shown below it, so there is never a version of the code on
      // screen that the text does not account for.
      val qr = rememberQrCode(invite)
      if (qr != null) {
        QrCodeImage(
          code = qr,
          contentDescription = "Your pairing code as a QR code. The text below says the same thing.",
          modifier = Modifier.align(Alignment.CenterHorizontally).size(QR_SIZE).padding(bottom = 12.dp),
          dark = Accent900,
          light = Accent100,
        )
        Text(
          "Have them point their camera at this.",
          style = PorygonType.Meta,
          color = Accent800,
          modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
        )
      }
      Text(
        invite,
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.45),
        color = Accent800,
        modifier = Modifier.padding(bottom = 12.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton(
          "Copy your code",
          onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Porygonlist", invite))) } },
          modifier = Modifier.heightIn(min = 44.dp),
          style = PorygonType.BodyLarge,
        )
      }
      Text(
        "There is no secret in this. It is a public key and your name, which is why it is safe to " +
          "send — what makes it mean anything is that it reached them from you.",
        style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
        color = Accent800,
        modifier = Modifier.padding(top = 12.dp),
      )
    }

    SectionLabel("Their code", Modifier.padding(top = 26.dp, bottom = 6.dp))
    Text(
      "Open your own camera app and point it at the code on their screen — it will offer to open " +
        "porygonlist, and their code lands here. If they sent it instead, tap the link or paste it below.",
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

    if (note.isNotEmpty()) {
      Text(note, style = PorygonType.Fine, color = Neutral700, modifier = Modifier.padding(top = 8.dp))
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
          "Is this someone new, or a phone replacing one you already share with?",
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
            "Replacing keeps everything that person has written on your lists, and stops waiting on " +
              "the phone they no longer have.",
            style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
            color = Neutral700,
          )
        }
      }
    }

    // Worth saying once, quietly, at the foot: the camera in this flow is always the phone's own
    // camera app, never this one. That is a deliberate property rather than a missing feature, and
    // somebody looking for a "scan" button here should find out why there isn't one.
    Text(
      "Porygonlist never asks for your camera. Your phone's camera app already reads these, and " +
        "the code carries a public key and a name — no secret either way.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.5),
      color = Neutral700,
      modifier = Modifier.padding(top = 26.dp),
    )
  }
}
