package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
import dev.marzahn.sankaku.di.gridModule
import dev.marzahn.sankaku.di.postModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import coil3.PlatformContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #492: a restored grid whose place can't be looked up keeps that place. Offline (or 429, 401) the lookup stays pending,
 * the grid saves nothing over it, and the lookup runs again when the connection is back; a place that isn't found any
 * more says so, with Try again. The real [GridScreen] in its tab, on two synthetic keyset pages (no pictures).
 */
@OptIn(ExperimentalTestApi::class)
class PlaceLookupFailureTest {
  @Volatile private var online = false

  // Whether the second page holds the saved post: false for a post that's gone.
  @Volatile private var savedPostListed = true

  // Sankaku answers 429.
  @Volatile private var rateLimited = false

  // Every place the tab held, checked on each frame the test runs: a place saved over the pending one and then put
  // back would still show here.
  private val placesHeld = linkedSetOf<GridPlace?>()
  private var heldPlace: () -> GridPlace? = { null }

  private val requests = java.util.concurrent.atomic.AtomicInteger()
  private val route = Route.Grid(listOf("fixture_tag"))
  private val saved = GridPlace("fixture-45", cursor = "page-2", page = 2)
  private val scope = CoroutineScope(Dispatchers.Default)

  private fun post(n: Int) =
    """{"id":"fixture-$n","md5":"fixture-md5-$n","rating":"s","status":"active","file_type":"image/png",""" +
      """"width":300,"height":300,"preview_width":300,"preview_height":300}"""

  private fun page(next: String?, posts: List<Int>) =
    posts.joinToString(",", """{"meta":{"next":${next?.let { "\"$it\"" }},"prev":null},"data":[""", "]}", transform = ::post)

