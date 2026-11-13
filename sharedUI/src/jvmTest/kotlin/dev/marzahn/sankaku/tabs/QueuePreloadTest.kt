package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.SuccessResult
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.GridSettings
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.features.grid.searchedTags
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.features.post.decodedInMemory
import dev.marzahn.sankaku.features.post.firstShownRequests
import dev.marzahn.sankaku.features.post.keptSampleRequest
import dev.marzahn.sankaku.features.post.sampleRequest
import dev.marzahn.sankaku.images.createImageLoader
import dev.marzahn.sankaku.images.fittedRequestSize
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** #263: a neighbour's preload is what the lone post's first frame draws, so a move shows the picture at once. */
@OptIn(ExperimentalTestApi::class)
class QueuePreloadTest {
  private val now = Instant.parse("2026-11-02T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val scope = CoroutineScope(SupervisorJob())
  private val png = ByteArrayOutputStream().also {
    ImageIO.write(BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB), "png", it)
  }.toByteArray()
  // Written from Ktor's threads while the test reads them: copy-on-write, so a read (`toSet`, `filter`, `sorted`)
  // iterates a snapshot. A synchronized list threw ConcurrentModificationException when a request landed mid-read.
  private val imageRequests: MutableList<String> = CopyOnWriteArrayList()
  private val cancelled: MutableList<String> = CopyOnWriteArrayList()

  // Image paths in [hanging] never answer, so a load that's cancelled shows in [cancelled].
  private var hanging = emptySet<String>()

