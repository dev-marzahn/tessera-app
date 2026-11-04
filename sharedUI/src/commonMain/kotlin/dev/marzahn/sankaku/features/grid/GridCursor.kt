package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntRect
import dev.marzahn.sankaku.ui.components.Shortcuts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A step of the grid's keyboard cursor: W/A/S/D or the arrows. */
internal enum class GridMove { Up, Left, Down, Right }

internal fun gridMoveFor(key: Key): GridMove? = when {
  Shortcuts.Up.matches(key) -> GridMove.Up
  Shortcuts.Previous.matches(key) -> GridMove.Left
  Shortcuts.Down.matches(key) -> GridMove.Down
  Shortcuts.Next.matches(key) -> GridMove.Right
  else -> null
}

/**
 * A search submitted from the keyboard asks for the grid it opens to start with its cursor on the first cell. The
 * request lasts until that grid takes it or the search panel lets it lapse a few frames later, so a grid opened later
 * (a restored or background tab) never starts with one.
 */
class GridCursorRequest {
  private var pending = false

  fun request() {
    pending = true
  }

  fun lapse() {
    pending = false
  }

  /** Whether the grid asking starts with its cursor; only the first one gets it. */
  fun take(): Boolean = pending.also { pending = false }
}

/**
 * Every laid-out post cell of the grid (not the footer) by index, in the grid's item coordinates, less those [skip]
 * leaves out.
 */
internal fun LazyStaggeredGridState.cellBounds(postCount: Int, skip: (Int) -> Boolean): Map<Int, IntRect> =
  layoutInfo.visibleItemsInfo.filter { it.index < postCount && !skip(it.index) }.associate { it.index to IntRect(it.offset, it.size) }

/**
 * Whether [r] is wholly inside the grid's viewport with room for the cursor's ring around it. Item offsets start after
 * the top content padding, which is the ring's reach: so 0 is that far below the viewport's top, and the same margin is
 * kept above its bottom.
 */
internal fun LazyStaggeredGridState.fullyShows(r: IntRect): Boolean =
  r.top >= 0 && r.bottom <= shownBottom()

/** The lowest item offset [fullyShows] allows: the viewport's height less the ring's reach at either edge. */
internal fun LazyStaggeredGridState.shownBottom(): Int =
  layoutInfo.viewportSize.height - 2 * layoutInfo.beforeContentPadding

/** The cell where the cursor appears: the first wholly visible one, top to bottom, then left to right. */
internal fun firstShown(cells: Map<Int, IntRect>, fullyShows: (IntRect) -> Boolean): Int? =
  cells.filter { fullyShows(it.value) }.ifEmpty { cells }
    .minWithOrNull(compareBy<Map.Entry<Int, IntRect>> { it.value.top }.thenBy { it.value.left })?.key

/**
 * After a page jump, the cell nearest the point ([x], [y]) where the cursor was on screen: one that contains it, else
 * the closest centre.
 */
internal fun cellNear(cells: Map<Int, IntRect>, x: Int, y: Int): Int? =
  cells.minByOrNull { (_, r) ->
    val dx = (x - x.coerceIn(r.left, r.right)).toLong()
    val dy = (y - y.coerceIn(r.top, r.bottom)).toLong()
    dx * dx + dy * dy
  }?.key

/**
 * The cell a [move] from [from] lands on, among [cells] (item index to its bounds in the grid), or null if there is
 * none that way. The grid is masonry, so there are no rows: up and down go to the nearest cell above or below that
 * shares some width with it (in practice the next one in its column); left and right go to the nearest column on
 * that side and pick its cell that overlaps [from] most vertically.
 */
internal fun nextCell(cells: Map<Int, IntRect>, from: Int, move: GridMove): Int? {
  val at = cells[from] ?: return null
  val others = cells.filterKeys { it != from }
  fun horizontal(r: IntRect) = min(r.right, at.right) - max(r.left, at.left)
  fun vertical(r: IntRect) = min(r.bottom, at.bottom) - max(r.top, at.top)
  return when (move) {
    GridMove.Down -> others.filter { (_, r) -> r.top >= at.bottom && horizontal(r) > 0 }
      .minWithOrNull(compareBy<Map.Entry<Int, IntRect>> { it.value.top }.thenByDescending { horizontal(it.value) })?.key
    GridMove.Up -> others.filter { (_, r) -> r.bottom <= at.top && horizontal(r) > 0 }
      .minWithOrNull(compareByDescending<Map.Entry<Int, IntRect>> { it.value.bottom }.thenByDescending { horizontal(it.value) })?.key
    GridMove.Left, GridMove.Right -> {
      val side = others.filter { (_, r) -> if (move == GridMove.Right) r.left >= at.right else r.right <= at.left }
      val column = side.values.map { it.left }.let { if (move == GridMove.Right) it.minOrNull() else it.maxOrNull() }
      side.filter { it.value.left == column }
        .minWithOrNull(
          compareByDescending<Map.Entry<Int, IntRect>> { vertical(it.value) }
            .thenBy { abs(it.value.center.y - at.center.y) },
        )?.key
    }
  }
}
