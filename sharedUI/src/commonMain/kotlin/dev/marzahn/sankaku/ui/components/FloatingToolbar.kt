package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hides a floating piece ([hidesOnScroll]) while the content it's attached to ([connection], through `nestedScroll`)
 * scrolls down, and brings it back on scrolling up: M3 Expressive's floating toolbar behaviour
 * (`exitAlwaysScrollBehavior`), used on the count pill (#178), which isn't a toolbar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Stable
class HideOnScroll internal constructor(
  internal val behavior: FloatingToolbarScrollBehavior,
  private val scope: CoroutineScope,
) {
  val connection: NestedScrollConnection get() = behavior

  /**
   * How far down, in px, the piece has slid from its place to hide; 0 while it shows. A snapshot read. Subtracted
   * rather than negated, so a shown piece reads 0, not -0.
   */
  val hiddenBy: Float get() = 0f - behavior.state.offset

  /** The slide [show] started, if it's still running. */
  private var sliding: Job? = null

  /**
   * Slides the piece back in, as when the content under it changes (#300): on the spring it settles with after a
   * scroll, so it comes back with the same kind of motion it hides with rather than in one frame. It runs in the
   * piece's own scope, so the caller's effect restarting doesn't leave it halfway. A scroll or a drag that moves it
   * meanwhile takes over and stops the slide; a later [show] or [showAtOnce] replaces it.
   */
  fun show() {
    sliding?.cancel()
    val state = behavior.state
    state.contentOffset = 0f
    if (state.offset == 0f) return
    sliding = scope.launch {
      var placed = state.offset
      AnimationState(placed).animateTo(0f, behavior.snapAnimationSpec) {
        if (state.offset != placed) {
          cancelAnimation()
        } else {
          state.offset = value
          placed = state.offset
        }
      }
    }
  }

  /** Shows the piece in the same frame, for when it isn't on screen to be seen coming back. Stops a slide. */
  fun showAtOnce() {
    sliding?.cancel()
    behavior.state.offset = 0f
    behavior.state.contentOffset = 0f
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberHideOnScroll(): HideOnScroll {
  val behavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(FloatingToolbarExitDirection.Bottom)
  val scope = rememberCoroutineScope()
  return remember(behavior, scope) { HideOnScroll(behavior, scope) }
}

/**
 * Slides this piece down out of its parent while [hideOnScroll]'s content scrolls down, and back on scrolling up, as
 * M3's floating toolbar does with its scroll behaviour: it's hidden once it has gone past its parent's bottom, so give
 * the parent the room under it (the navigation bar's too). Like the toolbar, a drag down on it hides it too. Other
 * pieces follow it by [HideOnScroll.hiddenBy]. Null leaves it in place.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun Modifier.hidesOnScroll(hideOnScroll: HideOnScroll?): Modifier =
  if (hideOnScroll == null) this else with(hideOnScroll.behavior) { this@hidesOnScroll.floatingScrollBehavior() }
