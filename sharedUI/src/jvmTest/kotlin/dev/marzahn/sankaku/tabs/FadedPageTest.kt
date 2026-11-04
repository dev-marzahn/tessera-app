package dev.marzahn.sankaku.tabs

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** #215: the queue page's Faded section is shut by default, counts, and offers Restore and Delete now. */
@OptIn(ExperimentalTestApi::class)
class FadedPageTest {
  private val start = Instant.parse("2026-11-01T12:00:00Z")
  private var now = start
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock, seen = seen)
  private val privacy = PrivacySettings(InMemoryTextFile())
  private var dismissed = 0

  // Browse is shown. "artist_x" (with a kept post), "blue_hair" and an R18 search fade.
  private fun fade() {
    tabs.open(Route.Grid(listOf("artist_x")), background = false)
    tabs.openInBlock(listOf(Route.Post("p1", "m1", listOf("artist_x"), label = "Rosehip", rating = "s")))
    tabs.open(Route.Grid(listOf("blue_hair")), background = true)
    tabs.open(Route.Grid(listOf("rating:e", "lace")), background = true)
    tabs.activate(tabs.tabs.value.first().id)
    now = start + 5.days
    tabs.sweep()
    assertEquals(3, tabs.faded.value.size)
  }

  private fun ComposeUiTest.show() {
    setContent {
      SankakuTheme {
        QueuePage(
          onDismiss = { dismissed++ },
          controller = tabs,
          posts = TabPosts.None,
          privacy = privacy,
          seen = seen,
          clearViewed = ClearViewed(tabs, seen, Messages()),
          interacted = { false }, onInteract = {},
        )
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.actions(title: String) =
    onNodeWithText(title).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

  @Test
  fun theSectionIsShutWithItsCountUntilOpened() = runComposeUiTest {
    fade()
    show()

    onNodeWithText("Faded · 3").assertExists()
    onNodeWithText("Artist X").assertDoesNotExist()

    onNodeWithText("Faded · 3").performClick()
    waitForIdle()

    onNodeWithText("Artist X").assertExists()
    onNodeWithText("Blue Hair").assertExists()
  }

  @Test
  fun aRowSaysWhenItIsDeletedAndHowManyKeptPostsFadedWithIt() = runComposeUiTest {
    fade()
    show()
    onNodeWithText("Faded · 3").performClick()
    waitForIdle()

    onNodeWithText("Deleted in 28 days · 1 kept post").assertExists()
  }

  @Test
  fun restoreBringsTheSourceBackToTheQueueAndKeepsThePage() = runComposeUiTest {
    fade()
    show()
    onNodeWithText("Faded · 3").performClick()
    waitForIdle()

    actions("Artist X").first { it.label == "Restore" }.action()
    waitForIdle()

    assertEquals(listOf("Browse", "Artist X", "Rosehip"), tabs.tabs.value.map { it.title })
    assertEquals(0, dismissed)
    onNodeWithText("Faded · 2").assertExists()
  }

  @Test
  fun aTapOpensTheSourceAndLeavesThePage() = runComposeUiTest {
    fade()
    show()
    onNodeWithText("Faded · 3").performClick()
    waitForIdle()

    onNodeWithText("Artist X").performClick()
    waitForIdle()

    assertEquals("Artist X", tabs.activeTab.title)
    assertEquals(1, dismissed)
  }

  @Test
  fun deleteNowDeletesAndLeavesAnUndo() = runComposeUiTest {
    fade()
    show()
    onNodeWithText("Faded · 3").performClick()
    waitForIdle()

    actions("Blue Hair").first { it.label == "Delete now" }.action()
    waitForIdle()

    assertTrue(tabs.faded.value.none { it.title == "Blue Hair" })
    onNodeWithText("Closed “Blue Hair”").assertExists()
    onNodeWithText("Undo").performClick()
    waitForIdle()
    assertTrue(tabs.tabs.value.any { it.title == "Blue Hair" })
  }

  @Test
  fun theButtonsRestoreAndDeleteToo() = runComposeUiTest {
    fade()
    show()
    onNodeWithText("Faded · 3").performClick()
    waitForIdle()

    onAllNodes(hasText("Restore")).onFirst().performClick()
    waitForIdle()
    assertEquals(2, tabs.faded.value.size)

    onAllNodes(hasText("Delete now")).onFirst().performClick()
    waitForIdle()
    assertEquals(1, tabs.faded.value.size)
  }

  @Test
  fun withGOnlyAnR18SourceIsCountedAndNotListed() = runComposeUiTest {
    fade()
    privacy.setGOnly(true)
    show()
    onNodeWithText("Faded · 3").performClick()
    waitForIdle()

    onNodeWithText("Artist X").assertExists()
    onNodeWithText("Lace").assertDoesNotExist()
    onNodeWithText("Hidden by G only · 1", substring = true).assertExists()
  }

  @Test
  fun opensItsFadingAsThePageOpens() = runComposeUiTest {
    tabs.open(Route.Grid(listOf("artist_x")), background = true)
    now = start + 4.days

    show()

    onNodeWithText("Faded · 1").assertExists()
  }
}
