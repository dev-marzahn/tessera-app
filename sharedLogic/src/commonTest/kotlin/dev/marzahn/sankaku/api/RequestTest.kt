package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.html
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parsing.ParseException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTest {
  private fun apiHarness() = Harness(InMemoryTokenStore(storedTokens())) { request ->
    when {
      request.url.host in IMAGE_HOSTS ->
        respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
      request.url.encodedPath == "/v2/posts/keyset" -> json(Fixtures.page())
      request.url.encodedPath == "/v2/posts" -> json("[${Fixtures.post("p2")}]")
      request.url.encodedPath.endsWith("/favorite") -> json(Fixtures.FAVOURITE)
      request.url.encodedPath == "/posts/p1" -> json(Fixtures.fullPost("p1"))
      request.url.encodedPath == "/users/me" -> json(Fixtures.USER_ME)
      request.url.encodedPath == "/tags/autosuggestCreating" -> json(Fixtures.autocomplete())
      request.url.encodedPath == "/auth/token" && request.url.host == API_HOST -> json(Fixtures.tokens())
      request.url.encodedPath == "/auth/token" -> json(Fixtures.LOGIN_OK)
      request.url.encodedPath == "/sso/token-exchange" -> json(Fixtures.tokens())
      else -> error("unexpected ${request.url}")
    }
  }

  private fun HttpRequestData.headerSet(): Set<HeaderSet> = HeaderSet.entries.filter { set ->
    set != HeaderSet.NONE && set.headers.all { (key, value) -> headers.getAll(key).orEmpty().contains(value) }
  }.toSet()

  private fun HttpRequestData.carriesAnyOf(set: HeaderSet) =
    set.headers.any { (key, value) -> headers.getAll(key).orEmpty().contains(value) }

  @Test
  fun eachEndpointCarriesExactlyItsHeaderSet() = runTest {
    val h = apiHarness()
    h.session.restore()
    h.posts.keyset(listOf("rating:s"))
    h.posts.byId("p1")
    h.posts.favourite("p1")
    h.posts.unfavourite("p1")
    h.users.me()
    h.authApi.refresh("old-refresh")
    h.authApi.passwordLogin("tester", "secret")
    h.authApi.exchange("login-token")

    h.posts.full("p1")
    h.tags.autocomplete("blue_ha")
    h.posts.recommended("p1")

    assertEquals(1, h.requestsTo("/posts/p1").size)
    val suggest = h.requestsTo("/tags/autosuggestCreating").single()
    assertEquals("blue_ha", suggest.url.parameters["tag"])
    assertEquals("post", suggest.url.parameters["target"])
    val recommended = h.requestsTo("/v2/posts").single()
    for (request in h.requestsTo("/v2/posts/keyset") + h.requestsTo("/posts/p1") + suggest + recommended) {
      assertEquals(setOf(HeaderSet.POSTS), request.headerSet())
      assertFalse(request.carriesAnyOf(HeaderSet.V2), "posts must never carry a V2 header")
    }
    for (path in listOf("/posts/p1/favorite", "/users/me", "/auth/token", "/sso/token-exchange")) {
      for (request in h.requestsTo(path)) {
        assertFalse(request.carriesAnyOf(HeaderSet.POSTS), "$path carries POSTS")
        assertFalse(request.carriesAnyOf(HeaderSet.V2), "$path carries V2")
      }
    }
  }

  @Test
  fun aFeedTagIsSentAsItsFlagAndNeverAsATag() = runTest {
    val h = apiHarness()
    h.session.restore()
    h.posts.keyset(listOf("feed:for-you", "blue_hair", "rating:s"))
    h.posts.keyset(listOf("feed:following"))

    val (forYou, following) = h.requestsTo("/v2/posts/keyset")
    assertEquals("true", forYou.url.parameters["is_recommendation"])
    assertEquals("blue_hair rating:s", forYou.url.parameters["tags"])
    assertEquals("true", following.url.parameters["is_following"])
    assertEquals(null, following.url.parameters["tags"])
    assertEquals(setOf(HeaderSet.POSTS), following.headerSet())
  }

  @Test
  fun aKeysetPageSendsItsNumberWithTheCursor() = runTest {
    val h = apiHarness()
    h.session.restore()
    h.posts.keyset(listOf("feed:for-you"))
    h.posts.keyset(listOf("feed:for-you"), next = "c3", page = 3)

    val (first, third) = h.requestsTo("/v2/posts/keyset")
    assertEquals("1", first.url.parameters["page"])
    assertEquals(null, first.url.parameters["next"])
    assertEquals("3", third.url.parameters["page"])
    assertEquals("c3", third.url.parameters["next"])
  }

  @Test
  fun recommendedIsOnePageOfTheRecommendedForPostTagAndSendsNoTracking() = runTest {
    val h = apiHarness()
    h.session.restore()
    val posts = h.posts.recommended("p1", tags = listOf("rating:s"))

    assertEquals(listOf("p2"), posts.map { it.id })
    val request = h.requests.single()
    assertEquals("/v2/posts", request.url.encodedPath)
    assertEquals("recommended_for_post:p1 rating:s", request.url.parameters["tags"])
    assertEquals("1", request.url.parameters["page"])
    assertEquals("${PostsApi.RECOMMENDED_LIMIT}", request.url.parameters["limit"])
    assertTrue(h.requests.none { it.url.encodedPath.startsWith("/recommendations") })
  }

  @Test
  fun everyRequestHasLangUserAgentAndClientHints() = runTest {
    val h = apiHarness()
    h.session.restore()
    h.posts.keyset(emptyList())
    h.posts.favourite("p1")
    h.users.me()
    h.tags.autocomplete("blue_ha")
    h.authApi.exchange("login-token")
    h.authApi.refresh("old-refresh")
    h.authApi.passwordLogin("tester", "secret")
    h.mainClient.get("https://v.sankakucomplex.com/data/x.jpg?e=1")
    h.mainClient.get("https://s.sankakucomplex.com/data/x.png?e=1")

    val fingerprint = BrowserFingerprint.Current
    assertEquals(setOf("login.sankakucomplex.com", API_HOST) + IMAGE_HOSTS, h.requests.map { it.url.host }.toSet())
    for (request in h.requests) {
      val headers = request.headers
      assertEquals(fingerprint.userAgent, headers[HttpHeaders.UserAgent], "${request.url}")
      fingerprint.clientHints.forEach { (name, value) -> assertEquals(listOf(value), headers.getAll(name), name) }
      if (request.url.host == API_HOST) assertEquals("en", request.url.parameters["lang"], "${request.url}")
    }
  }

  @Test
  fun authClientCallsCarryNoAuthorization() = runTest {
    val h = apiHarness()
    h.session.restore()
    h.users.me() // the main client now holds a bearer token
    h.authApi.passwordLogin("tester", "secret")
    h.authApi.exchange("login-token")
    h.authApi.refresh("old-refresh")

    val authCalls = h.requestsTo("/auth/token") + h.requestsTo("/sso/token-exchange")
    assertEquals(3, authCalls.size)
    authCalls.forEach { assertNull(it.headers[HttpHeaders.Authorization], "${it.url}") }
    assertEquals("Bearer old-access", h.requestsTo("/users/me").single().headers[HttpHeaders.Authorization])
  }

  @Test
  fun apiServerErrorIsSentExactlyOnce() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{"error":"boom"}""", HttpStatusCode.ServiceUnavailable) }
    h.session.restore()

    val error = assertFailsWith<ApiError.Http> { h.posts.favourite("p1") }
    assertEquals(503, error.status)
    assertEquals(1, h.requests.size)

    assertFailsWith<ApiError.Http> { h.posts.keyset(emptyList()) }
    assertEquals(2, h.requests.size)
  }

  @Test
  fun imageRedirectToUnsignedUrlIsRefused() = runTest {
    val h = Harness { request ->
      if (request.url.encodedPath == "/data/x.jpg") {
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://v.sankakucomplex.com/expired.png"))
      } else {
        respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
      }
    }

    assertFailsWith<ImageRefusedException> { h.mainClient.get("https://v.sankakucomplex.com/data/x.jpg?e=1&m=a") }
    assertTrue(h.requestsTo("/expired.png").isEmpty(), "the unsigned placeholder was fetched")
  }

  @Test
  fun imageRedirectToSignedUrlIsFollowed() = runTest {
    val h = Harness { request ->
      if (request.url.encodedPath == "/data/x.jpg") {
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://v.sankakucomplex.com/data/y.jpg?e=2"))
      } else {
        respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
      }
    }

    val response = h.mainClient.get("https://v.sankakucomplex.com/data/x.jpg?e=1")
    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun theRateLimitedErrorShowsThePause() = runTest {
    var retryAfter = "2.4"
    // The limiter's own pause on the test scheduler's clock, so the 429s' pauses pass in virtual time.
    val h = Harness(limiter = RateLimiter(testScheduler.timeSource, limits = emptyMap())) {
      respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, retryAfter))
    }
    h.session.restore()
    suspend fun shown() = assertFailsWith<ApiError.RateLimited> { h.posts.keyset(emptyList()) }.retryAfterSeconds

    assertEquals(3, shown())
    retryAfter = "0"
    assertEquals(1, shown())
    retryAfter = "Thu, 29 Oct 2099 12:00:00 GMT"
    assertEquals(600, shown())
  }

  @Test
  fun responsesMapToApiErrors() = runTest {
    var status = HttpStatusCode.Forbidden
    var asHtml = false
    val h = Harness { if (asHtml) html() else json("""{"code":"nope"}""", status) }
    h.session.restore()

    assertFailsWith<ApiError.Rejected> { h.posts.keyset(emptyList()) }
    status = HttpStatusCode.TooManyRequests
    assertFailsWith<ApiError.RateLimited> { h.posts.keyset(emptyList()) }
    status = HttpStatusCode.NotFound
    val notFound = assertFailsWith<ApiError.Http> { h.posts.keyset(emptyList()) }
    assertEquals("code=\"nope\"", notFound.hint)
    status = HttpStatusCode.OK
    assertFailsWith<ApiError.Decoding> { h.posts.keyset(emptyList()) }
    asHtml = true
    assertFailsWith<ApiError.Rejected> { h.posts.keyset(emptyList()) }
  }

  /** Sankaku refuses a search past the tag limit with a 429 (step d27, `docs/api.md` → Search limits; #520). */
  @Test
  fun aTagLimit429IsTheTagLimitWithTheLimitFromParam() = runTest {
    val cases = listOf(
      """{"success":false,"code":"snackbar__account_regular_tags-explicit-limit","param":"member_tag_limit_9_and_rating_R15+_R18+_2"}""" to 9,
      """{"success":false,"code":"snackbar__anonymous_tags-limit","param":"4"}""" to 4,
      """{"success":false,"code":"snackbar__anonymous_tags-limit"}""" to null,
    )
    for ((body, limit) in cases) {
      val h = Harness { json(body, HttpStatusCode.TooManyRequests) }
      h.session.restore()

      assertEquals(limit, assertFailsWith<ApiError.TagLimit>(body) { h.posts.keyset(emptyList()) }.limit, body)
    }
  }

  /** A header Ktor can't parse got an answer, so it's no connection error: never "You're offline" (#473). */
  @Test
  fun aParseFailureIsNotANetworkError() = runTest {
    val h = Harness { throw ParseException("Invalid blob value: it should be token68") }
    h.session.restore()

    assertFailsWith<ApiError.Decoding> { h.posts.keyset(emptyList()) }
    assertFalse(h.connectivity.offline.value)
  }

  @Test
  fun searchTimeoutMapsOnlyFromItsCode() = runTest {
    var body = """{"success":false,"code":"snackbar__account_search-query-timeout"}"""
    val h = Harness { json(body, HttpStatusCode.RequestTimeout) }
    h.session.restore()

    assertFailsWith<ApiError.SearchTimeout> { h.posts.keyset(listOf("fav:someone")) }
    body = """{"code":"nope"}"""
    assertEquals(408, assertFailsWith<ApiError.Http> { h.posts.keyset(emptyList()) }.status)
    body = "{}"
    assertEquals(408, assertFailsWith<ApiError.Http> { h.posts.keyset(emptyList()) }.status)
  }
}
