package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.QueuePageAndTopLine
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.ShellBox
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabContent
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.UnderQueuePage
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
import dev.marzahn.sankaku.di.gridModule
import dev.marzahn.sankaku.di.postModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
import dev.marzahn.sankaku.features.grid.GridScreen
import dev.marzahn.sankaku.features.grid.mosaicCellTag
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.rememberQueuePageTransitions
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * #322: a grid's mosaic on the queue page and that grid's first cells as one element each, through the app's own
 * chain: the shell's scope ([ShellBox]), the page's transitions, the tab's [ActiveTab] with the real [GridScreen] under
 * the page ([UnderQueuePage]) and the real [QueuePage] over it. Synthetic fixture posts with one-colour thumbnails,
 * never a real post. These check bounds and state only; how the move looks is QA'd in the app (#332).
 *
 * The window shows a grid's first two cells (one column), so of its mosaic's four thumbnails two have a cell to move to
 * and two don't.
 */
@OptIn(ExperimentalTestApi::class)
class QueueMosaicTransitionTest {
  private val png = ByteArrayOutputStream().also {
    val image = BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
      color = java.awt.Color(0xFF1565C0.toInt())
      fillRect(0, 0, 300, 400)
      dispose()
    }
    ImageIO.write(image, "png", it)
  }.toByteArray()

  private var pageOpen by mutableStateOf(false)
  private var interacted by mutableStateOf(true)

  @AfterTest
  fun tearDown() {
    SingletonImageLoader.reset()
    stopKoin()
  }

  private fun url(path: String) = "https://v.sankakucomplex.com/data/$path?e=${(Clock.System.now() + 1.hours).epochSeconds}"

  /** 30 fixture posts, rated [rating]. */
  private fun page(rating: String): String {
    val posts = (1..30).joinToString(",") { n ->
      """{"id":"fixture-$n","md5":"fixture-md5-$n","rating":"$rating","status":"active","file_type":"image/png",""" +
        """"width":300,"height":400,"preview_width":300,"preview_height":400,"preview_url":"${url("preview/fixture-$n.png")}"}"""
    }
    return """{"meta":{"next":null,"prev":null},"data":[$posts]}"""
  }

  private fun startFixtureKoin(): TabsController {
    val cache = Files.createTempDirectory("queue-mosaic").toFile()
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
          val path = request.url.encodedPath
          when {
            request.url.host == "sankakuapi.com" && path == "/v2/posts/keyset" -> {
              // An R15 grid's search names it; any other is rated G.
              val rating = if (request.url.parameters.getAll("tags").orEmpty().any { "fixture_r15" in it }) "q" else "s"
              respond(page(rating), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            path.startsWith("/data/preview/") -> respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            else -> respondError(HttpStatusCode.NotFound)
          }
        }
      }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule, fixtures) }
    SingletonImageLoader.setUnsafe(GlobalContext.get().get<ImageLoader>())
    runBlocking { GlobalContext.get().get<AuthSession>().restore() }
    return GlobalContext.get().get()
  }

  /** [grids] (the first one current) and a lone post, each its own item, and nothing else. */
  private fun TabsController.only(vararg grids: Route.Grid) {
    grids.forEachIndexed { i, grid -> open(grid, background = i > 0) }
    open(LONE_POST, background = true)
    tabs.value.filter { it.top != LONE_POST && grids.none { grid -> grid == it.top } }.forEach { close(it.id) }
  }

  /** The shell as `Shell` lays it out on a phone: the tab's content under the page, the page over it. */
  private fun ComposeUiTest.shell(tabs: TabsController) {
    val navigator = TabsNavigator(tabs)
    val koin = GlobalContext.get()
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
        SankakuTheme {
          ShellBox(shared = true, Modifier.fillMaxSize()) {
            val transitions = rememberQueuePageTransitions(pageOpen)
            val activeId by tabs.active.collectAsState()
            val tabList by tabs.tabs.collectAsState()
            val active = tabList.first { it.id == activeId }
            TabContent(shown = true, tabList) { holder ->
              UnderQueuePage(transitions) {
                ActiveTab(active, tabs, navigator, holder) { key ->
                  NavEntry<NavKey>(key) {
                    when (key) {
                      is Route.Grid -> GridScreen(key)
                      else -> Box(Modifier.fillMaxSize().background(Color.DarkGray))
                    }
                  }
                }
              }
            }
            QueuePageAndTopLine(remember { SnackbarHostState() }, transitions) { onListTop ->
              QueuePage(
                onDismiss = { pageOpen = false },
                controller = tabs,
                posts = TabPosts.None,
                privacy = koin.get<PrivacySettings>(),
                seen = null,
                interacted = { interacted },
                onInteract = {},
                onListTop = onListTop,
                leaving = !pageOpen,
              )
            }
          }
        }
      }
    }
  }

  private fun ComposeUiTest.boundsOf(tag: String): Rect? =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.boundsInRoot

  private fun ComposeUiTest.cells() = List(4) { boundsOf(mosaicCellTag(it)) }

  private fun ComposeUiTest.thumbs() = List(4) { boundsOf(mosaicThumbTag(it)) }

  /** Waits, on the real clock, for [tabs]' current grid to show its cells and report its mosaic. */
  private fun ComposeUiTest.gridLoaded(tabs: TabsController) {
    waitUntil(timeoutMillis = 5_000) { tabs.activeTab.mosaic?.thumbs?.size == 4 && boundsOf(mosaicCellTag(0)) != null }
    mainClock.autoAdvance = false
    settle()
  }

  private fun ComposeUiTest.settle() = repeat(200) { mainClock.advanceTimeByFrame() }

  private fun ComposeUiTest.tapRow(title: String) = onAllNodesWithText(title).onFirst().performClick()

  /**
   * The acceptance test: a grid's row tapped on the page. Each mosaic thumbnail whose post is among the grid's first
   * cells moves to that cell's bounds as the page closes, as one element with the cell: the cell is laid out at the
   * bounds in flight, from the thumbnail's to its own, and it's there on every frame, so no cell waits empty while a
   * thumbnail flies to it. The thumbnails with no cell on screen stay where they are and fade with the page. Before #322
   * the cells and the thumbnails had nothing to do with each other.
   */
  @Test
  fun aGridRowsMosaicMovesIntoItsFirstCellsAsThePageCloses() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
    val tabs = startFixtureKoin()
    tabs.only(GRID)
    shell(tabs)
    gridLoaded(tabs)
    val cellsAtRest = cells()
    val onScreen = cellsAtRest.indices.filter { cellsAtRest[it] != null }
    val offScreen = cellsAtRest.indices - onScreen.toSet()
    assertTrue(onScreen.isNotEmpty() && offScreen.isNotEmpty(), "some of the mosaic's posts have a cell on screen, some not: $cellsAtRest")

    // The queue page, from the pill (the current item is the grid); then another item comes forward under it, and
    // the grid's row is tapped.
    pageOpen = true
    settle()
    tabs.activate(tabs.tabs.value.first { it.top == LONE_POST }.id)
    tabs.activate(tabs.tabs.value.first { it.top == GRID }.id)
    mainClock.advanceTimeByFrame()
    val thumbsAtRest = thumbs()
    assertTrue(thumbsAtRest.all { it != null }, "the current item's row shows its four thumbnails: $thumbsAtRest")

    tapRow(tabs.tabs.value.first { it.top == GRID }.title)
    val frames = List(40) {
      mainClock.advanceTimeByFrame()
      thumbs() to cells()
    }
    frames.forEachIndexed { frame, (thumbs, cells) ->
      for (i in onScreen) assertTrue(cells[i] != null, "frame $frame: cell $i is there")
      for (i in offScreen) {
        assertEquals(null, cells[i], "frame $frame: no cell $i comes into view")
        thumbs[i]?.let { assertTrue(it.near(thumbsAtRest[i]!!), "frame $frame: thumbnail $i has no cell and stays put: $it") }
      }
    }
    for (i in onScreen) {
      val path = frames.map { (_, cells) -> cells[i]!! }
      val from = thumbsAtRest[i]!!
      val to = cellsAtRest[i]!!
      assertTrue(path.first().near(from, slack = 8f), "cell $i leaves from its thumbnail: ${path.first()}, $from")
      assertTrue(path.any { it.width > from.width * 2 && it.width < to.width * 0.8f }, "cell $i moves in between: $path")
      assertTrue(path.last().near(to), "cell $i lands where it was: ${path.last()}, $to")
    }
  }

  /** The other way, from the pill (optional in #322): the grid's cells move into its mosaic as the page opens. */
  @Test
  fun fromThePillTheCellsMoveIntoTheMosaic() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
    val tabs = startFixtureKoin()
    tabs.only(GRID)
    shell(tabs)
    gridLoaded(tabs)
    val cellsAtRest = cells()

    pageOpen = true
    val path = List(40) {
      mainClock.advanceTimeByFrame()
      cells()[0]
    }.filterNotNull()
    assertTrue(path.first().near(cellsAtRest[0]!!), "the first cell leaves from its place: ${path.first()}, ${cellsAtRest[0]}")
    assertTrue(path.any { it.width < cellsAtRest[0]!!.width * 0.5f }, "and shrinks towards its thumbnail: $path")
    settle()
    val thumb = boundsOf(mosaicThumbTag(0))!!
    assertTrue(path.last().width < thumb.width * 2, "it ends at its thumbnail: ${path.last()}, $thumb")
  }

  /**
   * A grid that hasn't loaded when the page closes (a restored item, shown for the first time) keeps the plain fade:
   * its row's thumbnails stay put on every frame, before and after its cells come, and the cells come in their place.
   */
  @Test
  fun aGridStillLoadingKeepsThePlainFade() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
    val tabs = startFixtureKoin()
    tabs.only(GRID)
    tabs.activate(tabs.tabs.value.first { it.top == LONE_POST }.id)
    // As saved at the last launch: the grid has its mosaic, and nothing loaded.
    val grid = tabs.tabs.value.first { it.top == GRID }
    grid.reportMosaic(GRID, QueueMosaic((1..4).map { QueueThumb("fixture-md5-$it", "s") }, 30))
    pageOpen = true
    shell(tabs)
    mainClock.autoAdvance = false
    settle()
    tabs.activate(grid.id)
    mainClock.advanceTimeByFrame()
    val thumbsAtRest = thumbs()
    assertTrue(thumbsAtRest.all { it != null })

    tapRow(tabs.tabs.value.first { it.top == GRID }.title)
    val cellSizes = mutableListOf<Rect>()
    repeat(40) { frame ->
      mainClock.advanceTimeByFrame()
      // Real time for the page to load meanwhile.
      Thread.sleep(10)
      thumbs().forEachIndexed { i, thumb ->
        thumb?.let { assertTrue(it.near(thumbsAtRest[i]!!), "frame $frame: thumbnail $i stays put: $it") }
      }
      cells()[0]?.let(cellSizes::add)
    }
    settle()
    val landed = cells()[0]
    assertTrue(landed != null, "the grid loaded meanwhile")
    assertTrue(cellSizes.all { it.near(landed) }, "its cells come in their place, never in flight: $cellSizes")
  }

  /** An R15 grid's blurred mosaic (#164) never moves: the page opens and closes over its cells with the plain fade. */
  @Test
  fun aBlurredMosaicNeverMoves() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
    val tabs = startFixtureKoin()
    tabs.only(R15_GRID)
    interacted = false
    shell(tabs)
    gridLoaded(tabs)
    val cellsAtRest = cells()

    pageOpen = true
    repeat(40) { frame ->
      mainClock.advanceTimeByFrame()
      cells()[0]?.let { assertTrue(it.near(cellsAtRest[0]!!), "frame $frame: the cell stays put as the page opens: $it") }
    }
    settle()
    assertTrue(onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size >= 4, "the mosaic is blurred")
    assertEquals(0, thumbs().count { it != null }, "no thumbnail is shared")

    pageOpen = false
    repeat(40) { frame ->
      mainClock.advanceTimeByFrame()
      cells()[0]?.let { assertTrue(it.near(cellsAtRest[0]!!), "frame $frame: the cell stays put as the page closes: $it") }
    }
  }

  private fun Rect.near(other: Rect, slack: Float = 2f) =
    abs(left - other.left) <= slack && abs(top - other.top) <= slack && abs(right - other.right) <= slack && abs(bottom - other.bottom) <= slack

  private companion object {
    val GRID = Route.Grid(listOf("fixture_tag"))
    val R15_GRID = Route.Grid(listOf("fixture_r15"))
    val LONE_POST = Route.Post("fixture-lone", "fixture-lone-md5", emptyList(), "Lone")

    // A phone's 411 dp width: one column of cells, two of them in view. At 1 px per dp, so each frame costs little to
    // draw: what's checked is bounds, never pixels.
    const val WIDTH = 411
    const val HEIGHT = 610
    const val DENSITY = 1f
  }
}
