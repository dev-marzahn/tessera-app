package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewerSheetTest {
  private val stops = SheetStops(half = 400f, full = 1000f)
  private val fling = 1200f

  @Test
  fun slowReleaseSettlesAtNearestStop() {
    assertEquals(0f, settleLift(150f, 0f, stops, fling))
    assertEquals(400f, settleLift(250f, 0f, stops, fling))
    assertEquals(400f, settleLift(650f, 0f, stops, fling))
    assertEquals(1000f, settleLift(750f, 0f, stops, fling))
  }

  @Test
  fun flingUpGoesToNextStopAbove() {
    assertEquals(400f, settleLift(20f, -2000f, stops, fling))
    // From a stop, the next one: a flick at half lifts it full.
    assertEquals(1000f, settleLift(400f, -2000f, stops, fling))
    assertEquals(1000f, settleLift(1000f, -2000f, stops, fling))
  }

  @Test
  fun flingDownGoesToNextStopBelow() {
    assertEquals(400f, settleLift(980f, 2000f, stops, fling))
    assertEquals(0f, settleLift(400f, 2000f, stops, fling))
    assertEquals(0f, settleLift(0f, 2000f, stops, fling))
  }

  @Test
  fun fractionsRunPeekToHalfThenHalfToFull() {
    assertEquals(0f to 0f, sheetFractions(0f, stops))
    assertEquals(0.5f to 0f, sheetFractions(200f, stops))
    assertEquals(1f to 0.5f, sheetFractions(700f, stops))
    assertEquals(1f to 1f, sheetFractions(1000f, stops))
    // Stops not measured yet.
    assertEquals(0f to 0f, sheetFractions(0f, SheetStops(0f, 0f)))
  }

  @Test
  fun positionReadsLikeDesktopAndSaysOrMoreAloud() {
    assertEquals("12 of 48+", positionLabel(11, 48, hasMore = true))
    assertEquals("Post 12 of 48 or more", positionDescription(11, 48, hasMore = true))
    assertEquals("Post 48 of 48", positionDescription(47, 48, hasMore = false))
  }

  @Test
  fun zoomLevelHasOneDecimal() {
    assertEquals("2.5×", zoomLevelLabel(2.5f))
    assertEquals("6.3×", zoomLevelLabel(6.25f))
  }
}
