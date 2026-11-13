package dev.marzahn.sankaku.tabs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.REOPEN_TAB_LABEL
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #420: while something can be reopened, the strip's + carries the phone pill's ↶ badge, named "Reopen closed tab
 * (Z)", which reopens as Z does. It goes when Recently closed is empty.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripReopenBadgeTest {
  private val tabs = TabsController(FileTabStateStore(InMemoryTextFile(null)))
  private val navigator = TabsNavigator(tabs)
  private val artist = Route.Grid(listOf("artist_x"))
  private val tag = Route.Grid(listOf("tag_t"))

  private fun ComposeUiTest.strip() = setContent {
    SankakuTheme { TabStrip(controller = tabs, clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages()), tabPosts = TabPosts.None) }
  }

  private fun ComposeUiTest.badge() = onNodeWithContentDescription(REOPEN_TAB_LABEL)

  private fun ComposeUiTest.assertNoBadge() = onNodeWithContentDescription(REOPEN_TAB_LABEL).assertDoesNotExist()

  @Test
  fun noBadgeWhileNothingIsClosed() = runComposeUiTest {
    tabs.open(artist, background = false)
    strip()

    assertNoBadge()
    onNodeWithContentDescription("New tab (⌘T)").assertExists()
  }

  @Test
  fun xOrCmdWShowsItAndZTakesItAwayOnceRecentlyClosedIsEmpty() = runComposeUiTest {
    tabs.open(artist, background = false)
    strip()

    // X and Cmd/Ctrl-W both close the active tab.
    tabs.close(tabs.active.value)
    waitForIdle()
    badge().assertExists()

    tabs.reopenClosed()
    waitForIdle()
    assertNoBadge()
  }

  @Test
  fun escOnAGridOpenedFromAnItemShowsIt() = runComposeUiTest {
    // A lone post p (from artist_x, with k1 kept), and a search opened "now" from it.
    tabs.open(artist, background = false)
    navigator.openInNewTab(Route.Post("k1", "mk1", artist.tags, label = "k1"))
    navigator.goTo(Route.Post("p", "mp", artist.tags, label = "p"))
    navigator.goTo(tag)
    strip()
    assertNoBadge()

    // Esc goes back, which closes a grid opened from an item (#349).
    navigator.goBack()
    waitForIdle()
    badge().assertExists()
  }

  @Test
  fun itIsANamedButtonOnThePlusTopRightCornerThatReopens() = runComposeUiTest {
    tabs.open(artist, background = false)
    tabs.close(tabs.active.value)
    strip()

    badge()
      .assertHasClickAction()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    // The name and tooltip the issue gives it, and one node: the ↶ icon says nothing of its own.
    assertEquals("Reopen closed tab (Z)", REOPEN_TAB_LABEL)
    onAllNodesWithContentDescription(REOPEN_TAB_LABEL, useUnmergedTree = true).assertCountEquals(1)
    val badge = badge().getUnclippedBoundsInRoot()
    val plus = onNodeWithContentDescription("New tab (⌘T)").getUnclippedBoundsInRoot()
    assertEquals(22.dp, badge.right - badge.left)
    assertEquals(22.dp, badge.bottom - badge.top)
    // Over the +'s top-right corner.
    assertTrue(badge.left > plus.left + (plus.right - plus.left) / 2, "badge $badge, + $plus")
    assertTrue(badge.top < plus.top + (plus.bottom - plus.top) / 2, "badge $badge, + $plus")
    // Inside the strip, which is at the window's top edge: hung above the +, it was cut in half.
    assertTrue(badge.top >= 0.dp, "badge $badge")
    assertTrue(badge.right <= onNodeWithContentDescription("More").getUnclippedBoundsInRoot().left, "badge $badge")

    // Every point of it takes clicks: clicked at its outermost corner pixel, not its centre.
    badge().performTouchInput { click(Offset(width - 1f, 0f)) }
    waitForIdle()

    assertEquals(listOf(Route.Grid(emptyList()), artist), tabs.tabs.value.map { it.top })
    assertEquals(artist, tabs.activeTab.top)
    assertNoBadge()
  }

  @Test
  fun eachReopenSaysWhichTabCameBackForItsPulse() = runTest {
    val reopened = mutableListOf<Set<TabId>>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tabs.reopened.toList(reopened) }
    tabs.open(artist, background = false)
    tabs.open(tag, background = true)
    tabs.close(tabs.tabs.value.last().id)
    tabs.close(tabs.active.value)

    tabs.reopenClosed()
    val first = tabs.activeTab
    tabs.reopenClosed()

    assertEquals(artist, first.top)
    assertEquals(listOf(setOf(first.id), setOf(tabs.tabs.value.single { it.top == tag }.id)), reopened)
    // Nothing left to reopen: no pulse.
    tabs.reopenClosed()
    assertEquals(2, reopened.size)
  }
}
