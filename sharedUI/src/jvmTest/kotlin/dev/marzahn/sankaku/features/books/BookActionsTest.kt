package dev.marzahn.sankaku.features.books

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.LOGIN_BASE
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
import dev.marzahn.sankaku.data.BookFavourite
import dev.marzahn.sankaku.data.BookFavourites
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.ui.Messages
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * #383: a book's ♡ as posts' is: optimistic, rolled back with a message and a rejection on failure; signed out it opens
 * sign-in and ♡s the book once signed in, unless the account had already.
 */
class BookActionsTest {
  private val now = Instant.parse("2026-11-06T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val scope = CoroutineScope(SupervisorJob())
  private val exchangedTokens = """
    { "success": true, "token_type": "Bearer", "access_token": "new-access", "access_token_ttl": 604800,
      "refresh_token": "new-refresh", "refresh_token_ttl": 7776000,
      "current_user": { "id": "42", "name": "tester", "avatar_url": null, "mfa_method": 0 } }
  """.trimIndent()
  private val loginOk = """
    { "success": true, "token_type": "Bearer", "access_token": "login-token", "refresh_token": "login-refresh",
      "current_user": { "id": 42, "name": "tester" } }
  """.trimIndent()
  private val book = BookDto(id = "b1", favCount = 10)

  /** [serverFavourited]: what `/pools/b1` says once signed in. */
  private inner class Rig(signedIn: Boolean, status: HttpStatusCode = HttpStatusCode.OK, serverFavourited: Boolean = false) {
    /** The ♡ calls. */
    val favourites = mutableListOf<HttpMethod>()

    /** Completed once the book has been asked for. */
    val bookAsked = CompletableDeferred<Unit>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val engine = MockEngine { request ->
      when {
        request.url.host == Url(LOGIN_BASE).host -> respond(loginOk, HttpStatusCode.OK, json)
        request.url.encodedPath.endsWith("/sso/token-exchange") -> respond(exchangedTokens, HttpStatusCode.OK, json)
        request.url.encodedPath == "/pools/b1" -> {
          bookAsked.complete(Unit)
          respond("""{ "id": "b1", "is_favorited": $serverFavourited, "fav_count": 20 }""", HttpStatusCode.OK, json)
        }
        request.url.encodedPath == "/pools/b1/favorite" -> {
          favourites += request.method
          respond("""{ "success": true, "fav_count": 12 }""", status, json)
        }
        else -> respond("{}", HttpStatusCode.NotFound, json)
      }
    }
    private val log = RequestLog(clock)
    private val limiter = RateLimiter(limits = emptyMap())
    private val connectivity = ConnectivityState()
    private val tokens = if (signedIn) {
      StoredTokens("access", "refresh", now + 7.days, now + 90.days, UserSummary(id = "42", name = "tester", avatarUrl = null))
    } else {
      null
    }
    val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), clock), InMemoryTokenStore(tokens), clock)
    val api = BooksApi(createMainClient(engine, log, limiter, connectivity, session))

    init {
      runBlocking { session.restore() }
    }

    val signIn = SignInController()
    val messages = Messages()
    val actions = BookActions(BookFavourites(api, session, scope), session, signIn, messages, scope)

    /** Every heart [actions] played, in order. */
    val hearts: MutableList<BookHeart> = Collections.synchronizedList(mutableListOf())

    init {
      scope.launch(start = CoroutineStart.UNDISPATCHED) { actions.hearts.collect { hearts += it } }
    }

    suspend fun settled(): BookFavourite = withTimeout(5_000) {
      actions.favourites.overrides.first { it["b1"]?.count == 12 }.getValue("b1")
    }
  }

  @AfterTest
  fun tearDown() {
    scope.cancel()
    stopKoin()
  }

  @Test
  fun signedInItFavouritesWithTheResponsesCount() = runBlocking {
    val rig = Rig(signedIn = true)

    rig.actions.toggleFavourite(book)

    assertEquals(BookFavourite(on = true, count = 12), withTimeout(5_000) {
      rig.actions.favourites.overrides.first { it["b1"]?.count == 12 }.getValue("b1")
    })
    assertEquals(listOf(HttpMethod.Post), rig.favourites)
  }

  @Test
  fun aFailedFavouriteRollsBackWithAMessageAndARejection() = runBlocking {
    val rig = Rig(signedIn = true, status = HttpStatusCode.InternalServerError)
    val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { rig.messages.messages.first() } }
    val rejection = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { rig.actions.rejections.first() } }

    rig.actions.toggleFavourite(book)

    assertTrue(message.await().let { it.isError && it.text.startsWith("Couldn't favourite") })
    assertEquals("b1", rejection.await())
    assertEquals(BookFavourite(on = false, count = 10), rig.actions.favourites.current(book))
  }

  @Test
  fun signedOutItOpensSignInAndFavouritesOnceSignedIn() = runBlocking {
    val rig = Rig(signedIn = false)

    rig.actions.toggleFavourite(book)
    assertTrue(rig.signIn.isOpen.value)
    assertEquals("to favourite", rig.signIn.reason.value)
    assertTrue(rig.favourites.isEmpty())

    rig.session.signIn("tester", "password")
    rig.signIn.succeeded()

    assertEquals(BookFavourite(on = true, count = 12), rig.settled())
    assertEquals(listOf(HttpMethod.Post), rig.favourites)
  }

  @Test
  fun aToggleShowsItsHeartAndAnUnfavouriteTheBrokenOne() = runBlocking {
    val rig = Rig(signedIn = true)

    rig.actions.toggleFavourite(book)
    rig.settled()
    rig.actions.toggleFavourite(book)
    withTimeout(5_000) { while (rig.favourites.size < 2 || rig.hearts.size < 2) delay(10) }

    assertEquals(listOf(BookHeart("b1", true), BookHeart("b1", false)), rig.hearts.toList())
  }

  @Test
  fun aRollbackShowsTheHeartOfTheStateItWentBackTo() = runBlocking {
    val rig = Rig(signedIn = true, status = HttpStatusCode.InternalServerError)
    val rejection = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { rig.actions.rejections.first() } }

    rig.actions.toggleFavourite(book)
    rejection.await()

    assertEquals(listOf(BookHeart("b1", true), BookHeart("b1", false)), rig.hearts.toList())
  }

  @Test
  fun signedOutTheHeartWaitsForTheFavouriteToRun() = runBlocking {
    val rig = Rig(signedIn = false)

    rig.actions.toggleFavourite(book)
    delay(100)
    assertTrue(rig.hearts.isEmpty(), "opening sign-in plays nothing")

    rig.session.signIn("tester", "password")
    rig.signIn.succeeded()
    rig.settled()

    assertEquals(listOf(BookHeart("b1", true)), rig.hearts.toList())
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aRowsHeartFavouritesAtOnceWithoutOpeningTheBook() = runComposeUiTest {
    val rig = Rig(signedIn = true)
    var opened = 0
    startKoin { modules(module { single { ConnectivityState() } }) }
    setContent {
      SankakuTheme {
        BookRow(
          pictures = book.copy(nameEn = "Fixture book"),
          text = book.copy(nameEn = "Fixture book").rowText(reading = null),
          locked = false,
          signedIn = true,
          onOpen = { opened++ },
          onOpenInNewTab = { opened++ },
          favourite = book,
          books = BooksRepository(rig.api, scope),
          actions = rig.actions,
        )
      }
    }
    onNodeWithText("10 favourites").assertExists()
    // Named by its title, its text (#521): no description stands in for it, as the count's ♥ "Favourites" did on
    // every row.
    onNodeWithTag(BOOK_ROW_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    assertEquals("Fixture book", onNodeWithTag(BOOK_ROW_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].first().text)

    onNodeWithContentDescription("Favourite").performClick()

    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("12 favourites").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithContentDescription("Unfavourite").assertExists()
    assertEquals(0, opened, "the heart doesn't open the book")
    assertEquals(listOf(HttpMethod.Post), rig.favourites)
  }

  @Test
  fun signedOutABookTheAccountHadFavouritedIsLeftAlone() = runBlocking {
    val rig = Rig(signedIn = false, serverFavourited = true)

    rig.actions.toggleFavourite(book)
    rig.session.signIn("tester", "password")
    rig.signIn.succeeded()
    withTimeout(5_000) { rig.bookAsked.await() }
    // Time for a ♡ call, had the answer been "not favourited".
    delay(300)

    assertTrue(rig.favourites.isEmpty(), "not un-favourited by a toggle from what a signed-out list showed")
    assertTrue(rig.hearts.isEmpty(), "nothing changed, so no heart")
  }
}
