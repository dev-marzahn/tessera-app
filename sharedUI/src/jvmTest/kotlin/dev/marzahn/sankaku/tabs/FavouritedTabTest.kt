package dev.marzahn.sankaku.tabs

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #419: a post tab in the strip shows ♥ on its icon while its post is favourited, from [TabPosts] (the
 * PostStateStore's favourite over the held copy's), so a toggle anywhere shows at once and an unfavourite takes it
 * away. A grid tab never has one.
 */
@OptIn(ExperimentalTestApi::class)
class FavouritedTabTest {
  private val tabs = TabsController()

  /** Which posts are favourited, as the store would say. */
  private val favourited = MutableStateFlow(setOf<String>())
  private val posts = TabPosts { ids ->
    favourited.map { fav -> ids.associateWith { TabPost(it in fav, emptyList()) } }
  }

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")

  init {
    tabs.open(Route.Grid(listOf("a")), background = false)
    tabs.open(post("p1"), background = true)
    tabs.open(post("p2"), background = true)
  }

  private fun ComposeUiTest.show() {
    val seen = SeenStore(InMemoryTextFile())
    setContent { SankakuTheme { TabStrip(controller = tabs, clearViewed = ClearViewed(tabs, seen, Messages()), tabPosts = posts) } }
    waitForIdle()
  }

  /**
   * The titles of the tabs that show a heart. A tab's heart sits on its icon, just before its title: the title it
   * belongs to is the first one to its right.
   */
  private fun ComposeUiTest.heartedTabs(): Set<String> {
    val titles = tabs.tabs.value.map { it.title }.associateWith {
      onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    }
    return onAllNodesWithTag(FAVOURITED_MARK_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { heart ->
      titles.filterValues { it.left >= heart.boundsInRoot.left }.minBy { it.value.left }.key
    }.toSet()
  }

  private fun title(id: String) = tabs.tabs.value.single { (it.top as? Route.Post)?.id == id }.title

  @Test
  fun onlyAFavouritedPostsTabShowsAHeart() = runComposeUiTest {
    favourited.value = setOf("p2")
    show()

    assertEquals(setOf(title("p2")), heartedTabs())
    // A screen reader hears it as the tab's state; the tab's name stays its title (#521).
    val tab = onNode(hasContentDescription(title("p2")) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
      .fetchSemanticsNode()
    assertEquals(listOf(title("p2")), tab.config[SemanticsProperties.ContentDescription])
    assertEquals(FAVOURITED_TAB, tab.config[SemanticsProperties.StateDescription])
  }

  @Test
  fun theHeartFollowsAFavouriteAndAnUnfavourite() = runComposeUiTest {
    show()
    assertEquals(emptySet(), heartedTabs())

    // Favourited anywhere (the viewer over a grid, another tab, the grid's ♡): the store changes, the strip follows.
    favourited.value = setOf("p1")
    waitForIdle()
    assertEquals(setOf(title("p1")), heartedTabs())

    favourited.value = emptySet()
    waitForIdle()
    assertEquals(emptySet(), heartedTabs())
  }
}
