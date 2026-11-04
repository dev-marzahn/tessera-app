package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ReadingOrderTest {
  private fun ids(count: Int) = (0 until count).map { "p$it" }

  private fun cells(vararg cells: Pair<Int, GridCell>) = cells.associate { (index, cell) -> "p$index" to cell }

  private fun order(count: Int, laidOut: LaidOutCells): List<Int> {
    val ids = ids(count)
    val order = readingOrder(count, ids::getOrNull, laidOut)
    return (0 until count).map(order::indexAt)
  }

  @Test
  fun drawnCellsAreReadByTopEdgeThenLeftToRight() {
    // Three lanes laid out from a jump: list order isn't screen order. 3 (left, top 100) comes before 1 (middle,
    // top 150); 2 and 4 share a top, so the left one, 4, goes first.
    val laidOut = LaidOutCells.None.seen(
      cells(
        0 to GridCell(lane = 0, top = 0),
        1 to GridCell(lane = 1, top = 150),
        2 to GridCell(lane = 2, top = 300),
        3 to GridCell(lane = 0, top = 100),
        4 to GridCell(lane = 1, top = 300),
        5 to GridCell(lane = 2, top = 0),
      ),
    )
    assertEquals(listOf(0, 5, 3, 1, 4, 2), order(6, laidOut))
    // Both ways round.
    val order = readingOrder(6, ids(6)::getOrNull, laidOut)
    assertEquals(listOf(0, 3, 5, 2, 4, 1), (0 until 6).map(order::positionOf))
  }

  @Test
  fun undrawnPostsKeepTheirListOrderAroundTheDrawnOnes() {
    // Drawn: 2..5. 0 and 1 (above, never on screen) come first, 6 and 7 (not laid out yet) last, and 4, which never
    // showed between them, follows 3.
    val laidOut = LaidOutCells.None.seen(
      cells(
        2 to GridCell(lane = 1, top = 50),
        3 to GridCell(lane = 0, top = 0),
        5 to GridCell(lane = 2, top = 10),
      ),
    )
    assertEquals(listOf(0, 1, 3, 4, 5, 2, 6, 7), order(8, laidOut))
  }

  @Test
  fun withNothingDrawnItsTheListOrder() {
    assertEquals(listOf(0, 1, 2), order(3, LaidOutCells.None))
  }

  @Test
  fun scrolledCellsKeepTheirPlaceAgainstTheOnesOnScreen() {
    // Screen one: 0 and 1. Scrolled 200 px down: 1 is still there, 200 px higher, and 2 joins.
    val first = LaidOutCells.None.seen(cells(0 to GridCell(0, 0), 1 to GridCell(1, 250)))
    val scrolled = first.seen(cells(1 to GridCell(1, 50), 2 to GridCell(0, 100)))
    assertEquals(GridCell(0, 300), scrolled.cells["p2"])
    // 0 scrolled off and keeps its top.
    assertEquals(GridCell(0, 0), scrolled.cells["p0"])
    assertEquals(listOf(0, 1, 2), order(3, scrolled))
  }

  @Test
  fun aLayoutWithNothingNewKeepsTheSameState() {
    val first = LaidOutCells.None.seen(cells(0 to GridCell(0, 0), 1 to GridCell(1, 250)))
    assertSame(first, first.seen(cells(0 to GridCell(0, -40), 1 to GridCell(1, 210))))
    assertSame(first, first.seen(emptyMap()))
  }

  @Test
  fun aJumpOrReflowStartsAgainFromTheScreen() {
    val first = LaidOutCells.None.seen(cells(0 to GridCell(0, 0), 1 to GridCell(1, 0), 2 to GridCell(2, 0)))
    // Nothing in common: a jump far down.
    assertEquals(setOf("p40", "p41"), first.seen(cells(40 to GridCell(0, 0), 41 to GridCell(1, 0))).cells.keys)
    // A cell changed lanes.
    assertEquals(setOf("p0", "p3"), first.seen(cells(0 to GridCell(1, 0), 3 to GridCell(0, 0))).cells.keys)
    // Cells moved against each other.
    assertEquals(setOf("p0", "p1"), first.seen(cells(0 to GridCell(0, 0), 1 to GridCell(1, 30))).cells.keys)
  }

  @Test
  fun aLoadedPageSlotsInWithoutMovingWhatsAlreadyOrdered() {
    // The viewer is open on 2; the grid behind it shows 0..3, and a new page adds 4 and 5, which only the order's
    // tail knows about until the grid draws them.
    val laidOut = LaidOutCells.None.seen(
      cells(0 to GridCell(0, 0), 1 to GridCell(1, 0), 2 to GridCell(2, 0), 3 to GridCell(1, 120)),
    )
    val before = order(4, laidOut)
    val after = order(6, laidOut)
    assertEquals(before, after.take(4))
    assertEquals(listOf(4, 5), after.drop(4))
    // Drawn once the grid lays them out: 4 lands above 3, in lane 0, and is read before it.
    val drawn = laidOut.seen(cells(3 to GridCell(1, 120), 4 to GridCell(0, 100), 5 to GridCell(2, 130)))
    assertEquals(listOf(0, 1, 2, 4, 3, 5), order(6, drawn))
  }

  @Test
  fun outsideTheOrderTheIndexStands() {
    val order = ReadingOrder.ListOrder
    assertEquals(7, order.indexAt(7))
    assertEquals(7, order.positionOf(7))
  }
}
