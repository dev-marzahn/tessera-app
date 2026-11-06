package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.gridModule
import dev.marzahn.sankaku.di.postModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
import dev.marzahn.sankaku.features.grid.GridScreen
import dev.marzahn.sankaku.features.grid.searchedTags
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import coil3.PlatformContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * #317: a queue swipe from a lone post onto a grid shown for the first time, frame by frame, through the app's own
 * chain (the shell's [QueueSwipeHost] with its own [PreloadNeighbours], the tab's [ActiveTab] and the real
 * [GridScreen]), with synthetic fixture posts and one-colour thumbnails, never a real post. The landing frame shows the
 * grid's cells with their thumbnails, not its loading indicator, and the grid made no request of its own for its page.
 *
 * The frames are captured one test-clock frame apart. With `EVIDENCE_DIR` set they're saved there as PNGs.
 */
@OptIn(ExperimentalTestApi::class)
class QueueGridLandingRenderTest {
  private val thumbnail = 0xFF1565C0.toInt()
  private val lonePostFill = 0xFF2E7D32.toInt()
  private val png = ByteArrayOutputStream().also {
    val image = BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
      color = java.awt.Color(thumbnail)
      fillRect(0, 0, 300, 400)
      dispose()
    }
    ImageIO.write(image, "png", it)
  }.toByteArray()

  // When each request started and finished, in ms from the first, by kind only: no URL, id or search.
  private val started = System.nanoTime()
  private val requestLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val pageRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

  private fun ms() = (System.nanoTime() - started) / 1_000_000

  private fun url(path: String) =
    "https://v.sankakucomplex.com/data/$path?e=${(Clock.System.now() + 1.hours).epochSeconds}"

  private fun page(): String {
    val posts = (1..30).joinToString(",") { n ->
      """{"id":"fixture-$n","md5":"fixture-md5-$n","rating":"s","status":"active","file_type":"image/png",""" +
        """"width":300,"height":400,"preview_width":300,"preview_height":400,"preview_url":"${url("preview/fixture-$n.png")}"}"""
    }
    return """{"meta":{"next":null,"prev":null},"data":[$posts]}"""
  }

  private fun startFixtureKoin() {
    val cache = Files.createTempDirectory("queue-grid-landing").toFile()
    val platform = module {
      single<TokenStore> { InMemoryTokenStore() }
      single { TokenStorageStatus.InMemory }
      single { DebugFlags() }
      single(CACHE_DIR) { cache.toOkioPath() }
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, PRIVACY_FILE, ACCESSIBILITY_FILE, QUEUE_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, FIRST_LAUNCH_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<SaveFolder> { DirectorySaveFolder(cache, opener = {}) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    val fixtures = module {
      single<HttpClientEngine> {
        MockEngine { request ->
          val path = request.url.encodedPath
          when {
            request.url.host == "sankakuapi.com" && path == "/v2/posts/keyset" -> {
              pageRequests += path
              requestLog += "${ms()} ms: page request starts"
              // A normal connection's round trip.
              Thread.sleep(150)
              requestLog += "${ms()} ms: page request ends"
              respond(page(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            path.startsWith("/data/preview/") -> {
              requestLog += "${ms()} ms: thumbnail request"
              respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            }
            else -> respondError(HttpStatusCode.NotFound)
          }
        }
      }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, fixtures) }
    // The cells' AsyncImage uses the singleton, which the app sets to this loader.
    SingletonImageLoader.setUnsafe(GlobalContext.get().get<ImageLoader>())
  }

  /** The share of a frame's pixels (every 4th) that are a thumbnail's, and the surface's. */
  private fun shares(frame: BufferedImage, surface: Int): Pair<Float, Float> {
    var t = 0
    var s = 0
    var all = 0
    for (y in 0 until frame.height step 4) for (x in 0 until frame.width step 4) {
      val rgb = frame.getRGB(x, y)
      if (rgb == thumbnail) t++
      if (rgb == surface) s++
      all++
    }
    return t.toFloat() / all to s.toFloat() / all
  }

  @Test
  fun aGridLandedOnForTheFirstTimeShowsItsThumbnailsInTheLandingFrame() {
    startFixtureKoin()
    try {
      val koin = GlobalContext.get()
      val tabs = koin.get<TabsController>()
      val session = koin.get<AuthSession>()
      runBlocking { session.restore() }
      val target = Route.Grid(listOf("fixture_tag"))
      val start = Route.Post("fixture-post", "fixture-post-md5", emptyList(), "First")
      tabs.open(start, background = false)
      tabs.open(target, background = true)
      tabs.tabs.value.filter { it.top != start && it.top != target }.forEach { tabs.close(it.id) }
      val navigator = TabsNavigator(tabs)
      var swipe: QueueSwipeState? = null
      var surface = 0
      val frames = mutableListOf<Pair<Float, Float>>()
      var landing = -1
      runDesktopComposeUiTest(width = 1080, height = 2402) {
        setContent {
          CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
            SankakuTheme {
              surface = MaterialTheme.colorScheme.surface.toArgb()
              val activeId by tabs.active.collectAsState()
              val tabList by tabs.tabs.collectAsState()
              val active = tabList.first { it.id == activeId }
              val holder = rememberSaveableStateHolder()
              // The shell's own preload ([PreloadNeighbours]), as the app composes it.
              QueueSwipeHost(active, tabs = tabs, upNext = noUpNext(tabs), undo = ClosedTabUndo(tabs), content = {
                swipe = LocalQueueSwipe.current
                ActiveTab(active, tabs, navigator, holder) { key ->
                  NavEntry<NavKey>(key) {
                    when (key) {
                      is Route.Grid -> GridScreen(key)
                      // The lone post swiped from stands in as a fill: what's measured is the grid.
                      else -> Box(Modifier.fillMaxSize().background(Color(lonePostFill)).queueSwipe(enabled = true, from = active.id))
                    }
                  }
                }
              })
            }
          }
        }
        mainClock.advanceTimeBy(500)
        // The preload runs on the engine's own threads: up to 3 s for the grid's first screen to be decoded, the two
        // posts of its one 411 dp column, which is plenty after the page's 150 ms. Before #317 nothing loads, and the
        // swipe starts when the time is up.
        val repository = koin.get<PostsRepository>()
        val loader = koin.get<ImageLoader>()
        val decoded = (1..2).map { MemoryCache.Key(PostImage.cacheKey("fixture-md5-$it", Variant.Preview)) }
        try {
          waitUntil(timeoutMillis = 3_000) {
            repository.cachedFirstPage(searchedTags(target.tags, signedIn = false, safeOnly = false)) != null &&
              loader.memoryCache!!.keys.containsAll(decoded)
          }
          requestLog += "${ms()} ms: first screen decoded; swipe starts"
        } catch (_: ComposeTimeoutException) {
          requestLog += "${ms()} ms: nothing preloaded; swipe starts"
        }
        val from = tabs.active.value
        fun frame() {
          val image = onRoot().captureToImage().toAwtImage()
          System.getenv("EVIDENCE_DIR")?.let { dir -> ImageIO.write(image, "png", File(dir, "grid-landing-%03d.png".format(frames.size))) }
          frames += shares(image, surface)
          val moved = tabs.active.value != from
          if (moved && landing < 0) landing = frames.size - 1
          val (t, s) = frames.last()
          println("grid-landing frame %03d: thumbnails %.2f, surface %.2f, moved %s".format(frames.size - 1, t, s, moved))
        }
        mainClock.autoAdvance = false
        runOnIdle {
          swipe!!.start()
          swipe!!.dragBy(-1080 * 0.6f)
        }
        mainClock.advanceTimeByFrame()
        frame()
        runOnIdle { swipe!!.release(velocity = 0f, minFling = 1000f, from = from) }
        var after = 0
        while (after < 10 && frames.size < 200) {
          mainClock.advanceTimeByFrame()
          frame()
          if (tabs.active.value != from) after++
        }
        assertEquals(10, after, "didn't move within ${frames.size} frames")
        assertEquals(target, tabs.tabs.value.first { it.id == tabs.active.value }.top, "didn't land on the grid")
        requestLog += "${ms()} ms: landed at frame $landing and 10 frames on"
      }
      requestLog.forEach { println("grid-landing $it") }
      // The landing frame and every one after it: the grid's thumbnails, not its loading indicator on the surface.
      val landed = frames.drop(landing)
      val bare = landed.indices.filter { landed[it].first < 0.3f }
      assertTrue(bare.isEmpty(), "frames ${bare.map { it + landing }} after landing show no thumbnails: $landed")
      // The grid took the preloaded page: one request for it in all.
      assertEquals(1, pageRequests.size, "page requests")
    } finally {
      SingletonImageLoader.reset()
      stopKoin()
    }
  }
}
