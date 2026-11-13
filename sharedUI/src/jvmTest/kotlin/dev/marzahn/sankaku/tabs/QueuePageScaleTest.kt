package dev.marzahn.sankaku.tabs

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription

/** #132, kept by the queue page (#177): positions, opening on the current item, find, bulk closes and the scrubber at scale. */
@OptIn(ExperimentalTestApi::class)
class QueuePageScaleTest {
  private val tabs = TabsController()

  private fun post(id: String, label: String = id) =
    Route.Post(id, md5 = null, originTags = listOf("from_$id"), label = label)

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun ComposeUiTest.show(posts: TabPosts = TabPosts.None) {
    val privacy = PrivacySettings(InMemoryTextFile())
    setContent {
      SankakuTheme {
        QueuePage(onDismiss = {}, controller = tabs, posts = posts, privacy = privacy, seen = null, clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages()), interacted = { false }, onInteract = {})
      }
    }
    waitForIdle()
  }

  @Test
  fun rowsShowTheirPositionsAndTheListOpensOnTheCurrentTab() = runComposeUiTest {
    repeat(122) { tabs.open(post("p$it"), background = true) }
    tabs.activate(tabs.tabs.value[89].id)
    show()

    onNodeWithContentDescription("123 items").assertExists()
    onNodeWithContentDescription("Item 90 of 123").assertIsDisplayed()
    onNodeWithText("Kept · current").assertIsDisplayed()
    onNodeWithContentDescription("Item 1 of 123").assertDoesNotExist()
  }

  @Test
  fun findFiltersByTitleAndTagsAndClosingItShowsEveryTabAgain() = runComposeUiTest {
    tabs.open(Route.Grid(listOf("blue_hair")), background = true)
    tabs.open(post("p1", label = "Seele Vollerei"), background = true)
    tabs.open(post("p2", label = "Furina"), background = true)
    // p2's own tags, as a loaded page holds them.
    show(TabPosts { flowOf(mapOf("p2" to TabPost(favourited = false, tags = listOf("long_hair", "blue_hair")))) })

    onNodeWithContentDescription("Find in queue").performClick()
    onNodeWithText("Find by title or tag").performTextInput("blue hair")
    waitForIdle()

    onNodeWithText("Furina").assertExists()
    onNodeWithText("Seele Vollerei").assertDoesNotExist()
    onNodeWithText("Browse").assertDoesNotExist()
    // The grid by its search; its row keeps its real position.
    onNodeWithContentDescription("Item 2 of 4").assertExists()

    onNodeWithText("blue hair").performTextClearance()
    onNodeWithText("Find by title or tag").performTextInput("seele")
    waitForIdle()
    onNodeWithText("Seele Vollerei").assertExists()
    onNodeWithText("Furina").assertDoesNotExist()

    onNodeWithContentDescription("Close find").performClick()
    waitForIdle()
    onNodeWithContentDescription("4 items").assertExists()
    onNodeWithText("Furina").assertExists()
  }

  @Test
  fun aFindShowsItsFirstMatchesAndShuttingItReturnsToTheCurrentTab() = runComposeUiTest {
    // A hundred matches, more than the list shows at once.
    repeat(199) { tabs.open(post("p$it", label = if (it < 100) "Seele $it" else "Other $it"), background = true) }
    tabs.activate(tabs.tabs.value[149].id)
    show()

    onNodeWithContentDescription("Find in queue").performClick()
    onNodeWithText("Find by title or tag").performTextInput("seele")
    waitForIdle()
    onNodeWithText("Seele 0").assertIsDisplayed()

    onNodeWithContentDescription("Close find").performClick()
    waitForIdle()
    onNodeWithContentDescription("Item 150 of 200").assertIsDisplayed()
  }

  @Test
  fun eachBulkCloseShowsItsCountIsDisabledAtNoneAndOneUndoBringsTheBatchBack() = runComposeUiTest {
    listOf("a", "b", "a", "c", "b").forEach { tabs.open(post(it), background = true) }
    // The current tab is the last: nothing to its right.
    tabs.activate(tabs.tabs.value.last().id)
    val before = titles()
    show(TabPosts { flowOf(mapOf("c" to TabPost(favourited = true, tags = emptyList()))) })

    onNodeWithContentDescription("More").performClick()
    onNodeWithText("Close everything below (0)").assertIsNotEnabled()
    onNodeWithText("Close favourited (1)").assertIsEnabled()
    onNodeWithText("Close all but current (5)").assertIsEnabled()
    onNodeWithText("Close duplicates (2)").performClick()
    waitForIdle()

    assertEquals(listOf("Browse", "a", "c", "b"), titles())
    onNodeWithText("Closed 2").assertExists()
    onNodeWithText("Undo").performClick()
    waitForIdle()
    assertEquals(before, titles())
    assertEquals("b", tabs.activeTab.title)
  }

  @Test
  fun theScrubberJumpsThrough400TabsWithAPositionBubble() = runComposeUiTest {
    repeat(399) { tabs.open(post("p$it"), background = true) }
    show()

    val thumb = onNodeWithTag(SCRUBBER_TAG, useUnmergedTree = true)
    // The thumb's strip is left clear: no row's ≡ lies under it, so reordering works on every row.
    val thumbLeft = thumb.getUnclippedBoundsInRoot().left
    onAllNodesWithContentDescription("Move", useUnmergedTree = true).fetchSemanticsNodes().forEach { handle ->
      assertTrue(handle.boundsInRoot.right <= thumbLeft.value * density.density)
    }
    thumb.performTouchInput {
      down(center)
      // Far past the track's end: the thumb stops at the bottom.
      repeat(40) { moveBy(Offset(0f, 100f), delayMillis = 16) }
    }
    waitForIdle()
    onNodeWithText("400 / 400").assertExists()
    thumb.performTouchInput { up() }
    waitForIdle()

    onNodeWithContentDescription("Item 400 of 400").assertIsDisplayed()
    onNodeWithText("400 / 400").assertDoesNotExist()
    assertEquals(400, tabs.tabs.value.size)
  }
}
