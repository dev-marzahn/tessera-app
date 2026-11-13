package dev.marzahn.sankaku.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.BookListResponse
import dev.marzahn.sankaku.api.dto.Meta
import dev.marzahn.sankaku.api.dto.Page
import dev.marzahn.sankaku.api.dto.PostDto
import io.ktor.http.HttpStatusCode
import kotlin.test.assertFailsWith
import dev.marzahn.sankaku.api.dto.SeriesSummaryDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class BookSearchTest {
  @Test
  fun popularIsTheDefaultAndNeverStoredNewestIsOrderDate() {
    assertEquals(BookSort.Popular, BookSearch.sortOf(emptyList()))
    assertEquals(listOf("order:date", "maid"), BookSearch.withSort(listOf("maid"), BookSort.Newest))
    assertEquals(BookSort.Newest, BookSearch.sortOf(listOf("order:date", "maid")))
    assertEquals(listOf("maid"), BookSearch.withSort(listOf("order:date", "maid"), BookSort.Popular))
  }

  @Test
  fun englishOnlyIsOffByDefaultAndToggles() {
    assertFalse(BookSearch.englishOnly(emptyList()))
    val on = BookSearch.withEnglishOnly(listOf("maid"), on = true)
    assertEquals(listOf("maid", "english_language"), on)
    assertTrue(BookSearch.englishOnly(on))
    assertEquals(listOf("maid"), BookSearch.withEnglishOnly(on, on = false))
  }

  @Test
  fun searchedAlwaysNamesTheOrderAndTheLockSendsRatingS() {
    assertEquals(listOf("order:popularity", "maid"), BookSearch.searched(listOf("maid"), ratingLocked = false))
    assertEquals(
      listOf("order:date", "rating:s", "maid", "english_language"),
      BookSearch.searched(listOf("order:date", "rating:e", "maid", "english_language"), ratingLocked = true),
    )
    assertEquals(listOf("order:popularity", "rating:q"), BookSearch.searched(listOf("rating:q"), ratingLocked = false))
  }

  @Test
  fun continueReadingIsOrderReadingAndOnlyAPlainSearchShowsIt() {
    // #503: Sankaku's own Continue reading list (`docs/api.md` → Books → Continue reading), narrowed like the list.
    assertEquals(listOf("order:reading"), BookSearch.reading(ratingLocked = false))
    assertEquals(listOf("order:reading", "rating:s"), BookSearch.reading(ratingLocked = true))
    assertTrue(BookSearch.isPlain(emptyList()))
    assertTrue(BookSearch.isPlain(listOf("order:date", "english_language")))
    assertFalse(BookSearch.isPlain(listOf("maid")))
    assertFalse(BookSearch.isPlain(listOf("rating:e")))
  }

  @Test
  fun aSubmittedSearchDropsGridOnlyFiltersAndKeepsTheBooksSettings() {
    val current = listOf("order:date", "english_language")
    assertEquals(
      listOf("rating:q", "order:date", "maid", "english_language"),
      BookSearch.submitted(listOf("feed:following", "threshold:3", "order:quality", "rating:q", "maid", "english_language"), current),
    )
    assertEquals(listOf("maid"), BookSearch.submitted(listOf("order:popularity", "maid"), listOf("order:date")))
    // Chips removed in the search bar turn Newest and English only off.
    assertEquals(listOf("maid"), BookSearch.submitted(listOf("maid"), current))
  }
}

class BookListPagingSourceTest {
  private val config = PagingConfig(pageSize = 2, initialLoadSize = 2, prefetchDistance = 1, enablePlaceholders = false)

  private fun book(id: String) = BookDto(id = id, name = id)

  private fun response(vararg books: String, series: List<SeriesSummaryDto> = emptyList()) = BookListResponse(
    series = BookListResponse.SeriesList(series),
    pools = Page(books.map(::book), Meta(next = "ignored")),
  )

  @Test
  fun seriesComeFirstOnPageOneAndAnEmptyPageEndsTheList() = runTest {
    val asked = mutableListOf<Int>()
    val series = listOf(
      SeriesSummaryDto(id = "s1", poolCount = 3, firstPool = book("b9")),
      // Signed out: nothing to show or open.
      SeriesSummaryDto(id = "s2", poolCount = 2, firstPool = null),
    )
    val pager = TestPager(
      config,
      BookListPagingSource { page ->
        asked += page
        when (page) {
          1 -> response("b1", "b2", series = series)
          // A short page doesn't end the list.
          2 -> response("b3")
          3 -> response("b4", "b5")
          else -> response()
        }
      },
    )

    val first = assertIs<LoadResult.Page<Int, BookListItem>>(pager.refresh())
    assertEquals(listOf("series-s1", "book-b1", "book-b2"), first.data.map { it.key })
    assertEquals(2, first.nextKey)
    assertEquals(listOf("book-b3"), assertIs<LoadResult.Page<Int, BookListItem>>(pager.append()).data.map { it.key })
    assertEquals(4, assertIs<LoadResult.Page<Int, BookListItem>>(pager.append()).nextKey)
    val last = assertIs<LoadResult.Page<Int, BookListItem>>(pager.append())
    assertTrue(last.data.isEmpty())
    assertNull(last.nextKey)
    assertEquals(listOf(1, 2, 3, 4), asked)
  }

