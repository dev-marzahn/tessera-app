package dev.marzahn.sankaku.tabs

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.geometry.Offset
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** #120: a sideways swipe on a row always closes it; only a vertical drag on ≡ (or TalkBack) moves it. */
@OptIn(ExperimentalTestApi::class)
class QueuePageTest {
  private var now = Instant.parse("2026-11-01T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock).apply {
    open(Route.Grid(listOf("a")), background = true)
    open(Route.Grid(listOf("b")), background = true)
  }

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun ComposeUiTest.show() {
    val privacy = PrivacySettings(InMemoryTextFile())
    setContent {
      SankakuTheme {
        QueuePage(onDismiss = {}, controller = tabs, posts = TabPosts.None, privacy = privacy, seen = seen, clearViewed = ClearViewed(tabs, seen, Messages()), interacted = { false }, onInteract = {})
      }
    }
    waitForIdle()
  }

  @Test
  fun aSeenKeptPostSaysSeenAndMarkUnseenTakesItBack() = runComposeUiTest {
    tabs.open(Route.Post("p1", "m1", listOf("a"), label = "Rosehip"), background = true)
    val kept = tabs.tabs.value.last()
    now += 5.minutes
    seen.record("p1", "m1")
    show()

    fun actions() =
      onNodeWithText("Rosehip").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
    onNodeWithText("Kept · seen").assertExists()
    assertTrue(kept.seenSinceKept(seen))

    now += 1.minutes
    actions().first { it.label == "Mark unseen" }.action()
    waitForIdle()

    assertFalse(kept.seenSinceKept(seen))
    onNodeWithText("Kept").assertExists()
    // Nothing to take back now, so the action's gone.
    assertFalse(actions().any { it.label == "Mark unseen" })
  }

  @Test
  fun followingIsAddedLastUncountedAndCannotBeClosed() = runComposeUiTest {
    tabs.pinFeeds(listOf(Feed.Following))
    show()

    // A feed is a source that persists (#327): its row says "Feed", under no "Feeds" section.
    onNodeWithText("Feeds").assertDoesNotExist()
    onNodeWithText("Feed").assertExists()
    onNodeWithContentDescription("3 items").assertExists()
    val row = onNodeWithText("Following")
    val actions = row.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
    assertFalse(actions.any { it.startsWith("Close") }, "$actions")
    row.performTouchInput { swipeLeft() }
    waitForIdle()

    assertEquals(listOf("Browse", "A", "B", "Following"), titles())
    assertTrue(tabs.closed.value.isEmpty())
  }

  /** The ≡ of the row at [index], the spot a right thumb starts a swipe to the left from. */
  private fun ComposeUiTest.handle(index: Int): SemanticsNodeInteraction =
    onAllNodesWithContentDescription("Move", useUnmergedTree = true)[index]

  /**
   * A thumb's swipe to the left from the middle of the node: it rests [restLongPresses] long-press timeouts, rolls a
   * little down and sideways as a thumb does (so the whole movement passes the touch slop before its sideways part),
   * then sweeps 600 px left, one step each [stepMillis].
   */
  private fun SemanticsNodeInteraction.thumbSwipeLeft(restLongPresses: Int, stepMillis: Long) = performTouchInput {
    down(center)
    advanceEventTime(viewConfiguration.longPressTimeoutMillis * restLongPresses)
    // Just past the slop in all, but only 0.6 of it sideways and 0.8 of it down.
    val slop = viewConfiguration.touchSlop * 1.05f
    repeat(4) { moveBy(Offset(-0.6f * slop / 4, 0.8f * slop / 4), delayMillis = stepMillis) }
    repeat(20) { moveBy(Offset(-30f, 1f), delayMillis = stepMillis) }
    up()
  }

  @Test
  fun aQuickSwipeToTheLeftThatStartsOnTheHandleClosesTheTab() = runComposeUiTest {
    show()

    handle(1).thumbSwipeLeft(restLongPresses = 0, stepMillis = 5)
    waitForIdle()

    assertEquals(listOf("Browse", "B"), titles())
    onNodeWithText("Closed “A”").assertExists()
  }

  @Test
  fun aSlowSwipeToTheLeftAfterTheFingerRestsOnTheHandleStillClosesTheTab() = runComposeUiTest {
    show()

    // Resting longer than a long press must not pick the row up either.
    handle(1).thumbSwipeLeft(restLongPresses = 3, stepMillis = 40)
    waitForIdle()

    assertEquals(listOf("Browse", "B"), titles())
    onNodeWithText("Closed “A”").assertExists()
  }

  @Test
  fun aVerticalDragOnTheHandleMovesTheTab() = runComposeUiTest {
    show()

    handle(0).performTouchInput { swipeDown(startY = centerY, endY = centerY + height * 1.6f, durationMillis = 600) }
    waitForIdle()

    assertEquals(listOf("A", "Browse", "B"), titles())
  }

