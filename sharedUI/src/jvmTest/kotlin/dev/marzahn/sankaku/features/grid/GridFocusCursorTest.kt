package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import dev.marzahn.sankaku.ui.components.KeyFocus
import dev.marzahn.sankaku.ui.components.LocalKeyFocus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
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
import dev.marzahn.sankaku.auth.AuthSession
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
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.shell.GridRefresh
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
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #418: the grid has one selection. A cell taking keyboard focus (Tab, Shift-Tab) takes the cursor's ring, and the
 * cursor moved with WASD takes focus with it, so Space opens the ring's post and Tab goes on from the ring. The real
 * [GridScreen] in its tab, with synthetic fixture posts (no pictures), never a real one.
 */
@OptIn(ExperimentalTestApi::class)
class GridFocusCursorTest {
  // How many posts the search returns: a refresh after changing it reloads the grid shorter or empty (#488).
  @Volatile private var posts = POSTS

  // Search requests answered, so a reload is known to have landed.
  @Volatile private var searches = 0

  private fun page() = (1..posts).joinToString(",","""{"meta":{"next":null,"prev":null},"data":[""", "]}") { n ->
    """{"id":"fixture-$n","md5":"fixture-md5-$n","rating":"s","status":"active","file_type":"image/png",""" +
      """"width":300,"height":300,"preview_width":300,"preview_height":300,"preview_url":"${url("preview/fixture-$n.png")}"}"""
  }

