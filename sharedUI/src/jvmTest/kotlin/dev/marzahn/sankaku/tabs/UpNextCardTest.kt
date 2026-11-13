package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * #216, #230: past the last source a swipe, › or TalkBack's "Up next" brings the Up next card, and past the last item
 * End of the queue. Both are text only, a page of their own with no grid behind, a pane with a heading for the title
 * that is focused as it appears, and their actions as buttons and as custom actions.
 */
@OptIn(ExperimentalTestApi::class)
class UpNextCardTest {
  private val start = Instant.parse("2026-11-01T12:00:00Z")
  private var now = start
  private val clock = object : Clock {
    override fun now() = now
  }
  private val tabs = TabsController(clock = clock, seen = SeenStore(InMemoryTextFile(), clock))
  private val upNext = UpNextState(tabs, PrivacySettings(InMemoryTextFile()), Messages())

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  // "oldest" (touched day 0) and "middle" (day 1) faded; "last" is shown, on day 5.
  private fun twoFaded() {
    tabs.open(grid("oldest"), background = true)
    now = start + 1.days
    tabs.open(grid("middle"), background = true)
    now = start + 5.days
    // Browse was looked at today, so it doesn't fade.
    tabs.activate(tabs.tabs.value.first().id)
    tabs.open(grid("last"), background = false)
    tabs.sweep()
    assertEquals(listOf("Oldest", "Middle"), tabs.faded.value.map { it.title })
  }

  /** The shell's part: a card stands in for the tab's content, and another item coming forward takes it away. */
  private fun ComposeUiTest.shell() {
    setContent {
      SankakuTheme {
        val activeId by tabs.active.collectAsState()
        val tabList by tabs.tabs.collectAsState()
        val active = tabList.first { it.id == activeId }
        LaunchedEffect(active.id) { upNext.dismiss() }
        Box(Modifier.size(400.dp).testTag("shell")) {
          QueueSwipeHost(active, tabs = tabs, upNext = upNext, undo = ClosedTabUndo(tabs), preload = { _, _ -> emptyMap() }, content = {
            Box(Modifier.size(400.dp).testTag("grid").queueSwipe(enabled = true, from = active.id)) {
              Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { Box(Modifier.height(4000.dp)) }
            }
          })
        }
      }
    }
  }

  private fun ComposeUiTest.swipeTowardsTheEnd() {
    onNodeWithTag("grid").performTouchInput { swipe(Offset(width - 20f, centerY), Offset(20f, centerY), 300) }
    waitForIdle()
  }

  /** A swipe on whatever is shown, a card included: [back] towards the end (the previous item), else towards the start. */
  private fun ComposeUiTest.swipeOnShown(back: Boolean) {
    onNodeWithTag("shell").performTouchInput {
      if (back) swipe(Offset(20f, centerY), Offset(width - 20f, centerY), 300) else swipe(Offset(width - 20f, centerY), Offset(20f, centerY), 300)
    }
    waitForIdle()
  }

  private fun ComposeUiTest.titleActions() =
    onNode(isHeading()).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

  private fun ComposeUiTest.paneTitle() =
    onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode().config[SemanticsProperties.PaneTitle]

  @Test
  fun aSwipePastTheLastSourceOffersTheFadedSourceWithOneOpenButtonAndNoGridBehind() = runComposeUiTest {
    twoFaded()
    shell()

    swipeTowardsTheEnd()

    onNodeWithText("Pick up Oldest again?").assertExists()
    onNodeWithText("Deleted in 28 days").assertExists()
    // One Open, plus Skip; no Open grid.
    onAllNodesWithText("Open").assertCountEquals(1)
    onNodeWithText("Open grid").assertDoesNotExist()
    onNodeWithText("Skip").assertExists()
    // The card is its own page: the grid isn't behind it.
    onNodeWithTag("grid").assertDoesNotExist()
    // The item stays where it was: the card is past it.
    assertEquals("Last", tabs.activeTab.title)
  }

  @Test
  fun theSearchLineShowsTheCardsOwnSearchOrNoneNeverTheGridsBehind() {
    twoFaded()
    val top = tabs.activeTab.top
    assertEquals(top, shownSearch(null, top))

    upNext.show()
    assertEquals(grid("oldest"), shownSearch(upNext.card, top))

    upNext.skip()
    upNext.skip()
    assertEquals(UpNextCard.End, upNext.card)
    assertEquals(grid(), shownSearch(upNext.card, top))
  }

