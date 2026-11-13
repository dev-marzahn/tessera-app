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
  fun aTagFollowedHereIsListedFirstBeforeSankakusListHasIt() = runTest {
    // #539: the followed tags page reads Sankaku's list; a follow made in this run shows there at once regardless.
    val h = Harness { json("""{ "success": true }""") }
    val follows = TagFollows(h.tags)
    val listed = listOf(TagDto(name = "Tree", tagName = "tree", id = "t9", isFollowing = true))

    follows.toggle(tag).getOrThrow()

    assertEquals(listOf("t1", "t9"), follows.withChanges(listed).map { it.id })
    // Once Sankaku lists it, it isn't there twice.
    assertEquals(listOf("t1", "t9"), follows.withChanges(listOf(tag.copy(isFollowing = true)) + listed).map { it.id })
  }

  @Test
  fun aTagFollowedAndUnfollowedHereIsntAddedBack() = runTest {
    val h = Harness { json("""{ "success": true }""") }
    val follows = TagFollows(h.tags)

    follows.toggle(tag).getOrThrow()
    follows.toggle(tag).getOrThrow()

    assertEquals(emptyList(), follows.withChanges(emptyList()))
    // While the page still shows its row, it stays there to follow back.
    assertEquals(listOf("t1"), follows.withChanges(emptyList(), keep = setOf("t1")).map { it.id })
    // Still in a list read before the unfollow reached Sankaku: it stays, shown unfollowed, to follow back.
    val listed = listOf(tag.copy(isFollowing = true))
    assertEquals(listed, follows.withChanges(listed))
    assertFalse(follows.isFollowing(listed.single()))
  }

  @Test
  fun signingInOrOutForgetsTheTagsChangedHere() = runTest {
    val h = Harness { json("""{ "success": true }""") }
    val follows = TagFollows(h.tags)
    follows.toggle(tag).getOrThrow()

    follows.clear()

    assertEquals(emptyList(), follows.withChanges(emptyList()))
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
  fun aFollowOfATagAlreadyFollowedCountsAsDone() = runTest {
    // A follow sent again (from the outbox, after one that timed out reached Sankaku) is answered so (step d24).
    val h = Harness {
      json("""{"success":false,"code":"user__content-already-followed","errorId":"x"}""", HttpStatusCode.BadRequest)
    }
    val follows = TagFollows(h.tags)

    assertTrue(follows.toggle(tag).isSuccess)
    assertTrue(follows.isFollowing(tag))
  }

  @Test
  fun anUnfollowOfATagNotFollowedCountsAsDone() = runTest {
    val h = Harness {
      json(
        """{"success":false,"code":"snackbar__server-error_not-found","error":"user__not-follow-content"}""",
        HttpStatusCode.NotFound,
      )
    }
    val follows = TagFollows(h.tags)

    assertTrue(follows.toggle(tag.copy(isFollowing = true)).isSuccess)
    assertFalse(follows.isFollowing(tag.copy(isFollowing = true)))
  }

  @Test
  fun anotherNotFoundIsStillARefusal() = runTest {
    val h = Harness { json("""{"success":false,"code":"snackbar__server-error_not-found"}""", HttpStatusCode.NotFound) }
    val follows = TagFollows(h.tags)

    assertTrue(follows.toggle(tag.copy(isFollowing = true)).isFailure)
    assertTrue(follows.isFollowing(tag.copy(isFollowing = true)))
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
