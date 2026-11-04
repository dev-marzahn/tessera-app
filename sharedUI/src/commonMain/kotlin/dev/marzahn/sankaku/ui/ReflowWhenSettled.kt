package dev.marzahn.sankaku.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lays the content out at the width it last settled at, scaled to fill the width it has now, and lays it out at the
 * new width once that has held for [settle]. For the staggered grid: it places posts in its columns afresh for every
 * width, so a live window resize reshuffled them on every pixel. Now they keep their places while the window is
 * dragged and reflow once when it stops. A change in height alone lays out at once, unscaled.
 */
fun Modifier.reflowWhenSettled(settle: Duration = 200.milliseconds): Modifier = composed {
  // The width the content is laid out at; null until the first measure, which uses its own width.
  var settled by remember { mutableStateOf<Int?>(null) }
  // Written by the measure below and read only by the effect, so a change doesn't measure again.
  val current = remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    snapshotFlow { current.intValue }.collectLatest { width ->
      if (width <= 0 || width == settled) return@collectLatest
      if (settled != null) delay(settle)
      settled = width
    }
  }
  layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
      val placeable = measurable.measure(constraints)
      return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    current.intValue = width
    val laidOut = settled ?: width
    if (laidOut == width) {
      val placeable = measurable.measure(constraints)
      return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val scale = width.toFloat() / laidOut
    val placeable = measurable.measure(Constraints.fixed(laidOut, (height / scale).roundToInt()))
    layout(width, height) {
      placeable.placeWithLayer(0, 0) {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0f, 0f)
      }
    }
  }
}
