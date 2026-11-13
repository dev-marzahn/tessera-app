package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.TabContent
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #259: the queue page takes the tab's content out of composition (App.kt) and Back or Continue brings it back. The
 * grid's saved state (its scroll position, an open viewer) must come back with it, as it does when the tab is switched
 * away and back.
 */
@OptIn(ExperimentalTestApi::class)
class QueuePageKeepsGridTest {
  private val tabs = TabsController(FileTabStateStore(InMemoryTextFile(null)))
  private val navigator = TabsNavigator(tabs)

  private var gridState: LazyStaggeredGridState? = null
  private var viewerIndex: Int? = null

  /** Stands in for the grid screen: a staggered grid's scroll state and the viewer's index, both saved state. */
  private val entryProvider: (NavKey) -> NavEntry<NavKey> = { key ->
    NavEntry(key) {
      val state = rememberLazyStaggeredGridState()
      gridState = state
      val viewer = rememberSaveable { mutableStateOf<Int?>(null) }
      viewerIndex = viewer.value
      openViewer = { viewer.value = it }
      LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), state = state) {
        items(500) { BasicText("$it", Modifier.fillMaxWidth().height(100.dp)) }
      }
    }
  }

  private var openViewer: (Int) -> Unit = {}

  @Test
  fun closingTheQueuePageBringsTheGridBackWhereItWas() = runComposeUiTest {
    var queuePageOpen by mutableStateOf(false)
    setContent {
      // App's own wrapper: shown = !queuePageOpen on touch.
      TabContent(shown = !queuePageOpen, tabs.tabs.collectAsState().value) { ActiveTab(tabs.activeTab, tabs, navigator, it, entryProvider) }
    }
    runOnIdle { openViewer(VIEWER) }
    runOnIdle { runBlocking { gridState!!.scrollToItem(120, 30) } }
    waitForIdle()
    assertEquals(120, gridState!!.firstVisibleItemIndex)
    assertEquals(VIEWER, viewerIndex)
    saveFrame("grid-before-queue-page.png")

    runOnIdle { queuePageOpen = true }
    waitForIdle()
    runOnIdle { queuePageOpen = false }
    waitForIdle()
    saveFrame("grid-after-queue-page.png")

    assertEquals(120, gridState!!.firstVisibleItemIndex)
    assertEquals(30, gridState!!.firstVisibleItemScrollOffset)
    assertEquals(VIEWER, viewerIndex)
  }

  /** With `EVIDENCE_DIR` set, saves the frame there for the PR: numbered placeholder cells, never a real post. */
  private fun ComposeUiTest.saveFrame(name: String) {
    System.getenv("EVIDENCE_DIR")?.let { dir ->
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
    }
  }

  private companion object {
    const val VIEWER = 7
  }
}
