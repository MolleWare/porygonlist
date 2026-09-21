package io.github.molleware.porygonlist.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import io.github.molleware.porygonlist.theme.Accent2300
import io.github.molleware.porygonlist.theme.Accent2400
import io.github.molleware.porygonlist.theme.Accent300
import io.github.molleware.porygonlist.theme.Accent400
import io.github.molleware.porygonlist.theme.Accent700
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** How long the whole thing lasts. Long enough to read as a flourish, short enough to not be in the way. */
private const val DURATION_MS = 1500

/**
 * How many pieces.
 *
 * Every one is a rotated rectangle on a single canvas, so the cost is a few dozen draw ops per
 * frame and no recomposition at all. The number is a taste decision rather than a budget one.
 */
private const val PIECES = 30

/** The app's own colours. Confetti in colours the app never uses would look like someone else's. */
private val COLOURS = listOf(Accent400, Accent300, Accent700, Accent2300, Accent2400)

/** One piece: where it starts, where it is going, and how it spins. All in fractions of the canvas. */
private class Piece(
  val startX: Float,
  val startY: Float,
  val velocityX: Float,
  val velocityY: Float,
  val spin: Float,
  val phase: Float,
  val width: Float,
  val height: Float,
  val colour: Color,
)

private fun pieces(random: Random) =
  List(PIECES) {
    // Fired up and outward from just below the middle, in a fan rather than a full circle: a burst
    // that starts by going downward reads as dropping something, not as celebrating.
    val angle = (-PI / 2 + (random.nextFloat() - 0.5f) * (PI * 0.9)).toFloat()
    val speed = 0.9f + random.nextFloat() * 0.7f
    Piece(
      startX = 0.5f + (random.nextFloat() - 0.5f) * 0.22f,
      startY = 0.52f + (random.nextFloat() - 0.5f) * 0.08f,
      velocityX = cos(angle) * speed,
      velocityY = sin(angle) * speed,
      spin = (random.nextFloat() - 0.5f) * 1440f,
      phase = random.nextFloat() * 360f,
      width = 0.014f + random.nextFloat() * 0.012f,
      height = 0.007f + random.nextFloat() * 0.007f,
      colour = COLOURS[random.nextInt(COLOURS.size)],
    )
  }

/**
 * A one-off burst, for finishing a list.
 *
 * Draws itself and then calls [onFinished], which is how it leaves: the caller stops composing it,
 * and from that moment it costs nothing. There is no looping animation and nothing left running.
 *
 * The progress value is read **inside the draw lambda**, so each frame invalidates drawing only.
 * Reading it in composition instead would recompose this subtree sixty times a second for a second
 * and a half, which is the version of this that would show up in a frame timing.
 *
 * Honours the system "remove animations" setting by finishing immediately without drawing. Someone
 * who has turned animations off system-wide has said what they want, and a surprise full-screen
 * burst is exactly what they turned off.
 */
@Composable
fun Confetti(onFinished: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val animationsOff =
    remember(context) {
      // Read once, not per frame: it is a content provider call behind a cheap-looking getter.
      runCatching {
          Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
        .getOrDefault(false)
    }

  if (animationsOff) {
    LaunchedEffect(Unit) { onFinished() }
    return
  }

  val progress = remember { Animatable(0f) }
  // Seeded per burst so two in a row are not identical, and stable across recomposition so the
  // pieces do not jump if something above this recomposes mid-flight.
  val pieces = remember { pieces(Random(System.nanoTime())) }

  LaunchedEffect(Unit) {
    progress.animateTo(1f, tween(durationMillis = DURATION_MS, easing = LinearEasing))
    onFinished()
  }

  Canvas(modifier.fillMaxSize()) {
    val t = progress.value
    // Fades over the last third rather than vanishing mid-air.
    val alpha = 1f - ((t - 0.66f) / 0.34f).coerceIn(0f, 1f)
    if (alpha <= 0f) return@Canvas

    pieces.forEach { piece ->
      // Position under gravity. Constants are in fractions of the canvas per unit of progress, so
      // the burst has the same shape on any screen.
      val x = (piece.startX + piece.velocityX * t * 0.45f) * size.width
      val y = (piece.startY + piece.velocityY * t * 0.45f + 1.15f * t * t * 0.5f) * size.height

      val w = piece.width * size.width
      val h = piece.height * size.width
      rotate(degrees = piece.phase + piece.spin * t, pivot = Offset(x, y)) {
        drawRect(
          color = piece.colour,
          topLeft = Offset(x - w / 2f, y - h / 2f),
          size = Size(w, h),
          alpha = alpha,
        )
      }
    }
  }
}
