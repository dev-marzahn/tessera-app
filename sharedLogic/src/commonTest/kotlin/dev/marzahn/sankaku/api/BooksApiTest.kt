package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.api.dto.PostDto
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooksApiTest {
  private var seriesBody = SERIES
  private val harness = Harness(InMemoryTokenStore(storedTokens())) { request ->
    val path = request.url.encodedPath
    when {
      path == "/poolseriesv2" -> json(LIST)
      path == "/pools/b1" -> json(BOOK)
      path == "/v2/posts/keyset" -> json(Fixtures.page(null, "p1", "p2", "p3"))
      path == "/graphql" -> json(seriesBody)
      path == "/pools/b1/reading" -> json("""{ "success": true, "code": "x", "message": "x" }""")
      path == "/pools/readings/destroy" -> json("""{ "success": true, "deleted": 1, "code": "x", "message": "x" }""")
      path == "/pools/b1/favorite" -> json("""{ "success": true, "pool_id": "b1", "fav_count": 12 }""")
      path == "/post/aB3dE5gH7jK/pools" -> json("""[{ "id": "bK2mN4pQ6rT", "name": "Title", "rating": "s", "posts": [] }]""")
      else -> error("unexpected $path")
    }
  }
  private val books = BooksApi(harness.mainClient)

  private fun HttpRequestData.carries(set: HeaderSet) = set.headers.any { (key, value) -> headers.getAll(key).orEmpty().contains(value) }

  private fun HttpRequestData.bodyJson() = Json.parseToJsonElement((body as TextContent).text).jsonObject

  @Test
  fun readsSendPostsAndWritesNoSetNeverV2() = runTest {
    harness.session.restore()
    books.list(listOf("order:popularity", "rating:s"))
    books.pages("b1")
    books.firstPages("b1")
    books.series("s1")
    books.booksOf("aB3dE5gH7jK")
    books.saveReading("b1", 2)
    books.clearReading("b1")
    books.favourite("b1", on = true)
    books.favourite("b1", on = false)

    for (path in listOf("/poolseriesv2", "/pools/b1", "/v2/posts/keyset", "/graphql", "/post/aB3dE5gH7jK/pools")) {
      val request = harness.requestsTo(path).single()
      assertTrue(request.carries(HeaderSet.POSTS), "$path lacks POSTS")
      assertFalse(request.carries(HeaderSet.V2), "$path carries V2")
    }
    for (path in listOf("/pools/b1/reading", "/pools/readings/destroy", "/pools/b1/favorite")) {
      for (request in harness.requestsTo(path)) {
        assertFalse(request.carries(HeaderSet.POSTS), "$path carries POSTS")
        assertFalse(request.carries(HeaderSet.V2), "$path carries V2")
      }
    }
    assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), harness.requestsTo("/pools/b1/favorite").map { it.method })
  }

  @Test
  fun listSendsTheWebAppsQueryAndDecodesSeriesAndBooks() = runTest {
    val list = books.list(listOf("order:date", "rating:s"), page = 2)
    val query = harness.requestsTo("/poolseriesv2").single().url.parameters
    assertEquals("order:date rating:s", query["tags"])
    assertEquals("2", query["page"])
    assertEquals("pools", query["includes[]"])
    assertEquals("deleted", query["exceptStatuses[]"])
    assertEquals("true", query["filledPools"])

    val series = list.series.data.single()
    assertEquals("A series", series.title)
    assertEquals(6, series.poolCount)
    assertEquals("b9", series.firstPool?.id)
    val book = list.pools.data.single()
    assertEquals("English title", book.title)
    assertEquals(34, book.postCount)
    assertEquals("p1", book.coverPost?.id)
    assertTrue(book.posts.isEmpty())
    assertEquals("cursor-2", list.pools.meta.next)
  }

  @Test
  fun pagesDecodeInOrderWithReadingAndTags() = runTest {
    val book = books.pages("b1")
    assertEquals(listOf("p1", "p2"), book.posts.map { it.id })
    assertEquals(3, book.reading?.currentPage)
    assertEquals(2, book.reading?.sequence)
    assertEquals(listOf("artist_a"), book.artistTags.map { it.searchName })
    assertEquals(listOf("comic"), book.genreTags.map { it.searchName })
    assertEquals("deleted", harness.requestsTo("/pools/b1").single().url.parameters["exceptStatuses[]"])
  }

  @Test
  fun firstPagesAskThePoolSearchForThree() = runTest {
    assertEquals(listOf("p1", "p2", "p3"), books.firstPages("b1").map { it.id })
    val query = harness.requestsTo("/v2/posts/keyset").single().url.parameters
    assertEquals("pool:b1", query["tags"])
    assertEquals("3", query["limit"])
    assertNull(query["hide_posts_in_books"], "would hide the very pages asked for")
  }

  @Test
  fun booksOfAPostAskTheWebAppsWayAndDecodeTheBooks() = runTest {
    // The web app's `/post/{id}/pools` (#475), which takes the desktop's obfuscated id, unlike `/pools/keyset?post_id=`.
    val found = books.booksOf("aB3dE5gH7jK")
    assertEquals(1, harness.requestsTo("/post/aB3dE5gH7jK/pools").size)
    assertEquals(listOf("bK2mN4pQ6rT"), found.map { it.id })
    assertEquals("Title", found.single().title)
  }

  @Test
  fun aPostSaysWhetherItIsABooksPage() {
    val list = LENIENT.decodeFromString<PostDto>(
      Fixtures.post("p1").trimEnd().removeSuffix("}") + """, "in_visible_pool": true }""",
    )
    assertTrue(list.inVisiblePool)
  }

  @Test
  fun seriesIsTheGraphQlQueryDecodedAsBooks() = runTest {
    val series = books.series("s1")
    val body = harness.requestsTo("/graphql").single().bodyJson()
    assertEquals("s1", body["variables"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    assertTrue("seriesV2" in body["query"]!!.jsonPrimitive.content)
    assertEquals(listOf(1, 2), series.books.map { it.sequence })
    assertEquals(listOf("e", "s"), series.books.map { it.rating })
    assertEquals("https://v.sankakucomplex.com/s.jpg", series.books.first().coverPost?.sampleUrl)
    assertEquals(4, series.books.last().reading?.currentPage)
  }

  @Test
  fun aGraphQlErrorOrNoSeriesIsAnError() = runTest {
    seriesBody = """{ "errors": [{ "message": "Cannot query field" }] }"""
    assertFailsWith<ApiError.Http> { books.series("s1") }
    seriesBody = """{ "data": { "series": null } }"""
    assertFailsWith<ApiError.Http> { books.series("s1") }
  }

  @Test
  fun writesSendTheirBodiesAndFavouriteReturnsTheCount() = runTest {
    books.saveReading("b1", 2)
    assertEquals("2", harness.requestsTo("/pools/b1/reading").single().bodyJson()["sequence"]!!.jsonPrimitive.content)
    books.clearReading("b1")
    val destroy = harness.requestsTo("/pools/readings/destroy").single()
    assertEquals(HttpMethod.Patch, destroy.method)
    assertEquals(listOf("b1"), destroy.bodyJson()["pool_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals(12, books.favourite("b1", on = true).favCount)
  }

  @Test
  fun aFailedWriteThrows() = runTest {
    val failing = Harness(InMemoryTokenStore(storedTokens())) { json("""{ "success": false }""", HttpStatusCode.OK) }
    assertFailsWith<ApiError.Http> { BooksApi(failing.mainClient).saveReading("b1", 0) }
    assertFailsWith<ApiError.Http> { BooksApi(failing.mainClient).favourite("b1", on = true) }
  }

  private companion object {
    val LENIENT = Json { ignoreUnknownKeys = true }

    /** Shaped like `:cli` step b2 (2026-11-06), cut to the fields the app reads plus a few it doesn't. */
    val LIST = """
      { "page": 1,
        "series": { "totalCount": "1", "data": [
          { "id": "s1", "name": "Series", "name_en": "A series", "rating": "s", "poolCount": 6, "firstPoolId": "b9",
            "firstPool": { "id": "b9", "name": "Volume 2", "post_count": 10, "posts": [] }, "favCount": 3 } ] },
        "pools": { "meta": { "next": "cursor-2", "prev": null }, "data": [
          { "id": "b1", "name": "Title", "name_en": "English title", "rating": "s", "status": "active",
            "post_count": 34, "pages_count": 33, "visible_post_count": 34, "fav_count": 11, "is_favorited": false,
            "reading": null, "redirect_to_signup": false, "posts": [], "cover_post": ${Fixtures.post("p1")},
            "brand_new_field": 1 } ] } }
    """.trimIndent()

    val BOOK = """
      { "id": "b1", "name": "Title", "rating": "s", "status": "active", "post_count": 2, "fav_count": 11,
        "is_favorited": true, "description": "About",
        "reading": { "current_page": 3, "sequence": 2, "post_id": "p3", "series_id": null, "percent": 14 },
        "artist_tags": [{ "id": "t1", "name": "Artist A", "tagName": "artist_a", "type": 1 }],
        "genre_tags": [{ "id": "t2", "name": "Comic", "tagName": "comic", "type": 5 }],
        "posts": [${Fixtures.post("p1")}, ${Fixtures.post("p2")}] }
    """.trimIndent()

    val SERIES = """
      { "data": { "series": { "id": "s1", "name": "Series", "name_en": "A series", "pool_count": 2, "books": [
        { "id": "b8", "name": "Volume 1", "post_count": 15, "rating": "e", "status": "active", "is_favorited": false,
          "fav_count": 0, "sequence": 1, "reading": null,
          "cover_post": { "id": "p8", "md5": "0123456789abcdef0123456789abcdef", "rating": "e", "status": "active",
            "preview_url": "https://v.sankakucomplex.com/p.avif", "sample_url": "https://v.sankakucomplex.com/s.jpg" } },
        { "id": "b9", "name": "Volume 2", "post_count": 10, "rating": "s", "status": "active", "is_favorited": false,
          "fav_count": 2, "sequence": 2, "reading": { "current_page": 4 }, "cover_post": null } ] } } }
    """.trimIndent()
  }
}
