package io.github.molleware.porygonlist.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type from the handoff: Caprasimo for headings, Figtree for body.
 *
 * Both are SIL OFL 1.1, so they can be bundled in an F-Droid build — but the font provider route
 * (`androidx.compose.ui.text.googlefonts`) cannot, since it needs Google Play Services.
 *
 * The TTFs are not in the repo yet. Run `./scripts/fetch-fonts.sh` to pull them into
 * `app/src/main/res/font/`, then swap the two families below to the bundled versions:
 *
 *     val HeadingFont = FontFamily(Font(R.font.caprasimo_regular, FontWeight.Normal))
 *     val BodyFont = FontFamily(
 *       Font(R.font.figtree_regular, FontWeight.Normal),
 *       Font(R.font.figtree_semibold, FontWeight.SemiBold),
 *       Font(R.font.figtree_bold, FontWeight.Bold),
 *     )
 *
 * Until then both fall back to the system face. Everything else about the design is unaffected;
 * only the lettering differs.
 */
val HeadingFont: FontFamily = FontFamily.Default

val BodyFont: FontFamily = FontFamily.Default

// Headings, per the design system: line-height 1.12, letter-spacing -0.015em, weight 400.
private fun heading(sizeSp: Double) =
  TextStyle(
    fontFamily = HeadingFont,
    fontWeight = FontWeight.Normal,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.12).sp,
    letterSpacing = (-0.015).em,
  )

private fun body(sizeSp: Double, weight: FontWeight = FontWeight.Normal, lineHeight: Double = 1.55) =
  TextStyle(fontFamily = BodyFont, fontWeight = weight, fontSize = sizeSp.sp, lineHeight = (sizeSp * lineHeight).sp)

/**
 * The design's own scale. The sizes are the literal values in `Porygonlist.dc.html` — it overrides
 * the design system's h2/h4 defaults per screen, so these are named for their use, not for `h2`.
 */
object PorygonType {
  /** The "porygonlist" wordmark on the lists screen. */
  val Wordmark = heading(29.0)

  /** Every other screen title: list name, "In the shop", "Staples", "Sharing". */
  val ScreenTitle = heading(27.0)

  /** Bottom-sheet titles. */
  val SheetTitle = heading(19.0)

  /** The "Send … as a text" card heading. */
  val CardHeading = heading(16.0)

  /** A list's name on its card. Tighter leading than the scale: the design pins line-height 1.1. */
  val ListName = heading(18.0).copy(lineHeight = (18 * 1.1).sp)

  /** The invite call-to-action title, and the conflict-card title. */
  val InlineHeading = heading(15.0)

  /** The quantity readout in the edit sheet. */
  val QtyValue = heading(20.0)

  /** `h6` — small uppercase section labels. */
  val SectionLabel =
    TextStyle(
      fontFamily = HeadingFont,
      fontWeight = FontWeight.Normal,
      fontSize = 13.sp,
      lineHeight = (13 * 1.12).sp,
      letterSpacing = 0.08.em,
    )

  /** An item's name in the list detail. */
  val ItemName = body(15.5, FontWeight.SemiBold, lineHeight = 1.3)

  /** An item's name in shopping mode — bigger, for reading at arm's length. */
  val ShopItemName = body(18.0, FontWeight.SemiBold, lineHeight = 1.3)

  /** Network and person names on the sharing screen. */
  val RowName = body(14.5, FontWeight.SemiBold, lineHeight = 1.3)

  /** Default prose, and text-field contents. */
  val Body = body(15.0)

  /** The paragraph under "Sharing". */
  val BodyLarge = body(13.5)

  /** Secondary text: card meta, sync-pill labels, sheet body copy. */
  val Meta = body(12.5)

  /** Bolded variant used for the sync pill's headline. */
  val MetaBold = body(12.5, FontWeight.Bold)

  /** Back links — "All lists", "Leave shopping mode". */
  val BackLink = body(13.0, FontWeight.Bold)

  /** Sub-labels under an item, network details, fine print. */
  val Fine = body(11.5, lineHeight = 1.5)

  /** The "editing" chip and staple tile captions. */
  val Tiny = body(10.5, FontWeight.Bold)

  /** Bottom-bar labels. */
  val TabLabel = body(10.0, FontWeight.Bold).copy(letterSpacing = 0.02.em)
}

/** Applied when an item is ticked off. */
val StrikeThrough = TextDecoration.LineThrough

/**
 * Handed to [androidx.compose.material3.MaterialTheme] so stray Material components inherit the
 * right faces. The screens style themselves from [PorygonType] directly.
 */
val Typography =
  Typography(
    displayLarge = PorygonType.Wordmark,
    headlineLarge = PorygonType.ScreenTitle,
    headlineSmall = PorygonType.SheetTitle,
    titleMedium = PorygonType.ListName,
    titleSmall = PorygonType.RowName,
    bodyLarge = PorygonType.Body,
    bodyMedium = PorygonType.Meta,
    bodySmall = PorygonType.Fine,
    labelSmall = PorygonType.TabLabel,
  )
