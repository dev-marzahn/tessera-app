package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridLayoutInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull

/*
 * The viewer steps through the grid in reading order (#110): by each cell's top edge in the grid's layout, then left
 * to right for equal tops. The grid's layout doesn't change; only the viewer's order does.
 *
 * Filling down from the top, the masonry grid puts each cell into the lane whose bottom is highest, the leftmost of
 * equals. That bottom never rises, so each cell's top is at least every earlier cell's, and there the reading order is
 * the list order. They part where the grid was laid out from somewhere else: a jump (closing the viewer far down the
 * list scrolls straight to its post, a tab shown again starts at its saved item) lays out the cells around it with
 * lanes of its own, and scrolling back up then stacks the cells above them lane by lane, so a cell's list neighbours
 * can sit anywhere on screen. So the order comes from where the grid actually drew each cell ([LaidOutCells]).
 */

/** A cell's lane (0 is the leftmost) and its top edge in px, in one frame of reference for all the cells. */
internal data class GridCell(val lane: Int, val top: Int)

/**
 * Where the grid has drawn its cells, by post id: the cells seen on screen since the grid was last laid out afresh,
 * their tops measured from a common origin, so cells that have scrolled away keep their place relative to those on
 * screen. Immutable; [seen] returns the next state.
 */
internal class LaidOutCells private constructor(val cells: Map<String, GridCell>) {
  /**
   * The cells after a layout that showed [visible] (post id to lane and top, relative to the viewport). The cells
   * already known give the scroll since they were measured, and the new ones join them in the same frame. A layout
   * that moved the known cells against each other or changed a lane (a jump, a reflow, a reloaded list), or that
   * shows none of them, starts again from what's on screen.
   */
  fun seen(visible: Map<String, GridCell>): LaidOutCells {
    if (visible.isEmpty()) return this
    val shifts = visible.mapNotNull { (id, now) ->
      val then = cells[id] ?: return@mapNotNull null
      if (then.lane != now.lane) return LaidOutCells(visible)
      then.top - now.top
    }.distinct()
    val shift = shifts.singleOrNull() ?: return LaidOutCells(visible)
    if (visible.all { (id, now) -> cells[id]?.top == now.top + shift }) return this
    return LaidOutCells(cells + visible.mapValues { (_, now) -> now.copy(top = now.top + shift) })
  }

  companion object {
    val None = LaidOutCells(emptyMap())
  }
}

/** The grid's cells on screen, by post id: lane and top relative to the viewport. The footer has no post id. */
internal fun LazyStaggeredGridLayoutInfo.visibleCells(): Map<String, GridCell> =
  visibleItemsInfo.mapNotNull { item -> (item.key as? String)?.let { it to GridCell(item.lane, item.offset.y) } }.toMap()

/**
 * The viewer's order over a list of [count] posts whose ids [idAt] gives, from the cells the grid has drawn: those by
 * top edge, then left to right. Posts the grid hasn't drawn keep their list order: the ones before every drawn post
 * come first, the ones after them last (the grid will fill them in below, in that order), and any in between follow
 * the drawn post just before them in the list.
 */
internal fun readingOrder(count: Int, idAt: (Int) -> String?, laidOut: LaidOutCells): ReadingOrder {
  val drawn = (0 until count).mapNotNull { index -> idAt(index)?.let(laidOut.cells::get)?.let { index to it } }
  if (drawn.isEmpty()) return ReadingOrder.ListOrder
  val first = drawn.first().first
  val last = drawn.last().first
  val isDrawn = BooleanArray(count).also { array -> drawn.forEach { array[it.first] = true } }
  val order = ArrayList<Int>(count)
  (0 until first).forEach(order::add)
  drawn.sortedWith(compareBy({ it.second.top }, { it.second.lane }, { it.first })).forEach { (index) ->
    order.add(index)
    var next = index + 1
    while (next < last && !isDrawn[next]) order.add(next++)
  }
  (last + 1 until count).forEach(order::add)
  return ReadingOrder(order.toIntArray())
}

/**
 * The viewer's order over the grid's list: position `p` in reading order shows the post at list index [indexAt]`(p)`.
 * Outside the order both directions return what they're given, so a list that grew a frame before its order did still
 * shows something sensible.
 */
internal class ReadingOrder(private val indices: IntArray) {
  private val positions = IntArray(indices.size).also { for (position in indices.indices) it[indices[position]] = position }

  fun indexAt(position: Int): Int = indices.getOrElse(position) { position }

  fun positionOf(index: Int): Int = positions.getOrElse(index) { index }

  companion object {
    val ListOrder = ReadingOrder(IntArray(0))
  }
}

/**
 * Where [state]'s grid has drawn its cells, following every layout it makes except while [paused]: a viewer open
 * over the grid keeps its order fixed, even when its close scrolls the grid to the post (#115 review), once a layout has
 * been seen.
 */
@Composable
internal fun rememberLaidOutCells(state: LazyStaggeredGridState, paused: () -> Boolean = { false }): State<LaidOutCells> {
  val laidOut = remember(state) { mutableStateOf(LaidOutCells.None) }
  LaunchedEffect(state) {
    // Paused, it still takes the first layout with cells, so a viewer already open as the grid comes back (its saved
    // state restored, or back from a post tab) steps in reading order too.
    snapshotFlow {
      val cells = state.layoutInfo.visibleCells()
      cells.takeUnless { paused() && laidOut.value.cells.isNotEmpty() }
    }.filterNotNull().collect { laidOut.value = laidOut.value.seen(it) }
  }
  return laidOut
}
