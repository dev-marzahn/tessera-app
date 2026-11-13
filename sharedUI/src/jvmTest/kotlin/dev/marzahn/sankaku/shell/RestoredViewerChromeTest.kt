package dev.marzahn.sankaku.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabContent
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.LocalTabEntry
import dev.marzahn.sankaku.tabs.TabsController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #287, frame by frame: what the shell draws (the top bar above or under the content, its count pill) around a grid
 * whose viewer is open. The tabs, their saved state and the shell's rule (`TabState.chromeCovered`) are the app's; the
 * grid and its viewer are stand-ins wired as `GridScreen` and `PhoneViewer` are. Each piece logs itself as it's drawn,
 * once a frame, by name only.
 */
@OptIn(ExperimentalTestApi::class)
class RestoredViewerChromeTest {
  private val tabs = TabsController(FileTabStateStore(InMemoryTextFile(null)))
  private val navigator = TabsNavigator(tabs)
  private val gridA = Route.Grid(listOf("a"))
  private val gridB = Route.Grid(listOf("b"))

  private var frame by mutableIntStateOf(0)
  private val drawn = mutableMapOf<Int, MutableList<String>>()
  private var queuePageOpen by mutableStateOf(false)

  /** The viewer's hand-back (#103): closing, its pill on the grid's line, it stops covering the chrome. */
  private var handBack by mutableStateOf(false)

  /** Each grid's viewer, open or not: saved state, as `GridScreen`'s viewer index is. */
  private val viewers = mutableMapOf<Route.Grid, MutableState<Boolean>>()

  /** Reading [frame] in draw redraws the piece every frame, so each frame logs everything on screen. */
  private fun Modifier.logs(name: String) = drawBehind { drawn.getOrPut(frame) { mutableListOf() } += name }

  private val entryProvider: (NavKey) -> NavEntry<NavKey> = { key ->
    NavEntry(key) {
      val route = key as Route.Grid
      val tab = LocalTabEntry.current!!.tab
      val viewer = rememberSaveable { mutableStateOf(false) }
      viewers[route] = viewer
      // As GridScreen: the viewer reports on the tab, and the grid uncovers once the viewer is gone.
      val viewerShown = remember { MutableTransitionState(viewer.value) }
      viewerShown.targetState = viewer.value
      val coverChrome: (Boolean) -> Unit = remember(tab, route) { { covers -> tab.coverChrome(route, covers) } }
      SideEffect { if (viewerShown.isIdle && !viewerShown.currentState) coverChrome(false) }
      Box(Modifier.size(10.dp).logs("grid ${route.tags.single()}"))
      AnimatedVisibility(visibleState = viewerShown, enter = EnterTransition.None, exit = ExitTransition.None) {
        CompositionLocalProvider(LocalCoverChrome provides coverChrome) {
          // As PhoneViewer: it covers the chrome until the hand-back, and draws its own pill.
          CoverChrome(enabled = !handBack)
          Box(Modifier.size(10.dp).logs("viewer pill"))
        }
      }
    }
  }

  /** Stands in for `Shell`, with its rule for the top bar and the pill. */
  @Composable
  private fun Shell() {
    val activeId by tabs.active.collectAsState()
    val active = tabs.tabs.collectAsState().value.first { it.id == activeId }
    val covered = active.chromeCovered
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.size(10.dp).zIndex(if (covered) 0f else 1f).logs(if (covered) "bar under" else "bar on top"))
      TabContent(shown = !queuePageOpen, tabs.tabs.collectAsState().value) { ActiveTab(active, tabs, navigator, it, entryProvider) }
      if (!covered && !queuePageOpen) Box(Modifier.size(10.dp).logs("shell pill"))
    }
  }

  /** Runs [change] at the start of frame 1, then draws [count] frames one by one. */
  private fun ComposeUiTest.frames(count: Int, change: () -> Unit) {
    mainClock.autoAdvance = false
    drawn.clear()
    for (f in 1..count) {
      runOnUiThread {
        frame = f
        if (f == 1) change()
      }
      mainClock.advanceTimeByFrame()
    }
  }

  private fun log(name: String): String {
    val frames = drawn.keys.sorted().joinToString("\n") { "  frame $it: ${drawn[it]!!.sorted().joinToString(", ")}" }
    return "$name\n$frames".also(::println)
  }

  private fun ComposeUiTest.start() {
    tabs.open(gridB, background = true)
    tabs.open(gridA, background = false)
    setContent { Shell() }
    waitForIdle()
    runOnIdle { viewers.getValue(gridA).value = true }
    waitForIdle()
  }

  @Test
  fun aGridBackWithItsViewerOpenIsCoveredFromItsFirstFrame() = runComposeUiTest {
    start()
    runOnIdle { queuePageOpen = true }
    waitForIdle()

    // Continue on the queue page: the grid comes back with its viewer open.
    frames(4) { queuePageOpen = false }
    val log = log("Queue page closed over a grid with its viewer open:")
    drawn.values.forEach { assertEquals(listOf("bar under", "grid a", "viewer pill"), it.sorted(), log) }
  }

  @Test
  fun leavingACoveredGridForAnotherShowsTheBarAndPillFromItsFirstFrame() = runComposeUiTest {
    start()

    frames(4) { tabs.activate(tabs.tabs.value.first { it.top == gridB }.id) }
    val log = log("Covered grid left for another grid:")
    drawn.values.forEach { assertEquals(listOf("bar on top", "grid b", "shell pill"), it.sorted(), log) }
  }

  @Test
  fun aTabBackInFrontWithItsViewerOpenIsCoveredFromItsFirstFrame() = runComposeUiTest {
    start()
    val tabA = tabs.activeTab.id
    runOnIdle { tabs.activate(tabs.tabs.value.first { it.top == gridB }.id) }
    waitForIdle()

    frames(4) { tabs.activate(tabA) }
    val log = log("Tab back in front with its viewer open:")
    drawn.values.forEach { assertEquals(listOf("bar under", "grid a", "viewer pill"), it.sorted(), log) }
  }

  @Test
  fun openingAndClosingTheViewerHasAPillInEveryFrame() = runComposeUiTest {
    start()
    runOnIdle { viewers.getValue(gridA).value = false }
    waitForIdle()

    // Opening: the viewer draws its pill from its first frame, and covers the chrome from the next.
    frames(3) { viewers.getValue(gridA).value = true }
    val opening = log("Viewer opened over a grid:")
    drawn.values.forEach { pieces -> assertTrue(pieces.any { "pill" in it }, opening) }
    assertEquals(listOf("bar under", "grid a", "viewer pill"), drawn.getValue(3).sorted(), opening)

    // The hand-back, then the viewer goes a couple of frames later, as PhoneViewer's does (#103).
    frames(3) { handBack = true }
    runOnUiThread { viewers.getValue(gridA).value = false }
    for (f in 4..6) {
      runOnUiThread { frame = f }
      mainClock.advanceTimeByFrame()
    }
    val closing = log("Viewer closed over a grid:")
    assertEquals((1..6).toList(), drawn.keys.sorted(), closing)
    drawn.values.forEach { pieces -> assertTrue(pieces.any { "pill" in it }, closing) }
    assertEquals(listOf("bar on top", "grid a", "shell pill"), drawn.getValue(6).sorted(), closing)
  }
}
