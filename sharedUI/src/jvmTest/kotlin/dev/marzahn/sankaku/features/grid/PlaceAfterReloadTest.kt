package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.GridPlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #415: signing in reloads a scrolled grid's list while its tab is in the background, and the list is empty for a
 * moment when the grid is shown again. The grid's place mustn't peek past the list (that threw on the event thread
 * and froze the app), and mustn't report anything until the list is back: the saved place stays.
 */
@OptIn(ExperimentalTestApi::class)
class PlaceAfterReloadTest {
  private val posts = List(100) { PostDto(id = "p$it", md5 = "m$it", rating = "s", status = "active") }

  @Test
  fun aScrolledGridShownAgainOnAnEmptyReloadKeepsItsPlace() = runDesktopComposeUiTest(width = 400, height = 800) {
    val data = MutableStateFlow(PagingData.from(posts))
    val reported = mutableListOf<GridPlace?>()
    var shown by mutableStateOf(true)
    lateinit var state: LazyStaggeredGridState
    lateinit var scope: CoroutineScope
    var itemCount = { 0 }
    setContent {
      // The grid's state and list outlive its screen being shown, as the tab's view model does.
      state = rememberLazyStaggeredGridState()
      scope = rememberCoroutineScope()
      val items = data.collectAsLazyPagingItems()
      itemCount = { items.itemCount }
      if (shown) {
        LaunchedEffect(state) {
          state.placeReports(items, midway = false, cursorOf = { null }).collect { reported += it }
        }
        LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(3), state = state, modifier = Modifier.fillMaxSize()) {
          items(items.itemCount) { Box(Modifier.height(120.dp)) }
        }
      }
    }
    waitForIdle()
    scope.launch { state.scrollToItem(28) }
    waitForIdle()
    assertEquals(GridPlace("p28"), reported.last(), "scrolled: the first post whose top shows")
    val before = reported.size

    // Another tab in front; sign-in reloads the list, empty until its first page is in; the grid is shown again.
    shown = false
    waitForIdle()
    data.value = PagingData.empty()
    waitForIdle()
    shown = true
    waitForIdle()

    assertEquals(before, reported.size, "nothing reported while the list is empty, so the saved place stays")

    // The first page comes in: the grid still stands at item 28, and the shown-again collector's first report is the
    // place the tab already has. That's the one GridScreen's drop(1) skips, so skipping it loses nothing.
    data.value = PagingData.from(posts)
    // Paging diffs the new list off the main thread, so frames run until it's in.
    waitUntil(timeoutMillis = 5_000) { mainClock.advanceTimeByFrame(); itemCount() > 0 }
    waitForIdle()
    assertEquals(before + 1, reported.size, "one report once the list is back")
    assertEquals(GridPlace("p28"), reported.last(), "the refilled grid is where it was")
  }
}
