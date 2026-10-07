package io.github.molleware.porygonlist.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The design draws its own icons as stroked SVG paths rather than pulling a set, so these are the
 * path strings copied verbatim from `Porygonlist.dc.html` and rendered through Compose's own
 * [PathParser]. Keeping the source data means the shapes match the mockup exactly.
 *
 * Every path is authored in a 24x24 viewport.
 */
object IconPaths {
  /** The wifi arcs — used for the invite call-to-action, network rows and the Share tab. */
  const val WIFI = "M5 12.55a11 11 0 0 1 14 0 M8.5 16.4a6 6 0 0 1 7 0 M12 20h.01"

  /** Back chevron. */
  const val CHEVRON_LEFT = "M15 18l-6-6 6-6"

  /** The conflict card's stacked rules. */
  const val MERGE_LINES = "M8 6h10 M6 12h12 M8 18h10"

  /** Tick, for a checked item. */
  const val CHECK = "M20 6L9 17l-5-5"

  /** Edit an item. */
  const val PENCIL = "M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"

  /** Add. */
  const val PLUS = "M12 5v14 M5 12h14"

  /** Fewer, in the quantity stepper. */
  const val MINUS = "M5 12h14"

  /** The drag handle: two columns of three dots, drawn as round-capped dabs. */
  const val GRIP = "M9 6h.01 M15 6h.01 M9 12h.01 M15 12h.01 M9 18h.01 M15 18h.01"

  /** A pushpin, for keeping a list at the top. */
  const val PIN = "M12 17v5 M8 3h8 M9 3v7l-3 4v3h12v-3l-3-4V3"

  // Bottom bar.
  const val TAB_LISTS = "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"
  const val TAB_SHOP = "M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4zM3 6h18M16 10a4 4 0 0 1-8 0"
  const val TAB_PANTRY = "M4 4h16v6H4zM4 14h16v6H4zM9 7h.01M9 17h.01"
  const val TAB_INVITE = WIFI
}

/**
 * Draws one of [IconPaths] as a stroked outline.
 *
 * [strokeWidth] is given in the path's own 24-unit viewport — the same number the SVG carries — so
 * the stroke scales with [size] exactly as it does in the browser.
 */
@Composable
fun StrokeIcon(
  pathData: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  size: Dp = 24.dp,
  strokeWidth: Float = 2.75f,
  tint: Color = LocalContentColor.current,
) {
  val path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
  val semantics =
    if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }

  Canvas(modifier.size(size).then(semantics)) {
    val scale = this.size.minDimension / VIEWPORT
    withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
      drawPath(
        path = path,
        color = tint,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }
  }
}

private const val VIEWPORT = 24f
