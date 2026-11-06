package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** With an Android User-Agent the API answers ids as JSON numbers (docs/api.md → Browser fingerprint, #204). */
class NumericIdsTest {
  private fun numeric(post: String) = post
    .replace("\"id\": \"p1\"", "\"id\": 12345678")
    .replace("\"parent_id\": \"p0\"", "\"parent_id\": 12345677")
    .replace("\"id\": \"t1\"", "\"id\": 42")
    .replace("\"id\": \"a1\"", "\"id\": 7")

  @Test
  fun numericIdsDecodeAsStrings() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      when {
        request.url.encodedPath.endsWith("/favorite") ->
          json(Fixtures.FAVOURITE.replace("\"post_id\": \"p1\"", "\"post_id\": 12345678"))
        else -> json(numeric(Fixtures.page()))
      }
    }
    h.session.restore()

    val post = h.posts.keyset(emptyList()).data.single()
    assertEquals("12345678", post.id)
    assertEquals("12345677", post.parentId)
    assertEquals("42", post.tags.single().id)
    h.posts.favourite(post.id)
    assertEquals("/posts/12345678/favorite", h.requests.last().url.encodedPath)
  }
}