  // The searches (by their tag) whose page was asked for, and with [hangingPages] those whose request was cancelled.
  private val pageRequests: MutableList<String> = CopyOnWriteArrayList()
  private val cancelledPages: MutableList<String> = CopyOnWriteArrayList()
  private var hangingPages = false
  private val engine = MockEngine { request ->
    val path = request.url.encodedPath
    if (request.url.host == "sankakuapi.com" && path == "/v2/posts/keyset") {
      // A grid's page: 30 posts named after its search's tag (besides the rating), in portrait.
      val search = request.url.parameters["tags"].orEmpty().split(' ').first { !it.startsWith("rating:") }
      pageRequests += search
      if (hangingPages) {
        try {
          awaitCancellation()
        } finally {
          cancelledPages += search
        }
      }
      val posts = (1..30).joinToString(",") { n ->
        val id = "$search-$n"
        """{"id":"$id","md5":"m$id","rating":"s","status":"active","file_type":"image/png","width":300,"height":400,""" +
          """"preview_width":300,"preview_height":400,"preview_url":"${url("preview/m$id.avif")}"}"""
      }
      return@MockEngine respond(
        """{"meta":{"next":null,"prev":null},"data":[$posts]}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
      )
    }
    if (request.url.host == "sankakuapi.com") {
      val id = path.substringAfterLast('/')
      return@MockEngine respond(
        """{"id":"$id","md5":"m$id","rating":"s","status":"active","file_type":"image/png","width":400,"height":600,""" +
          """"preview_url":"${url("preview/m$id.avif")}","sample_url":"${url("sample/m$id.png")}","file_url":"${url("m$id.png")}"}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
      )
    }
    imageRequests += path
    if (path in hanging) {
      try {
        awaitCancellation()
      } finally {
        cancelled += path
      }
    }
    respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
  }
  private val log = RequestLog(clock)
  private val limiter = RateLimiter(limits = emptyMap())
  private val connectivity = ConnectivityState()
  private val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), clock), InMemoryTokenStore(), clock)
  private val client = createMainClient(engine, log, limiter, connectivity, session)
  private val store = PostStateStore()
  private val repository = PostsRepository(PostsApi(client), store, session, scope)
  private val loader =
    createImageLoader(PlatformContext.INSTANCE, client, repository, store, clock, Files.createTempDirectory("images").toOkioPath())
  private val privacy = PrivacySettings(InMemoryTextFile())
  private val grid = GridSettings(InMemoryTextFile())

  init {
    // Signed out, so a grid's search gets the locked `rating:s`.
    runBlocking { session.restore() }
  }

  private fun url(path: String) = "https://v.sankakucomplex.com/data/$path?e=${(now + 1.hours).epochSeconds}"

  private fun post(fileType: String = "image/png") = PostDto(
    id = "1",
    md5 = "m1",
    rating = "s",
    status = "active",
    fileType = fileType,
    previewUrl = url("preview/m1.avif"),
    sampleUrl = url("sample/m1.png"),
    fileUrl = url("m1.png"),
    fileWidth = 400,
    fileHeight = 600,
  )

  private fun lonePost(id: String) = TabState(TabId("t$id"), listOf(Route.Post(id, "m$id", emptyList())))

  private fun grid(tag: String) = TabState(TabId("g$tag"), listOf(Route.Grid(listOf(tag))))

  private val window = object : WindowInfo {
    override val isWindowFocused = true
    override val keyboardModifiers = PointerKeyboardModifiers(0)
    override val containerSize = IntSize(WIDTH, HEIGHT)
  }

  @AfterTest
  fun tearDown() = scope.cancel()

  @Test
  fun aPreloadedStillPostIsDrawnFromMemoryWithoutAnotherRequest() = runBlocking {
    val post = post()
    firstShownRequests(PlatformContext.INSTANCE, post, WIDTH, HEIGHT).forEach { loader.execute(it) }
    assertEquals(listOf("/data/preview/m1.avif", "/data/sample/m1.png"), imageRequests)
    imageRequests.clear()

    // The lone post's first frame: the decoded sample, found by its key alone.
    assertNotNull(decodedInMemory(loader, post.md5, previewToo = false))
    // Its zoomable sample: the same request, answered from memory.
    val size = fittedRequestSize(400f / 600f, WIDTH, HEIGHT)
    val sample = loader.execute(sampleRequest(PlatformContext.INSTANCE, post, size))
    assertEquals(DataSource.MEMORY_CACHE, (sample as SuccessResult).dataSource)
    assertEquals(emptyList(), imageRequests)
  }

  @Test
  fun aVideoPreloadsOnlyItsPreview() {
    val requests = firstShownRequests(PlatformContext.INSTANCE, post(fileType = "video/mp4"), WIDTH, HEIGHT)
    assertEquals(listOf("m1-Preview"), requests.map { it.memoryCacheKey })
  }

  /** A keep puts the sample on disk only, so a neighbour's preload later reads it from there, not the network. */
  @Test
  fun aKeptPostsSampleGoesToDiskOnly() = runBlocking {
    val post = post()
    loader.execute(assertNotNull(keptSampleRequest(PlatformContext.INSTANCE, post)))
    assertEquals(listOf("/data/sample/m1.png"), imageRequests)
    assertNull(decodedInMemory(loader, post.md5))
    imageRequests.clear()

    val sample = firstShownRequests(PlatformContext.INSTANCE, post, WIDTH, HEIGHT)[1]
    assertEquals(DataSource.DISK, (loader.execute(sample) as SuccessResult).dataSource)
    assertEquals(emptyList(), imageRequests)
  }

  @Test
  fun aKeptVideoPrefetchesNothingMore() {
    assertNull(keptSampleRequest(PlatformContext.INSTANCE, post(fileType = "video/mp4")))
  }

  @Test
  fun aPostThatNeedsSigningInPreloadsNothing() {
    val requests = firstShownRequests(PlatformContext.INSTANCE, post().copy(redirectToSignup = true), WIDTH, HEIGHT)
    assertEquals(emptyList(), requests)
  }

  @Test
  fun movingOnCancelsWhatCanNoLongerBeLandedOnAndKeepsWhatItLandedOn() = runComposeUiTest {
    // Items 1, 2, 3 and 4 in a row; 2 is shown, so 1 and 3 load. Neither answers yet.
    hanging = setOf("/data/sample/m1.png", "/data/sample/m3.png")
    val items = listOf("1", "2", "3", "4").map(::lonePost)
    var shown by mutableStateOf(1)
    var ready: Map<String, LoadedNeighbour> = emptyMap()
    setContent {
      CompositionLocalProvider(LocalWindowInfo provides window) {
        ready = PreloadNeighbours(items[shown], listOfNotNull(items.getOrNull(shown - 1), items.getOrNull(shown + 1)), repository, loader, session, privacy, grid)
      }
    }
    waitUntil(timeoutMillis = 5_000) { "/data/sample/m1.png" in imageRequests && "/data/sample/m3.png" in imageRequests }
    // Their previews have loaded while the samples hang: ready for the peek with the preview.
    try {
      waitUntil(timeoutMillis = 5_000) { ready.keys.toSet() == setOf("1", "3") }
    } catch (e: Throwable) {
      throw AssertionError("ready ${ready.keys}, requests $imageRequests, memory ${loader.memoryCache?.keys}", e)
    }

    // On to 3: 1 can't be landed on any more, 3 is shown, 4 is the new neighbour.
    shown = 2
    waitUntil(timeoutMillis = 5_000) { "/data/sample/m4.png" in imageRequests && "/data/sample/m1.png" in cancelled }
    waitForIdle()
    assertEquals(listOf("/data/sample/m1.png"), cancelled.toList())
    // Each image asked for once: the shown post's load went on, and 2 (now behind) was loaded already.
    // One snapshot: 2's sample may be asked for between two reads.
    imageRequests.toList().let { assertEquals(it.distinct(), it) }
    // 1 is gone from what peeks in; 3, shown now, stays; 2 and 4, the new neighbours, come in.
    waitUntil(timeoutMillis = 5_000) { ready.keys.toSet() == setOf("2", "3", "4") }
  }

  /**
   * #317: a grid beside the item shown loads the page it opens on and the thumbnails of its first screen, decoded, and
   * nothing past them.
   */
  @Test
  fun aGridNeighbourLoadsItsFirstPageAndTheThumbnailsOfItsFirstScreen() = runComposeUiTest {
    val shown = lonePost("1")
    val next = grid("scenery")
    // At density 1, 220 px columns: 4 of them, and about 4 portrait rows.
    val small = object : WindowInfo by window {
      override val containerSize = IntSize(1080, 1200)
    }
    setContent {
      CompositionLocalProvider(LocalWindowInfo provides small) {
        PreloadNeighbours(shown, listOf(next), repository, loader, session, privacy, grid)
      }
    }
    val searched = searchedTags(listOf("scenery"), signedIn = false, safeOnly = false)
    try {
      waitUntil(timeoutMillis = 5_000) { repository.cachedFirstPage(searched) != null }
    } catch (e: Throwable) {
      throw AssertionError("pages $pageRequests, auth ${session.state.value}", e)
    }
    val page = assertNotNull(repository.cachedFirstPage(searched))
    val first = firstScreen(page, 1080, 1200, 220)
    assertEquals(16, first.size)
    val keys = first.map { PostImage.cacheKey(it.md5, Variant.Preview) }
    try {
      waitUntil(timeoutMillis = 5_000) { keys.all { MemoryCache.Key(it) in loader.memoryCache!!.keys } }
    } catch (e: Throwable) {
      throw AssertionError("requests $imageRequests, memory ${loader.memoryCache?.keys}", e)
    }
    waitForIdle()
    assertEquals(listOf("scenery"), pageRequests.toList())
    // The first screen's thumbnails only, once each.
    assertEquals(first.map { "/data/preview/${it.md5}.avif" }.sorted(), imageRequests.sorted())
  }

  /**
   * #317: a grid that stops being a neighbour has its page request cancelled, as a post's load is, and swiping along a
   * queue of grids and posts never has more than three items loading: the neighbours and the one shown.
   */
  @Test
  fun movingOnCancelsAGridsLoadAndAtMostThreeItemsLoad() = runComposeUiTest {
    hangingPages = true
    hanging = setOf("/data/sample/m1.png", "/data/sample/m3.png")
    // grid a, post 1, grid b, post 3, grid c. Post 1 is shown, so grids a and b load. Nothing answers.
    val items = listOf(grid("a"), lonePost("1"), grid("b"), lonePost("3"), grid("c"))
    var shown by mutableStateOf(1)
    setContent {
      CompositionLocalProvider(LocalWindowInfo provides window) {
        PreloadNeighbours(items[shown], listOfNotNull(items.getOrNull(shown - 1), items.getOrNull(shown + 1)), repository, loader, session, privacy, grid)
      }
    }
    // What's loading: the pages asked for and not cancelled, and the hanging samples likewise.
    fun loading() = (pageRequests - cancelledPages.toSet()).toSet() + (imageRequests.filter { it in hanging } - cancelled.toSet()).toSet()
    waitUntil(timeoutMillis = 5_000) { pageRequests.toSet() == setOf("a", "b") }
    assertEquals(setOf("a", "b"), loading())

    // On to grid b: grid a can't be landed on any more; b, shown now, goes on; posts 1 and 3 load.
    shown = 2
    waitUntil(timeoutMillis = 5_000) { "a" in cancelledPages && "/data/sample/m1.png" in imageRequests && "/data/sample/m3.png" in imageRequests }
    waitForIdle()
    assertEquals(setOf("b", "/data/sample/m1.png", "/data/sample/m3.png"), loading())

    // On to post 3: post 1 is cancelled, grid c loads, grid b (behind now) is still on the way.
    shown = 3
    waitUntil(timeoutMillis = 5_000) { "c" in pageRequests && "/data/sample/m1.png" in cancelled }
    waitForIdle()
    assertEquals(setOf("b", "/data/sample/m3.png", "c"), loading())
    // Each asked for once.
    assertEquals(listOf("a", "b", "c"), pageRequests.sorted())
    imageRequests.toList().let { assertEquals(it.distinct(), it) }
  }

  @Test
  fun theFirstScreenStopsOnceEveryColumnIsFull() {
    fun post(id: String, w: Int, h: Int) = post().copy(id = id, previewWidth = w, previewHeight = h)
    // Two 500 px columns, 1,000 px tall: two squares a column fill it.
    val squares = (1..10).map { post("$it", 100, 100) }
    assertEquals(4, firstScreen(squares, 1000, 1000, 400).size)
    // A tall post fills its column alone, so the other column takes the rest.
    val mixed = listOf(post("tall", 100, 300)) + (1..10).map { post("$it", 100, 100) }
    assertEquals(listOf("tall", "1", "2"), firstScreen(mixed, 1000, 1000, 400).map { it.id })
    // A post that needs signing in is left out of the grid, so it takes no room (#507).
    val restricted = listOf(post("1", 100, 100).copy(redirectToSignup = true)) + squares.drop(1)
    assertEquals(listOf("2", "3", "4", "5"), firstScreen(restricted, 1000, 1000, 400).map { it.id })
  }

  private companion object {
    const val WIDTH = 1280
    const val HEIGHT = 2856
  }
}
