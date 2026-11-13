package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.Route
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

/** #177: the queue page's blocks, seen, blur (until the queue's first use, #264), G only, Continue and a tap on a row. */
@OptIn(ExperimentalTestApi::class)
class QueuePageBehaviourTest {
  private val keptAt = Instant.parse("2026-11-01T12:00:00Z")
  private val tabs = TabsController(clock = object : Clock {
    override fun now() = keptAt
  })
  private val privacy = PrivacySettings(InMemoryTextFile())
  private val seen = SeenStore(InMemoryTextFile(), clock = object : Clock {
    override fun now() = keptAt + 1.minutes
  })
  private val messages = Messages()
  private var dismissed = 0

  private fun post(id: String, rating: String?) =
    Route.Post(id, md5 = "md5$id", originTags = listOf("a"), label = "Post $id", rating = rating)

  private fun keep(id: String, rating: String?) = tabs.openInBlock(listOf(Route.Grid(listOf("a"), at = id), post(id, rating)))

  // Whether the queue was used since the app started (#264): QueueStart's, here the page's alone.
  private var used by mutableStateOf(false)

  private fun ComposeUiTest.show() {
    setContent {
      SankakuTheme {
        QueuePage(
          onDismiss = { dismissed++ },
          controller = tabs,
          posts = TabPosts.None,
          privacy = privacy,
          seen = seen,
          clearViewed = ClearViewed(tabs, seen, messages),
          interacted = { used },
          onInteract = { used = true },
        )
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.blurred() = onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

  // Browse, then a with a G post and an R15 post kept.
  private fun block() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("g", "s")
    keep("q", "q")
  }

  @Test
  fun aSeenKeptPostIsMarkedInWords() = runComposeUiTest {
    block()
    seen.record("g", "md5g")
    show()

    onNodeWithText("Kept · seen").assertExists()
    onNodeWithText("Kept · R15").assertExists()
    onNodeWithText("Grid · current").assertExists()
  }

  @Test
  fun anR15ThumbnailIsBlurredUntilTheQueueIsUsed() = runComposeUiTest {
    block()
    show()

    assertEquals(1, blurred())
    onNodeWithContentDescription("Blurred", useUnmergedTree = true).assertExists()
    assertFalse(used)
  }

  @Test
  fun aTouchAnywhereOnThePageUnblursEveryThumbnail() = runComposeUiTest {
    block()
    show()

    // The header's title: no button, nothing else happens.
    onNodeWithText("Queue").performTouchInput {
      down(center)
      up()
    }
    waitForIdle()

    assertTrue(used)
    assertEquals(0, blurred())
    assertEquals(0, dismissed)
  }

  @Test
  fun aScrollUnblurs() = runComposeUiTest {
    block()
    repeat(12) { keep("g$it", "s") }
    show()

    onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
    waitForIdle()

    assertTrue(used)
  }

  @Test
  fun continueByTalkBackIsAUse() = runComposeUiTest {
    block()
    show()

    // A semantics click, as TalkBack's: no touch reaches the page.
    onNodeWithText("Continue", substring = true).performSemanticsAction(SemanticsActions.OnClick)

    assertTrue(used)
    assertEquals(1, dismissed)
  }

  @Test
  fun aTalkBackMoveIsAUse() = runComposeUiTest {
    block()
    show()

    onNodeWithText("Post g").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
      .first { it.label == "Move down" }.action()
    waitForIdle()

    assertTrue(used)
    assertEquals(0, blurred())
  }

  @Test
  fun gOnlyHidesR15ItemsWithTheirCountAndTurningItOffRestoresThem() = runComposeUiTest {
    block()
    show()

    onNodeWithContentDescription("G only").performClick()
    waitForIdle()
    onNodeWithText("Post q").assertDoesNotExist()
    onNodeWithText("Hidden by G only · 1").assertExists()
    onNodeWithText("Post g").assertExists()

    onNodeWithContentDescription("G only").performClick()
    waitForIdle()
    onNodeWithText("Post q").assertExists()
    onNodeWithText("Hidden by G only · 1").assertDoesNotExist()
  }

  @Test
  fun continueGoesBackToTheCurrentItem() = runComposeUiTest {
    block()
    tabs.activate(tabs.tabs.value[1].id)
    show()

    onNodeWithText("Continue · A").performClick()

    assertEquals(1, dismissed)
    assertEquals("A", tabs.activeTab.title)
  }

  @Test
  fun aTapOnARowGoesToThatItem() = runComposeUiTest {
    block()
    show()

    onNodeWithText("Post g").performClick()

    assertEquals(1, dismissed)
    assertEquals("Post g", tabs.activeTab.title)
  }

  @Test
  fun clearViewedIsHiddenUntilAKeptPostIsSeen() = runComposeUiTest {
    block()
    show()

    onNodeWithContentDescription("Clear viewed", substring = true).assertDoesNotExist()
  }

  @Test
  fun clearViewedShowsItsCountAndNamesTheKeptPosts() = runComposeUiTest {
    block()
    seen.record("g", "md5g")
    show()

    onNodeWithContentDescription("Clear viewed, 1 kept posts").assertExists()
  }

  @Test
  fun clearViewedRemovesTheViewedPostsAndLeavesOneClearedEntry() = runComposeUiTest {
    block()
    seen.record("g", "md5g")
    seen.record("q", "md5q")
    show()

    onNodeWithContentDescription("Clear viewed, 2 kept posts").performClick()
    waitForIdle()

    assertEquals(listOf("Browse", "A"), tabs.tabs.value.map { it.title })
    // The button is gone; the entry is in Recently closed.
    onNodeWithContentDescription("Clear viewed", substring = true).assertDoesNotExist()
    onNodeWithText("2 posts").assertExists()
    onNodeWithText("Cleared").assertExists()

    onNodeWithText("2 posts").performClick()
    waitForIdle()

    assertEquals(listOf("Browse", "A", "Post g", "Post q"), tabs.tabs.value.map { it.title })
    onNodeWithContentDescription("Clear viewed, 2 kept posts").assertExists()
  }
}
