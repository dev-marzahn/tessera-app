package dev.marzahn.sankaku.features.grid

import androidx.compose.ui.unit.Density
import dev.marzahn.sankaku.tabs.MIN_FLING
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #181: when a drag down closes a post, over a grid and on a lone post. */
class DragDownClosesTest {
  private fun closes(
    dy: Float = 10f,
    atPeek: Boolean = true,
    zoomed: Boolean = false,
    mode: ViewerMode = ViewerMode.Tab,
    closable: Boolean = true,
    fromSeekRow: Boolean = false,
  ) = dragDownCloses(dy, atPeek, zoomed, mode, closable, fromSeekRow)

  @Test
  fun aLonePostClosesOnlyAtPeekAt1xAndOffTheSeekRow() {
    assertTrue(closes())
    assertFalse(closes(atPeek = false), "half or full sheet: the drag moves the sheet")
    assertFalse(closes(zoomed = true), "zoomed: the drag pans")
    assertFalse(closes(fromSeekRow = true), "on the seek row")
    assertFalse(closes(dy = -10f), "up lifts the sheet")
    assertFalse(closes(closable = false), "nothing to close it with")
  }

  // A 2400 px window at a Pixel 9 Pro's 2.625 px a dp, where the queue swipe's 400 dp/s fling is 1050 px/s.
  private val minFling = with(Density(2.625f)) { MIN_FLING.toPx() }

  private fun loneCloses(offset: Float, velocity: Float) =
    loneDragCloses(offset, height = 2400f, velocity = velocity, minFling = minFling)

  @Test
  fun aLoneDragClosesPastAFifthOfTheHeightOrWithAFling() {
    assertTrue(loneCloses(offset = 0.21f * 2400f, velocity = 0f), "a 21% drag, let go still")
    assertFalse(loneCloses(offset = 0.19f * 2400f, velocity = 0f), "19% and slow springs back")
    assertFalse(loneCloses(offset = 0.1f * 2400f, velocity = 100f), "a 10% slow drag springs back")
    assertTrue(loneCloses(offset = 0.05f * 2400f, velocity = 2000f), "a short fast flick closes")
    assertTrue(loneCloses(offset = 0.25f * 2400f, velocity = -2000f), "past 20%, moving back up at release still closes")
  }

  /**
   * #308: a drag let go while still moving slowly springs back; only a deliberate fling, the queue swipe's, closes.
   * Before, any release faster than Android's minimum fling (about 50 dp/s) closed.
   */
  @Test
  fun aSlowReleaseSpringsBackAndADeliberateFlingCloses() {
    assertFalse(loneCloses(offset = 0.1f * 2400f, velocity = 300f), "300 px/s at 10% springs back")
    assertTrue(loneCloses(offset = 0.1f * 2400f, velocity = 1500f), "1500 px/s at 10% closes")
  }

  @Test
  fun aFlingNeedsThePictureBelowWhereItStarted() {
    assertFalse(loneCloses(offset = 0f, velocity = 2000f))
    assertFalse(loneCloses(offset = -10f, velocity = 2000f), "dragged back up above the start")
    assertFalse(loneCloses(offset = 0.1f * 2400f, velocity = minFling), "at the minimum exactly, it isn't faster than it")
  }

  @Test
  fun overAGridTheSeekRowDoesNotMatter() {
    assertTrue(closes(mode = ViewerMode.Overlay, fromSeekRow = true))
    assertFalse(closes(mode = ViewerMode.Overlay, atPeek = false))
  }
}
