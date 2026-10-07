package io.github.molleware.porygonlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.molleware.porygonlist.theme.Accent200
import io.github.molleware.porygonlist.theme.Accent300
import io.github.molleware.porygonlist.theme.Accent900
import io.github.molleware.porygonlist.theme.Neutral600
import io.github.molleware.porygonlist.theme.PorygonType
import io.github.molleware.porygonlist.theme.Shapes

/** The switch into and out of arranging a screen's rows. */
@Composable
fun EditOrderToggle(arranging: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Text(
    if (arranging) "Done" else "Edit order",
    style = PorygonType.Tiny.copy(fontSize = PorygonType.TabLabel.fontSize * 1.2),
    color = Accent900,
    modifier =
      modifier
        .clip(Shapes.Pill)
        .background(if (arranging) Accent300 else Accent200)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp),
  )
}

/** The grip a row is dragged by. [handle] is what [ReorderableColumn] hands each row. */
@Composable
fun DragHandle(handle: Modifier, contentDescription: String) {
  Box(Modifier.size(44.dp).then(handle), contentAlignment = Alignment.Center) {
    StrokeIcon(IconPaths.GRIP, contentDescription = contentDescription, size = 20.dp, strokeWidth = 3.4f, tint = Neutral600)
  }
}

/**
 * A column whose rows can be dragged into a new order by a handle.
 *
 * The row under your finger follows it, and the others swap past it as it crosses their midpoints,
 * so what you see while dragging is the order you will get. Nothing is written until you let go:
 * [onMove] is called once, with where the row ended up, rather than once per swap — each call is a
 * stamped edit that travels to the other phones, and a drag across six rows is one decision.
 *
 * Only the handle starts a drag. The rest of the row keeps its own taps and long-presses, and a
 * swipe anywhere else still scrolls the screen.
 *
 * The handle also carries "Move up" and "Move down" as accessibility actions, because a drag is
 * the one gesture a screen reader cannot perform.
 */
@Composable
fun <T> ReorderableColumn(
  items: List<T>,
  keyOf: (T) -> Any,
  onMove: (item: T, toIndex: Int) -> Unit,
  spacing: Dp,
  modifier: Modifier = Modifier,
  row: @Composable (item: T, handle: Modifier, dragging: Boolean) -> Unit,
) {
  val spacingPx = with(LocalDensity.current) { spacing.toPx() }
  val heights = remember { mutableStateMapOf<Any, Int>() }

  var draggingKey by remember { mutableStateOf<Any?>(null) }
  var offset by remember { mutableFloatStateOf(0f) }
  // The order on screen. Follows [items] except mid-drag, when it is the drag's working copy.
  var order by remember { mutableStateOf(items) }
  if (draggingKey == null && order != items) order = items
  val latestItems by rememberUpdatedState(items)
  val latestOnMove by rememberUpdatedState(onMove)

  fun finish() {
    val k = draggingKey ?: return
    val to = order.indexOfFirst { keyOf(it) == k }
    val from = latestItems.indexOfFirst { keyOf(it) == k }
    val item = order.getOrNull(to)
    draggingKey = null
    offset = 0f
    if (item != null && from >= 0 && to != from) latestOnMove(item, to)
  }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
    order.forEachIndexed { index, item ->
      val k = keyOf(item)
      // Keyed, so a row keeps its own composition — and the drag gesture running in it — as it
      // changes places. Positional slots would hand the gesture to whichever row moved into it.
      key(k) {
        val dragging = draggingKey == k

        val handle =
          Modifier.pointerInput(k) {
              detectDragGestures(
                onDragStart = {
                  draggingKey = k
                  offset = 0f
                },
                onDragEnd = { finish() },
                onDragCancel = { finish() },
                onDrag = { change, amount ->
                  change.consume()
                  offset += amount.y
                  val at = order.indexOfFirst { keyOf(it) == k }
                  // Past half of a neighbour, the two trade places and the offset carries on from
                  // the new slot, so the row stays under the finger.
                  val below = order.getOrNull(at + 1)
                  if (below != null) {
                    val step = (heights[keyOf(below)] ?: 0) + spacingPx
                    if (offset > step / 2) {
                      order = order.toMutableList().apply { add(at + 1, removeAt(at)) }
                      offset -= step
                    }
                  }
                  val above = order.getOrNull(at - 1)
                  if (above != null) {
                    val step = (heights[keyOf(above)] ?: 0) + spacingPx
                    if (offset < -step / 2) {
                      order = order.toMutableList().apply { add(at - 1, removeAt(at)) }
                      offset += step
                    }
                  }
                },
              )
            }
            .semantics {
              customActions =
                listOfNotNull(
                  if (index > 0) CustomAccessibilityAction("Move up") { latestOnMove(item, index - 1); true }
                  else null,
                  if (index < order.lastIndex) CustomAccessibilityAction("Move down") { latestOnMove(item, index + 1); true }
                  else null,
                )
            }

        Box(
          Modifier.onSizeChanged { heights[k] = it.height }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) offset else 0f }
        ) {
          row(item, handle, dragging)
        }
      }
    }
  }
}
