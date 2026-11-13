package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #494: the phone viewer's details sheet opens and closes without a drag. Its handle is a button that steps through
 * the stops and says which one it's at, the picture and the handle have Expand and Collapse actions, and a screen
 * reader's scroll moves the sheet a stop. The sheet here is [ViewerSheetState] with the handle, the actions and the
 * modifiers [PhoneViewer] gives it, over details that scroll as the post's do.
 */
@OptIn(ExperimentalTestApi::class)
class SheetHandleTest {
  private lateinit var sheet: ViewerSheetState
  private var expandedFromPicture = 0

  private fun ComposeUiTest.start() {
    setContent {
      val scope = rememberCoroutineScope()
      sheet = remember { ViewerSheetState(scope, tween(100), flingThreshold = 1200f) }
      LaunchedEffect(Unit) { sheet.update(SheetStops(half = HALF, full = FULL)) }
      // As PhoneViewer's picture: read in composition, so they follow the sheet.
      val pictureActions = sheetActions(sheet) { expandedFromPicture++ }
      Column(Modifier.size(400.dp, 800.dp)) {
        Box(Modifier.testTag("picture").size(400.dp, 100.dp).semantics { customActions = pictureActions })
        Column(Modifier.testTag("sheet").fillMaxWidth().height(700.dp).viewerSheet(sheet)) {
          SheetHandle(sheet, fullFraction = { 0f })
          Column(Modifier.testTag("details").fillMaxWidth().height(600.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.size(400.dp, 2000.dp))
          }
        }
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.handle(): SemanticsNodeInteraction = onNodeWithContentDescription("Details")

  private fun SemanticsNodeInteraction.state(): String? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

  private fun SemanticsNodeInteraction.actionLabels(): List<String> =
    fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

  @Test
  fun theHandleIsAButtonThatSaysItsStop() = runComposeUiTest {
    start()
    val node = handle().fetchSemanticsNode()
    assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
    assertEquals("Collapsed", handle().state())
    assertEquals("Expand", node.config.getOrNull(SemanticsActions.OnClick)?.label)
    // At peek it can only go up.
    assertEquals(listOf(EXPAND), handle().actionLabels())
  }

  @Test
  fun theHandleTakesItsRowAcrossTheSheet() = runComposeUiTest {
    start()
    // The handle's whole 16 dp row, the sheet's width, so peek stays 80 dp (the owner's choice on #494).
    assertEquals(onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.width, handle().fetchSemanticsNode().boundsInRoot.width)
    handle().assertHeightIsEqualTo(16.dp)
  }

  @Test
  fun aTapOnTheHandleStepsPeekHalfFullPeek() = runComposeUiTest {
    start()
    handle().performClick()
    waitForIdle()
    assertEquals(HALF, sheet.lift)
    assertEquals("Half expanded", handle().state())
    assertEquals(listOf(EXPAND, COLLAPSE), handle().actionLabels())

    handle().performClick()
    waitForIdle()
    assertEquals(FULL, sheet.lift)
    assertEquals("Expanded", handle().state())
    assertEquals("Collapse", handle().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
    assertEquals(listOf(COLLAPSE), handle().actionLabels())

    handle().performClick()
    waitForIdle()
    assertEquals(0f, sheet.lift)
    assertEquals("Collapsed", handle().state())
  }

  @Test
  fun theHandlesActionsMoveTheSheetAStop() = runComposeUiTest {
    start()
    handle().performCustomAccessibilityActionWithLabel(EXPAND)
    waitForIdle()
    assertEquals(HALF, sheet.lift)
    handle().performCustomAccessibilityActionWithLabel(EXPAND)
    waitForIdle()
    assertEquals(FULL, sheet.lift)
    handle().performCustomAccessibilityActionWithLabel(COLLAPSE)
    waitForIdle()
    assertEquals(HALF, sheet.lift)
  }

  @Test
  fun thePicturesActionsMoveTheSheetAStop() = runComposeUiTest {
    start()
    val picture = onNodeWithTag("picture")
    assertEquals(listOf(EXPAND), picture.actionLabels())
    picture.performCustomAccessibilityActionWithLabel(EXPAND)
    waitForIdle()
    assertEquals(HALF, sheet.lift)
    // Expanding from the picture brings tap-hidden chrome back first.
    assertEquals(1, expandedFromPicture)
    assertEquals(listOf(EXPAND, COLLAPSE), picture.actionLabels())
    picture.performCustomAccessibilityActionWithLabel(COLLAPSE)
    waitForIdle()
    assertEquals(0f, sheet.lift)
  }

  @Test
  fun aScreenReadersScrollOnTheSheetMovesItAStop() = runComposeUiTest {
    start()
    // TalkBack's scroll forward from the handle or ♡: the sheet is their nearest scrolling ancestor.
    val sheetNode = onNodeWithTag("sheet")
    assertNotNull(sheetNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
    sheetNode.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
    waitForIdle()
    assertEquals(HALF, sheet.lift)
    sheetNode.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -500f) }
    waitForIdle()
    assertEquals(0f, sheet.lift)
  }

  @Test
  fun aScreenReadersScrollInTheDetailsLiftsTheSheetAStop() = runComposeUiTest {
    start()
    val details = onNodeWithTag("details")
    // A scroll of 300 px from peek lands at half (400 px), not 300 px up between two stops.
    details.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
    waitForIdle()
    assertEquals(HALF, sheet.lift)
    // The sheet took the scroll: the details are still at their top.
    assertEquals(0f, details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
    details.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
    waitForIdle()
    assertEquals(FULL, sheet.lift)
    // Full, the details scroll.
    details.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
    waitForIdle()
    assertEquals(FULL, sheet.lift)
    assertTrue(details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
  }

  @Test
  fun aScreenReadersScrollBackAtTheDetailsTopLowersTheSheetAStop() = runComposeUiTest {
    start()
    runOnIdle { sheet.animateTo(FULL) }
    waitForIdle()
    // TalkBack itself would use the sheet's own scroll here, as the details can't go back at their top; this is the
    // path a mouse wheel takes, through the details' nested scroll.
    onNodeWithTag("details").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -300f) }
    waitForIdle()
    assertEquals(HALF, sheet.lift)
  }

  @Test
  fun aFingerInTheDetailsStillDragsTheSheet() = runComposeUiTest {
    start()
    mainClock.autoAdvance = false
    onNodeWithTag("details").performTouchInput {
      down(center)
      moveBy(Offset(0f, -120f))
      moveBy(Offset(0f, -120f))
    }
    mainClock.advanceTimeByFrame()
    // Following the finger, between stops, not sprung to one.
    val lift = sheet.lift
    assertTrue(lift > 100f && lift < HALF, "lift $lift")
    onNodeWithTag("details").performTouchInput { up() }
    mainClock.autoAdvance = true
    waitForIdle()
    assertTrue(sheet.lift in listOf(0f, HALF, FULL), "settled at ${sheet.lift}")
  }

  @Test
  fun aFlingUpInTheDetailsSettlesAtAStop() = runComposeUiTest {
    start()
    onNodeWithTag("details").performTouchInput { swipeUp(durationMillis = 100) }
    waitForIdle()
    assertTrue(sheet.lift in listOf(HALF, FULL), "settled at ${sheet.lift}")
  }

  private companion object {
    const val HALF = 400f
    const val FULL = 1000f
    const val EXPAND = "Expand details"
    const val COLLAPSE = "Collapse details"
  }
}
