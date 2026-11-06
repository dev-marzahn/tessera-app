package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.NOW
import dev.marzahn.sankaku.api.HeaderSet
import dev.marzahn.sankaku.api.SankakuJson
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class OriginalFilesTest {
  private val bytes = ByteArray(300_000) { (it % 251).toByte() }
  private val future = (NOW + 1.hours).epochSeconds
  private val listed: PostDto = SankakuJson.decodeFromString(Fixtures.post())
  private val usable = listed.copy(fileUrl = "https://s.sankakucomplex.com/data/01/23/x.png?e=$future&m=a")
  private val freshUrl = "https://s.sankakucomplex.com/data/01/23/x.png?e=${future + 60}&m=fresh"

  /** The list post with [freshUrl], as the keyset `id:` refetch returns it. */
  private val refetchPage = Fixtures.page(null, "p1").replace("https://s.sankakucomplex.com/data/01/23/x.png?e=1&m=a", freshUrl)

  @Test
  fun aUsableUrlIsDownloadedAsItIsWithoutAToken() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { respond(bytes, HttpStatusCode.OK) }
    val files = OriginalFiles(h.mainClient, PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope), PostStateStore(), FixedClock)
    val sink = Buffer()

    files.download(usable, sink)

    assertContentEquals(bytes, sink.readByteArray())
    assertEquals(listOf("s.sankakucomplex.com"), h.requests.map { it.url.host })
    assertNull(h.requests.single().headers[HttpHeaders.Authorization])
  }

  @Test
  fun anExpiredUrlIsRefetchedThroughThePostRequestWithoutV2() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      if (request.url.host == "s.sankakucomplex.com") respond(bytes, HttpStatusCode.OK) else json(refetchPage)
    }
    val store = PostStateStore()
    val files = OriginalFiles(h.mainClient, PostsRepository(h.posts, store, h.session, backgroundScope), store, FixedClock)
    val sink = Buffer()

    files.download(listed, sink)

    assertContentEquals(bytes, sink.readByteArray())
    val refetch = h.requestsTo("/v2/posts/keyset").single()
    HeaderSet.V2.headers.keys.forEach { assertTrue(refetch.headers[it] != HeaderSet.V2.headers[it], "sent V2 header $it") }
    assertEquals("fresh", h.requests.last().url.parameters["m"])
  }

  @Test
  fun aRefusedUrlIsRefetchedAndRetriedOnce() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      when {
        request.url.host != "s.sankakucomplex.com" -> json(refetchPage)
        request.url.parameters["m"] == "fresh" -> respond(bytes, HttpStatusCode.OK)
        else -> respond("", HttpStatusCode.Forbidden)
      }
    }
    val store = PostStateStore()
    val files = OriginalFiles(h.mainClient, PostsRepository(h.posts, store, h.session, backgroundScope), store, FixedClock)
    val sink = Buffer()

    files.download(usable, sink)

    assertContentEquals(bytes, sink.readByteArray())
    assertEquals(2, h.requests.count { it.url.host == "s.sankakucomplex.com" })
  }

  @Test
  fun aPostWithoutAFileFailsAfterTheRefetch() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      json(Fixtures.page(null, "p1").replace("\"file_url\": \"https://s.sankakucomplex.com/data/01/23/x.png?e=1&m=a\",", ""))
    }
    val store = PostStateStore()
    val files = OriginalFiles(h.mainClient, PostsRepository(h.posts, store, h.session, backgroundScope), store, FixedClock)

    assertFailsWith<NoOriginalFileException> { files.download(listed.copy(fileUrl = null), Buffer()) }
  }

  @Test
  fun aServerErrorFailsWithoutWritingAnything() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { respond("oops", HttpStatusCode.InternalServerError) }
    val store = PostStateStore()
    val files = OriginalFiles(h.mainClient, PostsRepository(h.posts, store, h.session, backgroundScope), store, FixedClock)
    val sink = Buffer()

    val error = assertFailsWith<DownloadFailedException> { files.download(usable, sink) }

    assertEquals(500, error.status)
    assertEquals(0, sink.size)
    // The message never holds the signed URL.
    assertTrue("sankakucomplex" !in error.message.orEmpty())
  }

  @Test
  fun theFileIsNamedByItsMd5AndExtension() {
    assertEquals("0123456789abcdef0123456789abcdef.png", originalFileName(listed))
    assertEquals("0123456789abcdef0123456789abcdef.mp4", originalFileName(listed.copy(fileExt = null, fileType = "video/mp4")))
    assertEquals("etcpasswd.png", originalFileName(listed.copy(md5 = "../../etc/passwd", fileExt = "p/n\u0000g")))
    assertEquals("0123456789abcdef0123456789abcdef.svg", originalFileName(listed.copy(fileExt = null, fileType = "image/svg+xml")))
    assertEquals("0123456789abcdef0123456789abcdef.mp4", originalFileName(listed.copy(fileExt = null, fileType = "video/mp4; codecs=avc1")))
    assertEquals("post.bin",originalFileName(listed.copy(md5 = "..", fileExt = "/", fileType = null)))
    assertEquals("image/png", originalMimeType(listed))
    assertEquals("video/mp4", originalMimeType(listed.copy(fileType = "video/mp4; codecs=avc1")))
    assertEquals("video/webm", originalMimeType(listed.copy(fileType = null, fileExt = "webm")))
  }
}
