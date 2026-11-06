package dev.marzahn.sankaku.features.grid

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GridCursorTest {
  // Three 100 px columns of a masonry grid, as Compose fills it: each item goes to the shortest column.
  //   col 0: 0 (0..150), 3 (160..260)
  //   col 1: 1 (0..80),  4 (90..300)
  //   col 2: 2 (0..100), 5 (110..170), 6 (180..400)
  private val cells = mapOf(
    0 to IntRect(0, 0, 100, 150),
    1 to IntRect(110, 0, 210, 80),
    2 to IntRect(220, 0, 320, 100),
    4 to IntRect(110, 90, 210, 300),
    5 to IntRect(220, 110, 320, 170),
    3 to IntRect(0, 160, 100, 260),
    6 to IntRect(220, 180, 320, 400),
  )

  @Test
  fun downGoesToTheCellBelowInTheSameColumnNotTheNextIndex() {
    assertEquals(3, nextCell(cells, from = 0, GridMove.Down))
    assertEquals(4, nextCell(cells, from = 1, GridMove.Down))
  }

  @Test
  fun upGoesToTheCellAboveInTheSameColumn() {
    assertEquals(5, nextCell(cells, from = 6, GridMove.Up))
  }

  @Test
  fun sidewaysGoesToTheNeighbouringColumnsCellThatOverlapsMostVertically() {
    // 4 spans 90..300: it overlaps 0 by 60 px and 3 by 100 px.
    assertEquals(3, nextCell(cells, from = 4, GridMove.Left))
    // On the right, 5 overlaps it by 60 px and 6 by 120 px.
    assertEquals(6, nextCell(cells, from = 4, GridMove.Right))
    assertEquals(1, nextCell(cells, from = 0, GridMove.Right))
  }

  @Test
  fun nothingInThatDirectionGivesNull() {
    assertNull(nextCell(cells, from = 0, GridMove.Left))
    assertNull(nextCell(cells, from = 6, GridMove.Right))
    assertNull(nextCell(cells, from = 1, GridMove.Up))
    assertNull(nextCell(cells, from = 6, GridMove.Down))
  }
}