  @Test
  fun aSwipeWithNothingToOfferShowsTheEndOfTheQueue() = runComposeUiTest {
    tabs.open(grid("a"), background = false)
    shell()

    swipeTowardsTheEnd()

    assertEquals(UpNextCard.End, upNext.card)
    onNodeWithTag(END_CARD).assertExists()
    onNodeWithText("You've reached the end").assertExists()
    onNodeWithText("Back to the start").assertExists()
    onNodeWithText("Browse").assertExists()
    onNodeWithTag("grid").assertDoesNotExist()
    assertEquals("A", tabs.activeTab.title)
  }

  @Test
  fun skipShowsTheNextFadedSourceAndTheLastSkipShowsTheEnd() = runComposeUiTest {
    twoFaded()
    shell()
    swipeTowardsTheEnd()

    onNodeWithContentDescription("Skip Oldest").performClick()
    waitForIdle()
    onNodeWithText("Pick up Middle again?").assertExists()

    onNodeWithContentDescription("Skip Middle").performClick()
    waitForIdle()
    onNodeWithTag(END_CARD).assertExists()
  }

  @Test
  fun openRestoresTheSourceAndShowsIt() = runComposeUiTest {
    twoFaded()
    shell()
    swipeTowardsTheEnd()

    onNodeWithText("Open").performClick()
    waitForIdle()

    assertNull(upNext.card)
    assertEquals("Oldest", tabs.activeTab.title)
    assertEquals(listOf("Middle"), tabs.faded.value.map { it.title })
    onNodeWithTag("grid").assertExists()
  }

  @Test
  fun backToTheStartGoesToItemOne() = runComposeUiTest {
    tabs.open(grid("a"), background = false)
    shell()
    swipeTowardsTheEnd()

    onNodeWithContentDescription("Back to the start of the queue").performClick()
    waitForIdle()

    assertNull(upNext.card)
    assertEquals(tabs.tabs.value.first().id, tabs.activeTab.id)
    onNodeWithTag("grid").assertExists()
  }

