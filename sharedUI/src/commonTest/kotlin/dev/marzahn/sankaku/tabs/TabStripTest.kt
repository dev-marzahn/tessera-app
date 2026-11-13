package dev.marzahn.sankaku.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabStripTest {
  @Test
  fun aTabCutByAFewPixelsAtTheEdgeStillCountsAsShown() {
    // A 100 px tab at 0..99 in a 300 px viewport, scrolled by 4 px.
    assertFalse(mostlyHidden(0..99, scrolled = 4, viewport = 300))
    // The same on the right: a tab at 204..303, its last 4 px past the edge.
    assertFalse(mostlyHidden(204..303, scrolled = 0, viewport = 300))
  }

  @Test
  fun hiddenTabsCountOnTheSideTheyAreOn() {
    // Five 100 px tabs in a 250 px viewport; the middle one always shows at least half.
    val spans = (0 until 5).map { it * 100 until it * 100 + 100 }
    // Scrolled to the end: two before, none after.
    assertEquals(2 to 0, hiddenCounts(spans, scrolled = 250, viewport = 250))
    assertEquals(0 to 2, hiddenCounts(spans, scrolled = 0, viewport = 250))
  }

  @Test
  fun twinsInARowAreNumberedByTheirPlaceInTheRun() {
    assertEquals(
      listOf("", " · 2", " · 3", "", "", "", " · 2"),
      twinSuffixes(listOf("Xingtong", "Xingtong", "Xingtong", "Rosehip", "Xingtong", "Rosehip", "Rosehip")),
    )
    assertEquals(emptyList(), twinSuffixes(emptyList()))
  }

  @Test
  fun aTabMostlyOrFullyOutOfViewCounts() {
    assertTrue(mostlyHidden(0..99, scrolled = 60, viewport = 300))
    assertTrue(mostlyHidden(400..499, scrolled = 0, viewport = 300))
  }
}
