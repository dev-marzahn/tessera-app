package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ModalWideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive's modal wide navigation rail, hidden while closed: it slides in over the whole window with a scrim,
 * and closes with a swipe towards its edge, back (Esc on desktop) or a click on the scrim, each of which calls
 * [onClose]. [content] is one column the rail's full height, so it can push a footer down with a weighted spacer.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModalNavigationRail(
  open: Boolean,
  onClose: () -> Unit,
  containerColor: Color,
  content: @Composable ColumnScope.() -> Unit,
) {
  val state = rememberWideNavigationRailState(if (open) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed)
  val close by rememberUpdatedState(onClose)
  // A new one for each opening: a rail closed by back or an entry leaves the last one believing it's open.
  val leaving = remember(open) { LeaveWatch() }
  LaunchedEffect(open) { if (open) state.expand() else state.collapse() }
  // The rail collapses itself on a swipe, back or the scrim; that is reported, so [open] follows. A collapse asked
  // for through [open] reports nothing new, since the target is then already collapsed. The first value is the
  // starting state, not a change.
  LaunchedEffect(state) {
    snapshotFlow { state.targetValue == WideNavigationRailValue.Expanded }.drop(1).collect { expanded ->
      if (!expanded) close()
    }
  }
  ModalWideNavigationRail(
    state = state,
    hideOnCollapse = true,
    colors = WideNavigationRailDefaults.colors(modalContainerColor = containerColor),
    // The column below takes the whole height, so the rail adds no padding of its own above it.
    contentPadding = PaddingValues(0.dp),
    // On the whole rail, its status bar strip included, where a drag can start too.
    modifier = Modifier.pointerInput(leaving) {
      awaitPointerEventScope {
        while (true) leaving.pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
      }
    },
  ) {
    // The rail opens in a dialog of its own, where nothing has focus, so Esc (which the rail handles) would go
    // unheard. Focus starts on the column; Tab moves on to the entries from there.
    val focus = remember { FocusRequester() }
    // The rail pads for the status bar only; its foot stays clear of the gesture bar too.
    Column(
      Modifier
        .width(RailWidth)
        .fillMaxHeight()
        .onGloballyPositioned { coordinates ->
          val left = coordinates.localToWindow(Offset.Zero).x
          val right = coordinates.localToWindow(Offset(coordinates.size.width.toFloat(), 0f)).x
          val settled = state.currentValue == WideNavigationRailValue.Expanded
          if (leaving.startsLeaving(left, scaled = abs(right - left - coordinates.size.width) > 1f, settled)) close()
        }
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
        .focusRequester(focus)
        .focusable(),
      content = content,
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
  }
}

/**
 * Tells when an open rail starts to slide out by itself. A click on the scrim slides the rail out first and tells its
 * state only once that slide has settled, about half a second after the rail has left the screen on a phone, and the
 * menu button's ✕ waited that long. So the close counts from the first frame the rail moves out with no finger on it:
 * the scrim's slide, or a swipe's fling once the finger lifts. While a finger drags it, it may still come back.
 * Left to right only: the app has no right-to-left layout, where the rail would leave to the right.
 */
internal class LeaveWatch {
  /** Whether a finger is down on the rail. */
  var pressed = false
  private var settledOpen = false
  private var lastLeft = 0f

  /**
   * Takes the rail's left edge in the window, whether it's drawn [scaled] and whether its state has [settled] open;
   * true once, on the frame it starts to leave. It counts only after the rail was wholly on screen: an opening rail
   * moves in from off screen. Leaving means moving left past its open place (the opening spring overshoots to the
   * right and comes back) at its own size: a predictive back gesture scales it, which moves its left edge without a
   * slide, and the gesture can still be cancelled.
   */
  fun startsLeaving(left: Float, scaled: Boolean, settled: Boolean): Boolean {
    val starts = settledOpen && !pressed && !scaled && left < lastLeft && left < -SLACK
    if (settled && left >= 0f) settledOpen = true
    if (starts) settledOpen = false
    lastLeft = left
    return starts
  }

  private companion object {
    /** Pixels the rail may sit left of its open place and still count as there: what a settling spring leaves. */
    const val SLACK = 1f
  }
}

private val RailWidth = 280.dp
