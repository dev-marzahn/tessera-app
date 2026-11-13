package dev.marzahn.sankaku.auth

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.NOW
import dev.marzahn.sankaku.TEST_USER
import dev.marzahn.sankaku.api.API_HOST
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.LOGIN_BASE
import dev.marzahn.sankaku.auth.AuthState.SignedOut.Reason
import dev.marzahn.sankaku.html
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AuthSessionTest {
  private val HttpRequestData.isRefresh get() = url.host == API_HOST && url.encodedPath == "/auth/token"
  private val HttpRequestData.bearer get() = headers[HttpHeaders.Authorization]

  /** `/users/me` accepts only `new-access`; a refresh returns the `new-*` pair. */
  private fun refreshingHarness(store: TokenStore = InMemoryTokenStore(storedTokens())) = Harness(store) { request ->
    when {
      request.isRefresh -> json(Fixtures.tokens())
      request.url.encodedPath == "/users/me" && request.bearer == "Bearer new-access" -> json(Fixtures.USER_ME)
      else -> json("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized)
    }
  }

  @Test
  fun concurrent401sCauseOneRefresh() = runTest {
    val h = refreshingHarness()
    h.session.restore()

    val users = (1..10).map { async { h.users.me() } }.awaitAll()

    assertEquals(10, users.size)
    assertEquals(1, h.requests.count { it.isRefresh })
    assertEquals("new-access", h.store.load()?.accessToken)
  }

  /** Sankaku's 401 for a rejected access token: a comma straight after the scheme, which Ktor can't parse (#470). */
  private fun MockRequestHandleScope.sankaku401(): HttpResponseData = respond(
    """{"success":false,"code":"snackbar__common_unauthorized"}""",
    HttpStatusCode.Unauthorized,
    headersOf(
      HttpHeaders.ContentType to listOf("application/json"),
      HttpHeaders.WWWAuthenticate to listOf(SANKAKU_WWW_AUTHENTICATE),
    ),
  )

  @Test
  fun sankakus401HeaderTriggersOneRefresh() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      when {
        request.isRefresh -> json(Fixtures.tokens())
        request.bearer == "Bearer new-access" -> json(Fixtures.USER_ME)
        else -> sankaku401()
      }
    }
    h.session.restore()

    h.users.me()

    assertEquals(1, h.requests.count { it.isRefresh })
    assertEquals("new-access", h.store.load()?.accessToken)
    assertTrue(h.session.isSignedIn.value)
  }

  @Test
  fun sankakus401HeaderWithARejectedRefreshIsSessionExpiredNotOffline() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { sankaku401() }
    h.session.restore()

    val error = runCatching { h.posts.keyset(emptyList()) }.exceptionOrNull()

    assertIs<ApiError.Unauthorized>(error)
    assertEquals(1, h.requests.count { it.isRefresh })
    assertEquals(AuthState.SignedOut(Reason.Expired), h.session.state.value)
    assertFalse(h.connectivity.offline.value)
  }

  @Test
  fun the401FromAnImageHostCausesNoRefresh() = runTest {
    val h = refreshingHarness(InMemoryTokenStore(storedTokens(access = "new-access")))
    h.session.restore()
    h.users.me() // the plugin now holds a token it could attach

    val response = h.mainClient.get("https://s.sankakucomplex.com/data/01/23/x.png?e=1")

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertEquals(0, h.requests.count { it.isRefresh })
    val imageRequests = h.requests.filter { it.url.host == "s.sankakucomplex.com" }
    assertEquals(1, imageRequests.size, "the 401 was not resent")
    assertNull(imageRequests.single().bearer, "image requests carry no token")
  }

  @Test
  fun pairIsSavedBeforeTheRetry() = runTest {
    lateinit var h: Harness
    var storedAtRetry: String? = null
    h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      when {
        request.isRefresh -> json(Fixtures.tokens())
        request.bearer == "Bearer new-access" -> {
          storedAtRetry = h.store.load()?.accessToken
          json(Fixtures.USER_ME)
        }
        else -> json("{}", HttpStatusCode.Unauthorized)
      }
    }
    h.session.restore()

    h.users.me()

    assertEquals("new-access", storedAtRetry)
  }

  @Test
  fun signOutClearsTokensBeforeStateChanges() = runTest {
    val h = refreshingHarness(InMemoryTokenStore(storedTokens(access = "new-access")))
    h.session.restore()
    h.users.me()
    val clearToken = h.session.onTokensChanged
    var signedInDuringCallback: Boolean? = null
    h.session.onTokensChanged = {
      signedInDuringCallback = h.session.isSignedIn.value
      clearToken()
    }

    h.session.signOut()
    runCatching { h.users.me() }

    assertEquals(true, signedInDuringCallback)
    assertFalse(h.session.isSignedIn.value)
    assertEquals(AuthState.SignedOut(Reason.None), h.session.state.value)
    assertNull(h.requests.last().bearer)
  }

  @Test
  fun refreshRejectedWith401SignsOut() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{"success":false}""", HttpStatusCode.Unauthorized) }
    h.session.restore()

    val result = h.session.refresh(BearerTokens("old-access", "old-refresh"))

    assertNull(result)
    assertEquals(AuthState.SignedOut(Reason.Expired), h.session.state.value)
    assertNull(h.store.load())
  }

  @Test
  fun transientRefreshFailuresKeepTheSession() = runTest {
    val outcomes: List<Pair<String, suspend MockRequestHandleScope.() -> HttpResponseData>> = listOf(
      "400" to { json("{}", HttpStatusCode.BadRequest) },
      "403" to { json("{}", HttpStatusCode.Forbidden) },
      "404" to { json("{}", HttpStatusCode.NotFound) },
      "408" to { json("{}", HttpStatusCode.RequestTimeout) },
      "429" to { json("{}", HttpStatusCode.TooManyRequests) },
      "500" to { json("{}", HttpStatusCode.InternalServerError) },
      "503 html" to { html(HttpStatusCode.ServiceUnavailable) },
      "200 html" to { html() },
      "200 not a token" to { json("""{"success":true}""") },
      "200 broken json" to { json("""{"access_token": """) },
      "connection error" to { throw IOException("connection reset") },
    )
    for ((name, outcome) in outcomes) {
      val h = Harness(InMemoryTokenStore(storedTokens())) { outcome() }
      h.session.restore()

      val result = h.session.refresh(BearerTokens("old-access", "old-refresh"))

      assertNull(result, name)
      assertEquals(AuthState.SignedIn(TEST_USER), h.session.state.value, name)
      assertEquals(storedTokens(), h.store.load(), name)
    }
  }

  @Test
  fun refreshReturnsAPairAnotherCallerAlreadyStored() = runTest {
    val h = refreshingHarness(InMemoryTokenStore(storedTokens(access = "newer", refresh = "newer-refresh")))
    h.session.restore()

    val result = h.session.refresh(BearerTokens("old-access", "old-refresh"))

    assertEquals("newer" to "newer-refresh", result?.let { it.accessToken to it.refreshToken })
    assertTrue(h.requests.isEmpty())
  }

  @Test
  fun refreshWithEmptyStoreSendsNothing() = runTest {
    val h = refreshingHarness(InMemoryTokenStore())
    h.session.restore()

    assertNull(h.session.refresh(null))
    assertTrue(h.requests.isEmpty())
  }

  @Test
  fun restoreUsesTheStoredUserWithoutNetwork() = runTest {
    val h = refreshingHarness()

    h.session.restore()

    assertEquals(AuthState.SignedIn(TEST_USER), h.session.state.value)
    assertTrue(h.session.isSignedIn.value)
    assertTrue(h.requests.isEmpty())
  }

  @Test
  fun restoreWithExpiredRefreshTokenSignsOut() = runTest {
    val expired = storedTokens().copy(refreshExpiresAt = NOW - 1.days)
    val h = refreshingHarness(InMemoryTokenStore(expired))

    h.session.restore()

    assertEquals(AuthState.SignedOut(Reason.Expired), h.session.state.value)
    assertTrue(h.requests.isEmpty())
  }

  @Test
  fun restoreWithNothingStoredIsSignedOut() = runTest {
    val h = refreshingHarness(InMemoryTokenStore())

    h.session.restore()

    assertEquals(AuthState.SignedOut(Reason.None), h.session.state.value)
  }

  private fun signInHarness(login: suspend MockRequestHandleScope.() -> HttpResponseData) = Harness { request ->
    if (request.url.host == Url(LOGIN_BASE).host) login() else json(Fixtures.tokens())
  }

  @Test
  fun signInSavesTheExchangedPairAndNeverThePassword() = runTest {
    val h = signInHarness { json(Fixtures.LOGIN_OK) }
    h.session.restore()

    val result = h.session.signIn("tester", "hunter2-secret")

    assertEquals(SignInResult.Ok, result)
    assertEquals(AuthState.SignedIn(TEST_USER), h.session.state.value)
    val stored = assertNotNull(h.store.load())
    assertEquals("new-access", stored.accessToken)
    assertEquals(NOW + 7.days, stored.accessExpiresAt)
    assertEquals(NOW + 90.days, stored.refreshExpiresAt)
    assertFalse("hunter2-secret" in stored.toString())
    assertEquals(
      """{"access_token":"login-token","client_id":"sankaku-web-app","url":"https://www.sankakucomplex.com"}""",
      h.requestsTo("/sso/token-exchange").single().body.toByteArrayText(),
    )
  }

  @Test
  fun signInOutcomes() = runTest {
    val cases: List<Triple<String, suspend MockRequestHandleScope.() -> HttpResponseData, SignInResult>> = listOf(
      Triple("403 success false", { json(WRONG_PASSWORD, HttpStatusCode.Forbidden) }, SignInResult.WrongCredentials),
      Triple("200 success false", { json("""{"success":false}""") }, SignInResult.WrongCredentials),
      Triple("mfa", { json("""{"success":true,"mfa_method":1}""") }, SignInResult.TwoFactorUnsupported),
    )
    for ((name, login, expected) in cases) {
      val h = signInHarness(login)
      h.session.restore()

      assertEquals(expected, h.session.signIn("tester", "secret"), name)
      assertNull(h.store.load(), name)
      assertFalse(h.session.isSignedIn.value, name)
    }
  }

  /** Only the recorded refusal (discovery step 8) means a wrong password; any other answer is Sankaku's (#493). */
  @Test
  fun aLoginAnswerOtherThanTheRefusalIsAnError() = runTest {
    val cases: List<Triple<String, suspend MockRequestHandleScope.() -> HttpResponseData, (ApiError) -> Unit>> = listOf(
      Triple(
        "429",
        { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "42")) },
        { assertEquals(42, assertIs<ApiError.RateLimited>(it).retryAfterSeconds) },
      ),
      Triple("500", { json("""{"success":false}""", HttpStatusCode.InternalServerError) }, { it.assertHttp(500) }),
      Triple("503 empty", { respond("", HttpStatusCode.ServiceUnavailable) }, { it.assertHttp(503) }),
      Triple("403 block page", { html(HttpStatusCode.Forbidden) }, { it.assertHttp(403) }),
      Triple("403 without success false", { json("{}", HttpStatusCode.Forbidden) }, { it.assertHttp(403) }),
      Triple("401 success false", { json(WRONG_PASSWORD, HttpStatusCode.Unauthorized) }, { it.assertHttp(401) }),
      Triple("400", { json("""{"error":"invalid"}""", HttpStatusCode.BadRequest) }, { it.assertHttp(400) }),
      Triple(
        "500 echoing the login",
        { json("""{"error":"no account tester"}""", HttpStatusCode.InternalServerError) },
        { it.assertHttp(500) },
      ),
    )
    for ((name, login, check) in cases) {
      val h = signInHarness(login)
      h.session.restore()

      val error = assertIs<SignInResult.Failed>(h.session.signIn("tester", "secret"), name).error
      check(error)
      assertFalse("tester" in error.message.orEmpty(), "$name: the login host's text isn't kept")
      assertEquals(AuthState.SignedOut(Reason.None), h.session.state.value, name)
      assertNull(h.store.load(), name)
    }
  }

  private fun ApiError.assertHttp(status: Int) = assertEquals(status, assertIs<ApiError.Http>(this).status)

  @Test
  fun signInWithoutConnectionKeepsTheState() = runTest {
    val h = signInHarness { throw IOException("no route to host") }
    h.session.restore()

    val result = h.session.signIn("tester", "secret")

    assertIs<ApiError.Network>(assertIs<SignInResult.Failed>(result).error)
    assertEquals(AuthState.SignedOut(Reason.None), h.session.state.value)
  }

  @Test
  fun debugFlagReplacesTheAccessTokenOnRestore() = runTest {
    val store = InMemoryTokenStore(storedTokens())
    val h = Harness(store) { respond("") }
    val session = AuthSession(h.authApi, store, FixedClock, expireAccessTokenOnRestore = true)

    session.restore()

    assertEquals(AuthSession.DEBUG_EXPIRED_ACCESS_TOKEN, store.load()?.accessToken)
    assertTrue(session.isSignedIn.value)
  }

  @Test
  fun debugFlagExpiresTheSessionOnRestore() = runTest {
    val store = InMemoryTokenStore(storedTokens())
    val h = Harness(store) { respond("") }
    val session = AuthSession(h.authApi, store, FixedClock, expireSessionOnRestore = true)

    session.restore()

    assertEquals(AuthState.SignedOut(Reason.Expired), session.state.value)
    assertEquals(null, store.load())
  }
}

/** The header of `sankakuapi.com`'s 401 for a rejected access token, as #470 recorded it. */
private const val SANKAKU_WWW_AUTHENTICATE = """Bearer, error="snackbar__common_unauthorized""""

/** The login host's answer to a wrong password, as discovery step 8 recorded it (`docs/api.md`). */
private const val WRONG_PASSWORD = """{"success":false,"error":"invalid login or password"}"""

private fun OutgoingContent.toByteArrayText(): String = when (this) {
  is TextContent -> text
  is ByteArrayContent -> bytes().decodeToString()
  else -> error("unexpected body ${this::class}")
}
