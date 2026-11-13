package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BookFavouritesTest {
  private val book = BookDto(id = "b1", isFavorited = false, favCount = 10)

  @Test
  fun aToggleShowsAtOnceThenTakesTheResponsesCount() = runTest {
    val answer = CompletableDeferred<Unit>()
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      answer.await()
      json("""{ "success": true, "fav_count": 42 }""")
    }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)

    val toggle = async { favourites.toggle(book) }
    runCurrent()
    assertEquals(BookFavourite(on = true, count = 11), favourites.current(book))
    assertEquals(BookFavourite(on = true, count = 11), book.favourite(favourites.overrides.value))

    answer.complete(Unit)
    assertEquals(BookFavourite(on = true, count = 42), toggle.await().getOrThrow())
    runCurrent()
    assertEquals(BookFavourite(on = true, count = 42), book.favourite(favourites.overrides.value))
    assertEquals(HttpMethod.Post, h.requestsTo("/pools/b1/favorite").single().method)
  }

  @Test
  fun aFailedToggleComesBackAsItWas() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{ "code": "server-error" }""", HttpStatusCode.InternalServerError) }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)

    assertTrue(favourites.toggle(book).isFailure)
    runCurrent()
    assertEquals(BookFavourite(on = false, count = 10), favourites.current(book))
    assertEquals(BookFavourite(on = false, count = 10), book.favourite(favourites.overrides.value))
  }

  @Test
  fun twoTapsThatBothFailShowWhatTheServerHas() = runTest {
    val answer = CompletableDeferred<Unit>()
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      answer.await()
      json("""{ "code": "server-error" }""", HttpStatusCode.InternalServerError)
    }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)

    // ♡, then un-♡ before the first call answers: the second starts from the first's optimistic state.
    val first = async { favourites.toggle(book) }
    runCurrent()
    val second = async { favourites.toggle(book) }
    runCurrent()
    answer.complete(Unit)
    assertTrue(first.await().isFailure)
    assertTrue(second.await().isFailure)

    // Not the first tap's ♡, which the server never had.
    assertEquals(BookFavourite(on = false, count = 10), favourites.current(book))
  }

  @Test
  fun aBookLoadedAfterASettledToggleSaysWhatTheServerHasNow() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{ "success": true, "fav_count": 11 }""") }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)
    favourites.toggle(book)
    assertEquals(BookFavourite(on = true, count = 11), favourites.current(book))

    // Un-♡ed on the website since: the next load says so, and wins.
    val reloaded = book.copy(isFavorited = false, favCount = 10)
    favourites.fresh({ reloaded }) { listOf(it) }
    assertEquals(BookFavourite(on = false, count = 10), favourites.current(reloaded))
  }

  @Test
  fun aLoadSentBeforeAToggleSettledDoesNotUndoIt() = runTest {
    val answer = CompletableDeferred<Unit>()
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      answer.await()
      json("""{ "success": true, "fav_count": 11 }""")
    }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)
    val toggle = async { favourites.toggle(book) }
    runCurrent()

    // A load that answers while the ♡ is on its way, and one sent before it settles that answers after.
    favourites.fresh({ book }) { listOf(it) }
    assertEquals(true, favourites.current(book).on, "a ♡ on its way stays")
    val load = CompletableDeferred<BookDto>()
    val slow = async { favourites.fresh({ load.await() }) { listOf(it) } }
    runCurrent()
    answer.complete(Unit)
    toggle.await()
    load.complete(book)
    slow.await()

    assertEquals(BookFavourite(on = true, count = 11), favourites.current(book))
  }

  @Test
  fun aSecondToggleUnfavouritesFromWhatTheFirstShowed() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{ "success": true }""") }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)

    favourites.toggle(book)
    assertEquals(BookFavourite(on = false, count = 10), favourites.toggle(book).getOrThrow())
    assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), h.requestsTo("/pools/b1/favorite").map { it.method })
  }

  @Test
  fun signedOutNothingIsAskedAndNoOverrideShows() = runTest {
    val h = Harness { json("""{ "success": true }""") }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)

    assertTrue(favourites.toggle(book).isFailure)
    assertTrue(h.requests.isEmpty())
    assertEquals(BookFavourite(on = false, count = 10), favourites.current(book))
  }

  @Test
  fun anotherAccountDoesNotSeeTheOverrides() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{ "success": true, "fav_count": 11 }""") }
    h.session.restore()
    val favourites = BookFavourites(BooksApi(h.mainClient), h.session, backgroundScope)
    favourites.toggle(book)
    runCurrent()
    assertEquals(true, favourites.overrides.value["b1"]?.on)

    h.session.signOut()
    runCurrent()
    assertTrue(favourites.overrides.value.isEmpty())
    assertEquals(BookFavourite(on = false, count = 10), favourites.current(book))
  }
}
