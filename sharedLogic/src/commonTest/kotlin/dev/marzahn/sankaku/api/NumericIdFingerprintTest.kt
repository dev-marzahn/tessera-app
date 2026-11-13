package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone claims Chrome's desktop site (#475), whose ids are obfuscated. A numeric id, saved by a build from before,
 * is only found with the Android User-Agent, so a request about one goes out as Android Chrome; nothing else does.
 */
class NumericIdFingerprintTest {
  private val desktopSite = BrowserFingerprint(BrowserPlatform.AndroidDesktopSite)
  private val android = BrowserFingerprint(BrowserPlatform.Android)

  private fun harness(fingerprint: BrowserFingerprint) = Harness(InMemoryTokenStore(storedTokens()), fingerprint = fingerprint) { request ->
    val path = request.url.encodedPath
    when {
      path.endsWith("/favorite") -> json(Fixtures.FAVOURITE)
      path.endsWith("/reading") || path.endsWith("/destroy") -> json("""{ "success": true, "code": "x", "message": "x" }""")
      path.startsWith("/post/") -> json("[]")
      path == "/graphql" -> json("""{ "data": { "series": { "id": "s1", "name": "S", "books": [] } } }""")
      path.startsWith("/pools/") -> json("""{ "id": "b1", "posts": [] }""")
      else -> json(Fixtures.page())
    }
  }

  private fun HttpRequestData.userAgent() = headers[HttpHeaders.UserAgent]

  private fun HttpRequestData.platformHint() = headers["sec-ch-ua-platform"]

  @Test
  fun aNumericIdGoesOutAsAndroidChromeAndAnythingElseAsTheDesktopSite() = runTest {
    val h = harness(desktopSite)
    h.session.restore()
    val books = BooksApi(h.mainClient)

    h.posts.favourite("12345678")
    h.posts.keyset(listOf("id:12345678"))
    books.pages("4012")
    books.saveReading("4012", 1)
    books.booksOf("12345678")
    // A series' id goes in GraphQL's body, not the URL (a series queue item, or an old book's series).
    books.series("31337")
    // So does a reading place's book id when it's forgotten (`pool_ids`).
    books.clearReading("4012")
    val legacy = h.requests.toList()
    h.requests.clear()

    h.posts.favourite("aB3dE5gH7jK")
    h.posts.keyset(listOf("blue_hair", "order:popularity"))
    books.pages("bK2mN4pQ6rT")
    books.firstPages("bK2mN4pQ6rT")
    books.booksOf("aB3dE5gH7jK")
    books.series("sR7tU9vW1xY")
    books.clearReading("bK2mN4pQ6rT")
    val current = h.requests.toList()

    for (request in legacy) {
      assertEquals(android.userAgent, request.userAgent(), request.url.encodedPath)
      assertEquals(android.clientHints["sec-ch-ua-platform"], request.platformHint(), request.url.encodedPath)
    }
    for (request in current) {
      assertEquals(desktopSite.userAgent, request.userAgent(), request.url.encodedPath)
      assertEquals(desktopSite.clientHints["sec-ch-ua-platform"], request.platformHint(), request.url.encodedPath)
    }
  }

  @Test
  fun desktopNeverClaimsAndroid() = runTest {
    val mac = BrowserFingerprint(BrowserPlatform.Mac)
    val h = harness(mac)
    h.session.restore()
    h.posts.favourite("12345678")
    BooksApi(h.mainClient).pages("4012")

    for (request in h.requests) assertEquals(mac.userAgent, request.userAgent(), request.url.encodedPath)
  }

  @Test
  fun whatCountsAsAboutANumericId() {
    for (url in listOf(
      "https://sankakuapi.com/posts/12345678",
      "https://sankakuapi.com/posts/12345678/fu",
      "https://sankakuapi.com/post/12345678/pools",
      "https://sankakuapi.com/pools/4012/reading",
      "https://sankakuapi.com/v2/posts/keyset?tags=id%3A12345678",
      "https://sankakuapi.com/v2/posts/keyset?tags=pool%3A4012+order%3Apopularity",
      "https://sankakuapi.com/v2/posts/keyset?tags=parent%3A12345678",
      "https://sankakuapi.com/v2/posts?tags=recommended_for_post%3A12345678+rating%3As",
    )) assertEquals(true, aboutNumericId(io.ktor.http.Url(url)), url)
    for (url in listOf(
      "https://sankakuapi.com/v2/posts/keyset?tags=threshold%3A4+rating%3As",
      "https://sankakuapi.com/v2/posts?tags=recommended_for_post%3AaB3dE5gH7jK",
      "https://sankakuapi.com/posts/aB3dE5gH7jK",
      "https://sankakuapi.com/pools/readings/destroy",
      "https://sankakuapi.com/users/blacklist/42",
      "https://sankakuapi.com/v2/posts/keyset?tags=blue_hair+width%3A1920",
      "https://sankakuapi.com/v2/posts/keyset?tags=pool%3AbK2mN4pQ6rT&limit=3",
      "https://v.sankakucomplex.com/data/sample/ab/cd/abcd.jpg?e=1700000000",
    )) assertEquals(false, aboutNumericId(io.ktor.http.Url(url)), url)
  }
}
