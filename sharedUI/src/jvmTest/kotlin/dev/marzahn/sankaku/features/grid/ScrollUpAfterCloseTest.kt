package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #299: ↑ Top follows the user scrolling back up, not the grid's own scroll as the viewer closes. A three-column grid
 * of 400 fixed cells, the viewer's close as [scrollBackForClose] (what `closeViewer` runs), and the user's scrolls as
 * real swipes.
 */
@OptIn(ExperimentalTestApi::class)
class ScrollUpAfterCloseTest {
  private class Grid(val state: LazyStaggeredGridState, val watch: ScrollUpWatch, val scope: CoroutineScope)

  private fun ComposeUiTest.grid(): Grid {
    lateinit var grid: Grid
    setContent {
      val state = rememberLazyStaggeredGridState()
      val watch = rememberScrollUpWatch(state)
      val scope = rememberCoroutineScope()
      grid = Grid(state, watch, scope)
      LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(3), state = state, modifier = Modifier.fillMaxSize().testTag(GRID)) {
        items(400) { Box(Modifier.height(120.dp)) }
      }
    }
    waitForIdle()
    return grid
  }

  /** The user scrolls far down, as with a fling: a jump, then a swipe to be sure the last move was down. */
  private fun ComposeUiTest.userScrollsFarDown(grid: Grid) {
    grid.scope.launch { grid.state.scrollToItem(150) }
    waitForIdle()
    onNodeWithTag(GRID).performTouchInput { swipeUp() }
    waitForIdle()
  }

  private fun ComposeUiTest.closeOnto(grid: Grid, index: Int) {
    grid.scope.launch { grid.state.scrollBackForClose(index, grid.watch) }
    waitForIdle()
  }

  @Test
  fun aCloseThatScrollsUpDoesNotShowTop() = runDesktopComposeUiTest(width = 400, height = 800) {
    val grid = grid()
    userScrollsFarDown(grid)
    assertFalse(grid.watch.backUp, "scrolled down: no Top yet")
    val before = grid.state.firstVisibleItemIndex
    // Paged back about 10 posts' worth of rows in the viewer, then closed.
    closeOnto(grid, before - 60)
    assertTrue(grid.state.firstVisibleItemIndex < before, "the close scrolled the grid up")
    assertFalse(grid.watch.backUp, "the close's own scroll up shows Top")
  }

  @Test
  fun aCloseThatScrollsDownDoesNotShowTopEither() = runDesktopComposeUiTest(width = 400, height = 800) {
    val grid = grid()
    userScrollsFarDown(grid)
    val before = grid.state.firstVisibleItemIndex
    closeOnto(grid, before + 90)
    assertTrue(grid.state.firstVisibleItemIndex > before, "the close scrolled the grid down")
    assertFalse(grid.watch.backUp)
  }

  @Test
  fun aCloseOntoACellInViewMovesNothing() = runDesktopComposeUiTest(width = 400, height = 800) {
    val grid = grid()
    userScrollsFarDown(grid)
    val before = grid.state.firstVisibleItemIndex to grid.state.firstVisibleItemScrollOffset
    closeOnto(grid, grid.state.layoutInfo.visibleItemsInfo.first { it.offset.y > 0 }.index)
    assertEquals(before, grid.state.firstVisibleItemIndex to grid.state.firstVisibleItemScrollOffset)
    assertFalse(grid.watch.backUp)
  }

  @Test
  fun aRealScrollUpAfterTheCloseStillShowsTop() = runDesktopComposeUiTest(width = 400, height = 800) {
    val grid = grid()
    userScrollsFarDown(grid)
    closeOnto(grid, grid.state.firstVisibleItemIndex - 60)
    assertFalse(grid.watch.backUp)
    onNodeWithTag(GRID).performTouchInput { swipeDown() }
    waitForIdle()
    assertTrue(grid.watch.backUp, "the user's scroll up after the close shows Top (#261)")
    // And a scroll down hides it again, as before.
    onNodeWithTag(GRID).performTouchInput { swipeUp() }
    waitForIdle()
    assertFalse(grid.watch.backUp)
  }

  @Test
  fun aTopShownBeforeTheViewerOpenedStaysThroughTheClose() = runDesktopComposeUiTest(width = 400, height = 800) {
    val grid = grid()
    userScrollsFarDown(grid)
    onNodeWithTag(GRID).performTouchInput { swipeDown() }
    waitForIdle()
    assertTrue(grid.watch.backUp)
    closeOnto(grid, grid.state.firstVisibleItemIndex + 90)
    assertTrue(grid.watch.backUp, "the close's own scroll down hid the user's Top")
  }

  /**
   * A user scroll cancels the close's scroll right after its jump, before a frame has passed: the jump is still the
   * app's, so it shows no ↑ Top. The scroll here cancels itself after its jump, as a user scroll would cancel it.
   */
  @Test
  fun aCloseCancelledAfterItsJumpDoesNotShowTop() = runDesktopComposeUiTest(width = 400, height = 800) {
    val grid = grid()
    userScrollsFarDown(grid)
    val target = grid.state.firstVisibleItemIndex - 60
    grid.scope.launch {
      grid.watch.quietly {
        grid.state.scrollToItem(target)
        coroutineContext.cancel()
        yield()
      }
    }
    waitForIdle()
    assertEquals(target, grid.state.firstVisibleItemIndex, "the jump happened")
    assertFalse(grid.watch.backUp, "a cancelled close's jump shows Top")
  }

  /** The bug the quiet scroll fixes: the same scroll, not marked as the app's own, read as the user scrolling up. */
  @Test
  fun theSameScrollUnmarkedWouldShowTop() = runDesktopComposeUiTest(width = 400, height = 800) {
    val grid = grid()
    userScrollsFarDown(grid)
    grid.scope.launch { grid.state.scrollFullyIntoView(grid.state.firstVisibleItemIndex - 60) }
    waitForIdle()
    assertTrue(grid.watch.backUp)
  }

  private companion object {
    const val GRID = "grid"
  }
}
