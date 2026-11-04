package dev.marzahn.sankaku.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.Page
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a keyset list's page is: the server's `meta.next` cursor, and the page's number from 1. Most lists page by
 * the cursor alone, but For you ignores it and pages by the number (`docs/api.md`), so both are sent, as the web app
 * does.
 */
data class PageCursor(val next: String? = null, val number: Int = 1) {
  /** The page after this one, which [page] (this one) points to; null at the end. */
  internal fun after(page: Page<*>): PageCursor? {
    // A cursor that doesn't move ends the list: the For you feed answered a page with the cursor it was asked with
    // (2026-10-30), and without that the list would loop.
    val next = page.meta.next?.takeIf { it != next } ?: return null
    return PageCursor(next, number + 1)
  }
}

/**
 * Pages a keyset list by [PageCursor], from [start] (a grid opened at its place, #162). A refresh always restarts from
 * the first page. A post a page repeats from an earlier one is dropped (For you repeats a few from page to page): a
 * grid keys its cells by [id], and a key twice in a lazy grid crashes it. [onLoaded] sees each page's posts as the
 * grid gets them, with the cursor that loaded them.
 */
class KeysetPagingSource<T : Any>(
  private val id: (T) -> Any,
  private val start: PageCursor = PageCursor(),
  private val onLoaded: (PageCursor, List<T>) -> Unit = { _, _ -> },
  private val load: suspend (PageCursor) -> Page<T>,
) : PagingSource<PageCursor, T>() {
  // The ids this source has given out. One source is one generation of the list: a refresh makes a new one. This
  // relies on Paging never dropping and reloading a page (no `maxSize`): a reloaded page would come back empty.
  // [PostsRepository.cachedIndexOf] counts the grid the same way.
  private val seen = mutableSetOf<Any>()
  private val loading = Mutex()

  /**
   * The server can filter a whole page away (posts in books, the account's blacklist) and still give a `next`. Paging
   * asks for more only when items show, so an empty first page would end the grid as "no results": such pages, and
   * pages of nothing but repeats, are skipped, up to [MAX_EMPTY_PAGES] in a row.
   */
  override suspend fun load(params: LoadParams<PageCursor>): LoadResult<PageCursor, T> = try {
    loading.withLock {
      var cursor = params.key ?: start
      var page = load(cursor)
      var fresh = page.data.filter { seen.add(id(it)) }
      var skipped = 0
      while (fresh.isEmpty() && skipped < MAX_EMPTY_PAGES) {
        cursor = cursor.after(page) ?: break
        page = load(cursor)
        fresh = page.data.filter { seen.add(id(it)) }
        skipped++
      }
      onLoaded(cursor, fresh)
      LoadResult.Page(data = fresh, prevKey = null, nextKey = cursor.after(page))
    }
  } catch (e: ApiError) {
    LoadResult.Error(e)
  }

  override fun getRefreshKey(state: PagingState<PageCursor, T>): PageCursor? = null

  companion object {
    const val MAX_EMPTY_PAGES = 5
  }
}
