package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.SankakuJson
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.json
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagFollowsTest {
  private val tag = TagDto(name = "Blue Hair", tagName = "blue_hair", id = "t1")

  private fun HttpRequestData.jsonBody(): JsonObject = Json.parseToJsonElement((body as TextContent).text) as JsonObject

  @Test
  fun followPostsTheWebAppsBodyWithV2() = runTest {
    val h = Harness { json("""{ "success": true }""") }
    val follows = TagFollows(h.tags)

    follows.toggle(tag).getOrThrow()

    val request = h.requestsTo("/users/followings").single()
    assertEquals(HttpMethod.Post, request.method)
    assertEquals("2", request.headers["api-version"])
    val body = request.jsonBody()
    assertEquals("t1", body["following_id"]!!.jsonPrimitive.content)
    assertEquals("blue_hair", body["following_name"]!!.jsonPrimitive.content)
    assertEquals("tag", body["type"]!!.jsonPrimitive.content)
    assertTrue(follows.isFollowing(tag))
  }

  @Test
  fun aFollowedTagIsUnfollowedWithDelete() = runTest {
    val h = Harness { json("""{ "success": true }""") }
    val follows = TagFollows(h.tags)

    follows.toggle(tag.copy(isFollowing = true)).getOrThrow()

    assertEquals(HttpMethod.Delete, h.requestsTo("/users/followings").single().method)
    assertFalse(follows.isFollowing(tag.copy(isFollowing = true)))
  }

  @Test
  fun aRefusedFollowIsUndone() = runTest {
    val h = Harness { json("""{ "success": false, "code": "nope" }""", HttpStatusCode.BadRequest) }
    val follows = TagFollows(h.tags)

    assertTrue(follows.toggle(tag).isFailure)

    assertFalse(follows.isFollowing(tag))
  }

  @Test
  fun aSuccessFalseAnswerIsAFailure() = runTest {
    val h = Harness { json("""{ "success": false }""") }
    val follows = TagFollows(h.tags)

    assertTrue(follows.toggle(tag).isFailure)
    assertFalse(follows.isFollowing(tag))
  }

  @Test
  fun aTagWithoutAnIdIsNotSent() = runTest {
    val h = Harness { json("""{ "success": true }""") }

    assertTrue(TagFollows(h.tags).toggle(tag.copy(id = null)).isFailure)
    assertTrue(h.requests.isEmpty())
  }

  @Test
  fun aFullPostsTagsCarryTheirIdAndFollowedState() {
    val post = SankakuJson.decodeFromString<PostDto>(
      Fixtures.post().replace("\"type\": 0,", "\"type\": 0, \"is_following\": true,"),
    )

    assertEquals("t1", post.tags.single().id)
    assertTrue(post.tags.single().isFollowing)
  }
}
