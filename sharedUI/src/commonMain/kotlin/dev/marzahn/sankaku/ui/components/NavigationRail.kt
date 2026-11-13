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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive's modal wide navigation rail, hidden while closed: it slides in over the whole window with a scrim,
 * and closes with a swipe towards its edge, back (Esc on desktop) or a click on the scrim, each of which calls
 * [onClose]. [content] is one column the rail's full height, so it can push a footer down with a weighted spacer.
 *
 * Keys (#416): W/S (and ↑/↓) move focus to the entry above or below, and plain [closeKey] closes it through
 * [onClosedByKey], unless [closeKeyHeld]: the caller tracks whether that key is still down from opening or closing the
 * rail, so a held key doesn't open and close it by turns. [onCloseKeyUp] tells of the key's release when the rail
 * hears it, as it does while it opens or slides out. Focus starts on the column, unless [focusColumn] is false because an entry takes it; it comes back to the column when the
 * focused entry goes away (Sign out's question, answered), so the keys still reach the rail.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModalNavigationRail(
  open: Boolean,
  onClose: () -> Unit,
  containerColor: Color,
  closeKey: Key? = null,
  focusColumn: Boolean = true,
  closeKeyHeld: Boolean = false,
  onClosedByKey: () -> Unit = onClose,
  onCloseKeyUp: () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  val state = rememberWideNavigationRailState(if (open) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed)
  val close by rememberUpdatedState(onClose)
  val closeByKey by rememberUpdatedState(onClosedByKey)
  val closeKeyUp by rememberUpdatedState(onCloseKeyUp)
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
    // The rail's own focus manager: the rail is in a dialog, whose focus is apart from the window's.
    val focusManager = LocalFocusManager.current
    var columnFocused by remember { mutableStateOf(false) }
    var hadFocus by remember(open) { mutableStateOf(false) }
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
        .onPreviewKeyEvent { event ->
          if (event.key == closeKey && event.type == KeyEventType.KeyUp) closeKeyUp()
          railKey(
            event,
            closeKey.takeIf { !closeKeyHeld },
            columnFocused,
            onMove = { focusManager.moveFocus(it) },
            onClose = closeByKey,
          )
        }
        // Focus that leaves the rail's tree while it's open (its entry was removed) comes back to the column. Only
        // once the rail has had focus: on attach nothing has it yet, and an entry may be about to take it.
        .onFocusChanged {
          columnFocused = it.isFocused
          if (it.hasFocus) hadFocus = true
          if (!it.hasFocus && hadFocus && state.targetValue == WideNavigationRailValue.Expanded) focus.requestFocus()
        }
        .focusRequester(focus)
        .focusable(),
      content = content,
    )
    LaunchedEffect(Unit) { if (focusColumn) focus.requestFocus() }
  }
}

/**
 * The rail's own keys, before its entries see them: W/S (↑/↓) move to the entry above or below, from the column
 * itself into its first entry, and plain [closeKey] closes the rail. Letter keys with Cmd, Ctrl or Alt are left alone.
 */
internal fun railKey(
  event: KeyEvent,
  closeKey: Key?,
  columnFocused: Boolean,
  onMove: (FocusDirection) -> Unit,
  onClose: () -> Unit,
): Boolean {
  if (event.type != KeyEventType.KeyDown || event.isCommand || event.isAltPressed) return false
  val key = event.key
  return when {
    Shortcuts.Up.matches(key) || Shortcuts.Down.matches(key) -> true.also {
      onMove(
        when {
          columnFocused -> FocusDirection.Enter
          Shortcuts.Up.matches(key) -> FocusDirection.Up
          else -> FocusDirection.Down
        },
      )
    }
    key == closeKey && event.isPlain -> true.also { onClose() }
    else -> false
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