  @Test
  fun draggingAHeadMovesItsBlockTogether() = runComposeUiTest {
    // a, with a post kept from it, is a block of two rows.
    tabs.activate(tabs.tabs.value[1].id)
    tabs.openInBlock(listOf(Route.Grid(listOf("a"), at = "p1"), Route.Post("p1", null, listOf("a"), label = "p1")))
    show()

    // Browse's ≡ down past half of a's block (one 72 dp row), not past half of b's after it.
    handle(0).performTouchInput { swipeDown(startY = centerY, endY = centerY + height * 3.5f, durationMillis = 800) }
    waitForIdle()

    assertEquals(listOf("A", "p1", "Browse", "B"), titles())
  }

  @Test
  fun talkBackHearsWhichSourceAKeptPostIsUnderWhileTheScreenSaysKept() = runComposeUiTest {
    tabs.activate(tabs.tabs.value[1].id)
    tabs.openInBlock(listOf(Route.Grid(listOf("a"), at = "p1"), Route.Post("p1", null, listOf("a"), label = "p1")))
    show()

    // What TalkBack reads on a row, in order (the post's rating isn't known, so it's blurred too).
    fun spoken(title: String) =
      onNodeWithText(title).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    assertEquals(listOf("Item 3 of 4", "Blurred", "Kept from A", "Move"), spoken("p1"))
    onNodeWithText("Kept").assertExists()
    // The source's own row says nothing of the kind.
    assertFalse(spoken("A").any { "from" in it }, "${spoken("A")}")

    // Closed, its kept post is from "A (closed)" (#184), and it's the current item now.
    tabs.close(tabs.tabs.value[1].id)
    waitForIdle()
    assertTrue("Kept from A (closed) · current" in spoken("p1"), "${spoken("p1")}")
  }

  /** The TalkBack Close of the row titled [title], as a swipe closes it. */
  private fun ComposeUiTest.closeRow(title: String) {
    onNodeWithText(title).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
      .first { it.label == "Close" }.action()
    waitForIdle()
  }

  @Test
  fun undoOfASwipedKeptPostPutsItBackWithoutMakingItCurrent() = runComposeUiTest {
    tabs.open(Route.Post("p1", "m1", listOf("a"), label = "Rosehip"), background = true)
    val keptAt = tabs.tabs.value.last().keptAt
    // A row after it, so "its place" isn't simply the end.
    tabs.open(Route.Grid(listOf("c")), background = true)
    show()
    assertEquals("Browse", tabs.tabs.value.first { it.id == tabs.active.value }.title)

    closeRow("Rosehip")
    assertEquals(listOf("Browse", "A", "B", "C"), titles())
    onNodeWithText("Undo").performClick()
    waitForIdle()

    // #306: item 1 is still current, and the post is back in its place, kept as before and unseen.
    assertEquals("Browse", tabs.tabs.value.first { it.id == tabs.active.value }.title)
    assertEquals(listOf("Browse", "A", "B", "Rosehip", "C"), titles())
    val back = tabs.tabs.value[3]
    assertEquals(keptAt, back.keptAt)
    assertFalse(back.seenSinceKept(seen))
    onNodeWithText("Kept").assertExists()
    onNodeWithText("Kept · current").assertDoesNotExist()
  }

  @Test
  fun undoOfTheCurrentRowsCloseMakesItCurrentAgain() = runComposeUiTest {
    tabs.activate(tabs.tabs.value[1].id)
    show()

    closeRow("A")
    assertEquals("B", tabs.tabs.value.first { it.id == tabs.active.value }.title)
    onNodeWithText("Undo").performClick()
    waitForIdle()

    assertEquals(listOf("Browse", "A", "B"), titles())
    assertEquals("A", tabs.tabs.value.first { it.id == tabs.active.value }.title)
  }

  @Test
  fun recentlyClosedStillMakesWhatItReopensCurrent() = runComposeUiTest {
    tabs.close(tabs.tabs.value[1].id)
    show()

    onNodeWithText("Recently closed").assertExists()
    onNodeWithText("A").performClick()
    waitForIdle()

    assertEquals(listOf("Browse", "A", "B"), titles())
    assertEquals("A", tabs.tabs.value.first { it.id == tabs.active.value }.title)
  }

  @Test
  fun talkBackOffersCloseAndTheMovesThatFit() = runComposeUiTest {
    show()

    fun actions(title: String) =
      onNodeWithText(title).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
    assertEquals(listOf("Close", "Move down"), actions("Browse").map { it.label })
    assertEquals(listOf("Close", "Move up", "Move down"), actions("A").map { it.label })
    assertEquals(listOf("Close", "Move up"), actions("B").map { it.label })

    actions("A").first { it.label == "Move up" }.action()
    waitForIdle()
    assertEquals(listOf("A", "Browse", "B"), titles())

    actions("Browse").first { it.label == "Move down" }.action()
    waitForIdle()
    assertEquals(listOf("A", "B", "Browse"), titles())

    actions("B").first { it.label == "Close" }.action()
    waitForIdle()
    assertEquals(listOf("A", "Browse"), titles())
  }
}
