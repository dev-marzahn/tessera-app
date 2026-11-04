package dev.marzahn.sankaku

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GridScrollbarTest {
  /** Two lanes of 100 px posts, the left lane's index even, the right's odd, the first row at [top]. */
  private fun twoLanes(top: Int, rows: Int) = (0 until rows * 2).map { i ->
    val row = i / 2
    VisiblePost(index = i, lane = i % 2, top = top + row * 100, bottom = top + row * 100 + 100)
  }

  @Test
  fun atTheTopTheOffsetIsZeroAndTheViewportCountsWholeRows() {
    val rows = postRows(twoLanes(top = 0, rows = 3), viewStart = 0, viewEnd = 300, postCount = 20)

    assertEquals(0.0, rows.offset, 1e-9)
    assertEquals(3.0, rows.viewport, 1e-9)
    assertEquals(10.0, rows.content, 1e-9)
    assertEquals(2, rows.lanes)
  }

  @Test
  fun scrollingHalfAPostMovesTheOffsetHalfARow() {
    val rows = postRows(twoLanes(top = -50, rows = 4), viewStart = 0, viewEnd = 300, postCount = 20)

    // Posts 0 and 1, the first row, are half scrolled past.
    assertEquals(0.5, rows.offset, 1e-9)
    // Half of row 0, rows 1 and 2, half of row 3.
    assertEquals(3.0, rows.viewport, 1e-9)
  }

  @Test
  fun theFooterIsNotAPost() {
    val footer = VisiblePost(index = 20, lane = 0, top = 300, bottom = 360)

    val rows = postRows(twoLanes(top = 0, rows = 3) + footer, viewStart = 0, viewEnd = 400, postCount = 20)

    assertEquals(3.0, rows.viewport, 1e-9)
  }

  @Test
  fun rowsAboveCountWholeAndTheCrossingRowByItsFraction() {
    // Row 0 is above the view, row 1 (posts 2 and 3) half scrolled past, row 2 in view.
    val rows = postRows(twoLanes(top = -150, rows = 3), viewStart = 0, viewEnd = 300, postCount = 20)

    assertEquals(1.5, rows.offset, 1e-9)
    assertEquals(1.5, rows.viewport, 1e-9)
  }

  @Test
  fun theOffsetNeverGoesBackWhileTheViewMovesDown() {
    // Two lanes of uneven posts with 8 px gaps, placed as a staggered grid places them: each in the shorter lane.
    val heights = listOf(100, 300, 120, 80, 200, 60, 150, 90, 110, 240, 70, 130)
    val laneBottoms = intArrayOf(0, 0)
    val layout = heights.mapIndexed { i, height ->
      val lane = if (laneBottoms[0] <= laneBottoms[1]) 0 else 1
      val top = laneBottoms[lane] + if (laneBottoms[lane] > 0) 8 else 0
      laneBottoms[lane] = top + height
      VisiblePost(i, lane, top, top + height)
    }

    var previous = 0.0
    for (scrolled in 0..600) {
      // What the grid reports: the posts in view, in coordinates relative to the view's top.
      val visible = layout.filter { it.bottom > scrolled && it.top < scrolled + 300 }
        .map { it.copy(top = it.top - scrolled, bottom = it.bottom - scrolled) }
      val offset = postRows(visible, viewStart = 0, viewEnd = 300, postCount = heights.size).offset
      assertTrue(offset >= previous - 1e-9, "offset went back from $previous to $offset at $scrolled px")
      previous = offset
    }
  }

  private val grid = LazyStaggeredGridState()
  private lateinit var scope: CoroutineScope

  /** Three lanes of posts of uneven heights, so a row's first post isn't at the top of every lane. */
  private fun ComposeUiTest.showGrid() = setContent {
    scope = rememberCoroutineScope()
    Box(Modifier.size(400.dp, 400.dp)) {
      LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(3),
        state = grid,
        verticalItemSpacing = 8.dp,
        modifier = Modifier.fillMaxSize(),
      ) {
        items(POSTS) { i -> Box(Modifier.fillMaxWidth().height((60 + (i * 37) % 90).dp)) }
      }
      GridScrollbar(grid, POSTS, Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("scrollbar"))
    }
  }

  @Test
  fun scrollingToAnOffsetLandsOnIt() = runComposeUiTest {
    showGrid()
    val adapter = PostIndexScrollbarAdapter(grid) { POSTS }

    // In rows × 1,000, the adapter's unit: 30 posts in 3 lanes are 10 rows, about 3.5 of them in view.
    for (target in listOf(1_300.0, 2_700.0, 2_750.0, 5_000.0, 4_321.0, 500.0)) {
      runOnIdle { scope.launch { adapter.scrollTo(target) } }
      waitForIdle()
      assertEquals(target, adapter.scrollOffset, 20.0, "scrolled to $target")
    }
  }

  @Test
  fun draggingTheThumbScrollsAllTheWay() = runComposeUiTest {
    showGrid()

    onNodeWithTag("scrollbar").performMouseInput {
      moveTo(Offset(4f, 10f))
      press()
      // Small moves, as a hand makes them: each is a fraction of a row.
      repeat(150) { moveBy(Offset(0f, 2f)) }
      release()
    }
    waitForIdle()

    // 300 px of a 400 px track is most of the way down. The thumb used to snap back to the start of its row on every
    // move, so it barely left the top.
    assertTrue(grid.firstVisibleItemIndex > POSTS / 2, "first visible post ${grid.firstVisibleItemIndex}")
  }

  private companion object {
    const val POSTS = 30
  }
}
