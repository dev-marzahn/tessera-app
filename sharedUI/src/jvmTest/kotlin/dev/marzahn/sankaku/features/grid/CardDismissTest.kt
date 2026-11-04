package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CardDismissTest {
  private val spec = tween<Float>(300)

  @Test
  fun dragFollowsTheFingerButNeverAboveRest() = runComposeUiTest {
    lateinit var dismiss: CardDismiss
    setContent { dismiss = rememberCardDismiss(opening = 1, spec) }

    runOnIdle { dismiss.dragBy(300f) }
    assertEquals(300f, runOnIdle { dismiss.offset })
    assertEquals(0.5f, runOnIdle { dismiss.fraction(600f) })
    runOnIdle { dismiss.dragBy(-500f) }
    assertEquals(0f, runOnIdle { dismiss.offset })
    // Predictive back says where the card is.
    runOnIdle { dismiss.pullTo(900f) }
    assertEquals(1f, runOnIdle { dismiss.fraction(600f) })
    assertEquals(0f, runOnIdle { dismiss.fraction(0f) })
  }

  @Test
  fun letGoShortOfClosingSpringsBackAndADragStopsTheSpring() = runComposeUiTest {
    mainClock.autoAdvance = false
    lateinit var dismiss: CardDismiss
    setContent { dismiss = rememberCardDismiss(opening = 1, spec) }

    runOnIdle {
      dismiss.dragBy(200f)
      dismiss.springBack()
    }
    mainClock.advanceTimeBy(100)
    // Caught again: the finger has it, the spring lets go.
    runOnIdle { dismiss.dragBy(10f) }
    val caught = runOnIdle { dismiss.offset }
    assertTrue(caught in 11f..199f, "caught while springing back, at $caught")
    mainClock.advanceTimeBy(500)
    assertEquals(caught, runOnIdle { dismiss.offset })

    runOnIdle { dismiss.springBack() }
    mainClock.advanceTimeBy(500)
    assertEquals(0f, runOnIdle { dismiss.offset })
  }

  /** #105: a cell tapped while the card closed from a drag down reopened it still shrunk, over a dimmed grid. */
  @Test
  fun aReopenWhileClosingStartsWithTheCardWhole() = runComposeUiTest {
    mainClock.autoAdvance = false
    var opening by mutableIntStateOf(1)
    lateinit var dismiss: CardDismiss
    setContent { dismiss = rememberCardDismiss(opening, spec) }

    // Dragged down past the threshold: the viewer closes from there, the offset held through the close.
    runOnIdle { dismiss.dragBy(400f) }
    val closing = dismiss
    mainClock.advanceTimeByFrame()
    assertSame(closing, dismiss)
    assertEquals(400f, runOnIdle { dismiss.offset })

    // A cell tapped mid-close: the same composition, the next opening.
    runOnIdle { opening = 2 }
    mainClock.advanceTimeByFrame()
    assertNotSame(closing, dismiss)
    assertEquals(0f, runOnIdle { dismiss.offset })
    assertEquals(0f, runOnIdle { dismiss.fraction(1000f) })
  }

  @Test
  fun aReopenStopsTheLastOpeningsSpring() = runComposeUiTest {
    mainClock.autoAdvance = false
    var opening by mutableIntStateOf(1)
    lateinit var dismiss: CardDismiss
    setContent { dismiss = rememberCardDismiss(opening, spec) }

    // Predictive back cancelled: the card springs back, and a reopen lands before it's done.
    runOnIdle {
      dismiss.pullTo(300f)
      dismiss.springBack()
    }
    mainClock.advanceTimeBy(100)
    val last = dismiss
    runOnIdle { opening = 2 }
    mainClock.advanceTimeByFrame()
    val at = runOnIdle { last.offset }
    assertTrue(at in 1f..299f, "reopened while springing back, at $at")
    // The last opening's spring is stopped; the new card is whole.
    mainClock.advanceTimeBy(500)
    assertEquals(at, runOnIdle { last.offset })
    assertEquals(0f, runOnIdle { dismiss.offset })
  }
}