  private fun startFixtureKoin() {
    val cache = Files.createTempDirectory("place-lookup").toFile().apply { deleteOnExit() }
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
            requests.incrementAndGet()
            if (!online) throw IOException("offline")
            if (rateLimited) {
              return@MockEngine respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0"))
            }
            val body = when (request.url.parameters["next"]) {
              null -> page("page-2", (1..30).toList())
              else -> page(null, (31..60).filter { savedPostListed || it != 45 })
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
          } else {
            respondError(HttpStatusCode.NotFound)
          }
        }
      }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule, fixtures) }
  }

  @AfterTest
  fun stop() {
    scope.cancel()
    stopKoin()
  }

  private fun restored(
    online: Boolean = false,
    savedPostListed: Boolean = true,
    rateLimited: Boolean = false,
    test: ComposeUiTest.(TabState, MutableList<Message>) -> Unit,
  ) {
    // Set before the grid composes: its lookup starts at once.
    this.online = online
    this.savedPostListed = savedPostListed
    this.rateLimited = rateLimited
    startFixtureKoin()
    val koin = GlobalContext.get()
    val tabs = koin.get<TabsController>()
    runBlocking { koin.get<AuthSession>().restore() }
    tabs.open(route, background = false)
    tabs.tabs.value.filter { it.top != route }.forEach { tabs.close(it.id) }
    val tab = tabs.activeTab
    // As restored from tabs.json: the grid was left scrolled to its 45th post, on the second page.
    tab.reportPlace(route, saved)
    heldPlace = { tab.placeFor(route) }
    val messages = mutableListOf<Message>()
    scope.launch { koin.get<Messages>().messages.collect { messages += it } }
    val navigator = TabsNavigator(tabs)
    // Small enough that a page of 30 scrolls well past its first row.
    runDesktopComposeUiTest(width = 600, height = 500) {
      setContent {
        SankakuTheme {
          val activeId by tabs.active.collectAsState()
          val active = tabs.tabs.collectAsState().value.first { it.id == activeId }
          ActiveTab(active, tabs, navigator, rememberSaveableStateHolder()) { key ->
            NavEntry<NavKey>(key) { if (key is Route.Grid) GridScreen(key) }
          }
        }
      }
      test(tab, messages)
    }
  }

  private fun cell() = SemanticsMatcher("is a grid cell") {
    SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag].startsWith("grid-cell-")
  }

  private fun ComposeUiTest.cells() = onAllNodes(cell()).fetchSemanticsNodes()

  private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  /** Lets the grid's frames and Paging's background diff run until [condition]. */
  private fun ComposeUiTest.runUntil(condition: () -> Boolean) =
    waitUntil(timeoutMillis = 10_000) {
      mainClock.advanceTimeByFrame()
      placesHeld += heldPlace()
      condition()
    }

  @Test
  fun anOfflineLookupStaysPendingAndFindsThePlaceOnceTheConnectionIsBack() = restored { tab, _ ->
    runUntil { requests.get() > 0 && shows("You're offline") }
    waitForIdle()
    assertTrue(cells().isEmpty(), "no grid from the top under the pending place")
    // A wheel or swipe while it's pending has nothing to scroll and saves nothing.
    onRoot().performMouseInput { scroll(600f) }
    onRoot().performTouchInput { swipeUp() }
    runUntil { true }

    // The connection comes back: the lookup runs again by itself, with no tap.
    online = true
    GlobalContext.get().get<ConnectivityState>().requestRetry()
    runUntil { cells().isNotEmpty() && !shows("Loading where you were…") }
    waitForIdle()

    assertAtSavedPlace()
    assertEquals<Set<GridPlace?>>(setOf(saved), placesHeld, "the saved place was never overwritten")
    assertEquals(saved, tab.placeFor(route))
  }

  @Test
  fun aRateLimitedLookupWaitsForRetryNotTheConnection() = restored(online = true, rateLimited = true) { tab, _ ->
    runUntil { requests.get() > 0 && shows("Retry") }
    waitForIdle()
    assertTrue(cells().isEmpty(), "no grid from the top under the pending place")
    val asked = requests.get()

    // Only a lost connection is retried by itself: the network coming back doesn't ask again after a 429.
    rateLimited = false
    GlobalContext.get().get<ConnectivityState>().requestRetry()
    runUntil { true }
    waitForIdle()
    assertEquals(asked, requests.get(), "no request until Retry")

    onNodeWithText("Retry").performClick()
    runUntil { cells().isNotEmpty() && !shows("Loading where you were…") }
    waitForIdle()
    assertAtSavedPlace()
    assertEquals<Set<GridPlace?>>(setOf(saved), placesHeld, "the saved place was never overwritten")
    assertEquals(saved, tab.placeFor(route))
  }

  /**
   * The pager starts at the saved page, so the 45th post is its 15th item (index 14), and the grid has scrolled to it:
   * the page's first post (index 0) is off the top. A staggered grid's other lanes may show the items just before it.
   */
  private fun ComposeUiTest.assertAtSavedPlace() {
    val shown = shownIndices()
    assertTrue(14 in shown, "the grid shows the saved post: $shown")
    assertTrue(0 !in shown, "the grid has scrolled to it: $shown")
  }

  private fun ComposeUiTest.shownIndices() =
    cells().map { it.config[SemanticsProperties.TestTag].removePrefix("grid-cell-").toInt() }.toSet()

  @Test
  fun aPlaceThatIsGoneSaysSoAndTryAgainLooksAgain() = restored(online = true, savedPostListed = false) { tab, messages ->
    runUntil { messages.isNotEmpty() && cells().isNotEmpty() }
    waitForIdle()
    assertTrue(0 in shownIndices(), "given up, the grid starts at the top")

    val message = messages.single()
    assertEquals("Couldn't get back to where you were", message.text)
    assertEquals("Try again", message.actionLabel)
    assertEquals(saved, tab.placeFor(route), "given up, the place stays until the grid is scrolled")

    // The post is listed again (the filter changed back): Try again finds it.
    savedPostListed = true
    message.onAction()
    runUntil { 0 !in shownIndices() && cells().isNotEmpty() && !shows("Loading where you were…") }
    waitForIdle()
    assertAtSavedPlace()
    assertEquals(saved.post, tab.placeFor(route)?.post, "found again, the place is the saved one")
  }
}
