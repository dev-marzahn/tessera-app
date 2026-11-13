package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #314: every followed tag, page after page, until a short page. */
class FollowedTagListTest {
  private fun tags(from: Int, count: Int) = (from until from + count).joinToString(",") {
    """{ "id": "t$it", "name": "tag_$it", "tagName": "tag_$it", "type": 0, "post_count": $it, "is_following": true }"""
  }

  private fun harness(total: Int) = Harness(InMemoryTokenStore(null)) { request ->
    val page = request.url.parameters["page"]!!.toInt()
    val limit = request.url.parameters["limit"]!!.toInt()
    val from = (page - 1) * limit
    json("""{ "list": [${tags(from, (total - from).coerceIn(0, limit))}] }""")
  }

  @Test
  fun everyPageIsReadUntilAShortOne() = runTest {
    val harness = harness(total = 3)
    val list = FollowedTagList(TagsApi(harness.mainClient), pageSize = 2)

    assertEquals(listOf("tag_0", "tag_1", "tag_2"), list.all().map { it.searchName })
    assertEquals(2, harness.requests.size)
  }

  @Test
  fun aFullLastPageTakesOneMoreRequest() = runTest {
    val harness = harness(total = 4)
    val list = FollowedTagList(TagsApi(harness.mainClient), pageSize = 2)

    assertEquals(4, list.all().size)
    assertEquals(3, harness.requests.size)
  }

  @Test
  fun theIdsAreNullWhenTheListCantBeRead() = runTest {
    // #539: Following's context needs the follows; unknown, no post is taken for a late one.
    val ok = FollowedTagList(TagsApi(harness(total = 3).mainClient), pageSize = 2)
    val failing = FollowedTagList(TagsApi(Harness(InMemoryTokenStore(null)) { json("{}", HttpStatusCode.InternalServerError) }.mainClient))

    assertEquals(listOf("t0", "t1", "t2"), ok.idsOrNull())
    assertNull(failing.idsOrNull())
  }

  @Test
  fun aServerThatRepeatsItsPagesDoesntLoop() = runTest {
    val harness = Harness(InMemoryTokenStore(null)) { json("""{ "list": [${tags(0, 2)}] }""") }
    val list = FollowedTagList(TagsApi(harness.mainClient), pageSize = 2)

    assertEquals(2, list.all().size)
    assertEquals(2, harness.requests.size)
  }
}
