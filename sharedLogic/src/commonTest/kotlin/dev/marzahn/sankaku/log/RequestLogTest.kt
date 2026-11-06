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
      "/books/77" to "/books/{id}",
      "/data/01/23/0123456789abcdef0123456789abcdef.jpg" to "/data/{md5}",
      "/data/sample/01/23/sample-0123456789abcdef0123456789abcdef.jpg" to "/data/sample/{md5}",
      "/users/me" to "/users/me",
      "/" to "/",
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
