package dev.marzahn.sankaku

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeOnlyTest {
  @Test
  fun theLaunchExtraOrThePropertyTurnsItOn() {
    assertTrue(safeOnlyRequested(extra = true, property = null))
    // The launch Android makes itself after `adb install -r` has no extras; the property still holds (#97).
    assertTrue(safeOnlyRequested(extra = false, property = "true\n"))
    assertTrue(safeOnlyRequested(extra = false, property = "TRUE"))
    assertTrue(safeOnlyRequested(extra = true, property = "false"))
  }

  @Test
  fun neitherLeavesItOff() {
    assertFalse(safeOnlyRequested(extra = false, property = null))
    // `getprop` prints an empty line for a property that isn't set.
    assertFalse(safeOnlyRequested(extra = false, property = "\n"))
    assertFalse(safeOnlyRequested(extra = false, property = "false"))
  }
}
