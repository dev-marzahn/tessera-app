package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertEquals

class GridColumnsTest {
  @Test
  fun columnsAreCountedAsAdaptiveCellsCountThem() {
    // 1,904 px of grid with 8 px gaps: 220 px columns make 8, 460 px ones 4.
    assertEquals(8, gridColumns(width = 1904, minCell = 220, gap = 8))
    assertEquals(4, gridColumns(width = 1904, minCell = 460, gap = 8))
    // The last gap isn't needed: four 100 px columns and three gaps fit exactly 424 px.
    assertEquals(4, gridColumns(width = 424, minCell = 100, gap = 8))
    assertEquals(3, gridColumns(width = 423, minCell = 100, gap = 8))
  }

  @Test
  fun aPhonesDefaultFitsThreeColumnsOn360Dp() {
    // #468: a 360 dp phone (Galaxy Z Flip 7) at 3 px per dp: 6 dp edges, 4 dp gaps, 110 dp columns. In px, as the
    // grid measures them; 120 dp fitted only two.
    val density = 3
    val grid = (360 - 2 * 6) * density
    assertEquals(3, gridColumns(width = grid, minCell = 110 * density, gap = 4 * density))
    assertEquals(2, gridColumns(width = grid, minCell = 120 * density, gap = 4 * density))
    // The phone's slider ends: 80 dp makes four columns there, 200 dp one.
    assertEquals(4, gridColumns(width = grid, minCell = 80 * density, gap = 4 * density))
    assertEquals(1, gridColumns(width = grid, minCell = 200 * density, gap = 4 * density))
  }

  @Test
  fun aGridNarrowerThanOneColumnStillHasOne() {
    assertEquals(1, gridColumns(width = 50, minCell = 220, gap = 8))
    assertEquals(1, gridColumns(width = 0, minCell = 220, gap = 8))
  }
}
