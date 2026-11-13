package dev.marzahn.sankaku.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.Meta
import dev.marzahn.sankaku.api.dto.Page
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class KeysetPagingSourceTest {
  private val config = PagingConfig(pageSize = 2, initialLoadSize = 2, prefetchDistance = 2, enablePlaceholders = false)

  @Test
  fun cursorAndPageNumberBecomeNextKeyAndNullEndsPaging() = runTest {
    val seen = mutableListOf<PageCursor>()
    val source = KeysetPagingSource({ it }) { cursor ->
      seen += cursor
      when (cursor.next) {
        null -> Page(listOf("a", "b"), Meta(next = "c2"))
        else -> Page(listOf("c"), Meta(next = null))
      }
    }
    val pager = TestPager(config, source)

    val first = assertIs<LoadResult.Page<PageCursor, String>>(pager.refresh())
    val second = assertIs<LoadResult.Page<PageCursor, String>>(pager.append())

    assertEquals(listOf("a", "b"), first.data)
    assertEquals(PageCursor("c2", 2), first.nextKey)
    assertEquals(listOf("c"), second.data)
    assertNull(second.nextKey)
    assertEquals(listOf(PageCursor(null, 1), PageCursor("c2", 2)), seen)
  }

  /** The search-time limit is intermittent (`docs/api.md`, step 4): a page that ran past it is asked once more (#520). */
  @Test
  fun aSearchTimeoutIsRetriedOnceThenShown() = runTest {
    for (timeouts in 1..2) {
      var calls = 0
      val source = KeysetPagingSource({ it }) {
        calls++
        if (calls <= timeouts) throw ApiError.SearchTimeout() else Page(listOf("a"), Meta(next = null))
      }

      val result = TestPager(config, source).refresh()

      assertEquals(2, calls, "$timeouts timeouts")
      if (timeouts == 1) assertIs<LoadResult.Page<PageCursor, String>>(result) else assertIs<ApiError.SearchTimeout>(assertIs<LoadResult.Error<PageCursor, String>>(result).throwable)
    }
  }

  @Test
  fun aCursorThatDoesNotMoveEndsPaging() = runTest {
    val source = KeysetPagingSource({ it }) { cursor ->
      when (cursor.next) {
        null -> Page(listOf("a"), Meta(next = "c2"))
        else -> Page(listOf("b"), Meta(next = "c2"))
      }
    }
    val pager = TestPager(config, source)

    assertIs<LoadResult.Page<PageCursor, String>>(pager.refresh())
    val second = assertIs<LoadResult.Page<PageCursor, String>>(pager.append())

    assertEquals(listOf("b"), second.data)
    assertNull(second.nextKey)
  }

  /**
   * For you (2026-10-31) repeats a few posts from one page to the next. The grid keys its cells by id, and a key twice
   * crashed it when Top scrolled both copies into view (#72).
   */
  @Test
  fun aPostAnEarlierPageHadIsDroppedSoEachShowsOnce() = runTest {
    val source = KeysetPagingSource({ it }) { cursor ->
      when (cursor.number) {
        1 -> Page(listOf("a", "b"), Meta(next = "n2"))
        2 -> Page(listOf("b", "c"), Meta(next = "n3"))
        else -> Page(listOf("a", "d"), Meta(next = null))
      }
    }
    val pager = TestPager(config, source)

    pager.refresh()
    pager.append()
    pager.append()

    assertEquals(listOf("a", "b", "c", "d"), pager.getPages().flatMap { it.data })
  }

  @Test
  fun aPageOfNothingButRepeatsIsSkippedLikeAnEmptyOne() = runTest {
    val source = KeysetPagingSource({ it }) { cursor ->
      when (cursor.number) {
        1 -> Page(listOf("a", "b"), Meta(next = "n2"))
        2 -> Page(listOf("b", "a"), Meta(next = "n3"))
        else -> Page(listOf("c"), Meta(next = "n4"))
      }
    }
    val pager = TestPager(config, source)

    pager.refresh()
    val second = assertIs<LoadResult.Page<PageCursor, String>>(pager.append())

    assertEquals(listOf("c"), second.data)
    assertEquals(PageCursor("n4", 4), second.nextKey)
  }

  /** #507: posts hidden from signed-out users are left out, and a page of nothing else is skipped like an empty one. */
  @Test
  fun hiddenItemsAreLeftOutAndEachPageWithSomeIsReported() = runTest {
    var hidden = 0
    val source = KeysetPagingSource({ it }, hide = { it.startsWith("x") }, onHidden = { hidden++ }) { cursor ->
      when (cursor.number) {
        1 -> Page(listOf("a", "x1"), Meta(next = "n2"))
        2 -> Page(listOf("x2", "x3"), Meta(next = "n3"))
        else -> Page(listOf("b"), Meta(next = null))
      }
    }
    val pager = TestPager(config, source)

    val first = assertIs<LoadResult.Page<PageCursor, String>>(pager.refresh())
    val second = assertIs<LoadResult.Page<PageCursor, String>>(pager.append())

    assertEquals(listOf("a"), first.data)
    assertEquals(listOf("b"), second.data)
    assertNull(second.nextKey)
    assertEquals(2, hidden)
  }

  @Test
  fun aRefreshStartsANewListWhereEveryPostShowsAgain() = runTest {
    val source = { KeysetPagingSource({ it }) { Page(listOf("a"), Meta(next = null)) } }

    val first = assertIs<LoadResult.Page<PageCursor, String>>(TestPager(config, source()).refresh())
    val again = assertIs<LoadResult.Page<PageCursor, String>>(TestPager(config, source()).refresh())

    assertEquals(listOf("a"), first.data)
    assertEquals(listOf("a"), again.data)
  }

  @Test
  fun aPageTheServerFilteredEmptyIsSkippedSoTheGridDoesNotEnd() = runTest {
    val source = KeysetPagingSource({ it }) { cursor ->
      when (cursor.next) {
        null -> Page(emptyList(), Meta(next = "p2"))
        "p2" -> Page(emptyList(), Meta(next = "p3"))
        else -> Page(listOf("a"), Meta(next = "p4"))
      }
    }

    val first = assertIs<LoadResult.Page<PageCursor, String>>(TestPager(config, source).refresh())

    assertEquals(listOf("a"), first.data)
    assertEquals(PageCursor("p4", 4), first.nextKey)
  }

  @Test
  fun skippingEmptyPagesStopsAfterAFew() = runTest {
    var loads = 0
    val source = KeysetPagingSource<String>({ it }) { cursor -> loads++; Page(emptyList(), Meta(next = "more${cursor.number}")) }

    val first = assertIs<LoadResult.Page<PageCursor, String>>(TestPager(config, source).refresh())

    assertEquals(emptyList(), first.data)
    assertEquals(KeysetPagingSource.MAX_EMPTY_PAGES + 1, loads)
  }

  @Test
  fun apiErrorBecomesLoadError() = runTest {
    val source = KeysetPagingSource<String>({ it }) { throw ApiError.Http(503, "") }

    val result = TestPager(config, source).refresh()

    assertIs<ApiError.Http>(assertIs<LoadResult.Error<PageCursor, String>>(result).throwable)
  }
}
