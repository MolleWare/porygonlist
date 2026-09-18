package io.github.molleware.porygonlist.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens from the Claude Design handoff (`Porygonlist.dc.html`).
 *
 * The bundle has two layers: the "Organic" design system in `_ds/.../styles.css`, and an inline
 * `<style>` block in the design file that redefines every colour token. The inline block wins, so
 * these are the amber/brown values the screen actually renders — not the orange/sage in the DS file.
 * The DS still governs radii, shadows and type.
 */

// Semantic roles.
val Bg = Color(0xFFF8EED4)
val Surface = Color(0xFFF0DFB4)
val TextInk = Color(0xFF241A08)
val Accent = Color(0xFFE5A81C)
val Accent2 = Color(0xFF6B4A20)

/** `color-mix(in srgb, #241a08 16%, transparent)` — the ink at 16% alpha. */
val Divider = TextInk.copy(alpha = 0.16f)

// Tonal ramps. Generated in OKLCH on one shared lightness scale, so the same step of any role
// matches the others in visual value.
val Neutral100 = Color(0xFFFBF5E8)
val Neutral200 = Color(0xFFF1E7D2)
val Neutral300 = Color(0xFFDED0B4)
val Neutral400 = Color(0xFFC2B190)
val Neutral500 = Color(0xFFA39272)
val Neutral600 = Color(0xFF847357)
val Neutral700 = Color(0xFF66573E)
val Neutral800 = Color(0xFF483C28)
val Neutral900 = Color(0xFF2B2317)

val Accent100 = Color(0xFFFFF8DE)
val Accent200 = Color(0xFFFFEEB0)
val Accent300 = Color(0xFFFFDC72)
val Accent400 = Color(0xFFF6C33C)
val Accent500 = Color(0xFFE5A81C)
val Accent600 = Color(0xFFC08900)
val Accent700 = Color(0xFF976A00)
val Accent800 = Color(0xFF6D4C02)
val Accent900 = Color(0xFF463104)

val Accent2100 = Color(0xFFF8ECE0)
val Accent2200 = Color(0xFFECD9C3)
val Accent2300 = Color(0xFFD8BC9C)
val Accent2400 = Color(0xFFBD9A71)
val Accent2500 = Color(0xFF9D7A4F)
val Accent2600 = Color(0xFF7F5D33)
val Accent2700 = Color(0xFF624521)
val Accent2800 = Color(0xFF452F16)
val Accent2900 = Color(0xFF2C1E0E)

/**
 * Shadow ink. The inline override does not touch `--shadow-*`, so these keep the design system's
 * own neutral-900 (#2e2b25) rather than the overridden one above.
 */
val ShadowInk = Color(0xFF2E2B25)

/** Scrim behind the bottom sheets: `color-mix(in srgb, var(--color-neutral-900) 45%, transparent)`. */
val SheetScrim = Neutral900.copy(alpha = 0.45f)

// Shop mode paints straight onto the dark ground with white alphas rather than ramp steps.
val ShopRow = Color.White.copy(alpha = 0.10f)
val ShopRowChecked = Color.White.copy(alpha = 0.05f)
val ShopTrack = Color.White.copy(alpha = 0.12f)
val ShopHairline = Color.White.copy(alpha = 0.10f)
val ShopBoxBorder = Color.White.copy(alpha = 0.35f)
