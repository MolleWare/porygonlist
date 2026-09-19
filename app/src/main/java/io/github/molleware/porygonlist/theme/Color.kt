package io.github.molleware.porygonlist.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette: ColorHunt `1d4533-f7eae0-f9d2ba-5e3122` — deep green, cream, peach, brown.
 *
 * This replaces the amber/brown values ported from the Claude Design handoff (`Porygonlist.dc.html`).
 * Radii, shadows and type still come from that handoff; only colour is ours. If a fresh handoff zip
 * ever lands it will carry the old amber tokens and overwrite this file — re-apply these.
 *
 * Unlike the pastel set this replaces, these four span the range: two near-whites for the grounds
 * and two darks that can carry a control on their own. So both accents are **dark, with light
 * labels** — the inverse of the pastel scheme, and closer to how the original design treated its
 * brown second accent.
 *
 * The ramps extend each source hue along the shared lightness scale the amber ramps used, so the
 * design's tonal rhythm survives the swap. Green and brown both land on step 800, which makes them
 * a matched pair: the same value, distinguished only by hue.
 *
 * One consequence worth knowing: a dark accent is invisible on shopping mode's dark ground (1.44:1),
 * so that screen steps up to the 300/400 rungs. The design already did this for its back link, and
 * [TabBar] already did it for its labels; the rule is now applied consistently.
 */

// The palette, verbatim.
private val PaletteGreen = Color(0xFF1D4533)
private val PaletteCream = Color(0xFFF7EAE0)
private val PalettePeach = Color(0xFFF9D2BA)
private val PaletteBrown = Color(0xFF5E3122)

// Semantic roles.
val Bg = PaletteCream
val Surface = PalettePeach
val TextInk = Color(0xFF2C1A15)
val Accent = PaletteGreen
val Accent2 = PaletteBrown

/** The ink at 16% alpha, as the design's `color-mix(… 16%, transparent)` divider. */
val Divider = TextInk.copy(alpha = 0.16f)

// Tonal ramps. Generated in OKLCH on one shared lightness scale, so the same step of any role
// matches the others in visual value. Step 800 of each accent ramp is a palette colour verbatim.
val Neutral100 = Color(0xFFFAF4F0)
val Neutral200 = Color(0xFFF0E6DE)
val Neutral300 = Color(0xFFDECEC2)
val Neutral400 = Color(0xFFC1AFA2)
val Neutral500 = Color(0xFFA29183)
val Neutral600 = Color(0xFF817267)
val Neutral700 = Color(0xFF63574D)
val Neutral800 = Color(0xFF453C35)
val Neutral900 = Color(0xFF29231F)

val Accent100 = Color(0xFFE6FCF0)
val Accent200 = Color(0xFFCCF3DE)
val Accent300 = Color(0xFFA4E2C2)
val Accent400 = Color(0xFF7CC5A2)
val Accent500 = Color(0xFF5DA683)
val Accent600 = Color(0xFF438567)
val Accent700 = Color(0xFF30664D)
val Accent800 = PaletteGreen
val Accent900 = Color(0xFF132A1F)

val Accent2100 = Color(0xFFFFF0E4)
val Accent2200 = Color(0xFFFFDDC8)
val Accent2300 = Color(0xFFFFC0A0)
val Accent2400 = Color(0xFFEB9E79)
val Accent2500 = Color(0xFFC97F5B)
val Accent2600 = Color(0xFFA46343)
val Accent2700 = Color(0xFF7F4A30)
val Accent2800 = PaletteBrown
val Accent2900 = Color(0xFF351E12)

/**
 * Shadow ink. The design system's own neutral-900, left untinted so the lift under cards and sheets
 * stays a plain dark rather than picking up the palette's warmth.
 */
val ShadowInk = Color(0xFF2E2B25)

/** Scrim behind the bottom sheets: the design's neutral-900 at 45%. */
val SheetScrim = Neutral900.copy(alpha = 0.45f)

// Shop mode paints straight onto the dark ground with white alphas rather than ramp steps.
val ShopRow = Color.White.copy(alpha = 0.10f)
val ShopRowChecked = Color.White.copy(alpha = 0.05f)
val ShopTrack = Color.White.copy(alpha = 0.12f)
val ShopHairline = Color.White.copy(alpha = 0.10f)
val ShopBoxBorder = Color.White.copy(alpha = 0.35f)
