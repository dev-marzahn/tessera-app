package dev.marzahn.sankaku.tabs

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #184 on the queue page: a closed source's kept posts under their "from … (closed)" row, which TalkBack can move. */
@OptIn(ExperimentalTestApi::class)
class ClosedBlockPageTest {
  private val tabs = TabsController()

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")

  init {
    // Browse, a with p1 and p2 kept, then b.
    tabs.open(Route.Grid(listOf("a")), background = false)
    tabs.openInBlock(listOf(post("p1")))
    tabs.openInBlock(listOf(post("p2")))
    tabs.open(Route.Grid(listOf("b")), background = false)
  }

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun ComposeUiTest.show() {
    val privacy = PrivacySettings(InMemoryTextFile())
    val seen = SeenStore(InMemoryTextFile())
    setContent {
      SankakuTheme {
        QueuePage(
          onDismiss = {}, controller = tabs, posts = TabPosts.None, privacy = privacy,
          seen = seen, clearViewed = ClearViewed(tabs, seen, Messages()), interacted = { true }, onInteract = {},
        )
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.actions(text: String) =
    onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

  @Test
  fun theClosedSourcesRowNamesItAndCountsTheKeptPosts() = runComposeUiTest {
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)
    show()

    onNodeWithText("from A (closed)").assertExists()
    onNodeWithText("2 kept posts stay").assertExists()
    onNodeWithText("p1").assertExists()
  }

  @Test
  fun theRowIsAHeading() = runComposeUiTest {
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)
    show()

    val config = onNodeWithText("from A (closed)").fetchSemanticsNode().config
    assertTrue(config.contains(SemanticsProperties.Heading))
  }

  @Test
  fun talkBackMovesTheWholeClosedBlockUpAndDown() = runComposeUiTest {
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)
    show()

    assertEquals(listOf("Move up", "Move down"), actions("from A (closed)").map { it.label })
    actions("from A (closed)").first { it.label == "Move down" }.action()
    waitForIdle()
    assertEquals(listOf("Browse", "B", "p1", "p2"), titles())
    // Now it's last: only up is left.
    assertEquals(listOf("Move up"), actions("from A (closed)").map { it.label })

    actions("from A (closed)").first { it.label == "Move up" }.action()
    waitForIdle()
    assertEquals(listOf("Browse", "p1", "p2", "B"), titles())
  }

  @Test
  fun aPostOfTheClosedBlockOffersMovesAndDetachingGivesItNoSource() = runComposeUiTest {
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)
    show()

    // The first post can go up: out of the block.
    assertEquals(listOf("Close", "Move up", "Move down"), actions("p1").map { it.label })
    actions("p1").first { it.label == "Move up" }.action()
    waitForIdle()

    val p1 = tabs.tabs.value.single { it.title == "p1" }
    assertNull(p1.block)
    assertNull(p1.closedSource)
    assertNotNull(tabs.tabs.value.single { it.title == "p2" }.closedSource)
    onNodeWithText("1 kept post stays").assertExists()
  }

  @Test
  fun closingASourceSaysHowManyKeptPostsStayAndUndoIsThere() = runComposeUiTest {
    show()

    assertTrue(actions("A").any { it.label == "Close A · 2 kept stay" })
    actions("A").first { it.label == "Close A · 2 kept stay" }.action()
    waitForIdle()

    onNodeWithText("Closed “A”").assertExists()
    onNodeWithText("2 kept stay").assertExists()
    // TalkBack hears it as one sentence.
    assertEquals(
      "Closed “A” · 2 kept stay",
      onNodeWithText("Closed “A”").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single(),
    )
    onNodeWithText("from A (closed)").assertExists()

    onNodeWithText("Undo").performClick()
    waitForIdle()
    assertEquals(listOf("Browse", "A", "p1", "p2", "B"), titles())
    assertNull(tabs.tabs.value.single { it.title == "p1" }.closedSource)
  }
}