  @Test
  fun onlyRepeatsAfterTheSkipsEndTheList() = runTest {
    val pager = TestPager(config, BookListPagingSource { response("b1") })
    pager.refresh()
    assertNull(assertIs<LoadResult.Page<Int, BookListItem>>(pager.append()).nextKey)
  }

  @Test
  fun repeatedBooksAreDroppedAndAPageOfRepeatsIsSkipped() = runTest {
    val pager = TestPager(
      config,
      BookListPagingSource { page ->
        when (page) {
          1 -> response("b1", "b2")
          2 -> response("b2", "b1")
          3 -> response("b2", "b3")
          else -> response()
        }
      },
    )
    pager.refresh()
    val next = assertIs<LoadResult.Page<Int, BookListItem>>(pager.append())
    assertEquals(listOf("book-b3"), next.data.map { it.key })
    assertEquals(4, next.nextKey)
  }

  @Test
  fun anApiErrorIsALoadError() = runTest {
    val pager = TestPager(config, BookListPagingSource { throw ApiError.Network(RuntimeException("x")) })
    assertIs<LoadResult.Error<Int, BookListItem>>(pager.refresh())
  }
}

class BooksRepositoryFirstPagesTest {
  private class MovableClock(var now: Instant) : Clock {
    override fun now(): Instant = now
  }

  private val harness = Harness(InMemoryTokenStore(storedTokens())) { request ->
    when (request.url.encodedPath) {
      "/v2/posts/keyset" -> json(Fixtures.page(null, "p1", "p2", "p3"))
      else -> error("unexpected path")
    }
  }

  @Test
  fun aRowGoneBeforeTheAnswerLeavesItToFinishAndAnotherShares() = runTest {
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope)
    // The row scrolled away: its wait is cancelled, the ask isn't.
    val gone = launch { books.firstPages("b1", signedIn = false) }
    val shown = async { books.firstPages("b1", signedIn = false) }
    gone.cancel()
    assertEquals(3, shown.await().size)
    assertEquals(1, harness.requestsTo("/v2/posts/keyset").size)
    assertEquals(3, books.cachedFirstPages("b1", signedIn = false)?.size)
  }

  @Test
  fun firstPagesAreCachedTenMinutesPerSignedInState() = runTest {
    val clock = MovableClock(Instant.fromEpochSeconds(1_800_000_000))
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope, clock)

    assertNull(books.cachedFirstPages("b1", signedIn = false))
    assertEquals(listOf("p1", "p2", "p3"), books.firstPages("b1", signedIn = false).map { it.id })
    books.firstPages("b1", signedIn = false)
    assertEquals(1, harness.requestsTo("/v2/posts/keyset").size)
    // One more than a row shows: the cover is usually page 1.
    assertEquals("4", harness.requestsTo("/v2/posts/keyset").single().url.parameters["limit"])
    assertEquals(3, books.cachedFirstPages("b1", signedIn = false)?.size)

    // Signed in, a page can carry URLs it lacked signed out: asked again.
    books.firstPages("b1", signedIn = true)
    assertEquals(2, harness.requestsTo("/v2/posts/keyset").size)

    clock.now += 9.minutes
    books.firstPages("b1", signedIn = false)
    assertEquals(2, harness.requestsTo("/v2/posts/keyset").size)

    clock.now += 1.minutes
    assertNull(books.cachedFirstPages("b1", signedIn = false))
    books.firstPages("b1", signedIn = false)
    assertEquals(3, harness.requestsTo("/v2/posts/keyset").size)
  }
}

/** #386: the book a post is a page of, for the post sheet's row. */
class BooksRepositoryBookOfTest {
  private var failing = false
  private val harness = Harness(InMemoryTokenStore(storedTokens())) { request ->
    when {
      request.url.encodedPath != BOOKS_OF_PATH -> error("unexpected path")
      failing -> json("""{ "success": false, "code": "server-error" }""", HttpStatusCode.InternalServerError)
      else -> json(
        """[ { "id": 11, "name": "Kanji", "name_en": "Explicit edition", "rating": "e" },
          { "id": 12, "name": "Kanji", "name_en": "The book", "rating": "s" } ]""",
      )
    }
  }
  private val page = PostDto(id = "aB3dE5gH7jK", md5 = "m", rating = "s", status = "active", inVisiblePool = true)

