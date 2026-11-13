package dev.marzahn.sankaku

import dev.marzahn.sankaku.features.grid.gridColumns
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Android's own grid sizes on a 360 dp phone (a Galaxy Z Flip 7: 1080 px at 480 dpi, 3 px per dp), so a change to
 * the default, the edges or the gap that loses the third column fails here (#468).
 */
class PhoneGridColumnsTest {
  private val density = 3f

  private fun px(dp: Float) = (dp * density).roundToInt()

  private fun columnsAt(minCellDp: Int): Int = gridColumns(
    width = px(360f) - px(platformGridEdge.value) * 2 - px(gridScrollbarRoom.value),
    minCell = px(minCellDp.toFloat()),
    gap = px(platformCellGap.value),
  )

  @Test
  fun theDefaultFitsThreeColumnsOn360Dp() {
    assertEquals(3, columnsAt(platformPostSizes.default))
  }

  @Test
  fun theSlidersEndsMakeFourAndOneColumns() {
    assertEquals(4, columnsAt(platformPostSizes.min))
    assertEquals(1, columnsAt(platformPostSizes.max))
  }
}
