package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.keptWaitsText
import dev.marzahn.sankaku.ui.openedInFrontText
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
    fontScale: Float = 1f,
  ) {
    setContent {
      SankakuTheme {
        // Without the window, Undo stays until it's used: an idle test clock would run a window out at once.
        if (window) ClosedTabUndoWindow(closedUndo, controller)
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
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
  }

  /**
   * #581: Undo after a close looks the same every time. It was "↶ Undo" where the word fitted beside the pill and ↶
   * alone where it didn't, so it changed with the block's title on the pill (#501), the phone's width and the text
   * size. Now it's the ↶ circle on 360 and 412 dp phones, at 1× and 2× text, short count or long title, always beside
   * the pill on its line and never over anything: where the circle wouldn't fit, the pill's title is shortened, never
   * its figures (owner, on the draft). A tap on it undoes.
   */
  @Test
  fun undoIsTheSameCircleBesideAnyPillAtAnyTextSize() {
    for (width in listOf(360.dp, 412.dp)) for (fontScale in listOf(1f, 2f)) for (titled in listOf(false, true)) {
      runComposeUiTest {
        // Closing p1 lands on p2, "Landscape · 1 / 1"; three plain grids have no block, so the pill is a count.
        val queue = if (titled) blocks().apply { show("p1") } else TabsController().apply {
          open(Route.Grid(listOf("a")), background = true)
          open(Route.Grid(listOf("b")), background = true)
        }
        val closed = ClosedTabUndo(queue)
        showBar(controller = queue, closedUndo = closed, width = width, window = false, fontScale = fontScale)
        runOnIdle { closed.close(queue.activeTab.id) }

        val case = "$width at ${fontScale}×, ${if (titled) "titled" else "plain"} pill"
        if (titled) onNodeWithText("Landscape").assertExists() else onNodeWithText("Landscape").assertDoesNotExist()
        val chip = onNodeWithContentDescription(UNDO_CLOSE_LABEL).assertHasClickAction().getUnclippedBoundsInRoot()
        val pill = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
        assertEquals(48f, (chip.right - chip.left).value, 0.5f, "$case: $chip")
        assertEquals(48f, (chip.bottom - chip.top).value, 0.5f, "$case: $chip")
        // Beside the pill, 8 dp from it, on its line, and at least 8 dp in from the screen's edge.
        assertEquals(8f, (pill.left - chip.right).value, 0.5f, "$case: undo $chip, pill $pill")
        assertEquals(pill.centre.y.value, chip.centre.y.value, 0.5f, "$case: undo $chip off the pill's line $pill")
        assertTrue(chip.left >= 8.dp, "$case: undo $chip within 8 dp of the edge")
        // The figures are whole, inside the pill: a title too long for the room is what gives way.
        val figures = onNodeWithText(if (titled) "1 / 1" else "1 / 2").getUnclippedBoundsInRoot()
        assertTrue(figures.left >= pill.left && figures.right <= pill.right, "$case: figures $figures, pill $pill")
        val before = queue.tabs.value.size
        onNodeWithContentDescription(UNDO_CLOSE_LABEL).performClick()
        assertEquals(before + 1, queue.tabs.value.size, "$case: the tap didn't undo")
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
    // The ↶ circle's 48 dp target (#581).
    val full = BottomRowLayout.PieceTarget.value
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
    // The swipe tip would come first, with a next item here (#501): done.
    val tips = Tips(file, touch = true).apply { used(Tip.QueueSwipe) }
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
    assertFalse(Tips(file, touch = true).pending(Tip.ReopenHold))
  }

  @Test
  fun aReopenTipWhoseMomentPassedBeforeItsAnchorCameFreesItsPlace() = runComposeUiTest {
    val tips = Tips(InMemoryTextFile(), touch = true).apply { used(Tip.QueueSwipe) }
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
  fun anOpenOnlyPillOffersNoReopenTipAndNoUndo() = runComposeUiTest {
    // #485 review: over the window the book reader's pill only opens the queue; the hold there doesn't reopen.
    val tips = Tips(InMemoryTextFile(), touch = true).apply { used(Tip.QueueSwipe) }
    val tipped = ClosedTabUndo(tabs, tips)
    tipped.close(tabs.activeTab.id)
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = {}, controller = tabs, undo = tipped, settings = settings, tips = tips, upNext = noUpNext(tabs), openOnly = true)
      }
    }
    mainClock.advanceTimeBy(1_000)
    onNodeWithContentDescription(UNDO_CLOSE_LABEL).assertDoesNotExist()
    // Undo's window runs out, where a full pill would offer the hold's tip.
    mainClock.advanceTimeBy(13_000)
    onNodeWithText(Tip.ReopenHold.text).assertDoesNotExist()
    assertTrue(tips.current != Tip.ReopenHold)
  }

  @Test
  fun movingOnWithThePillsNextButtonDoesWhatTheSwipeTipTeaches() = runComposeUiTest {
    // #513 review: with queue buttons on, › moves on as a swipe does, so the swipe tip is done.
    val file = InMemoryTextFile()
    // Another tip is up (its anchor elsewhere), so the swipe tip isn't shown, which would make it done by itself.
    val tips = Tips(file, touch = true).apply { offer(Tip.GridGestures) }
    settings.setShowQueueButtons(true)
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = {}, controller = tabs, undo = undo, settings = settings, tips = tips, upNext = noUpNext(tabs))
      }
    }
    assertTrue(tips.pending(Tip.QueueSwipe))
    // The button's own action, as a tap does it (a tip on screen would take a first tap for itself).
    onNodeWithContentDescription("$NEXT_ITEM: A").performSemanticsAction(SemanticsActions.OnClick)

    assertEquals("A", tabs.activeTab.title)
    assertFalse(Tips(file, touch = true).pending(Tip.QueueSwipe))
  }

  @Test
  fun aSearchOpenedInFrontOfAnItemGetsItsTipFirstWithShowQueue() = runComposeUiTest {
    // #559: the tag tap's tip, before the swipe tip, which keeps its turn.
    mainClock.autoAdvance = false
    val queue = TabsController().apply { open(Route.Grid(listOf("landscape")), background = false) }
    queue.activeTab.markTouched(queue.activeTab.top as Route.Grid)
    dev.marzahn.sankaku.TabsNavigator(queue).goTo(Route.Grid(listOf("tree")))
    assertEquals(listOf("Browse", "Tree", "Landscape"), queue.tabs.value.map { it.title })
    val tips = Tips(InMemoryTextFile(), touch = true)
    var queuePage = false
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = { queuePage = true }, controller = queue, undo = ClosedTabUndo(queue), settings = settings, tips = tips, upNext = noUpNext(queue))
      }
    }
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(openedInFrontText("Tree")).assertExists()
    assertTrue(tips.pending(Tip.QueueSwipe))

    onNodeWithText(SHOW_QUEUE).performClick()
    mainClock.advanceTimeBy(1_000)
    assertTrue(queuePage)
    onNodeWithText(openedInFrontText("Tree")).assertDoesNotExist()
    assertFalse(tips.pending(Tip.OpenedInFront))
  }

  @Test
  fun aSourceWithKeptPostsGetsTheKeptTip() = runComposeUiTest {
    // #559: where the kept posts went, named by their source.
    mainClock.autoAdvance = false
    val queue = blocks()
    queue.show("Landscape")
    val tips = Tips(InMemoryTextFile(), touch = true)
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = {}, controller = queue, undo = ClosedTabUndo(queue), settings = settings, tips = tips, upNext = noUpNext(queue))
      }
    }
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(keptWaitsText("Landscape")).assertExists()

    // A kept post of the block isn't the moment: the tip stays for its source.
    runOnIdle { queue.show("p1") }
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(keptWaitsText("Landscape")).assertDoesNotExist()
  }

  /** Browse, then Landscape with p1 and p2 kept, then Cat (#501). */
  private fun blocks() = TabsController().apply {
    open(Route.Grid(listOf("landscape")), background = false)
    openInBlock(listOf(Route.Post("p1", md5 = null, originTags = emptyList(), label = "p1", rating = "s")))
    openInBlock(listOf(Route.Post("p2", md5 = null, originTags = emptyList(), label = "p2", rating = "s")))
    open(Route.Grid(listOf("cat")), background = true)
  }

  private fun TabsController.show(title: String) = activate(tabs.value.single { it.title == title }.id)

  @Test
  fun inABlockThePillCountsTheBlockAndTalkBackHearsBoth() = runComposeUiTest {
    val queue = blocks()
    queue.show("p1")
    showBar(controller = queue, closedUndo = ClosedTabUndo(queue), window = false)

    onNodeWithText("Landscape").assertExists()
    onNodeWithText("1 / 2").assertExists()
    onNodeWithContentDescription("Queue, Landscape, kept post 1 of 2, item 3 of 5").assertExists()
    // Not the last of its block: no line over the pill.
    onNodeWithText("Last from", substring = true, useUnmergedTree = true).assertDoesNotExist()

    // On the source, how many kept posts follow it.
    runOnIdle { queue.show("Landscape") }
    onNodeWithText("2 kept").assertExists()
    onNodeWithContentDescription("Queue, Landscape, 2 kept items follow, item 2 of 5").assertExists()
  }

  @Test
  fun onABlocksLastItemALineOverThePillSaysTheNextSwipeLeavesIt() = runComposeUiTest {
    val queue = blocks()
    queue.show("p2")
    showBar(controller = queue, closedUndo = ClosedTabUndo(queue), window = false)

    onNodeWithText("2 / 2").assertExists()
    // The line is drawn for the eye; TalkBack hears it in the pill's description (and the pane's title).
    onNodeWithText("Last from Landscape · next: Cat", useUnmergedTree = true).assertExists()
    onNodeWithText("Last from Landscape · next: Cat").assertDoesNotExist()
    onNodeWithContentDescription("Queue, Landscape, kept post 2 of 2, item 4 of 5, last, next Cat").assertExists()
    // Over the pill, centred, and taking no room from it: the pill stays on its line.
    val pill = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    val line = onNodeWithText("Last from Landscape · next: Cat", useUnmergedTree = true).getUnclippedBoundsInRoot()
    assertTrue(line.bottom <= pill.top, "$line over $pill")
    assertEquals(pill.centre.x.value, line.centre.x.value, 1f)
    assertEquals((300.dp - 24.dp - 32.dp).value, pill.centre.y.value, 0.5f)

    // Moving on into Cat, which has no kept posts: the plain count, and no line.
    runOnIdle { queue.next() }
    onNodeWithText("5 / 5").assertExists()
    onNodeWithText("Last from", substring = true, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun onEndOfTheQueueThePillSaysEndNotTheItemBehindIt() = runComposeUiTest {
    val queue = blocks()
    val upNext = noUpNext(queue)
    queue.show("Cat")
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = {}, controller = queue, undo = ClosedTabUndo(queue), settings = settings, tips = noTips(), upNext = upNext)
      }
    }
    runOnIdle { upNext.forward() }

    onNodeWithText("End").assertExists()
    onNodeWithText("5").assertExists()
    onNodeWithContentDescription("Queue, end, after 5 items").assertExists()
  }

  @Test
  fun theSwipeTipComesFirstWhenThereIsANextItemEvenInAQueueRestoredAtLaunch() = runComposeUiTest {
    val tips = Tips(InMemoryTextFile(), touch = true)
    val queue = TabsController()
    // Restored with two items at launch: no second item "appears", but there's somewhere to swipe to.
    tips.counted(2)
    queue.open(Route.Grid(listOf("cat")), background = true)
    // On the last item there's nowhere to swipe: no tip.
    queue.show("Cat")
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = {}, controller = queue, undo = ClosedTabUndo(queue), settings = settings, tips = tips, upNext = noUpNext(queue))
      }
    }
    mainClock.advanceTimeBy(1_000)
    assertEquals(null, tips.current)

    // On the first, the swipe tip, before "Tap the count…".
    runOnIdle { queue.show("Browse") }
    // Past the wait for the pill to settle.
    mainClock.advanceTimeBy(1_000)
    assertEquals(Tip.QueueSwipe, tips.current)
    onNodeWithText(Tip.QueueSwipe.text).assertExists()

    // Used before it showed, it never shows.
    val used = Tips(InMemoryTextFile(), touch = true).apply { used(Tip.QueueSwipe) }
    assertFalse(used.pending(Tip.QueueSwipe))
  }

  @Test
  fun aPillWhereASwipeStepsPostsOffersNoSwipeTip() = runComposeUiTest {
    // #538: the viewer over a grid draws its own pill, where a sideways swipe goes to the grid's next post, not the
    // next queue item.
    val tips = Tips(InMemoryTextFile(), touch = true)
    val queue = TabsController()
    tips.counted(2)
    queue.open(Route.Grid(listOf("cat")), background = true)
    var swipesQueue by mutableStateOf(false)
    setContent {
      SankakuTheme {
        QueueBar(
          onOpenQueue = {},
          controller = queue,
          undo = ClosedTabUndo(queue),
          settings = settings,
          tips = tips,
          upNext = noUpNext(queue),
          swipesQueue = swipesQueue,
        )
      }
    }
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(Tip.QueueSwipe.text).assertDoesNotExist()
    assertTrue(tips.current != Tip.QueueSwipe)
    // Not seen, so it still comes where the swipe does move on: back on the grid, or on a kept post.
    assertTrue(tips.pending(Tip.QueueSwipe))

    runOnIdle { swipesQueue = true }
    mainClock.advanceTimeBy(1_000)
    assertEquals(Tip.QueueSwipe, tips.current)
    onNodeWithText(Tip.QueueSwipe.text).assertExists()
  }

  @Test
  fun afterGotItOnTheSwipeTipTheQueueTipWaitsForTheNextGesture() = runComposeUiTest {
    // #538: the queue tip came at once over the pill and took the first swipe after Got it to dismiss itself.
    mainClock.autoAdvance = false
    val tips = Tips(InMemoryTextFile(), touch = true)
    val queue = TabsController()
    tips.counted(1)
    setContent {
      SankakuTheme {
        QueueBar(onOpenQueue = {}, controller = queue, undo = ClosedTabUndo(queue), settings = settings, tips = tips, upNext = noUpNext(queue))
      }
    }
    // A second item appears: both tips are due, the swipe tip first.
    runOnIdle {
      queue.open(Route.Grid(listOf("cat")), background = true)
      tips.counted(2)
    }
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(Tip.QueueSwipe.text).assertExists()

    onNodeWithText("Got it").performClick()
    mainClock.advanceTimeBy(2_000)
    onNodeWithText(Tip.TabList.text).assertDoesNotExist()
    assertEquals(null, tips.current)

    // The swipe went through and ended: now the queue tip comes.
    runOnIdle { tips.gestureEnded() }
    mainClock.advanceTimeBy(1_000)
    onNodeWithText(Tip.TabList.text).assertExists()
  }

  @Test
  fun aHoldThatReopensMeansTheReopenTipNeverShows() {
    val file = InMemoryTextFile()
    val tipped = ClosedTabUndo(tabs, Tips(file, touch = true))
    tipped.close(tabs.activeTab.id)
    assertTrue(tipped.reopenLatest())
    assertFalse(Tips(file, touch = true).pending(Tip.ReopenHold))
  }

  @Test
  fun aHoldWithNothingToReopenLeavesTheReopenTip() {
    val file = InMemoryTextFile()
    val tipped = ClosedTabUndo(tabs, Tips(file, touch = true))
    assertFalse(tipped.reopenLatest())
    assertTrue(Tips(file, touch = true).pending(Tip.ReopenHold))
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
    // The swipe tip comes first where there's a next item (#501); done here, so this one is next.
    val tips = Tips(file, touch = true).apply { used(Tip.QueueSwipe) }
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
    assertFalse(Tips(file, touch = true).pending(Tip.TabList))
  }

  @Test
  fun theQueueTipNeverShowsOnceTheQueueWasOpened() = runComposeUiTest {
    val tips = Tips(InMemoryTextFile(), touch = true).apply {
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
private fun noTips() = Tips(InMemoryTextFile(), touch = false)
