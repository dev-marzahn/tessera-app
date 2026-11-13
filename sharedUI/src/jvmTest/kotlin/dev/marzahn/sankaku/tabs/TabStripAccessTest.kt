package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #521: the strip's tabs are tabs to a screen reader, named by their titles, with one stop each for Tab; its "+N" counts
 * open every tab; its icon buttons show their names on hover; and the active tab stays in view as the window resizes,
 * the tabs shrinking before the strip scrolls.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripAccessTest {
  private val tabs = TabsController()
  private val clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages())
  private var width by mutableIntStateOf(1600)
  private var shownAll = 0

  private fun grid(tag: String) = Route.Grid(listOf(tag))

  private fun ComposeUiTest.strip(width: Int) {
    this@TabStripAccessTest.width = width
    setContent {
      SankakuTheme {
        Box(Modifier.width(this@TabStripAccessTest.width.dp)) {
          TabStrip(controller = tabs, clearViewed = clearViewed, tabPosts = TabPosts.None, onShowAll = { shownAll++ })
        }
      }
    }
  }

  private fun ComposeUiTest.tab(title: String) = onNode(hasContentDescription(title).and(isTab))

  private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

  @Test
  fun eachTabIsATabNamedByItsTitleAndSelectedWhenActive() = runComposeUiTest {
    tabs.open(grid("landscape"), background = true)
    tabs.open(grid("sky"), background = false)
    strip(width = 1600)

    val titles = tabs.tabs.value.map { it.title }
    onAllNodes(isTab).assertCountEquals(titles.size)
    titles.forEach { title ->
      // Its title, not "Grid" from its icon, which every grid tab had.
      tab(title).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, title == tabs.tabs.value.last().title))
    }
    onAllNodesWithContentDescription("Grid").assertCountEquals(0)
    // One tab list around them.
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
  }

  @Test
  fun aTabIsOneStopForTabAndItsCloseIsAnActionOfIt() = runComposeUiTest {
    tabs.open(grid("landscape"), background = false)
    strip(width = 1600)

    val title = tabs.tabs.value.last().title
    val node = tab(title).fetchSemanticsNode()
    // Its title alone: not its icon's "Grid", nor its ✕'s "Close tab", which is a node of its own.
    assertEquals(listOf(title), node.config[SemanticsProperties.ContentDescription])
    assertTrue(SemanticsActions.RequestFocus in node.config, "the tab takes the focus")
    assertEquals(listOf(CLOSE_TAB), node.config[SemanticsActions.CustomActions].map { it.label })
    node.config[SemanticsActions.CustomActions].single().action()
    waitForIdle()
    assertEquals(listOf(Route.Grid(emptyList())), tabs.tabs.value.map { it.top })
  }

  @Test
  fun tabGoesFromTabToTabPastTheirCloseButtons() = runComposeUiTest {
    tabs.open(grid("landscape"), background = true)
    tabs.open(grid("sky"), background = true)
    strip(width = 1600)
    val titles = tabs.tabs.value.map { it.title }

    tab(titles[0]).requestFocus()
    titles.drop(1).forEach { next ->
      onRoot().performKeyInput { pressKey(Key.Tab) }
      waitForIdle()
      // The next tab, not the ✕ of the one before.
      tab(next).assertIsFocused()
    }
  }

  @Test
  fun tabIntoTheStripLandsOnTheActiveTab() = runComposeUiTest {
    (1..3).forEach { tabs.open(grid("tag_$it"), background = true) }
    tabs.activate(tabs.tabs.value[2].id)
    strip(width = 1600)

    // From nothing focused, Tab enters the strip.
    onRoot().performKeyInput { pressKey(Key.Tab) }
    waitForIdle()
    tab(tabs.tabs.value[2].title).assertIsFocused()
  }

  @Test
  fun aPlusNCountIsAButtonThatShowsEveryTab() = runComposeUiTest {
    (1..12).forEach { tabs.open(grid("tag_$it"), background = true) }
    strip(width = 700)

    val after = onNodeWithTag(HIDDEN_AFTER_TAG).fetchSemanticsNode().children
    val count = onAllNodesWithContentDescription(" more tabs after", substring = true).fetchSemanticsNodes().single()
    assertEquals(Role.Button, count.config[SemanticsProperties.Role])
    assertTrue(after.isNotEmpty())

    onNodeWithContentDescription(" more tabs after", substring = true).performClick()
    assertEquals(1, shownAll)
  }

  @Test
  fun iconButtonsShowTheirNameAndKeyOnHover() = runComposeUiTest {
    tabs.open(grid("landscape"), background = false)
    strip(width = 1600)

    listOf(NEW_TAB_LABEL, MORE_LABEL).forEach { label ->
      onAllNodesWithText(label).assertCountEquals(0)
      onNodeWithContentDescription(label).performMouseInput { moveTo(center) }
      waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
      onNodeWithContentDescription(label).performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(-200f, -200f)) }
    }
  }

  @Test
  fun tabsShrinkBeforeTheStripOverflows() = runComposeUiTest {
    (1..4).forEach { tabs.open(grid("a_rather_long_search_tag_number_$it"), background = true) }
    strip(width = 900)

    // Five tabs at 240 dp would need 1,200: they share 900 instead, and nothing is out of view.
    onAllNodesWithTag(HIDDEN_AFTER_TAG).assertCountEquals(0)
    tabs.tabs.value.drop(1).forEach { tab ->
      val bounds = tab(tab.title).bounds()
      val wide = bounds.right - bounds.left
      assertTrue(wide < TabMaxWidth && wide >= TabMinWidth, "${tab.title} is $wide wide")
    }
  }

  @Test
  fun theActiveTabStaysInViewAfterAResize() = runComposeUiTest {
    (1..12).forEach { tabs.open(grid("tag_$it"), background = true) }
    tabs.activate(tabs.tabs.value[7].id)
    strip(width = 1600)
    val active = tabs.tabs.value[7].title

    listOf(900, 600, 400, 1200).forEach { w ->
      width = w
      waitForIdle()
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      val viewport = onNodeWithTag(TABS_VIEWPORT_TAG).bounds()
      val shown = tab(active).bounds()
      assertTrue(
        shown.left >= viewport.left - 0.5.dp && shown.right <= viewport.right + 0.5.dp,
        "at $w dp the active tab at $shown is out of $viewport",
      )
    }
  }

  @Test
  fun moreOpensTheKeyboardShortcuts() = runComposeUiTest {
    var opened = 0
    setContent {
      SankakuTheme {
        TabStrip(controller = tabs, clearViewed = clearViewed, tabPosts = TabPosts.None, onShortcuts = { opened++ })
      }
    }

    onNodeWithContentDescription(MORE_LABEL).performClick()
    waitForIdle()
    // Its key beside it, in a group of its own after Clear viewed.
    onNode(androidx.compose.ui.test.hasText(SHORTCUTS_ITEM) and androidx.compose.ui.test.hasText("?")).performClick()
    waitForIdle()
    assertEquals(1, opened)
  }

  @Test
  fun theTopBarsButtonsHintTheirKeys() {
    assertEquals("Menu (G)", dev.marzahn.sankaku.shell.menuHint(open = false, badge = dev.marzahn.sankaku.shell.MenuBadge.None))
    assertEquals("Close menu (G)", dev.marzahn.sankaku.shell.menuHint(open = true, badge = dev.marzahn.sankaku.shell.MenuBadge.None))
    assertEquals("Refresh (⌘R)", dev.marzahn.sankaku.shell.REFRESH_LABEL)
    assertEquals("Show all tabs (C)", SHOW_ALL_TABS_HINT)
    assertEquals("3 more tabs before", hiddenCountLabel(3, before = true))
    assertEquals("1 more tab after", hiddenCountLabel(1, before = false))
  }

  @Test
  fun aTabsWidthIsSharedBetweenTheBounds() {
    assertEquals(TabMaxWidth, sharedTabWidth(1600.dp, 3, 0))
    assertEquals(TabMinWidth, sharedTabWidth(8.dp + TabMinWidth * 3 + 8.dp, 3, 0))
    // Past the narrowest, the strip scrolls and each tab keeps its own width.
    assertEquals(TabMaxWidth, sharedTabWidth(400.dp, 12, 0))
    // 900 − 8 start − 4 × 4 gaps = 876, over 5, in whole dp.
    assertEquals(175.dp, sharedTabWidth(900.dp, 5, 0))
  }

  private fun SemanticsNodeInteraction.bounds(): DpRect = getBoundsInRoot()
}
