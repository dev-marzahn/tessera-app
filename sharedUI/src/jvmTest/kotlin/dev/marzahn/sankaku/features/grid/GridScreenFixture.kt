package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
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
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * The real [GridScreen] in its tab, with the app's modules and six synthetic fixture posts (no network): "Fixture 1"
 * to "Fixture 6", each titled by its own tag, PNGs but for the file types in [types] by number. Those numbered in
 * [locked] come back as the API sends posts hidden from signed-out users (`redirect_to_signup`, no URLs). A [player]
 * stands in for the video player; with [images], every picture loads as a plain synthetic bitmap (otherwise none
 * loads); [tokens] signs in. [start] before a test and [stop] after it.
 */
@OptIn(ExperimentalTestApi::class)
internal class GridScreenFixture(
  private val types: Map<Int, String> = emptyMap(),
  private val player: VideoPlayback? = null,
  private val images: Boolean = false,
  private val locked: Set<Int> = emptySet(),
  private val tokens: StoredTokens? = null,
  secondPageAtStart: SecondPage = SecondPage.None,
) {
  val grid = Route.Grid(listOf("fixture_tag"))
  private var collecting: Job? = null

  /** Every message shown since [start], by its text. */
  val messages = mutableListOf<String>()

  private fun url(path: String) = "https://v.sankakucomplex.com/data/$path?e=${(Clock.System.now() + 1.hours).epochSeconds}"

  /**
   * What the list's second page does (#524): with [None] the first page is the last; otherwise the first says there's
   * more, and asking for it fails (a 500, or Sankaku's search timeout), serves "Fixture 7" and "Fixture 8", or holds
   * until [releaseSecondPage] and then serves them. Changeable while a test runs.
   */
  enum class SecondPage { None, Hold, Fail, SearchTimeout, Serve }

  @Volatile
  var secondPage: SecondPage = secondPageAtStart

  private val held = CompletableDeferred<Unit>()

  /** A held second page is served now. */
  fun releaseSecondPage() {
    held.complete(Unit)
  }

  private fun page(numbers: IntRange, more: Boolean): String {
    val posts = numbers.joinToString(",") { n ->
      if (n in locked) {
        return@joinToString """{"id":"fixture-$n","md5":"fixture-md5-$n","rating":"s","status":"active","redirect_to_signup":true}"""
      }
      val type = types[n] ?: "image/png"
      val ext = type.substringAfter('/')
      """{"id":"fixture-$n","md5":"fixture-md5-$n","rating":"s","status":"active","file_type":"$type",""" +
        """"width":300,"height":400,"preview_width":300,"preview_height":400,"preview_url":"${url("preview/fixture-$n.png")}",""" +
        """"sample_url":"${url("sample/fixture-$n.$ext")}",""" +
        """"tags":[{"name":"Fixture $n","tagName":"fixture_$n","type":0}],"tag_names":["fixture_$n"]}"""
    }
    val next = if (more) "\"fixture-cursor-2\"" else "null"
    return """{"meta":{"next":$next,"prev":null},"data":[$posts]}"""
  }

  fun start() {
    val cache = Files.createTempDirectory("grid-screen-fixture").toFile()
    val platform = module {
      single<TokenStore> { InMemoryTokenStore(tokens) }
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
          val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          if (request.url.host == "sankakuapi.com" && request.url.encodedPath == "/v2/posts/keyset") {
            if (request.url.parameters["next"] == null) {
              respond(page(1..6, more = secondPage != SecondPage.None), HttpStatusCode.OK, json)
            } else {
              if (secondPage == SecondPage.Hold) held.await()
              if (secondPage == SecondPage.Fail) {
                respondError(HttpStatusCode.InternalServerError)
              } else if (secondPage == SecondPage.SearchTimeout) {
                respond("""{"success":false,"code":"snackbar__account_search-query-timeout"}""", HttpStatusCode.RequestTimeout, json)
              } else {
                respond(page(7..8, more = false), HttpStatusCode.OK, json)
              }
            }
          } else {
            respondError(HttpStatusCode.NotFound)
          }
        }
      }
      single<ImageLoader> {
        ImageLoader.Builder(PlatformContext.INSTANCE).apply {
          // Every picture a plain synthetic bitmap, so the viewer's posts are fully shown (#174).
          if (images) {
            components {
              add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                  val bitmap = Bitmap().apply { allocN32Pixels(30, 40); erase(0xFF2E7D32.toInt()) }
                  return SuccessResult(bitmap.asImage(), chain.request, DataSource.MEMORY)
                }
              })
            }
          }
        }.build()
      }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule, fixtures) }
    // The cells' AsyncImage uses the singleton: set here and reset after, so no later test finds one made already.
    SingletonImageLoader.setUnsafe(GlobalContext.get().get<ImageLoader>())
    collecting = CoroutineScope(Dispatchers.Unconfined).launch {
      GlobalContext.get().get<Messages>().messages.collect { messages += it.text }
    }
  }

  fun stop() {
    collecting?.cancel()
    SingletonImageLoader.reset()
    stopKoin()
  }

  val tabs: TabsController get() = GlobalContext.get().get()

  /** The grid alone in the queue, shown, once its first cell is in. */
  fun ComposeUiTest.showGrid() {
    tabs.open(grid, background = false)
    tabs.tabs.value.filter { it.top != grid }.forEach { tabs.close(it.id) }
    val navigator = TabsNavigator(tabs)
    setContent {
      SankakuTheme {
        val grid = @Composable {
          val activeId by tabs.active.collectAsState()
          val active = tabs.tabs.collectAsState().value.first { it.id == activeId }
          ActiveTab(active, tabs, navigator, rememberSaveableStateHolder()) { key ->
            NavEntry<NavKey>(key) { if (key is Route.Grid) GridScreen(key) }
          }
        }
        val fake = player
        if (fake == null) grid() else CompositionLocalProvider(LocalVideoPlayback provides { fake }) { grid() }
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Fixture 1", substring = true).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
  }

  /** Sends [key] as the desktop window does, with [downs] presses before its release (more than one is a held key). */
  @OptIn(InternalComposeUiApi::class)
  fun ComposeUiTest.press(key: Key, downs: Int = 1, shift: Boolean = false, alt: Boolean = false) {
    val scene = (this as SkikoComposeUiTest).scene
    repeat(downs) {
      runOnIdle { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isAltPressed = alt, isShiftPressed = shift)) }
    }
    runOnIdle { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isAltPressed = alt, isShiftPressed = shift)) }
    waitForIdle()
  }

  /** The number n of the cell the keyboard cursor is on ("Fixture n"), or null with no cursor. */
  fun ComposeUiTest.cursorPost(): Int? =
    onAllNodesWithContentDescription("Fixture", substring = true)
      .fetchSemanticsNodes()
      .firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }
      ?.config?.get(SemanticsProperties.ContentDescription)?.first()
      ?.let { Regex("Fixture (\\d+)").find(it)?.groupValues?.get(1)?.toInt() }

  /** Moves the cursor onto fixture post [n]'s cell with D, from wherever it is. */
  fun ComposeUiTest.cursorTo(n: Int) {
    repeat(12) {
      if (cursorPost() == n) return
      press(Key.D)
    }
    check(cursorPost() == n) { "the cursor didn't reach post $n" }
  }

  fun ComposeUiTest.viewerOpen(): Boolean = onAllNodesWithText("of 6", substring = true).fetchSemanticsNodes().isNotEmpty()

  /** The post the open viewer shows: its title, "Fixture n", in the info panel. */
  fun ComposeUiTest.viewerPost(): Int = (1..8).single { n -> onAllNodesWithText("Fixture $n").fetchSemanticsNodes().isNotEmpty() }

  /** The queue as its tops: a grid by its tags, a post by its id. */
  fun queue(): List<String> =
    tabs.tabs.value.map { (it.top as? Route.Post)?.id ?: (it.top as Route.Grid).tags.joinToString(" ") }
}