  @Test
  fun theUpNextTitleIsAHeadingTheFocusMovesToAndTheActionsAreButtonsAndCustomActions() = runComposeUiTest {
    twoFaded()
    shell()
    swipeTowardsTheEnd()

    onNode(isHeading()).assertIsFocused()
    assertEquals(listOf("Open", "Skip", "Back to Last"), titleActions())
    assertEquals(UP_NEXT_PANE, paneTitle())
    onNode(isHeading()).assert(
      SemanticsMatcher("describes the source") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Up next: pick up Oldest again? Deleted in 28 days") == true
      },
    )
    onNodeWithContentDescription("Open Oldest").assert(hasClickAction())
    onNodeWithContentDescription("Skip Oldest").assert(hasClickAction())
  }

  @Test
  fun theEndOfTheQueueHasTheSameTalkBackTreatment() = runComposeUiTest {
    tabs.open(grid("a"), background = false)
    shell()
    swipeTowardsTheEnd()

    onNode(isHeading()).assertIsFocused()
    assertEquals(END_PANE, paneTitle())
    assertEquals(listOf("Back to the start", "Browse", "Back to A"), titleActions())
    onNodeWithContentDescription("Browse, a new grid").assert(hasClickAction())

    val browse = onNode(isHeading()).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Browse" }
    runOnIdle { browse.action() }
    waitForIdle()
    assertNull(upNext.card)
    assertEquals(grid(), tabs.activeTab.top)
  }

  @Test
  fun focusMovesToTheNextCardAfterASkip() = runComposeUiTest {
    twoFaded()
    shell()
    swipeTowardsTheEnd()

    onNodeWithContentDescription("Skip Oldest").performClick()
    waitForIdle()

    onNode(isHeading()).assertIsFocused()
  }

  @Test
  fun aLastChanceReadsKeepAndLetGoAndLetGoMovesOn() = runComposeUiTest {
    tabs.open(grid("gone"), background = true)
    now = start + 4.days
    tabs.sweep()
    now = start + 26.days
    shell()

    upNext.show()
    waitForIdle()

    onNodeWithText("Last chance: Gone").assertExists()
    onNodeWithText("Deleted in 7 days").assertExists()
    assertEquals(listOf("Keep", "Let go", "Back to ${tabs.activeTab.title}"), titleActions())
    onNodeWithContentDescription("Let go of Gone").performClick()
    waitForIdle()
    assertTrue(tabs.faded.value.isEmpty())
    // Nothing else faded: the end.
    assertEquals(UpNextCard.End, upNext.card)
  }

  // #248: a swipe back on a card returns to the item it stands past, as the pill's ‹ does; a swipe forward does nothing.

  @Test
  fun aSwipeBackOnTheEndOfTheQueueShowsTheLastItem() = runComposeUiTest {
    tabs.open(grid("a"), background = false)
    shell()
    swipeTowardsTheEnd()
    assertEquals(UpNextCard.End, upNext.card)

    swipeOnShown(back = true)

    assertNull(upNext.card)
    onNodeWithTag("grid").assertExists()
    assertEquals("A", tabs.activeTab.title)
  }

  @Test
  fun aSwipeBackOnUpNextShowsTheItemItStandsPast() = runComposeUiTest {
    twoFaded()
    shell()
    swipeTowardsTheEnd()
    assertEquals("Oldest", (upNext.card as UpNextCard.Offer).title)

    swipeOnShown(back = true)

    assertNull(upNext.card)
    onNodeWithTag("grid").assertExists()
    assertEquals("Last", tabs.activeTab.title)
    // Nothing was answered: both sources stay faded.
    assertEquals(listOf("Oldest", "Middle"), tabs.faded.value.map { it.title })
  }

  @Test
  fun aSwipeForwardOnACardDoesNothing() = runComposeUiTest {
    twoFaded()
    shell()
    swipeTowardsTheEnd()

    // Up next: no Skip, no move on.
    swipeOnShown(back = false)
    assertEquals("Oldest", (upNext.card as UpNextCard.Offer).title)
    assertEquals("Last", tabs.activeTab.title)

    // End of the queue: nothing past it.
    upNext.skip()
    upNext.skip()
    waitForIdle()
    assertEquals(UpNextCard.End, upNext.card)
    swipeOnShown(back = false)
    assertEquals(UpNextCard.End, upNext.card)
    assertEquals("Last", tabs.activeTab.title)
  }

  @Test
  fun theSwipeBackIsACustomActionOnTheCardsTitle() = runComposeUiTest {
    tabs.open(grid("a"), background = false)
    shell()
    swipeTowardsTheEnd()

    val back = onNode(isHeading()).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Back to A" }
    runOnIdle { back.action() }
    waitForIdle()

    assertNull(upNext.card)
    assertEquals("A", tabs.activeTab.title)
  }

  private fun ComposeUiTest.pill(buttons: Boolean) {
    val settings = AccessibilitySettings(InMemoryTextFile()).apply { setShowQueueButtons(buttons) }
    setContent {
      SankakuTheme {
        Box(Modifier.size(412.dp, 300.dp)) {
          QueueBar(
            onOpenQueue = {},
            controller = tabs,
            undo = ClosedTabUndo(tabs),
            settings = settings,
            tips = Tips(InMemoryTextFile(), touch = false),
            upNext = upNext,
          )
        }
      }
    }
  }

  @Test
  fun theCountPillsArrowsGoThroughUpNextToTheEndAndBack() = runComposeUiTest {
    twoFaded()
    pill(buttons = true)

    // At the last source › is named for where it goes.
    onNodeWithContentDescription(UP_NEXT_ACTION).performClick()
    waitForIdle()
    assertEquals("Oldest", (upNext.card as UpNextCard.Offer).title)

    // On Up next, › goes on: no feeds, so to the end, and there › is off.
    onNodeWithContentDescription(END_PANE).performClick()
    waitForIdle()
    assertEquals(UpNextCard.End, upNext.card)
    onNodeWithContentDescription(NEXT_ITEM, substring = true).assertIsNotEnabled()

    // ‹ goes back to the item the card stands past.
    onNodeWithContentDescription("Back to Last").performClick()
    waitForIdle()
    assertNull(upNext.card)
    assertEquals("Last", tabs.activeTab.title)
  }

  @Test
  fun theCountPillOffersUpNextOrTheEndToTalkBack() = runComposeUiTest {
    twoFaded()
    pill(buttons = false)
    // On a card the pill names it (#501): "Queue, up next, …", "Queue, end, …".
    fun actions() = onNodeWithContentDescription("Queue, ", substring = true)
      .fetchSemanticsNode().config[SemanticsActions.CustomActions]

    runOnIdle { actions().single { it.label == UP_NEXT_ACTION }.action() }
    waitForIdle()
    assertEquals("Oldest", (upNext.card as UpNextCard.Offer).title)

    runOnIdle { actions().single { it.label == END_PANE }.action() }
    waitForIdle()
    assertEquals(UpNextCard.End, upNext.card)
    assertTrue(actions().none { it.label == NEXT_ITEM || it.label == END_PANE || it.label == UP_NEXT_ACTION })
  }
}
