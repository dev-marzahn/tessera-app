package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.merge
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.tabs.CachedTabPosts
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * #347: favourite a post, open one of its tags now (a search whose page lists the post again), then come back to the
 * post by each route: the post's sheet (back through the queue, or reopened from Recently closed, both a new
 * [PostViewModel] on the held copy), the queue page's knowledge of it ([CachedTabPosts]) and the grid it was favourited
 * from. Each shows it favourited.
 */
class FavouriteElsewhereTest {
  private val now = Instant.parse("2026-11-03T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val scope = CoroutineScope(SupervisorJob())
  private val json = headersOf(HttpHeaders.ContentType, "application/json")
  private var serverFavourited = false

  private fun post() =
    """{ "id": "p1", "md5": "0123456789abcdef0123456789abcdef", "rating": "s", "status": "active",
         "is_favorited": $serverFavourited, "fav_count": ${if (serverFavourited) 8 else 7},
         "tags": [{ "id": "t1", "name": "blue_hair", "type": 0 }] }"""

  private val engine = MockEngine { request ->
    when {
      request.url.encodedPath.endsWith("/posts/p1/favorite") && request.method == HttpMethod.Post -> {
        serverFavourited = true
        respond("""{ "success": true, "post_id": "p1" }""", HttpStatusCode.OK, json)
      }
      request.url.encodedPath.endsWith("/posts/p1") -> respond(post(), HttpStatusCode.OK, json)
      else -> respond("""{ "meta": { "next": null, "prev": null }, "data": [${post()}] }""", HttpStatusCode.OK, json)
    }
  }
  private val log = RequestLog(clock)
  private val limiter = RateLimiter(limits = emptyMap())
  private val connectivity = ConnectivityState()
  private val tokens =
    StoredTokens("access", "refresh", now + 7.days, now + 90.days, UserSummary(id = "42", name = "tester", avatarUrl = null))
  private val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), clock), InMemoryTokenStore(tokens), clock)
  private val client = createMainClient(engine, log, limiter, connectivity, session)

  init {
    runBlocking { session.restore() }
  }

  private val store = PostStateStore()
  private val repository = PostsRepository(PostsApi(client), store, session, scope)

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun aFavouriteShowsByEveryRouteBackAfterATagIsOpened() = runBlocking {
    // The grid the post was opened from, then the post's sheet with its full copy, both before the favourite.
    val origin = repository.gridStart(listOf("artist")).single()
    val full = repository.fullPost("p1")
    assertTrue(repository.toggleFavourite(full).isSuccess)

    // A tap on blue_hair opens that search now: its first page lists the post.
    repository.gridStart(listOf("blue_hair"))

    val sheet = PostViewModel("p1", repository, store, session)
    assertEquals(true, (sheet.state.value as PostUiState.Loaded).post.isFavorited, "the post's sheet")
    assertEquals(8, (sheet.state.value as PostUiState.Loaded).post.favCount)
    assertEquals(true, CachedTabPosts(repository, store).of(setOf("p1")).first()["p1"]?.favourited, "the queue page")
    assertEquals(true, origin.merge(store.overrides.value["p1"]).isFavorited, "the grid's cell")
  }
}
