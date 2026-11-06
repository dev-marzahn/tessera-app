package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Velocity
import dev.marzahn.sankaku.ui.components.spatialSpec
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #280 (#279, findings 1 and 2): a let-go queue swipe settles on the app's expressive spring without the bounce. The
 * stretch gets nothing once a swipe moves or springs back, only at an end; the settle keeps the finger's speed, never
 * passes the width, and the move lands as soon as the picture looks settled.
 */
@OptIn(ExperimentalTestApi::class)
class QueueSettleTest {
  private val width = 1280
  private val minFling = 1050f

  /** Android's stretch, as far as a swipe sees it: what's left of a fling after the settle is what it absorbs. */
  private class RecordingOverscroll : OverscrollEffect {
    var left: Velocity? = null
    override val isInProgress = false
    override val node: DelegatableNode = object : Modifier.Node() {}
    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset) =
      performScroll(delta)

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
      left = velocity - performFling(velocity)
    }
  }

  /** What followed a let-go: the offset per frame, the frame the queue stepped in, and what the stretch was left. */
  private class AfterLetGo(val frames: List<Float>, val movedAt: Int?, val move: QueueMove?, val left: Velocity?)

  /**
   * Drags the real [QueueSwipeState] [drag] px towards the end and lets go at [velocity] px/s, on a 16 ms test clock,
   * in a layout left to right or, with [direction] -1, right to left.
   */
  private fun letGo(drag: Float, velocity: Float, hasNext: Boolean = true, direction: Float = 1f): AfterLetGo {
    val overscroll = RecordingOverscroll()
    val frames = mutableListOf<Float>()
    var movedAt: Int? = null
    var moved: QueueMove? = null
    lateinit var state: QueueSwipeState
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        SankakuTheme {
          val scope = rememberCoroutineScope()
          val spec = spatialSpec<Float>()
          state = remember {
            QueueSwipeState(scope, spec) { move ->
              // The frame the queue steps in, counted from the let-go.
              movedAt = frames.size
              moved = move
              true
            }
          }
        }
      }
      runOnIdle {
        state.width = width
        state.hasPrevious = true
        state.hasNext = hasNext
        state.overscroll = overscroll
        state.direction = direction
        state.start()
        state.dragBy(drag)
        state.release(velocity, minFling, TabId("shown"))
      }
      repeat(90) {
        mainClock.advanceTimeByFrame()
        frames += runOnIdle { state.offset }
      }
    }
    return AfterLetGo(frames, movedAt, moved, overscroll.left)
  }

  @Test
  fun nothingIsLeftForTheStretchOnceASwipeMovesOrSpringsBack() {
    for (direction in listOf(1f, -1f)) {
      val layout = if (direction > 0f) "left to right" else "right to left"
      val flingToNext = letGo(drag = -300f, velocity = -3000f, direction = direction)
      assertEquals(QueueMove.Next, flingToNext.move)
      assertEquals(Velocity.Zero, flingToNext.left, "fling to the next item, $layout")

      val slowPastHalf = letGo(drag = -700f, velocity = -200f, direction = direction)
      assertEquals(QueueMove.Next, slowPastHalf.move)
      assertEquals(Velocity.Zero, slowPastHalf.left, "slow let-go past half, $layout")

      val flingBack = letGo(drag = -300f, velocity = 2000f, direction = direction)
      assertEquals(null, flingBack.move)
      assertEquals(Velocity.Zero, flingBack.left, "fling back that stays, $layout")
    }
  }

  @Test
  fun aLetGoAtAnEndStillReachesTheStretch() {
    // No next item: the drag towards it moves nothing, and the whole fling is the stretch's, in px from the left.
    for (direction in listOf(1f, -1f)) {
      val atEnd = letGo(drag = -300f, velocity = -3000f, hasNext = false, direction = direction)
      assertEquals(null, atEnd.move)
      assertEquals(Velocity(-3000f * direction, 0f), atEnd.left)
    }
  }

  @Test
  fun theSettleKeepsTheFlingNeverPassesTheWidthAndLandsOnceSettled() {
    for ((name, drag, velocity, target) in listOf(
      Case("fling to next", -300f, -3000f, -width.toFloat()),
      Case("slow past half", -700f, -200f, -width.toFloat()),
      Case("fling to previous", 300f, 3000f, width.toFloat()),
      Case("fling back, stays", -300f, 2000f, 0f),
      Case("slow short, stays", -200f, 0f, 0f),
    )) {
      val after = letGo(drag, velocity)
      val frames = after.frames
      println("#280 $name: " + frames.take(40).joinToString(" ") { it.toInt().toString() } + " | moved at frame ${after.movedAt}")
      assertTrue(frames.all { abs(it) <= width }, "$name passes the width: $frames")
      // From the let-go on, every frame moves until the offset is in place.
      assertNotEquals(drag, frames[0], "$name stands still on the first frame")
      // A fling carries on: the first frame goes at least most of the way the finger would have in one frame.
      if (abs(velocity) >= minFling) {
        val step = frames[0] - drag
        assertTrue(step * velocity > 0f && abs(step) >= 0.7f * abs(velocity) * 0.016f, "$name drops the fling: first step $step")
      }
      val inPlace = frames.indexOfFirst { abs(it - target) < 1f }
      assertTrue(inPlace >= 0, "$name never settles: $frames")
      for (i in 1..inPlace) assertNotEquals(frames[i - 1], frames[i], "$name stands still at frame $i: $frames")
      // Once within 1 px it's in place, and a move steps the queue within 2 frames.
      assertTrue(frames.drop(inPlace).all { it == target }, "$name creeps after landing: $frames")
      if (target != 0f) {
        val movedAt = after.movedAt
        assertTrue(movedAt != null && movedAt - inPlace <= 2, "$name moves at frame $movedAt, in place at $inPlace")
      }
    }
  }

  private data class Case(val name: String, val drag: Float, val velocity: Float, val target: Float)
}
