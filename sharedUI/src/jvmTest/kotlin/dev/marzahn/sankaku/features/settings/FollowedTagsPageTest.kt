package dev.marzahn.sankaku.features.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.data.FollowedTagList
import dev.marzahn.sankaku.data.TagFollows
import dev.marzahn.sankaku.features.tag.TagPages
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** #314: Settings → Account → Followed tags lists the follows, unfollows at once, and follows back. */
@OptIn(ExperimentalTestApi::class)
class FollowedTagsPageTest {
  private val calls = CopyOnWriteArrayList<String>()

  private val engine = MockEngine { request ->
    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    when {
      request.method == HttpMethod.Get && request.url.encodedPath == "/users/followings" -> respond(
        """{ "list": [
          { "id": "t1", "name": "artist_x", "tagName": "artist_x", "type": 1, "post_count": 1204, "is_following": true },
          { "id": "t2", "name": "landscape", "tagName": "landscape", "type": 0, "post_count": 88310, "is_following": true }
        ] }""",
        HttpStatusCode.OK,
        json,
      )
      request.url.encodedPath == "/users/followings" -> {
        calls += request.method.value
        respond("""{"success":true}""", HttpStatusCode.OK, json)
      }
      else -> respond("{}", HttpStatusCode.NotFound, json)
    }
  }

  private fun tagsApi(): TagsApi {
    val log = RequestLog(Clock.System)
    val limiter = RateLimiter(limits = emptyMap())
    val connectivity = ConnectivityState()
    val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), Clock.System), InMemoryTokenStore())
    return TagsApi(createMainClient(engine, log, limiter, connectivity, session))
  }

  @Test
  fun aFollowOrUnfollowElsewhereShowsInTheListAtOnce() = runDesktopComposeUiTest(width = 600, height = 900) {
    // #539: "rock" was followed from a tag's menu and "landscape" unfollowed; Sankaku's list (the mock) has neither change.
    val tags = tagsApi()
    val follows = TagFollows(tags)
    runBlocking {
      follows.toggle(TagDto(name = "rock", tagName = "rock", id = "t3", type = 0, postCount = 5000)).getOrThrow()
      follows.toggle(TagDto(name = "landscape", tagName = "landscape", id = "t2", isFollowing = true)).getOrThrow()
    }
    setContent {
      SankakuTheme {
        FollowedTagsPage(onDismiss = {}, onOpenTag = {}, touch = true, list = FollowedTagList(tags), follows = follows, toggle = {}, tagPages = TagPages())
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("followed-tag-rock")).fetchSemanticsNodes().isNotEmpty() }

    onNode(hasText("Unfollow") and hasAnyAncestor(hasTestTag("followed-tag-rock"))).assertIsDisplayed()
    onNode(hasText("Follow") and hasAnyAncestor(hasTestTag("followed-tag-landscape"))).assertIsDisplayed()
    // The new follow is first, as Sankaku lists a new follow.
    val rockTop = onNode(hasTestTag("followed-tag-rock")).fetchSemanticsNode().boundsInRoot.top
    val artistTop = onNode(hasTestTag("followed-tag-artist_x")).fetchSemanticsNode().boundsInRoot.top
    assertTrue(rockTop < artistTop)
  }

  @Test
  fun unfollowActsAtOnceAndFollowTakesItBack() = runDesktopComposeUiTest(width = 600, height = 900) {
    val tags = tagsApi()
    val follows = TagFollows(tags)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    setContent {
      SankakuTheme {
        FollowedTagsPage(
          onDismiss = {},
          onOpenTag = {},
          touch = true,
          list = FollowedTagList(tags),
          follows = follows,
          toggle = { tag -> scope.launch { follows.toggle(tag) } },
          tagPages = TagPages(),
        )
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Unfollow")).fetchSemanticsNodes().size == 2 }
    onNodeWithText("landscape", substring = true).assertIsDisplayed()

    val artistRow = hasAnyAncestor(hasTestTag("followed-tag-artist_x"))
    onNode(hasText("Unfollow") and artistRow).performClick()
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Follow") and artistRow).fetchSemanticsNodes().isNotEmpty() }
    onNode(hasText("Follow") and artistRow).performClick()
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Unfollow") and artistRow).fetchSemanticsNodes().isNotEmpty() }

    waitUntil(timeoutMillis = 5_000) { calls.size == 2 }
    assertEquals(listOf("DELETE", "POST"), calls.toList())
  }
}
