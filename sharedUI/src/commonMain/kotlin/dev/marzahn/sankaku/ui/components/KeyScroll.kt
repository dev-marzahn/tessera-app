package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How far a line key (W/S, ↑/↓) scrolls: the reader's strip and Settings (#423). */
val KeyScrollLine = 120.dp

/** How much of the viewport's height a screen key (Space, Page Down, Shift+W/S) scrolls, so a little stays in view. */
const val KEY_SCROLL_SCREEN = 0.85f

/**
 * The keys' scrolling of [state]: each step animates, and a key pressed while one runs adds to what's left of it rather
 * than cutting it short, so two presses of S go two lines.
 */
class KeyScroll(private val state: ScrollableState) {
  private var left = 0f
  private var job: Job? = null

  fun by(step: Float, scope: CoroutineScope) {
    left += step
    job?.cancel()
    job = scope.launch {
      val total = left
      var moved = 0f
      state.scroll {
        animate(0f, total) { value, _ ->
          scrollBy(value - moved)
          moved = value
          left = total - moved
        }
      }
      left = 0f
    }
  }
}
