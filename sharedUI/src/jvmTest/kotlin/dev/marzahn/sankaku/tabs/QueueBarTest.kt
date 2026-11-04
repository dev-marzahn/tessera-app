package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import dev.marzahn.sankaku.ui.components.CLOSE_LABEL
import dev.marzahn.sankaku.ui.components.REOPEN_LABEL
import dev.marzahn.sankaku.ui.components.UNDO_CLOSE_LABEL
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The count pill that retired the phone's tab bar (#178), its Undo, and the queue buttons. */
@OptIn(ExperimentalTestApi::class)
class QueueBarTest {
  private val tabs = TabsController().apply {
    open(Route.Grid(listOf("a")), background = true)
    open(Route.Grid(listOf("b")), background = true)
  }
  private val undo = ClosedTabUndo(tabs)
  private val settings = AccessibilitySettings(InMemoryTextFile())

  private fun ComposeUiTest.pill(): SemanticsNodeInteraction = onNodeWithContentDescription("Queue, item", substring = true)

  private fun ComposeUiTest.actions(): List<String> =
    pill().fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

  private fun ComposeUiTest.runAction(label: String) {
    val action = pill().fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
    runOnIdle { action.action() }
  }

  /** The bar on a 412 dp phone with a 24 dp navigation bar, on the bottom row's line. */
  private fun ComposeUiTest.showBar(
    controller: TabsController = tabs,
    closedUndo: ClosedTabUndo = undo,
    onOpen: () -> Unit = {},
    width: Dp = 412.dp,
    window: Boolean = true,
  ) {
    setContent {
      SankakuTheme {
        // Without the window, Undo stays until it's used: an idle test clock would run a window out at once.
        if (window) ClosedTabUndoWindow(closedUndo, controller)
        Box(Modifier.size(width, 300.dp)) {
          QueueBar(
            onOpenQueue = onOpen,
            bottom = BottomRowLayout.roomAbove(navigationBar = 24.dp),
            controller = controller,
            undo = closedUndo,
            settings = settings,
            tips = noTips(),
            upNext = noUpNext(controller),
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }
    }
  }

  @Test
  fun thePillShowsThePlaceInTheQueueOnTheRowsLineAndOpensTheQueuePage() = runComposeUiTest {
    var opened = 0
    showBar(onOpen = { opened++ })

    onNodeWithText("1 / 3").assertExists()
    val pill = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    assertEquals(206f, pill.centre.x.value, 1f, "$pill")
    assertEquals((300.dp - 24.dp - 32.dp).value, pill.centre.y.value, 0.5f)
    pill().performClick()
    assertEquals(1, opened)
  }

  @Test
  fun withoutTheSettingThereIsNoCloseAndNoArrows() = runComposeUiTest {
    showBar()

    onNodeWithContentDescription(CLOSE_LABEL).assertDoesNotExist()
    onNodeWithContentDescription(PREVIOUS_ITEM, substring = true).assertDoesNotExist()
    onNodeWithContentDescription(NEXT_ITEM, substring = true).assertDoesNotExist()
  }

  @Test
  fun oneItemStillReadsOneOfOne() = runComposeUiTest {
    val one = TabsController()
    showBar(controller = one, closedUndo = ClosedTabUndo(one))

    onNodeWithText("1 / 1").assertExists()
    pill().assertHasClickAction()
  }

  @Test
  fun followingIsNotCountedAndCannotBeClosed() = runComposeUiTest {
    tabs.pinFeeds(listOf(Feed.Following))
    settings.setShowQueueButtons(true)
    showBar(window = false)

    // On the other items, the count leaves the feed out.
    onNodeWithText("1 / 3").assertExists()
    runOnIdle { tabs.activate(tabs.tabs.value.last().id) }

    // On Following, the pill names it, and there's no ✕ and no Close action.
    onNodeWithText("Following").assertExists()
    onNodeWithContentDescription("Queue, Following, after 3 items").assertExists()
    onNodeWithContentDescription(CLOSE_LABEL).assertDoesNotExist()
    val actions = onNodeWithContentDescription("Queue, Following", substring = true)
      .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
    assertFalse(CLOSE_LABEL in actions, "$actions")
    assertTrue(PREVIOUS_ITEM in actions, "$actions")
  }

  @Test
  fun talkBackHasPreviousNextAndCloseWithoutTheSetting() = runComposeUiTest {
    tabs.activate(tabs.tabs.value[1].id)
    showBar(window = false)

    assertEquals(listOf(PREVIOUS_ITEM, NEXT_ITEM, CLOSE_LABEL), actions())
    runAction(NEXT_ITEM)
    assertEquals("B", tabs.activeTab.title)
    // At the end, next is End of the queue (#230).
    assertEquals(listOf(PREVIOUS_ITEM, END_PANE, CLOSE_LABEL), actions())
    runAction(PREVIOUS_ITEM)
    assertEquals("A", tabs.activeTab.title)

    runAction(CLOSE_LABEL)
    assertEquals(listOf("Browse", "B"), tabs.tabs.value.map { it.title })
    // While Undo is offered, it's an action too, and so is the hold's reopen.
    assertEquals(listOf(PREVIOUS_ITEM, END_PANE, CLOSE_LABEL, UNDO_CLOSE_LABEL, REOPEN_LABEL), actions())
    runAction(UNDO_CLOSE_LABEL)
    assertEquals(listOf("Browse", "A", "B"), tabs.tabs.value.map { it.title })
    assertEquals("A", tabs.activeTab.title)
  }

  @Test
  fun undoGrowsOutOfThePillsLeftAndReopensWhatClosed() = runComposeUiTest {
    tabs.activate(tabs.tabs.value[1].id)
    showBar(window = false)
    val before = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    onNodeWithContentDescription(UNDO_CLOSE_LABEL).assertDoesNotExist()

    // A drag down on a lone post closes through the same holder.
    runOnIdle { undo.dragClose(tabs.activeTab.id, noUpNext(tabs)) }

    val chip = onNodeWithContentDescription(UNDO_CLOSE_LABEL).assertHasClickAction()
    chip.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    val bounds = chip.getUnclippedBoundsInRoot()
    val after = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    assertEquals(before, after, "the pill moved")
    assertEquals(after.left - 8.dp, bounds.right)
    assertEquals(after.centre.y, bounds.centre.y)
    assertTrue(bounds.bottom - bounds.top >= 48.dp)
    // Said politely as it happens.
    onNode(liveRegion("Closed “A”. Undo available")).assertExists()

    chip.performClick()
    assertEquals(listOf("Browse", "A", "B"), tabs.tabs.value.map { it.title })
    waitForIdle()
    onNodeWithContentDescription(UNDO_CLOSE_LABEL).assertDoesNotExist()
  }

  /** The width each Undo on screen is drawn at: while one grows, its node is clipped to what's shown. */
  private fun ComposeUiTest.undoWidths(): List<Float> {
    val chips = onAllNodesWithContentDescription(UNDO_CLOSE_LABEL)
    return List(chips.fetchSemanticsNodes().size) { i -> chips[i].getBoundsInRoot().let { it.right - it.left }.value }
  }

  /**
   * Two bars sharing [undo], on a manual clock: the first always, the second once [second] says so, as the bar of a
   * screen the user moves on to.
   */
  private fun ComposeUiTest.showTwoBars(second: () -> Boolean) {
    mainClock.autoAdvance = false
    setContent {
      SankakuTheme {
        Column {
          val bar = @Composable {
            QueueBar(onOpenQueue = {}, controller = tabs, undo = undo, settings = settings, tips = noTips(), upNext = noUpNext(tabs))
          }
          bar()
          if (second()) bar()
        }
      }
    }
  }

  /** The last Undo's width in each of the next [frames] frames, logged as whole dp. */
  private fun ComposeUiTest.lastUndoWidths(name: String, frames: Int = 30): List<Float> = List(frames) {
    mainClock.advanceTimeByFrame()
    undoWidths().last()
  }.also { println("$name, undo width per frame (dp): ${it.map { w -> w.roundToInt() }}") }

  /** [widths] start at under half of [full] and pass through at least four frames between, as a grow does. */
  private fun assertGrows(widths: List<Float>, full: Float) {
    assertTrue(widths.first() < full / 2, "$widths")
    assertTrue(widths.count { it > 1f && it < full - 1f } >= 4, "$widths")
    assertEquals(full, widths.last(), 0.5f, "$widths")
  }

  @Test
  fun undoGrowsInABarComposedAfterTheCloseAndALaterBarShowsItAtOnce() = runComposeUiTest {
    // A drag down onto the next item, or the queue page's swipe-close: the bar that shows Undo is a new one.
    runOnIdle { undo.dragClose(tabs.activeTab.id, noUpNext(tabs)) }
    var second by mutableStateOf(false)
    showTwoBars { second }

    val widths = lastUndoWidths("bar composed after the close")
    val full = widths.last()
    assertTrue(full > 48f, "$widths")
    assertGrows(widths, full)

    // Once the enter has settled (its fade outlasts the width), a bar composed later for the same close (the user
    // moves on) shows it at once, at full width.
    mainClock.advanceTimeBy(1_000)
    second = true
    mainClock.advanceTimeByFrame()
    val both = undoWidths()
    println("later bar's first frame (dp): ${both.map { it.roundToInt() }}")
    assertEquals(2, both.size)
    both.forEach { assertEquals(full, it, 0.5f) }
  }

  @Test
  fun aSecondCloseWhileUndoShowsStillGrowsInTheNextBar() = runComposeUiTest {
    tabs.open(Route.Grid(listOf("c")), background = true)
    var second by mutableStateOf(false)
    showTwoBars { second }
    runOnIdle { undo.close(tabs.activeTab.id) }
    val full = lastUndoWidths("first close, in place").last()
    mainClock.advanceTimeBy(1_000)

    // Dragging the next lone post down while Undo shows: the bar there keeps it shown, and the screen's new bar grows it.
    runOnIdle { undo.dragClose(tabs.activeTab.id, noUpNext(tabs)) }
    mainClock.advanceTimeBy(1_000)
    second = true
    assertGrows(lastUndoWidths("second close, the next bar"), full)
  }

  @Test
  fun undoLeavesWhenItsWindowEnds() = runComposeUiTest {
    mainClock.autoAdvance = false
    showBar()
    runOnIdle { undo.close(tabs.activeTab.id) }
    mainClock.advanceTimeByFrame()
    onNodeWithContentDescription(UNDO_CLOSE_LABEL).assertExists()

    mainClock.advanceTimeBy(11_000)
    assertEquals(null, undo.pending.value)
    // The closed item can still come back with a hold.
    assertTrue(REOPEN_LABEL in actions())
  }

  @Test
  fun theReopenTipShowsOnThePillOnceUndoHasGoneAndNotAgain() = runComposeUiTest {
    mainClock.autoAdvance = false
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true)
    val tipped = ClosedTabUndo(tabs, tips)
    setContent {
      SankakuTheme {
        ClosedTabUndoWindow(tipped, tabs)
        QueueBar(onOpenQueue = {}, controller = tabs, undo = tipped, settings = settings, tips = tips, upNext = noUpNext(tabs))
      }
    }
    runOnIdle { tipped.close(tabs.activeTab.id) }
    // Not while Undo is offered, where the tip's first tap would be a tap on Undo.
    mainClock.advanceTimeBy(1_000)
    onNodeWithContentDescription(UNDO_CLOSE_LABEL).assertExists()
    onNodeWithText(Tip.ReopenHold.text).assertDoesNotExist()

    // Undo's window has run out: the hold is the way back, and the tip on the pill says so.
    mainClock.advanceTimeBy(11_000)
    assertEquals(null, tipped.pending.value)
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(Tip.ReopenHold.text).assertExists()
    assertEquals(Tip.ReopenHold, tips.current)

    onNodeWithText("Got it").performClick()
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(Tip.ReopenHold.text).assertDoesNotExist()
    assertEquals(null, tips.current)
    // Dismissed for good: another close, and a restart, bring it back nowhere.
    runOnIdle { tipped.close(tabs.activeTab.id) }
    mainClock.advanceTimeBy(13_000)
    onNodeWithText(Tip.ReopenHold.text).assertDoesNotExist()
    assertFalse(Tips(file, enabled = true).pending(Tip.ReopenHold))
  }

  @Test
  fun aReopenTipWhoseMomentPassedBeforeItsAnchorCameFreesItsPlace() = runComposeUiTest {
    val tips = Tips(InMemoryTextFile(), enabled = true)
    val tipped = ClosedTabUndo(tabs, tips)
    // Offered once a window ran out, then a close brought Undo back before the pill's anchor was composed.
    tipped.close(tabs.activeTab.id)
    assertTrue(tips.offer(Tip.ReopenHold))
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = {}, controller = tabs, undo = tipped, settings = settings, tips = tips, upNext = noUpNext(tabs))
      }
    }
    waitForIdle()
    assertEquals(null, tips.current)
    // Not seen: it can still come.
    assertTrue(tips.pending(Tip.ReopenHold))
  }

  @Test
  fun aHoldThatReopensMeansTheReopenTipNeverShows() {
    val file = InMemoryTextFile()
    val tipped = ClosedTabUndo(tabs, Tips(file, enabled = true))
    tipped.close(tabs.activeTab.id)
    assertTrue(tipped.reopenLatest())
    assertFalse(Tips(file, enabled = true).pending(Tip.ReopenHold))
  }

  @Test
  fun aHoldWithNothingToReopenLeavesTheReopenTip() {
    val file = InMemoryTextFile()
    val tipped = ClosedTabUndo(tabs, Tips(file, enabled = true))
    assertFalse(tipped.reopenLatest())
    assertTrue(Tips(file, enabled = true).pending(Tip.ReopenHold))
  }

  @Test
  fun holdingThePillReopensTheMostRecentlyClosedItemAtAnyTime() = runComposeUiTest {
    tabs.open(Route.Grid(listOf("c")), background = true)
    showBar()
    pill().assert(!hasAction(REOPEN_LABEL))
    assertFalse(pill().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().contains("reopen"))

    runOnIdle { undo.close(tabs.activeTab.id) }
    runOnIdle { undo.close(tabs.activeTab.id) }
    assertEquals(listOf("B", "C"), tabs.tabs.value.map { it.title })
    pill().assert(hasAction(REOPEN_LABEL))
    assertEquals(REOPEN_LABEL, pill().fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    assertEquals("Queue, item 1 of 2, closed items to reopen", pill().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())

    // Each hold walks back: "a", then Browse, each where it was.
    pill().performTouchInput { longClick() }
    assertEquals(listOf("A", "B", "C"), tabs.tabs.value.map { it.title })
    assertEquals("A", tabs.activeTab.title)
    pill().performTouchInput { longClick() }
    assertEquals(listOf("Browse", "A", "B", "C"), tabs.tabs.value.map { it.title })

    // Nothing left to reopen: a hold changes nothing.
    pill().performTouchInput { longClick() }
    assertEquals(4, tabs.tabs.value.size)
  }

  @Test
  fun showQueueButtonsBringsBackCloseAndTheArrows() = runComposeUiTest {
    settings.setShowQueueButtons(true)
    showBar(window = false)

    val close = onNodeWithContentDescription(CLOSE_LABEL).assertHasClickAction()
    val corner = close.getUnclippedBoundsInRoot()
    assertEquals(44f, corner.centre.x.value, 0.5f)
    assertEquals(56.dp, corner.right - corner.left)
    val pill = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    assertEquals(pill.centre.y, corner.centre.y)

    // ‹ › name where they go, and stop at the ends.
    onNodeWithContentDescription("$PREVIOUS_ITEM", substring = true).assertIsNotEnabled()
    onNodeWithContentDescription("$NEXT_ITEM: A").performClick()
    onNodeWithContentDescription("$NEXT_ITEM: B").performClick()
    onNodeWithText("3 / 3").assertExists()
    // Past the last item › goes to End of the queue (#230).
    onNodeWithContentDescription(END_PANE).assertIsEnabled()
    onNodeWithContentDescription("$PREVIOUS_ITEM: A").assertIsEnabled()

    // ✕ closes, and its Undo, ↶ alone, fits between ✕ and the pill.
    close.performClick()
    assertEquals(listOf("Browse", "A"), tabs.tabs.value.map { it.title })
    val chip = onNodeWithContentDescription(UNDO_CLOSE_LABEL).getUnclippedBoundsInRoot()
    val after = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    assertTrue(corner.right <= chip.left, "✕ ${corner.right}, ↶ ${chip.left}")
    assertTrue(chip.right <= after.left, "↶ ${chip.right}, pill ${after.left}")
    assertTrue(chip.right - chip.left >= 48.dp)

    // ✕ still holds to reopen.
    close.performTouchInput { longClick() }
    assertEquals(listOf("Browse", "A", "B"), tabs.tabs.value.map { it.title })
  }

  @Test
  fun onANarrowPhoneWithALongCountUndoGoesAboveCloseAndCoversNothing() = runComposeUiTest {
    // The owner's case (#118): about 360 dp wide, a three-digit count, queue buttons on.
    settings.setShowQueueButtons(true)
    val many = TabsController().apply { repeat(249) { open(Route.Grid(listOf("t$it")), background = true) } }
    val manyUndo = ClosedTabUndo(many)
    many.activate(many.tabs.value[192].id)
    showBar(controller = many, closedUndo = manyUndo, width = 360.dp, window = false)
    val before = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()

    runOnIdle { manyUndo.close(many.activeTab.id) }

    onNodeWithText("193 / 249").assertExists()
    val pill = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    assertEquals(before, pill, "the pill moved")
    val close = onNodeWithContentDescription(CLOSE_LABEL).getUnclippedBoundsInRoot()
    val chip = onNodeWithContentDescription(UNDO_CLOSE_LABEL).getUnclippedBoundsInRoot()
    assertTrue(close.right <= pill.left, "✕ ${close.right}, pill ${pill.left}")
    assertTrue(chip.bottom <= close.top, "↶ $chip, ✕ $close")
  }

  @Test
  fun everyPieceIsAButtonOfAtLeast48Dp() = runComposeUiTest {
    settings.setShowQueueButtons(true)
    tabs.activate(tabs.tabs.value[1].id)
    showBar(window = false)
    runOnIdle { undo.close(tabs.activeTab.id) }

    listOf(
      onNodeWithContentDescription(CLOSE_LABEL),
      onNodeWithContentDescription(PREVIOUS_ITEM, substring = true),
      // The closed item was the last, so › now goes to End of the queue (#230).
      onNodeWithContentDescription(END_PANE),
      onNodeWithContentDescription(UNDO_CLOSE_LABEL),
      pill(),
    ).forEach { node ->
      node.assertHasClickAction()
      node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      val bounds = node.getUnclippedBoundsInRoot()
      assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp, "$bounds")
    }
  }

  @Test
  fun closingTheLastItemLeavesBrowse() = runComposeUiTest {
    val only = TabsController().apply { activeTab.backStack.add(Route.Grid(listOf("x"))) }
    showBar(controller = only, closedUndo = ClosedTabUndo(only))

    runAction(CLOSE_LABEL)

    assertEquals(listOf(listOf<Route>(Route.Grid(emptyList()))), only.tabs.value.map { it.backStack.toList() })
    onNodeWithText("1 / 1").assertExists()
  }

  @Test
  fun theRowsLineStays32DpAboveTheNavigationBar() {
    // The 56 dp row's middle on R2's line: 4 dp of room above the bar's inset.
    assertEquals(28.dp, BottomRowLayout.roomAbove(navigationBar = 24.dp))
    assertEquals(52.dp, BottomRowLayout.roomAbove(navigationBar = 48.dp))
    assertEquals(BottomRowLayout.CentreLine, BottomRowLayout.roomAbove(navigationBar = 0.dp) + BottomRowLayout.Height / 2)
  }

  @Test
  fun theQueueTipShowsOnceASecondItemAppearsAndNotAgain() = runComposeUiTest {
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true)
    val tabs = TabsController().apply { activeTab.backStack.add(Route.Grid(listOf("a"))) }
    setContent { SankakuTheme { QueueBar(onOpenQueue = {}, controller = tabs, undo = ClosedTabUndo(tabs), settings = settings, tips = tips, upNext = noUpNext(tabs)) } }
    // One item: the pill, but no tip yet.
    onNodeWithText(Tip.TabList.text).assertDoesNotExist()

    tips.counted(1)
    tabs.open(Route.Grid(listOf("b")), background = true)
    tips.counted(2)
    // Past the wait for the pill to settle.
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(Tip.TabList.text).assertExists()
    onNodeWithText("Got it").performClick()
    waitForIdle()
    onNodeWithText(Tip.TabList.text).assertDoesNotExist()
    assertEquals(null, tips.current)
    assertFalse(Tips(file, enabled = true).pending(Tip.TabList))
  }

  @Test
  fun theQueueTipNeverShowsOnceTheQueueWasOpened() = runComposeUiTest {
    val tips = Tips(InMemoryTextFile(), enabled = true).apply {
      used(Tip.TabList)
      counted(1)
      counted(2)
    }
    val tabs = TabsController().apply { open(Route.Grid(listOf("a")), background = true) }
    setContent { SankakuTheme { QueueBar(onOpenQueue = {}, controller = tabs, undo = ClosedTabUndo(tabs), settings = settings, tips = tips, upNext = noUpNext(tabs)) } }
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(Tip.TabList.text).assertDoesNotExist()
  }
}

private fun hasAction(label: String) = SemanticsMatcher("has $label") { node ->
  node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
}

private fun liveRegion(text: String) = SemanticsMatcher("live region \"$text\"") { node ->
  node.config.getOrNull(SemanticsProperties.LiveRegion) != null &&
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
}

private val DpRect.centre get() = DpCenter((left + right) / 2, (top + bottom) / 2)

private data class DpCenter(val x: Dp, val y: Dp)

/** The tips, off: these tests are about the bar, and its queue tip is tested in `TipsTest`. */
private fun noTips() = Tips(InMemoryTextFile(), enabled = false)
