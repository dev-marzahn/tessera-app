package dev.marzahn.sankaku.tabs

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.navigation3.scene.Scene
import dev.marzahn.sankaku.inTabTransitions
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabContent
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #284: a search on an untouched grid, and Back within the tab, leave the old grid on screen for at most about
 * 200 ms on the test clock. Nav3's Android default, a 700 ms crossfade, kept it there for 700 ms with both grids drawn;
 * [androidDefaultKeepsTheOldGrid] shows the harness catches that.
 */
@OptIn(ExperimentalTestApi::class)
class InTabTransitionTest {
  private val tabs = TabsController(FileTabStateStore(InMemoryTextFile(null)))
  private val navigator = TabsNavigator(tabs)

  /** Stands in for the grid screen: a full-size coloured box per search, tagged by its search. */
  private val entryProvider: (NavKey) -> NavEntry<NavKey> = { key ->
    NavEntry(key) {
      val name = nameOf(key)
      Box(Modifier.fillMaxSize().background(if (name == FIRST) Color(0xFF3366CC) else Color(0xFFCC6633)).testTag(name)) {
        BasicText(name)
      }
    }
  }

  @Test
  fun aSearchAndBackCutToTheOtherGrid() = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent { TabContent(shown = true, tabs.tabs.collectAsState().value) { ActiveTab(tabs.activeTab, tabs, navigator, it, entryProvider) } }
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertShown(FIRST)

    runOnUiThread { navigator.goTo(SEARCH) }
    assertEquals(2, tabs.activeTab.backStack.size, "the search went onto the untouched grid's tab")
    val searchGone = framesUntilGone(FIRST, "search")
    assertShown(SECOND)
    saveFrame("after-search.png")

    runOnUiThread { navigator.goBack() }
    assertEquals(1, tabs.activeTab.backStack.size)
    val backGone = framesUntilGone(SECOND, "back")
    assertShown(FIRST)
    saveFrame("after-back.png")

    assertTrue(searchGone <= LIMIT_MS, "the old grid was drawn for $searchGone ms after a search")
    assertTrue(backGone <= LIMIT_MS, "the old grid was drawn for $backGone ms after Back")
  }

  /**
   * On JVM Nav3's own defaults are already a cut, so [aSearchAndBackCutToTheOtherGrid] can't tell [ActiveTab]'s specs
   * from them. This runs [ActiveTab]'s specs ([inTabTransitions]) in a bare `NavDisplay`, the same harness as
   * [androidDefaultKeepsTheOldGrid], so a slower spec put back there fails here.
   */
  @Test
  fun inTabSpecsCutWhereTheAndroidDefaultDoesNot() = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent {
      val transitions = inTabTransitions()
      BareDisplay(transitions.push, transitions.pop)
    }
    mainClock.advanceTimeByFrame()
    waitForIdle()

    runOnUiThread { navigator.goTo(SEARCH) }
    val searchGone = framesUntilGone(FIRST, "search, in-tab specs")
    runOnUiThread { navigator.goBack() }
    val backGone = framesUntilGone(SECOND, "back, in-tab specs")
    assertTrue(searchGone <= LIMIT_MS, "the old grid was drawn for $searchGone ms after a search")
    assertTrue(backGone <= LIMIT_MS, "the old grid was drawn for $backGone ms after Back")
  }

  /** The same search with Nav3's Android default specs: the old grid is still drawn past [LIMIT_MS]. */
  @Test
  fun androidDefaultKeepsTheOldGrid() = runComposeUiTest {
    mainClock.autoAdvance = false
    val crossfade: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform =
      { fadeIn(tween(ANDROID_DEFAULT_MS)) togetherWith fadeOut(tween(ANDROID_DEFAULT_MS)) }
    setContent { BareDisplay(crossfade, crossfade) }
    mainClock.advanceTimeByFrame()
    waitForIdle()

    runOnUiThread { navigator.goTo(SEARCH) }
    val gone = framesUntilGone(FIRST, "search, Android default")
    assertTrue(gone > LIMIT_MS, "the default crossfade was over in $gone ms")
  }

  @Composable
  private fun BareDisplay(
    push: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    pop: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
  ) = NavDisplay(
    backStack = tabs.activeTab.backStack,
    onBack = navigator::goBack,
    transitionSpec = push,
    popTransitionSpec = pop,
    entryProvider = entryProvider,
  )

  /**
   * Steps the test clock a frame at a time until [old] leaves the tree, logging each frame (numbers and the two
   * placeholder names only), and returns the time it took in ms. Gives up after a second.
   */
  private fun ComposeUiTest.framesUntilGone(old: String, label: String): Long {
    val start = mainClock.currentTime
    while (mainClock.currentTime - start <= 1_000) {
      mainClock.advanceTimeByFrame()
      waitForIdle()
      val elapsed = mainClock.currentTime - start
      val shown = listOf(FIRST, SECOND).filter { isShown(it) }
      println("#284 $label: ${elapsed} ms, drawn: ${shown.joinToString()}")
      if (!isShown(old)) return elapsed
    }
    return mainClock.currentTime - start
  }

  private fun ComposeUiTest.isShown(name: String) = onAllNodesWithTag(name).fetchSemanticsNodes().isNotEmpty()

  private fun ComposeUiTest.assertShown(name: String) = assertTrue(isShown(name), "$name isn't shown")

  /** With `EVIDENCE_DIR` set, saves the frame there for the PR: placeholder boxes, never a real post. */
  private fun ComposeUiTest.saveFrame(name: String) {
    System.getenv("EVIDENCE_DIR")?.let { dir ->
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
    }
  }

  private companion object {
    const val FIRST = "first grid"
    const val SECOND = "second grid"
    const val LIMIT_MS = 200L
    const val ANDROID_DEFAULT_MS = 700
    val SEARCH = Route.Grid(listOf("search"))

    fun nameOf(key: NavKey) = if (key == SEARCH) SECOND else FIRST
  }
}
