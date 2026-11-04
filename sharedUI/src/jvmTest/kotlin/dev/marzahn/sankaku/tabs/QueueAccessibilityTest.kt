package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The accessibility pass over the queue page (#185): its headings, and a 200% font cutting nothing off. */
@OptIn(ExperimentalTestApi::class)
class QueueAccessibilityTest {
  private val tabs = TabsController()

  private fun ComposeUiTest.show(fontScale: Float = 1f) {
    val privacy = PrivacySettings(InMemoryTextFile())
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = fontScale)) {
        SankakuTheme {
          QueuePage(
            onDismiss = {},
            controller = tabs,
            posts = TabPosts.None,
            privacy = privacy,
            seen = null,
            clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages()),
            interacted = { false }, onInteract = {},
          )
        }
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.headingTexts(): List<String> =
    onAllNodes(SemanticsMatcher("is a heading") { it.config.contains(SemanticsProperties.Heading) })
      .fetchSemanticsNodes()
      .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }

  @Test
  fun theSectionsAreHeadingsForHeadingNavigation() = runComposeUiTest {
    tabs.open(Route.Grid(listOf("feed:following")), background = true)
    tabs.open(Route.Grid(listOf("artist_x")), background = true)
    tabs.close(tabs.tabs.value.first { it.title == "Artist X" }.id)
    show()

    val texts = headingTexts()
    assertTrue("Queue" in texts, "$texts")
    // A feed is a source that persists (#327): it's listed where it stands, under no "Feeds" section.
    assertFalse("Feeds" in texts, "$texts")
    assertTrue("Recently closed" in texts, "$texts")
  }

  @Test
  fun aTwoHundredPercentFontCutsNoRowShort() = runComposeUiTest {
    tabs.open(Route.Grid(listOf("an_artist_with_a_very_long_name", "another_tag_that_is_long", "and_a_third_one")), background = true)
    tabs.activate(tabs.tabs.value.last().id)
    val title = tabs.activeTab.title
    show(fontScale = 2f)

    // Neither the title, the line under it nor Continue ends in "…".
    listOf(title, "Grid · current", "Continue · $title").forEach { text ->
      // The title is on the row and in Continue.
      val nodes = onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
      assertTrue(nodes.isNotEmpty(), "no node for $text")
      nodes.forEach { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return@forEach
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized), "\"$text\" is cut off with a “…”")
      }
    }
  }

  @Test
  fun aMoveSaysWhereTheItemWentSinceFocusStaysOnThePlace() = runComposeUiTest {
    tabs.open(Route.Grid(listOf("a")), background = true)
    tabs.open(Route.Grid(listOf("b")), background = true)
    show()
    // The live region empties itself after a few seconds: look before the clock gets there.
    mainClock.autoAdvance = false

    onNodeWithText("A").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
      .first { it.label == "Move down" }.action()
    mainClock.advanceTimeBy(100)

    val live = onAllNodes(SemanticsMatcher("polite live region") { it.config.contains(SemanticsProperties.LiveRegion) })
      .fetchSemanticsNodes()
      .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    assertTrue("A moved to item 3 of 3" in live, "$live")
  }
}
