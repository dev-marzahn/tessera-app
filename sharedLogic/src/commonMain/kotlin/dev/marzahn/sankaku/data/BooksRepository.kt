package dev.marzahn.sankaku.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.BookListResponse
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.SeriesDto
import dev.marzahn.sankaku.api.dto.SeriesSummaryDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A row of the Books list (#380): a standalone book, or a series shown as one row by its [SeriesSummaryDto.firstPool]. */
sealed interface BookListItem {
  /** Unique in one list: a book and a series can share an id. */
  val key: String

  data class Book(val book: BookDto) : BookListItem {
    override val key: String get() = "book-${book.id}"
  }

  data class Series(val series: SeriesSummaryDto, val first: BookDto) : BookListItem {
    override val key: String get() = "series-${series.id}"
  }
}

/** The Books list's two orders (#377 decision 7). */
enum class BookSort(val tag: String, val label: String) {
  Popular("order:popularity", "Popular"),
  Newest("order:date", "Newest"),
}

/**
 * A Books search as a route keeps it: the typed tags, an `order:` token only for [BookSort.Newest] (no order is
 * Popular, the default) and [ENGLISH] when "English only" is on. What's sent ([searched]) always names the order.
 */
object BookSearch {
  /** The "English only" toggle's tag (off by default). */
  const val ENGLISH = "english_language"

  private const val SAFE = "rating:s"

  fun sortOf(tags: List<String>): BookSort =
    BookSort.entries.firstOrNull { sort -> tags.any { it.equals(sort.tag, ignoreCase = true) } } ?: BookSort.Popular

  fun englishOnly(tags: List<String>): Boolean = tags.any { it.equals(ENGLISH, ignoreCase = true) }

  fun withSort(tags: List<String>, sort: BookSort): List<String> =
    SearchTags.filtersFirst(tags.filterNot(::isOrder) + listOfNotNull(sort.tag.takeIf { sort != BookSort.Popular }))

  fun withEnglishOnly(tags: List<String>, on: Boolean): List<String> =
    tags.filterNot { it.equals(ENGLISH, ignoreCase = true) } + listOfNotNull(ENGLISH.takeIf { on })

  /**
   * A search submitted from the search panel over a Books screen, whose draft started as [current]: [next]'s tags,
   * order and English only (a chip removed there turns it off), without what Books can't search by (a feed, a minimum
   * score). An order Books doesn't have (`order:score`) keeps [current]'s.
   */
  fun submitted(next: List<String>, current: List<String>): List<String> {
    val tags = next.filterNot { isOrder(it) || isFeedOrThreshold(it) || it.equals(ENGLISH, ignoreCase = true) }
    val order = next.firstOrNull(::isOrder)
    val sort = when (order) {
      null -> BookSort.Popular
      else -> BookSort.entries.firstOrNull { it.tag.equals(order, ignoreCase = true) } ?: sortOf(current)
    }
    return withEnglishOnly(withSort(tags, sort), englishOnly(next))
  }

  /**
   * What [BooksApi.list] is sent for [tags]: the order first, then the tags. With the rating locked (signed out, or
   * G only) any rating tag is replaced by `rating:s`, as on grids.
   */
  fun searched(tags: List<String>, ratingLocked: Boolean): List<String> {
    val rest = tags.filterNot(::isOrder).let { if (ratingLocked) it.filterNot(::isRating) + SAFE else it }
    return SearchTags.filtersFirst(listOf(sortOf(tags).tag) + rest)
  }

  /** Whether [tags] search for nothing: only the order and English only, the Books screen's own chips. */
  fun isPlain(tags: List<String>): Boolean = tags.all { isOrder(it) || it.equals(ENGLISH, ignoreCase = true) }

  /**
   * What the Continue reading shelf sends (#503): `order:reading`, the books with a saved place, the most recently
   * saved first (`docs/api.md` → Books → Continue reading); with the rating locked, `rating:s` too.
   */
  fun reading(ratingLocked: Boolean): List<String> = listOf(READING) + listOfNotNull(SAFE.takeIf { ratingLocked })

  private const val READING = "order:reading"

  private fun isOrder(tag: String) = tag.removePrefix("-").startsWith("order:", ignoreCase = true)

  private fun isRating(tag: String) = tag.removePrefix("-").startsWith("rating:", ignoreCase = true)

  private fun isFeedOrThreshold(tag: String) =
    tag.removePrefix("-").let { it.startsWith("feed:", ignoreCase = true) || it.startsWith("threshold:", ignoreCase = true) }
}

/**
 * The Books list and what its rows show, on [api] (the main client, so under the shared rate limiter). A row's first
 * pages are cached for [pagesFor] (10 minutes, #377 decision 4) in memory, per book and per signed-in state, since
 * signed out a page can come without its URLs. A row's ask runs in [scope], not the row's: a row scrolled away before
 * the answer leaves it to finish and be cached, and a row shown again meanwhile waits for the same ask. What the list
 * and a series say of the ♡ replaces an earlier ♡ made in the app ([BookFavourites.fresh], #383).
 */
class BooksRepository(
  private val api: BooksApi,
  private val scope: CoroutineScope,
  private val clock: Clock = Clock.System,
  private val pagesFor: Duration = FIRST_PAGES_TTL,
  signedIn: Flow<Boolean> = emptyFlow(),
  private val favourites: BookFavourites? = null,
) {
  /** One generation of the list for [searched] ([BookSearch.searched]); a refresh makes a new source. */
  fun list(searched: List<String>): Flow<PagingData<BookListItem>> = Pager(
    PagingConfig(pageSize = BooksApi.PAGE_SIZE, initialLoadSize = BooksApi.PAGE_SIZE, prefetchDistance = PREFETCH_DISTANCE, enablePlaceholders = false),
  ) { BookListPagingSource { page -> fresh({ api.list(searched, page) }) { it.pools.data + it.series.data.mapNotNull { s -> s.firstPool } } } }.flow

  /**
   * The books the account has a place in, the most recently saved first (#503): one page of `order:reading`, signed in
   * only. Finished ones are among them; the shelf leaves them out, as a place saved here may be newer.
   */
  suspend fun reading(ratingLocked: Boolean): List<BookDto> =
    fresh({ api.list(BookSearch.reading(ratingLocked), page = 1, limit = BooksApi.PAGE_SIZE) }) { it.pools.data }.pools.data

  private class CachedPages(val pages: List<PostDto>, val at: Instant)

  // Replaced whole on each change, so a row's first frame can read it from any thread ([cachedFirstPages]).
  private val pages = MutableStateFlow<Map<Pair<String, Boolean>, CachedPages>>(emptyMap())

  /**
   * [bookId]'s first [ROW_PAGES] pages for its row, from the cache while it's younger than [pagesFor].
   * One ask per book at a time ([asking]). A failure isn't cached, so the row asks again the next time it's shown.
   */
  suspend fun firstPages(bookId: String, signedIn: Boolean): List<PostDto> {
    cachedFirstPages(bookId, signedIn)?.let { return it }
    val key = bookId to signedIn
    val ask = askingLock.withLock {
      asking[key]?.takeUnless { it.isCompleted } ?: scope.async {
        api.firstPages(bookId, ROW_PAGES).also { loaded ->
          pages.update { cached ->
            val now = clock.now()
            cached.filterValues { now - it.at < pagesFor } + (key to CachedPages(loaded, now))
          }
        }
      }.also { asking[key] = it }
    }
    try {
      return ask.await()
    } finally {
      askingLock.withLock { if (asking[key] === ask && ask.isCompleted) asking.remove(key) }
    }
  }

  // The asks still running, by book and signed-in state.
  private val asking = mutableMapOf<Pair<String, Boolean>, Deferred<List<PostDto>>>()
  private val askingLock = Mutex()

  /** The cached first pages of [bookId], without asking; null when none are cached or they're too old. */
  fun cachedFirstPages(bookId: String, signedIn: Boolean): List<PostDto>? =
    pages.value[bookId to signedIn]?.takeIf { clock.now() - it.at < pagesFor }?.pages

  // The books holding a post, by post and signed-in state, newest last; replaced whole on each change, so the sheet's
  // first frame can read it from any thread ([cachedBookOf]).
  private val booksOfPosts = MutableStateFlow<Map<Pair<String, Boolean>, List<BookDto>>>(emptyMap())

  // Counts sign-outs, so an ask started before one isn't kept after it.
  private val signOuts = MutableStateFlow(0)

  // The post's-book asks still running, by post and signed-in state; under [askingLock] with the first pages'.
  private val askingBooks = mutableMapOf<Pair<String, Boolean>, Deferred<List<BookDto>>>()

  init {
    // Another account is always reached through signed out (a sign-out or an expired session), so forgetting the posts'
    // books then, and every answer still on its way, means no account sees what was found for another. The count goes up
    // before the books are cleared: an answer kept just before is cleared, one arriving after sees the new count.
    scope.launch {
      signedIn.filter { !it }.collect {
        askingLock.withLock {
          signOuts.update { it + 1 }
          booksOfPosts.value = emptyMap()
          askingBooks.clear()
        }
      }
    }
  }

  /**
   * The book [post] is a page of, for the post sheet's "From the book" row (#386), or null for none. A post that isn't
   * a page ([PostDto.inVisiblePool] false) costs no request. Otherwise one request per post and signed-in state, kept for the session (the
   * last [BOOKS_OF_POSTS_MAX] posts); a failure isn't kept, so the sheet asks again the next time it's shown. Asks for
   * one post at a time are shared ([askingBooks]), and run in [scope] as a row's do, so a sheet closed before the answer
   * still keeps it. With the rating locked, the G book of those holding the post; otherwise the first the API lists.
   */
  suspend fun bookOf(post: PostDto, signedIn: Boolean, ratingLocked: Boolean): BookDto? {
    if (!asksForBook(post)) return null
    val key = post.id to signedIn
    booksOfPosts.value[key]?.let { return pick(it, ratingLocked) }
    val ask = askingLock.withLock {
      askingBooks[key]?.takeUnless { it.isCompleted } ?: run {
        val since = signOuts.value
        scope.async {
          api.booksOf(post.id).also { loaded ->
            booksOfPosts.update { cached ->
              if (signOuts.value != since) cached
              else (cached - key + (key to loaded)).entries.toList().takeLast(BOOKS_OF_POSTS_MAX).associate { it.toPair() }
            }
          }
        }
      }.also { askingBooks[key] = it }
    }
    try {
      return pick(ask.await(), ratingLocked)
    } finally {
      askingLock.withLock { if (askingBooks[key] === ask && ask.isCompleted) askingBooks.remove(key) }
    }
  }

  /** The kept [bookOf] answer, without asking; null when there's none or it isn't kept. */
  fun cachedBookOf(post: PostDto, signedIn: Boolean, ratingLocked: Boolean): BookDto? =
    booksOfPosts.value[post.id to signedIn]?.let { pick(it, ratingLocked) }

  private fun asksForBook(post: PostDto) = post.inVisiblePool

  // With the rating locked only a G book: a page can be in two books of the same name, of different ratings, and the
  // query isn't narrowed by rating (`docs/api.md` → A post's book).
  private fun pick(books: List<BookDto>, ratingLocked: Boolean): BookDto? =
    if (ratingLocked) books.firstOrNull { it.rating.isSafe() } else books.firstOrNull()

  /**
   * The series [seriesId]'s books in series order. With the rating locked, only its G books ([BookDto.rating] `s`):
   * a series' books aren't narrowed by the list's `rating:s` (`docs/api.md` → Books).
   */
  suspend fun series(seriesId: String, ratingLocked: Boolean): SeriesDto {
    val series = fresh({ api.series(seriesId) }) { it.books }
    return if (ratingLocked) series.copy(books = series.books.filter { it.rating.isSafe() }) else series
  }

  private suspend fun <T> fresh(load: suspend () -> T, books: (T) -> List<BookDto>): T {
    val favourites = favourites ?: return load()
    return favourites.fresh(load, books)
  }

  companion object {
    val FIRST_PAGES_TTL: Duration = 10.minutes

    /**
     * The pages a row asks for: the 3 it shows at most, and one more, since the cover is usually page 1 and isn't shown
     * twice. Still one request of about 16 KB.
     */
    const val ROW_PAGES = BooksApi.FIRST_PAGES + 1

    /** The posts whose books [bookOf] keeps: a session's worth of sheets, each a few small books. */
    const val BOOKS_OF_POSTS_MAX = 200

    /** Rows are tall, so a page is several screens: one page ahead is plenty. */
    const val PREFETCH_DISTANCE = 6
  }
}

/** Whether a rating code is G (`s`, or the long `safe`). */
fun String?.isSafe(): Boolean = this.equals("s", ignoreCase = true) || this.equals("safe", ignoreCase = true)

/**
 * Pages the Books list by `page`, from 1 (the cursor alone repeats page 1, `docs/api.md` → Books). Page 1 starts with
 * its series, each as one row, then its books. A series with no first book (signed out) has nothing to show or open,
 * so it's left out. Only a page with no books ends the list: the server filters after the limit, so a short page
 * doesn't. A row a later page repeats (`order:popularity` moves between calls) is dropped, since rows are keyed by
 * [BookListItem.key]; a page of nothing but repeats is skipped, up to [MAX_REPEATED_PAGES] in a row.
 */
class BookListPagingSource(private val load: suspend (page: Int) -> BookListResponse) : PagingSource<Int, BookListItem>() {
  private val seen = mutableSetOf<String>()
  private val loading = Mutex()

  override suspend fun load(params: LoadParams<Int>): LoadResult<Int, BookListItem> = try {
    loading.withLock {
      var page = params.key ?: 1
      var response = load(page)
      var fresh = rows(page, response).filter { seen.add(it.key) }
      var skipped = 0
      while (fresh.isEmpty() && response.pools.data.isNotEmpty() && skipped < MAX_REPEATED_PAGES) {
        page++
        response = load(page)
        fresh = rows(page, response).filter { seen.add(it.key) }
        skipped++
      }
      // Still nothing new after the skips: the list is treated as ended, rather than asked again at once.
      val ended = response.pools.data.isEmpty() || fresh.isEmpty()
      LoadResult.Page(fresh, prevKey = null, nextKey = if (ended) null else page + 1)
    }
  } catch (e: ApiError) {
    LoadResult.Error(e)
  }

  private fun rows(page: Int, response: BookListResponse): List<BookListItem> {
    val series = if (page == 1) {
      response.series.data.mapNotNull { series -> series.firstPool?.let { BookListItem.Series(series, it) } }
    } else {
      emptyList()
    }
    return series + response.pools.data.map(BookListItem::Book)
  }

  override fun getRefreshKey(state: PagingState<Int, BookListItem>): Int? = null

  companion object {
    const val MAX_REPEATED_PAGES = 5
  }
}
