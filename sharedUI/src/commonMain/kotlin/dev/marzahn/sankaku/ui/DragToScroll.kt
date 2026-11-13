package dev.marzahn.sankaku.ui

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A mouse drag scrolls [state] vertically, as a finger does on a touch screen. Nothing happens until the pointer
 * has moved past the touch slop, so a click still reaches what's under it; past it, the drag is taken (the click
 * underneath doesn't fire on release) and the release flings. Touch already scrolls a scrollable, so only mouse
 * drags are handled, on desktop and on an Android device with a mouse alike.
 */
fun Modifier.dragToScroll(state: ScrollableState): Modifier = composed {
  val scope = rememberCoroutineScope()
  val fling by rememberUpdatedState(ScrollableDefaults.flingBehavior())
  pointerInput(state) {
    awaitEachGesture {
      // The initial pass reaches this before the children, so past the slop the drag is taken before a cell sees it.
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
      val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
      var dragging = false
      while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
          if (dragging) {
            change.consume()
            val velocity = tracker.calculateVelocity().y
            scope.launch { state.scroll { with(fling) { performFling(-velocity) } } }
          }
          break
        }
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!dragging && abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
          dragging = true
          // A fling still running from the last drag stops, as under a finger.
          scope.launch { state.stopScroll() }
        }
        if (dragging) {
          state.dispatchRawDelta(-(change.position.y - change.previousPosition.y))
          change.consume()
        }
      }
    }
  }
}
