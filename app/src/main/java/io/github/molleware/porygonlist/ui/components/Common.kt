package io.github.molleware.porygonlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.molleware.porygonlist.theme.Accent
import io.github.molleware.porygonlist.theme.Accent900
import io.github.molleware.porygonlist.theme.Bg
import io.github.molleware.porygonlist.theme.Divider
import io.github.molleware.porygonlist.theme.Elevation
import io.github.molleware.porygonlist.theme.Neutral400
import io.github.molleware.porygonlist.theme.Neutral700
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.ShadowInk
import io.github.molleware.porygonlist.theme.Shapes
import io.github.molleware.porygonlist.theme.ShopBoxBorder
import io.github.molleware.porygonlist.theme.Surface
import io.github.molleware.porygonlist.theme.TextInk

/** A person's initial in a filled circle. Overlapping avatars tuck under each other by 7dp. */
@Composable
fun Avatar(
  initial: String,
  background: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
  size: Dp = 26.dp,
  fontSize: TextUnit = PorygonType.Meta.fontSize,
  ringColor: Color? = null,
) {
  Box(
    modifier.size(size).clip(CircleShape).background(background).then(if (ringColor != null) Modifier.border(2.dp, ringColor, CircleShape) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    Text(initial, color = contentColor, style = PorygonType.MetaBold.copy(fontSize = fontSize))
  }
}

/** The small coloured status dot: 9dp beside sync copy, 11dp on a list card. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
  Box(modifier.size(size).clip(CircleShape).background(color))
}

/** `h6` — the uppercase section label above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Neutral700) {
  Text(text.uppercase(), style = PorygonType.SectionLabel, color = color, modifier = modifier)
}

/** "‹ All lists" and "‹ Leave shopping mode". */
@Composable
fun BackLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color) {
  Row(
    modifier.clip(Shapes.Pill).clickable(onClick = onClick).padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    StrokeIcon(IconPaths.CHEVRON_LEFT, contentDescription = null, size = 14.dp, tint = tint)
    Text(text, style = PorygonType.BackLink, color = tint)
  }
}

/**
 * `.btn.btn-primary` — filled amber pill. The design system sets buttons in the heading face; the
 * design file then overrides the label colour to accent-900.
 */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: TextStyle = PorygonType.Body.copy(fontSize = 14.sp),
  contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
) {
  Box(
    modifier.clip(Shapes.Pill).background(Accent).clickable(onClick = onClick).padding(contentPadding),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = style.copy(fontFamily = PorygonType.InlineHeading.fontFamily), color = Accent900, textAlign = TextAlign.Center)
  }
}

/** `.btn.btn-secondary` — hairline outline, transparent ground. */
@Composable
fun SecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: TextStyle = PorygonType.Body.copy(fontSize = 14.sp),
  contentColor: Color = TextInk,
  contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
) {
  Box(
    modifier.clip(Shapes.Pill).border(1.dp, Divider, Shapes.Pill).clickable(onClick = onClick).padding(contentPadding),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = style.copy(fontFamily = PorygonType.InlineHeading.fontFamily), color = contentColor, textAlign = TextAlign.Center)
  }
}

/** A square icon button, used for the add-item submit and the quantity stepper. */
@Composable
fun IconActionButton(
  pathData: String,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = 46.dp,
  iconSize: Dp = 20.dp,
  background: Color = Accent,
  tint: Color = Accent900,
  strokeWidth: Float = 2.9f,
  border: Color? = null,
) {
  Box(
    modifier
      .size(size)
      .clip(CircleShape)
      .background(background)
      .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    StrokeIcon(pathData, contentDescription, size = iconSize, strokeWidth = strokeWidth, tint = tint)
  }
}

/**
 * The round tick box beside an item.
 *
 * In shopping mode it grows to 34dp and borders in white, since it sits on the dark ground.
 */
@Composable
fun CheckCircle(
  checked: Boolean,
  modifier: Modifier = Modifier,
  size: Dp = 26.dp,
  dark: Boolean = false,
  iconSize: Dp = 14.dp,
) {
  val borderColor = if (checked) Accent else if (dark) ShopBoxBorder else Neutral400
  Box(
    modifier
      .size(size)
      .clip(CircleShape)
      .background(if (checked) Accent else Color.Transparent)
      .border(2.dp, borderColor, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    if (checked) {
      StrokeIcon(
        IconPaths.CHECK,
        contentDescription = null,
        size = iconSize,
        strokeWidth = 3.2f,
        tint = Bg,
      )
    }
  }
}

/** The approve-this-network toggle: a 52x30 track with a 24dp knob. */
@Composable
fun NetworkSwitch(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, contentDescription: String) {
  val label = contentDescription
  Row(
    modifier
      .width(52.dp)
      .height(30.dp)
      .clip(Shapes.Pill)
      .background(if (on) Accent else Neutral400)
      // Announced as a switch, so the state is read out along with the network's name.
      .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
      .semantics { this.contentDescription = label }
      .padding(3.dp),
    horizontalArrangement = if (on) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(24.dp)
        .shadow(Elevation.Sm, CircleShape, ambientColor = ShadowInk, spotColor = ShadowInk)
        .clip(CircleShape)
        .background(Bg)
        .clearAndSetSemantics {}
    )
  }
}

/** `.input` — surface-filled pill with a hairline edge. */
@Composable
fun PorygonTextField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  textStyle: TextStyle = PorygonType.Body,
  minHeight: Dp = 46.dp,
  shape: Shape = Shapes.Pill,
  singleLine: Boolean = true,
  imeAction: ImeAction = ImeAction.Done,
  onSubmit: (() -> Unit)? = null,
) {
  val interaction = remember { MutableInteractionSource() }
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.defaultMinSize(minHeight = minHeight),
    textStyle = textStyle.copy(color = TextInk),
    singleLine = singleLine,
    cursorBrush = SolidColor(Accent),
    interactionSource = interaction,
    keyboardOptions = KeyboardOptions(imeAction = imeAction),
    keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }, onSend = { onSubmit?.invoke() }),
    decorationBox = { inner ->
      Box(
        Modifier.fillMaxWidth()
          .defaultMinSize(minHeight = minHeight)
          .clip(shape)
          .background(Surface)
          .border(1.dp, Divider, shape)
          .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
      ) {
        if (value.isEmpty()) {
          Text(placeholder, style = textStyle, color = Neutral700)
        }
        inner()
      }
    },
  )
}

/** The grabber at the top of every bottom sheet. */
@Composable
fun SheetGrabber(modifier: Modifier = Modifier) {
  Box(modifier.width(44.dp).height(5.dp).clip(Shapes.Pill).background(Neutral400))
}

/** Full-bleed tap target that dismisses a sheet when the scrim above it is touched. */
@Composable
fun ScrimDismissArea(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
}

