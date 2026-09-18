package io.github.molleware.porygonlist.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Radii from the handoff.
 *
 * The design system ends with a "rounded frame" block that pushes cards to `radius-lg * 1.15` and
 * every small control to a full pill. The design file then pins its own radius per surface, so
 * these are named for the surface rather than for a t-shirt size.
 */
object Radius {
  /** Buttons, tags, inputs, avatars, switches — `border-radius: 999px`. */
  val Pill = 999.dp

  /** The export/import preview panel inside a sheet. */
  val Panel = 24.dp

  /** Item rows, network rows, person rows, the conflict card, staple tiles. */
  val Row = 26.dp

  /** Shopping-mode rows — slightly softer, to match their larger padding. */
  val ShopRow = 28.dp

  /** List cards, the invite call-to-action, the "send as a text" card. */
  val Card = 30.dp

  /** The top corners of a bottom sheet. */
  val Sheet = 34.dp
}

/** Corner shapes for the surfaces above. */
object Shapes {
  val Pill = RoundedCornerShape(Radius.Pill)
  val Panel = RoundedCornerShape(Radius.Panel)
  val Row = RoundedCornerShape(Radius.Row)
  val ShopRow = RoundedCornerShape(Radius.ShopRow)
  val Card = RoundedCornerShape(Radius.Card)
  val Sheet = RoundedCornerShape(topStart = Radius.Sheet, topEnd = Radius.Sheet)
}

/**
 * Elevation, approximating the design system's ink-tinted shadows.
 *
 * CSS gives blur and offset separately (`--shadow-md: 0 3px 10px …`); Compose takes a single
 * elevation and derives both, so these are the closest visual match rather than a literal port.
 */
object Elevation {
  /** `--shadow-sm` — list cards, staple tiles, the switch knob. */
  val Sm = 1.dp

  /** `--shadow-md` — the hover state on cards and tiles. */
  val Md = 6.dp

  /** `--shadow-lg` — bottom sheets. */
  val Lg = 16.dp
}

/**
 * The design is light-only. Shopping mode is deliberately dark, but that is one screen painting
 * itself on the neutral-900 ground rather than a second theme — so there is no dark colour scheme
 * here, and no dynamic colour: the amber palette is the brand.
 */
private val PorygonColorScheme =
  lightColorScheme(
    primary = Accent,
    onPrimary = Accent900,
    primaryContainer = Accent100,
    onPrimaryContainer = Accent800,
    secondary = Accent2,
    onSecondary = Neutral100,
    secondaryContainer = Accent2100,
    onSecondaryContainer = Accent2800,
    background = Bg,
    onBackground = TextInk,
    surface = Surface,
    onSurface = TextInk,
    surfaceVariant = Neutral200,
    onSurfaceVariant = Neutral700,
    outline = Neutral400,
    outlineVariant = Divider,
    scrim = SheetScrim,
  )

@Composable
fun PorygonListTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = PorygonColorScheme, typography = Typography, content = content)
}
