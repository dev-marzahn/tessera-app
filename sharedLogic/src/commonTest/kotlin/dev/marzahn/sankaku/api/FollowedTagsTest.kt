package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #314: the followed tags, `GET /users/followings?content_type=tags` with V2, paged by `limit` and `page` (step 25). */
class FollowedTagsTest {
  private val harness = Harness(InMemoryTokenStore(null)) { request ->
    when (request.url.encodedPath) {
      "/users/followings" -> json(
        """{ "list": [
          { "id": "t1", "name": "Artist X", "tagName": "artist_x", "type": 1, "post_count": 1204, "is_following": true },
          { "id": "t2", "name": "landscape", "tagName": "landscape", "type": 9, "post_count": 88310, "is_following": true }
        ] }""",
      )
      else -> error("unexpected ${request.url.encodedPath}")
    }
  }
  private val tags = TagsApi(harness.mainClient)

  @Test
  fun theFollowedTagsComeAsFullTags() = runTest {
    val page = tags.followedTags(page = 2, limit = 40)

    assertEquals(listOf("artist_x", "landscape"), page.map { it.searchName })
    assertEquals(listOf("t1", "t2"), page.map { it.id })
    assertTrue(page.all { it.isFollowing })
    assertEquals(1204, page.first().postCount)
    val request = harness.requests.single()
    assertEquals("tags", request.url.parameters["content_type"])
    assertEquals("2", request.url.parameters["page"])
    assertEquals("40", request.url.parameters["limit"])
    assertTrue(HeaderSet.V2.headers.all { (key, value) -> request.headers.getAll(key).orEmpty().contains(value) }, "V2")
  }
}
