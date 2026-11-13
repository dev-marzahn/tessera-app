package dev.marzahn.sankaku

import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.BrowserFingerprint
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.UsersApi
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

val NOW: Instant = Instant.parse("2026-10-29T12:00:00Z")

object FixedClock : Clock {
  override fun now(): Instant = NOW
}

val TEST_USER = UserSummary(id = "42", name = "tester", avatarUrl = null)

fun storedTokens(access: String = "old-access", refresh: String = "old-refresh") = StoredTokens(
  accessToken = access,
  refreshToken = refresh,
  accessExpiresAt = NOW + 7.days,
  refreshExpiresAt = NOW + 90.days,
  user = TEST_USER,
)

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
  respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

fun MockRequestHandleScope.html(status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
  respond("<html>Just a moment</html>", status, headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))

/** Both clients over one [MockEngine], as the app wires them, with no rate limits and no pause after a 429. */
class Harness(
  val store: TokenStore = InMemoryTokenStore(),
  limiter: RateLimiter = RateLimiter(limits = emptyMap(), defaultPause = Duration.ZERO),
  fingerprint: BrowserFingerprint = BrowserFingerprint.Current,
  handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
  val requests = mutableListOf<HttpRequestData>()
  val engine = MockEngine { request ->
    requests += request
    handler(request)
  }
  val log = RequestLog(FixedClock)
  val connectivity = ConnectivityState()
  val authClient = createAuthClient(engine, log, limiter, connectivity, fingerprint)
  val authApi = AuthApi(authClient, FixedClock)
  val session = AuthSession(authApi, store, FixedClock)
  val mainClient = createMainClient(engine, log, limiter, connectivity, session, fingerprint)
  val posts = PostsApi(mainClient)
  val users = UsersApi(mainClient)
  val tags = TagsApi(mainClient)

  fun requestsTo(path: String) = requests.filter { it.url.encodedPath == path }
}

object Fixtures {
  fun post(id: String = "p1", favourited: Boolean = false) = """
    {
      "id": "$id",
      "md5": "0123456789abcdef0123456789abcdef",
      "rating": "s",
      "status": "active",
      "author": { "id": "a1", "name": "artist", "level": 20 },
      "preview_url": "https://v.sankakucomplex.com/data/preview/01/23/x.jpg?e=1&m=a",
      "preview_width": 300,
      "preview_height": 200,
      "sample_url": "https://v.sankakucomplex.com/data/sample/01/23/x.jpg?e=1&m=a",
      "sample_width": 1000,
      "sample_height": 666,
      "file_url": "https://s.sankakucomplex.com/data/01/23/x.png?e=1&m=a",
      "width": 3000,
      "height": 2000,
      "file_ext": "png",
      "file_type": "image/png",
      "created_at": { "json_class": "Time", "s": 1761739200, "n": 500000000 },
      "is_favorited": $favourited,
      "fav_count": 7,
      "file_size": 123456,
      "vote_count": 4,
      "total_score": 17,
      "parent_id": "p0",
      "has_children": false,
      "tags": [{ "id": "t1", "name": "blue_hair", "type": 0, "brand_new_field": true }],
      "brand_new_field": { "nested": [1, 2, 3] }
    }
  """.trimIndent()

  /** `/posts/{id}`: a bare post object with every tag, unlike the keyset list's 5. */
  fun fullPost(id: String = "p1", tagCount: Int = 59, fileUrl: String = "https://s.sankakucomplex.com/data/01/23/x.png?e=2&m=b"): String {
    val tags = (1..tagCount).joinToString(",") {
      """{ "id": "t$it", "name": "Tag $it", "tagName": "tag_$it", "type": ${it % 5}, "post_count": ${it * 10} }"""
    }
    val names = (1..tagCount).joinToString(",") { "\"tag_$it\"" }
    return post(id)
      .replace(Regex("\"tags\": \\[.*]"), "\"tags\": [$tags], \"tag_names\": [$names], \"total_tags\": $tagCount")
      .replace("https://s.sankakucomplex.com/data/01/23/x.png?e=1&m=a", fileUrl)
  }

  fun page(next: String? = "cursor-2", vararg ids: String = arrayOf("p1")) = """
    { "meta": { "next": ${next?.let { "\"$it\"" } ?: "null"}, "prev": null, "page_count": 1 },
      "data": [${ids.joinToString(",") { post(it) }}] }
  """.trimIndent()

  fun tokens(access: String = "new-access", refresh: String = "new-refresh") = """
    { "success": true, "token_type": "Bearer", "access_token": "$access", "access_token_ttl": 604800,
      "refresh_token": "$refresh", "refresh_token_ttl": 7776000,
      "current_user": { "id": "42", "name": "tester", "avatar_url": null, "mfa_method": 0 } }
  """.trimIndent()

  const val LOGIN_OK = """
    { "success": true, "token_type": "Bearer", "access_token": "login-token", "refresh_token": "login-refresh",
      "current_user": { "id": 42, "name": "tester" } }
  """

  const val USER_ME = """{ "success": true, "user": { "id": "42", "name": "tester", "avatar_url": "https://a/b.png" } }"""

  /** Shaped like discovery `d1-autocomplete`: a bare array. With `tag=-…` the API prefixes `name`, not `tagName`. */
  fun autocomplete(excluded: Boolean = false): String {
    val dash = if (excluded) "-" else ""
    return """
      [{ "id": "t1", "tagName": "blue_hair", "name": "${dash}Blue Hair", "name_en": "Blue Hair", "type": 0,
         "count": 1200, "post_count": 1234, "rating": "s", "brand_new_field": 1 },
       { "id": "t2", "tagName": "blue_hat", "name": "${dash}blue_hat", "name_en": null, "type": 10, "count": 50,
         "post_count": 52, "rating": "q" }]
    """.trimIndent()
  }

  const val FAVOURITE ="""{ "success": true, "post_id": "p1", "favorited_users": "tester", "score": 8 }"""
}
