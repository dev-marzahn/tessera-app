package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #504: M3's tooltips share one mutex, so a hover tooltip (desktop, under wherever the pointer rests as a viewer
 * closes) took it from a one-time tip that had just appeared, which was then gone for good. The tip holds it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class TipAnchorTest {
  @Test
  fun aHoverTooltipDoesntTakeATipAway() = runDesktopComposeUiTest {
    val tips = Tips(InMemoryTextFile(), touch = false).apply { offer(Tip.KeepLoop) }
    val hover = TooltipState()
    setContent {
      SankakuTheme {
        Column {
          Box(Modifier.size(100.dp)) { TipAnchor(Tip.KeepLoop, tips, visible = true, Modifier.size(100.dp)) }
          TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text("Hover hint") } },
            state = hover,
          ) { Box(Modifier.size(40.dp)) }
        }
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(Tip.KeepLoop.text).fetchSemanticsNodes().isNotEmpty() }

    // As a hover shows it.
    val hovering = CoroutineScope(Dispatchers.Main).launch { runCatching { hover.show(MutatePriority.UserInput) } }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()

    onAllNodesWithText(Tip.KeepLoop.text).assertCountEquals(1)
    hovering.cancel()
  }

  @Test
  fun theNextTipWaitsUntilTheGestureAfterOneEndsAndThatGestureStillLands() = runDesktopComposeUiTest {
    // #538: the first swipe after Got it went to dismissing the tip that came at once.
    val tips = Tips(InMemoryTextFile(), touch = true).apply {
      offer(Tip.QueueSwipe)
      appeared(Tip.QueueSwipe)
      dismissed(Tip.QueueSwipe)
    }
    var dragged = 0f
    setContent {
      Box(Modifier.size(300.dp).endsTipRest(tips).testTag("window")) {
        Box(Modifier.size(300.dp).draggable(rememberDraggableState { dragged += it }, Orientation.Horizontal))
      }
    }
    onNodeWithTag("window").performTouchInput { down(center) }
    onNodeWithTag("window").performTouchInput { moveBy(Offset(-100f, 0f)) }
    // Mid-swipe, no tip yet.
    assertTrue(tips.resting)
    onNodeWithTag("window").performTouchInput { up() }

    assertFalse(tips.resting)
    assertTrue(dragged < 0f, "the swipe reached what's under it")
  }

  @Test
  fun noTipOverAPageThatCoversTheWindowAndOneShownGoes() = runDesktopComposeUiTest {
    // #522: the queue pill's swipe tip came up over a tag's page.
    val tips = Tips(InMemoryTextFile(), touch = false).apply { offer(Tip.KeepLoop) }
    tips.covered = true
    setContent {
      SankakuTheme { Box(Modifier.size(100.dp)) { TipAnchor(Tip.KeepLoop, tips, visible = true, Modifier.size(100.dp)) } }
    }
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    onAllNodesWithText(Tip.KeepLoop.text).assertCountEquals(0)

    tips.covered = false
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(Tip.KeepLoop.text).fetchSemanticsNodes().isNotEmpty() }
    tips.covered = true
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(Tip.KeepLoop.text).fetchSemanticsNodes().isEmpty() }
  }
}
