package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #422: twins in the strip are numbered, and the "+N" counts never cover a tab's title. */
@OptIn(ExperimentalTestApi::class)
class TabStripTitlesTest {
  private val tabs = TabsController()
  private val clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages())

  private fun post(id: String, label: String) = Route.Post(id, md5 = null, originTags = listOf("xingtong"), label = label)

  private fun ComposeUiTest.strip(width: Int) = setContent {
    SankakuTheme {
      Box(Modifier.width(width.dp)) { TabStrip(controller = tabs, clearViewed = clearViewed, tabPosts = TabPosts.None) }
    }
  }

  private fun SemanticsNode.text() = config[SemanticsProperties.Text].joinToString("") { it.text }

  /** Each tab's title, then its twin number when it has one: they share the tab's node as their parent. */
  private fun ComposeUiTest.shownTitles(): List<String> {
    val twins = onAllNodesWithTag(TAB_TWIN_TAG, useUnmergedTree = true).fetchSemanticsNodes().associateBy { it.parent?.id }
    return onAllNodesWithTag(TAB_TITLE_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { title ->
      title.text() + twins[title.parent?.id]?.text().orEmpty()
    }
  }

  @Test
  fun aLongTitleIsCutBeforeItsNumberNotAfter() = runComposeUiTest {
    val long = "Hatsune Miku, Kasane Teto, Akita Neru"
    listOf("p1", "p2").forEach { tabs.open(post(it, long), background = true) }
    strip(width = 1600)

    val title = onAllNodesWithTag(TAB_TITLE_TAG, useUnmergedTree = true)[2]
    val twin = onNodeWithTag(TAB_TWIN_TAG, useUnmergedTree = true)
    assertEquals(" · 2", twin.fetchSemanticsNode().text())
    // The title is cut short, and the number shows whole after it.
    assertTrue(title.layout().hasVisualOverflow, "the title fits, so this proves nothing")
    // Its text's width, not the paragraph's: a short text's paragraph keeps the width it was offered.
    twin.layout().let { assertTrue(it.getLineRight(0) <= it.size.width, "the number is cut: ${it.getLineRight(0)} in ${it.size}") }
    val titleBounds = title.bounds()
    val twinBounds = twin.bounds()
    assertTrue(twinBounds.width > 0f && twinBounds.left >= titleBounds.right, "the number at $twinBounds is after $titleBounds")
  }

  private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
    return results.single()
  }

  @Test
  fun threeTwinsInARowAreNumberedAndCloseRenumbersTheRest() = runComposeUiTest {
    listOf("p1", "p2", "p3").forEach { tabs.open(post(it, "Xingtong"), background = true) }
    strip(width = 1600)

    assertEquals(listOf("Browse", "Xingtong", "Xingtong · 2", "Xingtong · 3"), shownTitles())

    tabs.close(tabs.tabs.value[2].id)
    waitForIdle()

    assertEquals(listOf("Browse", "Xingtong", "Xingtong · 2"), shownTitles())
    // Only what the strip shows changes: each tab's own title stays.
    assertEquals(listOf("Browse", "Xingtong", "Xingtong"), tabs.tabs.value.map { it.title })
  }

  @Test
  fun twinsApartAreNotNumbered() = runComposeUiTest {
    tabs.open(post("p1", "Xingtong"), background = true)
    tabs.open(post("p2", "Rosehip"), background = true)
    tabs.open(post("p3", "Xingtong"), background = true)
    strip(width = 1600)

    assertEquals(listOf("Browse", "Xingtong", "Rosehip", "Xingtong"), shownTitles())
  }

  @Test
  fun theCountsNeverCoverATitle() = runComposeUiTest {
    (1..12).forEach { tabs.open(post("p$it", "aoi d. halo (kyūkyoku) $it"), background = true) }
    strip(width = 900)

    // Scrolled to the start, the middle and the end: the active tab is scrolled into view.
    listOf(0, 6, 12).forEach { index ->
      tabs.activate(tabs.tabs.value[index].id)
      waitForIdle()
      val counts = listOf(HIDDEN_BEFORE_TAG, HIDDEN_AFTER_TAG).map { onNodeWithTag(it).bounds() }
      // What shows of each title, clipped to the strip; a title scrolled out of view shows nothing.
      val titles = onAllNodesWithTag(TAB_TITLE_TAG, useUnmergedTree = true)
        .run { (0 until fetchSemanticsNodes().size).map { get(it).bounds() } }
      val shown = titles.filter { it.width > 0f }
      assertTrue(shown.size in 2 until titles.size, "the strip overflows, with tabs in view")
      counts.forEach { count -> shown.forEach { title -> assertTrue(!title.overlaps(count), "a count at $count covers $title") } }
      // And not over any part of a tab: each count sits outside the scrolling tabs.
      val viewport = onNodeWithTag(TABS_VIEWPORT_TAG).bounds()
      counts.forEach { count -> assertTrue(!count.overlaps(viewport), "a count at $count is over the tabs at $viewport") }
    }
  }

  private fun SemanticsNodeInteraction.bounds(): Rect = getBoundsInRoot().let {
    Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
  }
}
