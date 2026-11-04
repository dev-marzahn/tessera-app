package dev.marzahn.sankaku.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class LeaveWatchTest {
  /** One frame of the rail: its left edge in the window, and the rest of what [LeaveWatch.startsLeaving] takes. */
  private data class Frame(
    val left: Float,
    val settled: Boolean = true,
    val pressed: Boolean = false,
    val scaled: Boolean = false,
  )

  /** The frames on which the rail was reported to start leaving. */
  private fun leaves(vararg frames: Frame): List<Int> {
    val watch = LeaveWatch()
    return frames.withIndex().filter { (_, frame) ->
      watch.pressed = frame.pressed
      watch.startsLeaving(frame.left, frame.scaled, frame.settled)
    }.map { it.index }
  }

  @Test
  fun theScrimsSlideCountsFromItsFirstFrame() {
    // Opens from off screen, settles, then slides out.
    val opening = arrayOf(Frame(-840f, settled = false), Frame(-300f, settled = false), Frame(0f))
    val leaves = leaves(*opening, Frame(-40f), Frame(-400f), Frame(-840f))
    assertEquals(listOf(3), leaves)
  }

  @Test
  fun anOpeningRailIsNotLeaving() {
    // Its state settles while the rail's first placements are still off screen.
    assertEquals(emptyList(), leaves(Frame(-840f), Frame(-841f), Frame(-500f)))
  }

  @Test
  fun theOpeningSpringsOvershootComingBackIsNotLeaving() {
    assertEquals(emptyList(), leaves(Frame(-200f, settled = false), Frame(0f), Frame(6f), Frame(2f), Frame(0f)))
  }

  @Test
  fun aDragWaitsForTheFingerToLift() {
    // Dragged out, back a little, then let go towards the edge: the fling out counts.
    val leaves = leaves(Frame(0f), Frame(-200f, pressed = true), Frame(-100f, pressed = true), Frame(-300f))
    assertEquals(listOf(3), leaves)
  }

  @Test
  fun aDragLetGoThatSpringsBackOpenIsNotLeaving() {
    assertEquals(emptyList(), leaves(Frame(0f), Frame(-200f, pressed = true), Frame(-120f), Frame(0f)))
  }

  @Test
  fun aPredictiveBackGestureIsNotLeaving() {
    // The gesture scales the rail, which moves its left edge; cancelled, it grows back.
    assertEquals(emptyList(), leaves(Frame(0f), Frame(-20f, scaled = true), Frame(-48f, scaled = true), Frame(0f)))
  }
}
