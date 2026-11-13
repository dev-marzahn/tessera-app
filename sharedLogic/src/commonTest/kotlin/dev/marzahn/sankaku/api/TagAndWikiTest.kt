package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #486: a tag's page from `/tag-and-wiki/name/{tag}`, its key encoded in the path, with V2. */
class TagAndWikiTest {
  private val harness = Harness(InMemoryTokenStore(null)) { request ->
    when (request.url.encodedPath) {
      "/tag-and-wiki/name/coconut_%28girls_und_panzer%29", "/tag-and-wiki/name/coconut_(girls_und_panzer)" -> json(
        """{ "tag": { "id": "abc", "name": "Coconut (Girls und Panzer)", "tagName": "coconut_(girls_und_panzer)", "type": 4,
          "post_count": 1234, "pool_count": 7, "is_following": false },
          "wiki": { "title": "coconut_(girls_und_panzer)", "body": "A [[girls_und_panzer|GuP]] character." } }""",
      )
      "/tag-and-wiki/name/no_wiki" -> json("""{ "tag": { "name": "no_wiki", "tagName": "no_wiki", "post_count": 1 }, "wiki": null }""")
      else -> error("unexpected ${request.url.encodedPath}")
    }
  }
  private val tags = TagsApi(harness.mainClient)

  @Test
  fun aTagsPageHasItsCountsAndWiki() = runTest {
    val page = tags.tagAndWiki("coconut_(girls_und_panzer)")
    assertEquals(1234, page.tag.postCount)
    assertEquals(7, page.tag.poolCount)
    assertEquals("abc", page.tag.id)
    assertEquals("A [[girls_und_panzer|GuP]] character.", page.wiki?.body)
    val request = harness.requests.single()
    assertTrue(HeaderSet.V2.headers.all { (key, value) -> request.headers.getAll(key).orEmpty().contains(value) }, "V2")
  }

  @Test
  fun aTagWithNoWikiHasNone() = runTest {
    val page = tags.tagAndWiki("no_wiki")
    assertNull(page.wiki)
    assertNull(page.tag.poolCount)
  }
}
