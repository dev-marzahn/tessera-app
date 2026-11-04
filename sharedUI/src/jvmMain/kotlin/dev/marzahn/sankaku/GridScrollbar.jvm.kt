package dev.marzahn.sankaku

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor

/**
 * Compose has no scrollbar adapter for staggered grids. This one measures in posts, not pixels: the thumb's place is
 * the posts above the view out of the loaded posts, and its length the posts in view. Unlike an estimate from
 * item heights, that doesn't jump as differently sized posts scroll past. The keyset API has no total, so the thumb
 * shrinks as pages load, down to its minimum length.
 */
@Composable
actual fun GridScrollbar(state: LazyStaggeredGridState, postCount: Int, modifier: Modifier) {
  val count by rememberUpdatedState(postCount)
  val adapter = remember(state) { PostIndexScrollbarAdapter(state) { count } }
  VerticalScrollbar(
    adapter = adapter,
    modifier = modifier,
    style = defaultScrollbarStyle().copy(
      minimalHeight = 48.dp,
      thickness = SCROLLBAR_THICKNESS,
      shape = RoundedCornerShape(4.dp),
      unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
      hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    ),
  )
}

/** A visible post: its index, its lane, and its top and bottom in the grid's layout coordinates. */
internal data class VisiblePost(val index: Int, val lane: Int, val top: Int, val bottom: Int)

/** The grid in rows of posts: rows above the view (fractional), rows in view, and rows the loaded posts fill. */
internal data class PostRows(val offset: Double, val viewport: Double, val content: Double, val lanes: Int)

/**
 * Where the view is among [postCount] posts: the posts above the view, in rows of [PostRows.lanes]. A post crossing
 * the view's top counts by how far it's scrolled past, so the offset grows smoothly and never goes back while the
 * view moves down. (Averaging the index of the post crossing each lane's top did go back, by up to a fifth of a row,
 * whenever a lane's next post wasn't [PostRows.lanes] further on, or a gap between posts was at the top; a dragged
 * thumb stopped at those places.) The viewport counts the visible part of each post in view, so it changes smoothly
 * too as posts enter and leave.
 */
internal fun postRows(posts: List<VisiblePost>, viewStart: Int, viewEnd: Int, postCount: Int): PostRows {
  val cells = posts.filter { it.index < postCount && it.bottom > it.top }.distinctBy { it.index }
  val lanes = (cells.maxOfOrNull { it.lane } ?: 0).coerceAtLeast(0) + 1
  // A staggered grid places posts in order, each at the top of the shortest lane, so a post isn't below a later one:
  // every post before the last visible one that isn't visible is above the view.
  val last = cells.maxOfOrNull { it.index } ?: -1
  val hiddenAbove = (last + 1 - cells.size).coerceAtLeast(0)
  val scrolledPast = cells.sumOf { ((viewStart - it.top).toDouble() / (it.bottom - it.top)).coerceIn(0.0, 1.0) }
  val offset = (hiddenAbove + scrolledPast) / lanes
  val inView = cells.sumOf { (minOf(it.bottom, viewEnd) - maxOf(it.top, viewStart)).coerceAtLeast(0).toDouble() / (it.bottom - it.top) }
  val viewport = inView / lanes
  return PostRows(offset, viewport, maxOf(postCount.toDouble() / lanes, offset + viewport), lanes)
}

internal class PostIndexScrollbarAdapter(
  private val state: LazyStaggeredGridState,
  private val postCount: () -> Int,
) : ScrollbarAdapter {
  private val rows: PostRows
    get() {
      val info = state.layoutInfo
      val posts = info.visibleItemsInfo.map { VisiblePost(it.index, it.lane, it.offset.y, it.offset.y + it.size.height) }
      // Item offsets start after the top padding; the view spans the viewport less that padding.
      return postRows(posts, 0, info.viewportSize.height - info.beforeContentPadding, postCount())
    }

  override val scrollOffset: Double get() = rows.offset * ROW
  override val contentSize: Double get() = rows.content * ROW
  override val viewportSize: Double get() = rows.viewport * ROW

  /**
   * Lands where [scrollOffset] asks, as [postRows] measures it. The thumb drag relies on that: each move starts from
   * the offset the last one reached, so landing short (a post index doesn't say where the other lanes are in a
   * staggered grid) undid every move and the thumb snapped back. A far target first jumps by post index; the rest is
   * scrolled in pixels and corrected until the measured offset matches.
   */
  override suspend fun scrollTo(scrollOffset: Double) {
    val count = postCount()
    if (count == 0) return
    val target = (scrollOffset / ROW).coerceAtLeast(0.0)
    val start = rows
    if (abs(target - start.offset) > maxOf(start.viewport, 1.0) * JUMP_VIEWPORTS) {
      // With post n at the top, about n posts are above the view.
      state.scrollToItem(floor(target * start.lanes).toInt().coerceAtMost(count - 1))
    }
    repeat(CORRECTIONS) {
      val diff = target - rows.offset
      if (abs(diff) < PRECISION) return
      val rowPixels = rowPixels() ?: return
      // At either end the grid scrolls no further; asking again wouldn't change that.
      if (state.scrollBy((diff * rowPixels).toFloat()) == 0f) return
    }
  }

  /** About how many pixels scroll the view one row: the visible posts' average height and the gap between them. */
  private fun rowPixels(): Double? {
    val info = state.layoutInfo
    val count = postCount()
    val heights = info.visibleItemsInfo.filter { it.index < count && it.size.height > 0 }.map { it.size.height }
    if (heights.isEmpty()) return null
    return heights.average() + info.mainAxisItemSpacing
  }

  private companion object {
    /** Pixels per row, only for the adapter's units: the thumb depends on ratios alone. */
    const val ROW = 1_000.0

    /** Targets further than this many viewports away jump by post index before the pixel corrections. */
    const val JUMP_VIEWPORTS = 2.0
    const val CORRECTIONS = 4
    const val PRECISION = 0.001
  }
}

private val SCROLLBAR_THICKNESS = 8.dp

/** The scrollbar's thickness and a gap between it and the cells. */
actual val gridScrollbarRoom: Dp = SCROLLBAR_THICKNESS + 4.dp
