package io.github.molleware.porygonlist.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import io.github.molleware.porygonlist.data.crypto.QrCode

/**
 * Encodes [content] once and keeps the result across recompositions.
 *
 * Null when the text is too long for the encoder, which the caller has to handle — on the pairing
 * screen that means falling back to the invite as text, which is on screen regardless.
 *
 * Keyed on the string rather than on a [QrCode]: the encoder's result has no value equality, so
 * remembering it by identity would re-encode on every recomposition.
 */
@Composable
fun rememberQrCode(content: String): QrCode? = remember(content) { QrCode.encode(content) }

/**
 * Draws a QR code, one module per pixel, scaled up without smoothing.
 *
 * The bitmap is built at the symbol's natural size and stretched by the GPU rather than being
 * drawn module by module. A version-7 symbol is 45×45, so a `drawRect` per module would be two
 * thousand draw calls every frame; this is one, and [FilterQuality.None] is what keeps the edges
 * hard instead of blurring the modules into each other and making the thing unreadable.
 */
@Composable
fun QrCodeImage(
  code: QrCode,
  contentDescription: String,
  modifier: Modifier = Modifier,
  dark: Color = Color.Black,
  light: Color = Color.White,
) {
  val image =
    remember(code, dark, light) {
      // The quiet zone is part of the symbol, not decoration: readers need four clear modules of
      // margin to find the edge. Baking it into the bitmap means no caller can crop it off by
      // putting the code flush against a card.
      val quiet = 4
      val dimension = code.size + quiet * 2
      val darkArgb = dark.toArgb()
      val pixels = IntArray(dimension * dimension) { light.toArgb() }

      for (y in 0 until code.size) {
        for (x in 0 until code.size) {
          if (code[x, y]) pixels[(y + quiet) * dimension + (x + quiet)] = darkArgb
        }
      }
      Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

  Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
    // Snapped to a whole number of pixels per module, so every module is the same size. Left to
    // fractional scaling, some rows land a pixel wider than their neighbours and the symbol gets
    // visibly uneven at the sizes a phone screen shows it at.
    val scale = (minOf(size.width, size.height) / image.width).toInt().coerceAtLeast(1)
    val drawn = image.width * scale
    drawImage(
      image = image,
      dstOffset = centreOffset(drawn),
      dstSize = IntSize(drawn, drawn),
      filterQuality = FilterQuality.None,
    )
  }
}

private fun DrawScope.centreOffset(drawn: Int) =
  androidx.compose.ui.unit.IntOffset(
    x = ((size.width - drawn) / 2).toInt(),
    y = ((size.height - drawn) / 2).toInt(),
  )
