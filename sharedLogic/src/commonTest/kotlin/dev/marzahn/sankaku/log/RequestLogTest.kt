package dev.marzahn.sankaku.log

import dev.marzahn.sankaku.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestLogTest {
  @Test
  fun idsMd5sAndQueriesAreReplaced() {
    val cases = mapOf(
      "/posts/wvaemE6YDaz/favorite" to "/posts/{id}/favorite",
      "/posts/wvaemE6YDaz" to "/posts/{id}",
      "/v2/posts/keyset?tags=id%3Aabc&lang=en" to "/v2/posts/keyset",
      "/pools/123/posts" to "/pools/{id}/posts",
      "/pools/abc/reading" to "/pools/{id}/reading",
      "/pools/readings/destroy" to "/pools/readings/destroy",
      "/pools/keyset?post_id=1" to "/pools/keyset",
      "/data/01/23/0123456789abcdef0123456789abcdef.jpg" to "/data/{md5}",
      "/data/sample/01/23/sample-0123456789abcdef0123456789abcdef.jpg" to "/data/sample/{md5}",
      "/users/me" to "/users/me",
      "/users/blacklist" to "/users/blacklist",
      "/users/blacklist/vkr00AbcDef" to "/users/blacklist/{id}",
      "/post/aB3dE5gH7jK/pools" to "/post/{id}/pools",
      "/tag-and-wiki/name/blue_hair" to "/tag-and-wiki/name/{id}",
      "/o/x.png" to "/o/{id}",
      "/" to "/",
    )
    for ((path, expected) in cases) assertEquals(expected, RequestLog.template(path), path)
  }

  @Test
  fun aSegmentThatIsNoKnownWordIsReplaced() {
    assertEquals("/{id}/{id}/{id}", RequestLog.template("/somewhere/aB3dE5gH7jK/new"))
    assertEquals("/posts/{id}/{id}", RequestLog.template("/posts/aB3dE5gH7jK/aB3dE5gH7jK"))
  }

  @Test
  fun aValueThatIsAPathWordIsStillReplaced() {
    val cases = mapOf(
      "/tag-and-wiki/name/sample" to "/tag-and-wiki/name/{id}",
      "/tag-and-wiki/name/name" to "/tag-and-wiki/name/{id}",
      "/post/me/pools" to "/post/{id}/pools",
      "/posts/vote/favorite" to "/posts/{id}/favorite",
      "/pools/reading/reading" to "/pools/{id}/reading",
      "/users/blacklist/posts" to "/users/blacklist/{id}",
    )
    for ((path, expected) in cases) assertEquals(expected, RequestLog.template(path), path)
  }

  @Test
  fun imageHostPathsKeepOnlyTheirFolders() {
    val cases = mapOf(
      "/data/preview/01/23/0123456789abcdef0123456789abcdef.avif" to "/data/preview/{md5}",
      "/o/01/23/0123456789abcdef0123456789abcdef.png" to "/o/{md5}",
    )
    for ((path, expected) in cases) assertEquals(expected, RequestLog.template(path), path)
  }

  @Test
  fun bufferHoldsAtMost200Entries() {
    val log = RequestLog(FixedClock)

    repeat(250) { log.record("GET", "sankakuapi.com", "/posts/$it", 200, 10, null) }

    assertEquals(200, log.entries.value.size)
    assertEquals("/posts/{id}", log.entries.value.last().pathTemplate)
  }
}
