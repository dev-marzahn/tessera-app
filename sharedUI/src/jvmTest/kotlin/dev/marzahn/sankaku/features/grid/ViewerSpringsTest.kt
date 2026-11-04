package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.ui.components.spatialSpec
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #281: the viewer's sheet and its drag down, on the app's expressive spatial spring and a test clock, frame by frame,
 * never pass their stops.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerSpringsTest {
  private val stops = SheetStops(half = 800f, full = 2000f)

  @Test
  fun aFlingUpToFullNeverLiftsTheSheetPastFull() = runComposeUiTest {
    val sheet = sheet()
    runOnIdle { sheet.dragBy(-(stops.full - 100f)) }
    assertEquals(stops.full - 100f, runOnIdle { sheet.lift })

    runOnIdle { sheet.settle(-5000f) }
    val lifts = frames { sheet.lift }
    assertTrue(lifts.all { it in 0f..stops.full }, "lift left [0, full]: max ${lifts.max()}, min ${lifts.min()}")
    assertEquals(stops.full, lifts.last())
  }

  @Test
  fun aFlingDownToPeekNeverLowersTheSheetPastPeek() = runComposeUiTest {
    val sheet = sheet()
    runOnIdle { sheet.dragBy(-150f) }
    assertEquals(150f, runOnIdle { sheet.lift })

    runOnIdle { sheet.settle(5000f) }
    val lifts = frames { sheet.lift }
    assertTrue(lifts.all { it in 0f..stops.full }, "lift left [0, full]: max ${lifts.max()}, min ${lifts.min()}")
    assertEquals(0f, lifts.last())
  }

  @Test
  fun aSpringBackNeverPassesRest() = runComposeUiTest {
    val offsets = springBackFrames().map { it.first }
    assertTrue(offsets.all { it >= 0f }, "the card went above rest, to ${offsets.min()}")
    assertEquals(0f, offsets.last())
  }

  @Test
  fun swipesComeBackWithinTwoFramesOfTheCardBeingWithinAPixel() = runComposeUiTest {
    val frames = springBackFrames()
    val near = frames.indexOfFirst { abs(it.first) < 1f }
    val enabled = frames.indexOfFirst { it.second }
    assertTrue(near >= 0, "never within 1 px of rest")
    assertTrue(enabled in 0..near + 2, "within 1 px at frame $near, swipes enabled only at frame $enabled")
  }

  /**
   * A card dragged 300 px and let go: its offset after each frame, and whether the pager and the queue swipe are
   * enabled then (PhoneViewer enables them exactly when `atRest` is).
   */
  private fun ComposeUiTest.springBackFrames(): List<Pair<Float, Boolean>> {
    lateinit var dismiss: CardDismiss
    setContent { SankakuTheme { dismiss = rememberCardDismiss(opening = 1, spatialSpec()) } }
    mainClock.autoAdvance = false
    runOnIdle {
      dismiss.dragBy(300f)
      dismiss.springBack()
    }
    return frames { dismiss.offset to dismiss.atRest }
  }

  private fun ComposeUiTest.sheet(): ViewerSheetState {
    lateinit var sheet: ViewerSheetState
    setContent {
      SankakuTheme {
        val scope = rememberCoroutineScope()
        val spec: AnimationSpec<Float> = spatialSpec()
        sheet = remember { ViewerSheetState(scope, spec, flingThreshold = 1200f) }
      }
    }
    mainClock.autoAdvance = false
    runOnIdle { sheet.update(stops) }
    return sheet
  }

  /** [read] after each of the next [count] frames. */
  private fun <T> ComposeUiTest.frames(count: Int = 90, read: () -> T): List<T> = List(count) {
    mainClock.advanceTimeByFrame()
    runOnIdle { read() }
  }
}
