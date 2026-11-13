package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
import dev.marzahn.sankaku.di.gridModule
import dev.marzahn.sankaku.di.postModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.LocalVideoPlayback
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.features.post.VideoPlayback
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * On a video in the viewer, V (scrubbing: A/D seek) and T (the tag list: W/S walk the tags, A/D step posts) have the
 * keys one at a time: T ends scrubbing, and V from the tag list takes the keys back and scrubs. The real [GridScreen]
 * in its tab, with synthetic fixture posts (no network) and a fake video player.
 */
@OptIn(ExperimentalTestApi::class)
class VideoKeyModesTest {
  /** Fixture posts 1 to 6: an mp4 at 4, the rest PNGs; each titled by its own tag, "Fixture n". */
  private val types = mapOf(4 to "video/mp4")

  private fun url(path: String) = "https://v.sankakucomplex.com/data/$path?e=${(Clock.System.now() + 1.hours).epochSeconds}"

  private fun page(): String {
    val posts = (1..6).joinToString(",") { n ->
      val type = types[n] ?: "image/png"
      val ext = type.substringAfter('/')
      """{"id":"fixture-$n","md5":"fixture-md5-$n","rating":"s","status":"active","file_type":"$type",""" +
        """"width":300,"height":400,"preview_width":300,"preview_height":400,"preview_url":"${url("preview/fixture-$n.png")}",""" +
        """"sample_url":"${url("sample/fixture-$n.$ext")}",""" +
        """"tags":[{"name":"Fixture $n","tagName":"fixture_$n","type":0}],"tag_names":["fixture_$n"]}"""
    }
    return """{"meta":{"next":null,"prev":null},"data":[$posts]}"""
  }

  /** A 10 s video that records its seeks; no media, so nothing is drawn. */
  private class FakePlayback : VideoPlayback {
    override var isPlaying by mutableStateOf(false)
    override val hasMedia = false
    override val failed = false
    override val firstFrameShown = false
    override val duration = 10.0
    override var position by mutableStateOf(0.5f)
    override var volume = 1f
    override var dragging = false
    val seeks = mutableListOf<Float>()

    override fun open(url: String, cacheKey: String, play: Boolean) {
      isPlaying = play
    }

    override fun play() {
      isPlaying = true
    }

    override fun pause() {
      isPlaying = false
    }

    override fun seekTo(fraction: Float) {
      position = fraction.coerceIn(0f, 1f)
      seeks += position
    }
  }

  private val player = FakePlayback()
  private val grid = Route.Grid(listOf("fixture_tag"))

  @BeforeTest
  fun startFixtureKoin() {
    val cache = Files.createTempDirectory("video-key-modes").toFile()
    val platform = module {
      single<TokenStore> { InMemoryTokenStore() }
      single { TokenStorageStatus.InMemory }
      single { DebugFlags() }
      single(CACHE_DIR) { cache.toOkioPath() }
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, PRIVACY_FILE, ACCESSIBILITY_FILE, QUEUE_FILE, GRID_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, FIRST_LAUNCH_FILE, READING_FILE, OUTBOX_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<SaveFolder> { DirectorySaveFolder(cache, opener = {}) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    val fixtures = module {
      single<HttpClientEngine> {
        MockEngine { request ->
          if (request.url.host == "sankakuapi.com" && request.url.encodedPath == "/v2/posts/keyset") {
            respond(page(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
          } else {
            respondError(HttpStatusCode.NotFound)
          }
        }
      }
      single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule, fixtures) }
    // The cells' AsyncImage uses the singleton: set here and reset after, so no later test finds one made already.
    SingletonImageLoader.setUnsafe(GlobalContext.get().get<ImageLoader>())
  }

  @AfterTest
  fun stop() {
    SingletonImageLoader.reset()
    stopKoin()
  }

  /** The grid, with the viewer open on the video (post 4). */
  private fun ComposeUiTest.showVideo() {
    val tabs = GlobalContext.get().get<TabsController>()
    tabs.open(grid, background = false)
    tabs.tabs.value.filter { it.top != grid }.forEach { tabs.close(it.id) }
    val navigator = TabsNavigator(tabs)
    setContent {
      SankakuTheme {
        CompositionLocalProvider(LocalVideoPlayback provides { player }) {
          val activeId by tabs.active.collectAsState()
          val active = tabs.tabs.collectAsState().value.first { it.id == activeId }
          ActiveTab(active, tabs, navigator, rememberSaveableStateHolder()) { key ->
            NavEntry<NavKey>(key) { if (key is Route.Grid) GridScreen(key) }
          }
        }
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Fixture 1", substring = true).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
    repeat(12) {
      if (cursorPost() == 4) return@repeat
      press(Key.D)
    }
    press(Key.Spacebar)
    assertEquals(4, viewerPost())
    waitUntil(timeoutMillis = 5_000) { player.isPlaying }
  }

  /** Sends [key] as the desktop window does: a press, then its release. */
  @OptIn(InternalComposeUiApi::class)
  private fun ComposeUiTest.press(key: Key) {
    val scene = (this as SkikoComposeUiTest).scene
    runOnIdle { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)) }
    runOnIdle { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)) }
    waitForIdle()
  }

  /** The number n of the cell the keyboard cursor is on ("Fixture n"), or null with no cursor. */
  private fun ComposeUiTest.cursorPost(): Int? =
    onAllNodesWithContentDescription("Fixture", substring = true)
      .fetchSemanticsNodes()
      .firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }
      ?.config?.get(SemanticsProperties.ContentDescription)?.first()
      ?.let { Regex("Fixture (\\d+)").find(it)?.groupValues?.get(1)?.toInt() }

  /** The post the open viewer shows: its title, "Fixture n", in the info panel. */
  private fun ComposeUiTest.viewerPost(): Int =
    (1..6).single { n -> onAllNodesWithText("Fixture $n").fetchSemanticsNodes().isNotEmpty() }

  @Test
  fun scrubbingASeeksAndDSeeksOnFromThere() = runDesktopComposeUiTest(width = 1280, height = 832) {
    showVideo()
    press(Key.V)
    press(Key.D)
    press(Key.D)
    press(Key.A)

    // From 5 s of 10: on 5 s to the end, held there, then back 5 s.
    assertEquals(listOf(1f, 1f, 0.5f), player.seeks)
    assertEquals(4, viewerPost(), "A/D stepped posts while scrubbing")
  }

  @Test
  fun tEndsScrubbingSoDMovesInTheTagList() = runDesktopComposeUiTest(width = 1280, height = 832) {
    showVideo()
    press(Key.V)
    press(Key.T)
    press(Key.D)

    assertTrue(player.seeks.isEmpty(), "D seeked from the tag list")
    assertEquals(4, viewerPost(), "D stepped posts from the tag list instead of moving along the tags")
  }

  @Test
  fun vFromTheTagListTakesTheKeysBackAndScrubs() = runDesktopComposeUiTest(width = 1280, height = 832) {
    showVideo()
    press(Key.T)
    press(Key.V)
    press(Key.A)
    // Esc ends scrubbing; were the keys still with the tag list, it would have taken Esc and A would seek again.
    press(Key.Escape)
    press(Key.A)

    assertEquals(listOf(0f), player.seeks)
    assertEquals(3, viewerPost())
  }
}