  // A one-colour picture, so no cell shows a Retry button (a second focus stop).
  private val png = ByteArrayOutputStream().also {
    ImageIO.write(BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", it)
  }.toByteArray()

  private fun url(path: String) =
    "https://v.sankakucomplex.com/data/$path?e=${(Clock.System.now() + 1.hours).epochSeconds}"

  private val keyFocus = KeyFocus()

  private fun startFixtureKoin() {
    val cache = Files.createTempDirectory("grid-focus").toFile().apply { deleteOnExit() }
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
            searches++
            respond(page(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
          } else if (request.url.encodedPath.startsWith("/data/preview/")) {
            respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
          } else {
            respondError(HttpStatusCode.NotFound)
          }
        }
      }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule, fixtures) }
    // The cells' AsyncImage uses the singleton, which the app sets to this loader.
    SingletonImageLoader.setUnsafe(GlobalContext.get().get<ImageLoader>())
  }

  @AfterTest
  fun stop() {
    SingletonImageLoader.reset()
    stopKoin()
  }

  private fun grid(test: ComposeUiTest.() -> Unit) {
    startFixtureKoin()
    val koin = GlobalContext.get()
    val tabs = koin.get<TabsController>()
    runBlocking { koin.get<AuthSession>().restore() }
    val route = Route.Grid(listOf("fixture_tag"))
    tabs.open(route, background = false)
    tabs.tabs.value.filter { it.top != route }.forEach { tabs.close(it.id) }
    val navigator = TabsNavigator(tabs)
    runDesktopComposeUiTest(width = 1200, height = 900) {
      setContent {
        CompositionLocalProvider(LocalKeyFocus provides keyFocus) {
          SankakuTheme {
          val activeId by tabs.active.collectAsState()
          val active = tabs.tabs.collectAsState().value.first { it.id == activeId }
          ActiveTab(active, tabs, navigator, rememberSaveableStateHolder()) { key ->
            NavEntry<NavKey>(key) { if (key is Route.Grid) GridScreen(key) }
          }
          }
        }
      }
      waitUntil(timeoutMillis = 5_000) { onAllNodes(cell()).fetchSemanticsNodes().isNotEmpty() }
      test()
    }
  }

  private fun cell() = SemanticsMatcher("is a grid cell") {
    SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag].startsWith("grid-cell-")
  }

  private val hasCursor = isSelected()

  private fun ComposeUiTest.index(matcher: SemanticsMatcher): Int? =
    onAllNodes(cell() and matcher).fetchSemanticsNodes().singleOrNull()
      ?.let { it.config[SemanticsProperties.TestTag].removePrefix("grid-cell-").toInt() }

  /** The cursor's cell, after checking that the focused cell, if any, is that one. */
  private fun ComposeUiTest.selection(): Int? {
    waitForIdle()
    val rings = onAllNodes(cell() and hasCursor).fetchSemanticsNodes().size
    assertTrue(rings <= 1, "$rings rings")
    val cursor = index(hasCursor)
    assertEquals(cursor, index(isFocused()), "focus and the cursor's ring are on different cells")
    return cursor
  }

  private fun ComposeUiTest.press(key: Key, shift: Boolean = false) {
    onRoot().performKeyInput { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
  }

  @Test
  fun tabOntoACellPutsTheRingThereAndSpaceOpensIt() = grid {
    assertNull(index(hasCursor))
    press(Key.Tab)
    val first = selection()
    assertEquals(0, first, "Tab from the grid's start focuses its first cell")
    press(Key.Tab)
    val second = selection()
    assertNotEquals(first, second, "Tab moves the ring")
    press(Key.Tab, shift = true)
    assertEquals(first, selection(), "Shift-Tab moves it back")
    press(Key.Tab)
    press(Key.Spacebar)
    waitForIdle()
    onAllNodesWithText(positionLabel(second!!, POSTS, hasMore = false)).fetchSemanticsNodes().single()
  }

  @Test
  fun wasdMovesFocusWithTheCursorAndTabGoesOnFromIt() = grid {
    press(Key.D)
    assertEquals(0, selection(), "the first press shows the cursor on the first cell, focused")
    press(Key.D)
    press(Key.S)
    val moved = selection()!!
    assertNotEquals(0, moved)
    press(Key.Tab)
    val next = selection()!!
    assertEquals(moved + 1, next, "Tab goes on from the cursor's cell")
    press(Key.A)
    val back = selection()
    assertNotEquals(next, back)
    // Space opens the viewer on the ring's post.
    press(Key.Spacebar)
    waitForIdle()
    onAllNodesWithText(positionLabel(back!!, POSTS, hasMore = false)).fetchSemanticsNodes().single()
    // The viewer steps on; closing it leaves the ring and focus together on the post it closed on.
    press(Key.D)
    press(Key.Escape)
    waitForIdle()
    assertEquals(back + 1, selection(), "the ring and focus are on the post the viewer closed on")
    press(Key.Tab)
    assertEquals(back + 2, selection(), "Tab goes on from there")
  }

  @Test
  fun keysHandedBackToTheGridGoToTheCursorsCell() = grid {
    press(Key.D)
    press(Key.S)
    val at = selection()!!
    // What the search panel closing or Esc in the tag list does: the screen's KeyFocusHolder asks for the keys.
    runOnIdle { keyFocus.returnToContent() }
    assertEquals(at, selection(), "focus comes back to the cursor's cell")
    press(Key.Tab)
    assertEquals(at + 1, selection(), "Tab goes on from the cursor's cell, not the grid's top")
  }

  @Test
  fun keysComingBackLeaveAScrolledAwayCursorCellUnfocused() = grid {
    press(Key.D)
    assertEquals(0, selection())
    onNode(hasScrollToIndexAction()).performScrollToIndex(POSTS - 1)
    waitForIdle()
    runOnIdle { keyFocus.returnToContent() }
    waitForIdle()
    // Back in view later, the cursor's cell doesn't take the focus from what has it.
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    waitForIdle()
    assertNull(index(isFocused()), "a stale request took the focus when the cell came back")
  }

  /** Reloads the grid past the page cache, as the top bar's Refresh does, with [count] posts, and waits for it. */
  private fun ComposeUiTest.reloadWith(count: Int) {
    posts = count
    val before = searches
    val refresh = GlobalContext.get().get<GridRefresh>()
    runOnIdle { refresh.request() }
    waitUntil(timeoutMillis = 5_000) { searches > before && !refresh.running.value }
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(cell()).fetchSemanticsNodes().size.let { it <= count && (it == 0) == (count == 0) }
    }
    // The focused cell went with the posts: the keys come back to the grid, as after a search from the panel.
    runOnIdle { keyFocus.returnToContent() }
    waitForIdle()
  }

  // #488: a cursor left past the end of a grid that reloaded with fewer posts (or none) crashed every key but a
  // move. Each key does its own job, or nothing, and a move puts the cursor back on a cell there is.
  @Test
  fun everyKeyIsSafeWithACursorPastTheEndOfAShorterGrid() = grid {
    press(Key.D)
    repeat(4) { press(Key.S) }
    val at = selection()!!
    reloadWith(at / 2)
    for (key in GRID_KEYS) press(key)
    press(Key.D)
    val moved = selection()
    assertTrue(moved != null && moved < at / 2, "the cursor is on $moved, not a cell of the shorter grid")
  }

  @Test
  fun everyKeyIsSafeWithACursorOnAGridThatReloadedEmpty() = grid {
    press(Key.D)
    press(Key.D)
    assertEquals(1, selection())
    reloadWith(0)
    for (key in GRID_KEYS + Key.D) press(key)
    // The empty grid dropped the cursor: the posts coming back don't bring the ring back on the old index.
    reloadWith(POSTS)
    assertNull(index(hasCursor), "the cursor outlived the empty grid")
  }

  private companion object {
    const val POSTS = 30

    /** Every key the grid or what's around it takes, besides the moves (D is one; Esc last, as it goes back). */
    val GRID_KEYS = listOf(Key.E, Key.Spacebar, Key.X, Key.F, Key.I, Key.G, Key.Escape)
  }
}
