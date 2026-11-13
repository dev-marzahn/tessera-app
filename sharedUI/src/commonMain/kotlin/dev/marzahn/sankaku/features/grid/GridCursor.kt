package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntRect
import dev.marzahn.sankaku.ui.components.Shortcuts
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A step of the grid's keyboard cursor: W/A/S/D or the arrows. */
internal enum class GridMove { Up, Left, Down, Right }

internal fun gridMoveFor(key: Key): GridMove? = when {
  Shortcuts.Up.matches(key) -> GridMove.Up
  Shortcuts.Previous.matches(key) -> GridMove.Left
  Shortcuts.Down.matches(key) -> GridMove.Down
  Shortcuts.Next.matches(key) -> GridMove.Right
  else -> null
}

internal fun gridCellTag(index: Int) = "grid-cell-$index"

/**
 * A search submitted from the keyboard (the search panel, or the tag list's keys, #421) asks for the grid it opens to
 * start with its cursor on the first cell. The request lasts until that grid takes it, the search panel lets it lapse
 * a few frames later, or [lifetime] passes, so a grid opened later (a restored or background tab) never starts with
 * one. The tag list can't count frames: it leaves the composition as the grid it opened comes in, so it names the
 * search ([request]'s `tags`) and only that grid takes it; when the search's open source is shown instead, nothing
 * takes it, and no other grid composed within the lifetime (another tab, a new one) can.
 */
class GridCursorRequest(private val time: TimeSource = TimeSource.Monotonic, private val lifetime: Duration = 1.seconds) {
  private var until: TimeMark? = null
  private var tags: List<String>? = null

  /** Asks for the next grid of [tags] to start with its cursor, or the next grid at all when null. */
  fun request(tags: List<String>? = null) {
    until = time.markNow() + lifetime
    this.tags = tags
  }

  fun lapse() {
    until = null
  }

  /** Whether the grid of [tags] asking starts with its cursor; only the first one it was asked for gets it. */
  fun take(tags: List<String>): Boolean {
    if (this.tags != null && this.tags != tags) return false
    return (until?.hasNotPassedNow() == true).also { until = null }
  }
}

/**
 * Every laid-out post cell of the grid (not the footer, nor a feed's headings, #314) by post index, in the grid's item
 * coordinates.
 */
internal fun LazyStaggeredGridState.cellBounds(postCount: Int, gridItems: GridItems = GridItems.None): Map<Int, IntRect> =
  layoutInfo.visibleItemsInfo
    .mapNotNull { info -> gridItems.postOf(info.index)?.let { it to info } }
    .filter { (post) -> post < postCount }
    .associate { (post, info) -> post to IntRect(info.offset, info.size) }

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

/** How far [a] and [b] overlap vertically: negative when they don't. */
private fun overlap(a: IntRect, b: IntRect) = min(a.bottom, b.bottom) - max(a.top, b.top)

/**
 * The cells of the nearest column on [move]'s side of [from], among [cells], that has one level with it (overlapping
 * it vertically); with none, the nearest column. So a cell missing from [cells] is passed over
 * to the next in its row, rather than to one above or below it beside.
 */
private fun besideColumn(cells: Map<Int, IntRect>, from: Int, move: GridMove): Map<Int, IntRect> {
  val at = cells[from] ?: return emptyMap()
  val side = cells.filter { (i, r) -> i != from && if (move == GridMove.Right) r.left >= at.right else r.right <= at.left }
  val columns = side.values.map { it.left }.distinct().let { if (move == GridMove.Right) it.sorted() else it.sortedDescending() }
  val column = columns.firstOrNull { left -> side.values.any { it.left == left && overlap(it, at) > 0 } } ?: columns.firstOrNull()
  return side.filter { it.value.left == column }
}

/**
 * The cell a [move] from [from] lands on, among [cells] (item index to its bounds in the grid), or null if there is
 * none that way; the post grid and the Books list share it. Up and down go to the nearest cell above or below that
 * shares some width with it (in practice the next one in its column); left and right go to the nearest column on that
 * side with a cell level with it ([besideColumn]) and pick its cell that overlaps [from] most vertically, the upper one
 * on an exact tie (owner decision, #508). It reads only where the cells are, so a key from a cell always lands on the
 * same cell however the cursor got there. In the masonry grid a tall cell beside several short ones so leads back to
 * only one of them: the one it overlaps most.
 */
internal fun nextCell(cells: Map<Int, IntRect>, from: Int, move: GridMove): Int? {
  val at = cells[from] ?: return null
  val others = cells.filterKeys { it != from }
  fun horizontal(r: IntRect) = min(r.right, at.right) - max(r.left, at.left)
  fun vertical(r: IntRect) = overlap(r, at)
  return when (move) {
    GridMove.Down -> others.filter { (_, r) -> r.top >= at.bottom && horizontal(r) > 0 }
      .minWithOrNull(compareBy<Map.Entry<Int, IntRect>> { it.value.top }.thenByDescending { horizontal(it.value) })?.key
    GridMove.Up -> others.filter { (_, r) -> r.bottom <= at.top && horizontal(r) > 0 }
      .minWithOrNull(compareByDescending<Map.Entry<Int, IntRect>> { it.value.bottom }.thenByDescending { horizontal(it.value) })?.key
    GridMove.Left, GridMove.Right -> besideColumn(cells, from, move).minWithOrNull(
      compareByDescending<Map.Entry<Int, IntRect>> { vertical(it.value) }.thenBy { it.value.top },
    )?.key
  }
}
