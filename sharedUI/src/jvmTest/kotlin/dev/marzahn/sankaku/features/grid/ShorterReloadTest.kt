package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.marzahn.sankaku.api.dto.PostDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #472: signing in from the phone viewer's ♡ reloads the grid's list, and the new one can hold fewer posts. The
 * viewer's pager rebuilt its key map with its old page count, one past the shorter list's end, and its key peeked
 * there: IndexOutOfBounds. Past the end, the key is the page and a post is null.
 */
@OptIn(ExperimentalTestApi::class)
class ShorterReloadTest {
  private fun posts(count: Int) = List(count) { PostDto(id = "p$it", md5 = "m$it", rating = "s", status = "active") }

  /** As PhoneViewer's pager: its page count comes in from the grid's composition, its keys from the list itself. */
  @Composable
  private fun Viewer(items: LazyPagingItems<PostDto>, loadedCount: Int, page: Int) {
    val pager = rememberPagerState(initialPage = page) { loadedCount }
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items.viewerPageKey(it, ReadingOrder.ListOrder) }) {
      Box(Modifier.fillMaxSize())
    }
  }

  @Test
  fun theViewerSurvivesItsListReloadingShorter() = runDesktopComposeUiTest(width = 400, height = 800) {
    val data = MutableStateFlow(PagingData.from(posts(20)))
    var count = { 0 }
    setContent {
      val items = data.collectAsLazyPagingItems()
      count = { items.itemCount }
      if (items.itemCount > 0) Viewer(items, items.itemCount, page = 18)
    }
    waitUntil(timeoutMillis = 5_000) { mainClock.advanceTimeByFrame(); count() == 20 }
    waitForIdle()

    data.value = PagingData.from(posts(19))
    waitUntil(timeoutMillis = 5_000) { mainClock.advanceTimeByFrame(); count() == 19 }
    waitForIdle()
  }

  @Test
  fun pastTheEndThereIsNoPostAndTheKeyIsThePage() = runDesktopComposeUiTest {
    val data = MutableStateFlow(PagingData.from(posts(3)))
    lateinit var items: LazyPagingItems<PostDto>
    setContent { items = data.collectAsLazyPagingItems() }
    waitUntil(timeoutMillis = 5_000) { mainClock.advanceTimeByFrame(); items.itemCount == 3 }

    assertEquals("p2", items.viewerPageKey(2, ReadingOrder.ListOrder))
    assertEquals(3, items.viewerPageKey(3, ReadingOrder.ListOrder))
    assertEquals("p2", items.peekOrNull(2)?.id)
    assertNull(items.peekOrNull(3))
    assertNull(items.getOrNull(3))
    assertNull(items.peekOrNull(-1))
  }
}
