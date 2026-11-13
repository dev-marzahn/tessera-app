package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Whether the user is scrolling a grid back up, more than a screen away from its top: what shows ↑ Top. The grid's
 * own scrolls go through [quietly] and count as neither up nor down, so a closing viewer that brings its cell back
 * into view never shows ↑ Top, or hides it, by itself (#299).
 */
@Stable
internal class ScrollUpWatch {
  var backUp by mutableStateOf(false)
    private set

  private var quiet by mutableIntStateOf(0)

  /**
   * Runs [block], the app's own scroll: whatever it moves is not the user scrolling. [watch] judges a move by the
   * count when it next reads the position, a frame or so after the move, so [block] must end with a frame after its
   * last move. A user scroll that cancels [block] gets that frame here, or the move [block] made would read as theirs.
   * That user scroll moves the grid in the frame too, and that frame's move is read as ours: a swipe up ending within
   * it shows no ↑ Top. Any move after it counts as theirs again.
   */
  suspend fun quietly(block: suspend () -> Unit) {
    quiet++
    try {
      block()
    } catch (e: CancellationException) {
      withContext(NonCancellable) { withFrameNanos {} }
      throw e
    } finally {
      quiet--
    }
  }

  /** Follows [state]'s scroll for as long as it's called. */
  suspend fun watch(state: LazyStaggeredGridState) {
    var lastIndex = 0
    var lastOffset = 0
    snapshotFlow { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, quiet > 0) }.collect { (index, offset, ours) ->
      val up = index < lastIndex || (index == lastIndex && offset < lastOffset)
      val down = index > lastIndex || (index == lastIndex && offset > lastOffset)
      when {
        index < 10 -> backUp = false
        ours -> Unit
        up -> backUp = true
        down -> backUp = false
      }
      lastIndex = index
      lastOffset = offset
    }
  }
}

/** A [ScrollUpWatch] following [state]. */
@Composable
internal fun rememberScrollUpWatch(state: LazyStaggeredGridState): ScrollUpWatch {
  val watch = remember { ScrollUpWatch() }
  LaunchedEffect(state, watch) { watch.watch(state) }
  return watch
}

/**
 * The closing viewer's scroll: the least that shows the whole cell at [index], quietly for [scrollUp]. A cell the
 * scroll just composed has no bounds yet, so a scroll waits two frames before returning; closing at once would snap
 * the card into it. Those frames are inside the quiet scroll too: they are what lets [ScrollUpWatch] see the move while
 * it still counts as ours.
 */
internal suspend fun LazyStaggeredGridState.scrollBackForClose(index: Int, scrollUp: ScrollUpWatch) {
  scrollUp.quietly {
    if (scrollFullyIntoView(index)) repeat(2) { withFrameNanos {} }
  }
}
