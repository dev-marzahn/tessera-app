package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where a [FullScreenSearchBar] grows from and shrinks back into: the collapsed search line, marked with
 * [fullScreenSearchAnchor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class FullScreenSearchState internal constructor(internal val bar: SearchBarState) {
  /** Shrunk all the way back into the line (or never opened). */
  val isCollapsed: Boolean get() = bar.currentValue == SearchBarValue.Collapsed && !bar.isAnimating
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberFullScreenSearchState(): FullScreenSearchState {
  val bar = rememberSearchBarState()
  return remember(bar) { FullScreenSearchState(bar) }
}

/** Marks the collapsed search line that [state]'s bar grows from. */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.fullScreenSearchAnchor(state: FullScreenSearchState): Modifier =
  onGloballyPositioned { state.bar.collapsedCoords = it }

/**
 * M3's `ExpandedFullScreenSearchBar`: while [open], a full-screen dialog over the app that grows out of the anchored
 * search line, with [inputField] in the line's place (as tall as the line) and [content] under it, above the keyboard.
 * Back (and predictive back) shrinks it into the line and calls [onClose]; setting [open] false does the same without
 * the call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenSearchBar(
  state: FullScreenSearchState,
  open: Boolean,
  onClose: () -> Unit,
  inputField: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val bar = state.bar
  val isOpen by rememberUpdatedState(open)
  val close by rememberUpdatedState(onClose)
  LaunchedEffect(open) { if (open) bar.animateToExpanded() else bar.animateToCollapsed() }
  // The dialog collapses the bar itself on back; the owner learns of it so its `open` follows.
  LaunchedEffect(bar) {
    var previous = bar.targetValue
    snapshotFlow { bar.targetValue }.collect { target ->
      if (previous == SearchBarValue.Expanded && target == SearchBarValue.Collapsed && isOpen) close()
      previous = target
    }
  }
  ExpandedFullScreenSearchBar(state = bar, inputField = inputField, modifier = modifier, content = content)
}
