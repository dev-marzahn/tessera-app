package dev.marzahn.sankaku.features.post

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChipAboveOrBelowTest {
  private fun chip(left: Float, top: Float, width: Float = 60f) = Rect(left, top, left + width, top + 24f)

  // Row 0: 0, 1, 2. Row 1: 3, 4 (a wide one). Then a type's header, and row 2: 5.
  private val chips = listOf(
    chip(0f, 0f), chip(65f, 0f), chip(130f, 0f),
    chip(0f, 29f), chip(65f, 29f, width = 120f),
    chip(0f, 90f),
  )

  @Test
  fun sGoesToTheChipBelowNearestItSideways() {
    assertEquals(3, chipAboveOrBelow(chips, 0, down = true))
    assertEquals(4, chipAboveOrBelow(chips, 1, down = true))
    assertEquals(4, chipAboveOrBelow(chips, 2, down = true))
  }

  @Test
  fun wGoesToTheChipAboveNearestItSideways() {
    assertEquals(0, chipAboveOrBelow(chips, 3, down = false))
    assertEquals(1, chipAboveOrBelow(chips, 4, down = false))
  }

  @Test
  fun aTypesHeaderBetweenRowsIsPassedOver() {
    assertEquals(5, chipAboveOrBelow(chips, 4, down = true))
    assertEquals(3, chipAboveOrBelow(chips, 5, down = false))
  }

  @Test
  fun theTopAndBottomRowsGoNowhere() {
    assertNull(chipAboveOrBelow(chips, 1, down = false))
    assertNull(chipAboveOrBelow(chips, 5, down = true))
  }

  @Test
  fun chipsNotLaidOutAreLeftOut() {
    assertEquals(4, chipAboveOrBelow(chips.toMutableList<Rect?>().also { it[3] = null }, 0, down = true))
    assertNull(chipAboveOrBelow(listOf(null, chip(0f, 29f)), 0, down = true))
  }
}
