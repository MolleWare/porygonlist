package io.github.molleware.porygonlist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.TextInk
import io.github.molleware.porygonlist.ui.components.PorygonTextField
import io.github.molleware.porygonlist.ui.components.PrimaryButton

/**
 * First run, and the only thing the app asks for before it will show anything.
 *
 * One field. The key this phone is known by was made in the keystore the moment the app opened, and
 * there is deliberately nothing about it here: it cannot be read out, typed in or chosen, so putting
 * it on screen would only be asking someone to care about a decision they do not get to make. The
 * closing line says that much and no more.
 *
 * The name is asked for now rather than at the first share because it is what every later screen
 * reads — "Hugo added this", the letter in an avatar, the label inside a pairing invite. Collecting
 * it at the point of sharing would mean every list built before then was authored by nobody.
 */
@Composable
fun NameScreen(name: String, onNameChange: (String) -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
  val ready = name.isNotBlank()

  Column(
    modifier.verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 30.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("porygonlist", style = PorygonType.Wordmark, color = TextInk)

    Text(
      "What should we call you?",
      style = PorygonType.ScreenTitle,
      color = TextInk,
      modifier = Modifier.padding(top = 18.dp),
    )
    Text(
      "Your name goes beside the things you add, so whoever you share a list with can see what came " +
        "from you. A first name is plenty.",
      style = PorygonType.BodyLarge.copy(lineHeight = PorygonType.BodyLarge.fontSize * 1.55),
      color = Neutral700,
    )

    PorygonTextField(
      value = name,
      onValueChange = onNameChange,
      placeholder = "Your name",
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
      imeAction = ImeAction.Done,
      onSubmit = onContinue,
    )

    // Dimmed rather than removed: the button staying put is what makes it obvious the field is the
    // thing standing in the way.
    PrimaryButton(
      "Continue",
      onContinue,
      modifier = Modifier.fillMaxWidth().alpha(if (ready) 1f else 0.45f),
      style = PorygonType.Body,
    )

    Text(
      "This phone made itself a key when you opened the app. It never leaves the device and there is " +
        "nothing to write down — it is what proves your lists came from you.",
      style = PorygonType.Fine.copy(lineHeight = PorygonType.Fine.fontSize * 1.55),
      color = Neutral700,
      modifier = Modifier.padding(top = 10.dp),
    )
  }
}