  @Test
  fun aPostInNoBookAsksNothing() = runTest {
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope)
    assertNull(books.bookOf(page.copy(inVisiblePool = false), signedIn = true, ratingLocked = false))
    assertTrue(harness.requests.isEmpty())
  }

  @Test
  fun aPageAsksOnceAndIsKept() = runTest {
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope)
    assertNull(books.cachedBookOf(page, signedIn = true, ratingLocked = false))
    assertEquals("11", books.bookOf(page, signedIn = true, ratingLocked = false)?.id)
    assertEquals("11", books.bookOf(page, signedIn = true, ratingLocked = false)?.id)
    assertEquals("11", books.cachedBookOf(page, signedIn = true, ratingLocked = false)?.id)
    assertEquals(1, harness.requestsTo(BOOKS_OF_PATH).size)
    // Signed out, a book can come without what it had signed in: asked again.
    books.bookOf(page, signedIn = false, ratingLocked = true)
    assertEquals(2, harness.requestsTo(BOOKS_OF_PATH).size)
  }

  @Test
  fun twoSheetsOnOnePostShareOneAsk() = runTest {
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope)
    val first = async { books.bookOf(page, signedIn = true, ratingLocked = false) }
    val second = async { books.bookOf(page, signedIn = true, ratingLocked = true) }
    assertEquals("11", first.await()?.id)
    assertEquals("12", second.await()?.id)
    assertEquals(1, harness.requestsTo(BOOKS_OF_PATH).size)
  }

  @Test
  fun theLockedRatingPicksTheGBookFromTheSameAnswer() = runTest {
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope)
    assertEquals("The book", books.bookOf(page, signedIn = true, ratingLocked = true)?.title)
    assertEquals("Explicit edition", books.cachedBookOf(page, signedIn = true, ratingLocked = false)?.title)
    assertEquals(1, harness.requestsTo(BOOKS_OF_PATH).size)
  }

  @Test
  fun aFailureIsNotKept() = runTest {
    // Supervised as the app's scope is, so the failed ask reaches only its caller.
    val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
    val books = BooksRepository(BooksApi(harness.mainClient), scope)
    failing = true
    assertFailsWith<ApiError> { books.bookOf(page, signedIn = true, ratingLocked = false) }
    failing = false
    assertEquals("11", books.bookOf(page, signedIn = true, ratingLocked = false)?.id)
    assertEquals(2, harness.requestsTo(BOOKS_OF_PATH).size)
  }
}

/** #386 review: what was found for one account is forgotten on sign-out, the way to any other account. */
class BooksRepositoryBookOfAccountTest {
  private val harness = Harness(InMemoryTokenStore(storedTokens())) {
    json("""[{ "id": 11, "name_en": "The book", "rating": "s" }]""")
  }
  private val page = PostDto(id = "aB3dE5gH7jK", md5 = "m", rating = "s", status = "active", inVisiblePool = true)

  @Test
  fun signingOutForgetsThePostsBooks() = runTest {
    val signedIn = kotlinx.coroutines.flow.MutableStateFlow(true)
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope, signedIn = signedIn)
    books.bookOf(page, signedIn = true, ratingLocked = false)
    assertEquals("11", books.cachedBookOf(page, signedIn = true, ratingLocked = false)?.id)
    signedIn.value = false
    testScheduler.runCurrent()
    assertNull(books.cachedBookOf(page, signedIn = true, ratingLocked = false))
  }
}

/** #395 review: an answer still on its way at a sign-out isn't kept for whoever signs in next. */
class BooksRepositoryBookOfSignOutRaceTest {
  private val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
  private val harness = Harness(InMemoryTokenStore(storedTokens())) {
    answer.await()
    json("""[{ "id": 11, "name_en": "The book", "rating": "s" }]""")
  }
  private val page = PostDto(id = "aB3dE5gH7jK", md5 = "m", rating = "s", status = "active", inVisiblePool = true)

  @Test
  fun anAskInFlightAtASignOutIsNotKept() = runTest {
    val signedIn = kotlinx.coroutines.flow.MutableStateFlow(true)
    val books = BooksRepository(BooksApi(harness.mainClient), backgroundScope, signedIn = signedIn)
    val asking = async { books.bookOf(page, signedIn = true, ratingLocked = false) }
    testScheduler.runCurrent()
    signedIn.value = false
    testScheduler.runCurrent()
    answer.complete(Unit)
    asking.await()
    assertNull(books.cachedBookOf(page, signedIn = true, ratingLocked = false))
    // Signed in again: asked afresh.
    signedIn.value = true
    assertEquals("11", books.bookOf(page, signedIn = true, ratingLocked = false)?.id)
    assertEquals(2, harness.requestsTo(BOOKS_OF_PATH).size)
  }
}

/** Where a post's books are asked for: the web app's `/post/{id}/pools` (#475). */
private const val BOOKS_OF_PATH = "/post/aB3dE5gH7jK/pools"
