package dev.marzahn.sankaku.features.books

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.centerLeft
import androidx.compose.ui.test.centerRight
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import kotlin.math.abs
import kotlin.test.assertTrue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import coil3.ImageLoader
import coil3.PlatformContext
import com.github.panpf.zoomimage.util.setMainThreadChecker
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.PlayerPreferences
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #382: the reader over fixture results, no network. ← → (A/D) and swipes move between the results, the end of a
 * series' book is a "Next" card that continues into the next book, and Esc closes through the transition.
 */
@OptIn(ExperimentalTestApi::class)
class BookPagingTest {
  private val reader = BookReader { _, _ -> }
  private val seriesAsked = mutableListOf<String>()
  private val booksAsked = mutableListOf<String>()

  private fun startFixtureKoin() {
    // zoomimage checks its gestures run on the main thread; a UI test sends them from the test's thread.
    setMainThreadChecker { true }
    val cache = Files.createTempDirectory("book-paging").toFile()
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
          val body = when {
            path == "/graphql" -> SERIES_JSON.also { seriesAsked += path }
            path.startsWith("/pools/") -> path.removePrefix("/pools/").let { booksAsked += it; bookJson(it) }
            else -> """{ "meta": {}, "data": [] }"""
          }
          respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
      }
      single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
      single { PlayerPreferences(get(PLAYER_FILE)) }
      single<Navigator> {
        object : Navigator {
          override fun goTo(route: Route) = Unit

          override fun goBack() = Unit

          override fun replaceTop(route: Route) = Unit

          override fun openInNewTab(route: Route): Int? = null
        }
      }
    }
    startKoin { modules(sharedModule, platform, booksModule, authModule, fixtures, module { single { Messages() } }) }
  }

  @AfterTest
  fun stop() = stopKoin()

  /** Results as a list shows them: [books], each with the series of [seriesOf], if any. */
  private class FixtureResults(private val books: List<ResultBook>, override val seriesId: String? = null) : BookResults() {
    override val count get() = books.size
    override val locked get() = false

    override fun bookAt(index: Int) = books.getOrNull(index)

    override fun peek(index: Int) = books.getOrNull(index)

    override suspend fun reveal(index: Int) = Unit
  }

  private fun ComposeUiTest.open(results: BookResults, index: Int = 0) {
    startFixtureKoin()
    setContent { BookReaderPage(reader) }
    reader.open(results, index)
    waitForPages(results.peek(index)!!.book.id)
  }

  /** Until the book [id]'s first page is laid out (the books either side are too, so a title doesn't say which). */
  private fun ComposeUiTest.waitForPages(id: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithContentDescription("Page 1 of 2").fetchSemanticsNodes().isNotEmpty() && reader.shown.value?.id == id
    }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
  }

  private fun ComposeUiTest.key(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
  }

  /** Scrolls the current book's strip to its end. */
  private fun ComposeUiTest.toEnd() = repeat(3) { key(Key.PageDown) }

  private fun ComposeUiTest.nextVolumes() = onAllNodesWithContentDescription("$NEXT_VOLUME:", substring = true).fetchSemanticsNodes().size

  private fun ComposeUiTest.nextResults() = onAllNodesWithContentDescription("$NEXT_RESULT:", substring = true).fetchSemanticsNodes().size

  private val where get() = reader.session.value!!.let { it.book.id to it.index }

  @Test
  fun arrowsAndADMoveBetweenTheResults() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.map { ResultBook(it) }))
    // The first result: ← goes nowhere.
    key(Key.DirectionLeft)
    assertEquals("b1" to 0, where)
    key(Key.DirectionRight)
    waitForPages("b2")
    assertEquals("b2" to 1, where)
    key(Key.D)
    waitForPages("b3")
    // The last result: → goes nowhere.
    key(Key.DirectionRight)
    assertEquals("b3" to 2, where)
    key(Key.A)
    key(Key.DirectionLeft)
    waitForPages("b1")
    assertEquals("b1" to 0, where)
  }

  @Test
  fun sidewaysSwipesMoveBetweenTheResults() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.map { ResultBook(it) }), index = 1)
    onRoot().performTouchInput { swipeLeft() }
    mainClock.advanceTimeBy(1_000)
    waitForPages("b3")
    assertEquals("b3" to 2, where)
    onRoot().performTouchInput { swipeRight() }
    mainClock.advanceTimeBy(1_000)
    waitForPages("b2")
    assertEquals("b2" to 1, where)
    // The book it came from stayed ready: swiping there and back asked for neither again. (Book one, two away while on
    // book three, left the pager's window and is asked for again as a neighbour.)
    assertEquals(1, booksAsked.count { it == "b2" })
    assertEquals(1, booksAsked.count { it == "b3" })
  }

  // #503: every book ends with the end card: "Finished", the next volume when the series is known, the next result,
  // and Back to Books.

  @Test
  fun aSeriesListsBookEndsWithTheNextVolumeWhichPagesToIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.take(2).map { ResultBook(it, "s1") }, seriesId = "s1"))
    toEnd()
    onNodeWithText(FINISHED).assertExists()
    // In a series' list the next result is the next volume: offered once.
    assertEquals(0, nextResults())
    onNodeWithContentDescription(nextVolumeLabel("Book two")).performClick()
    mainClock.advanceTimeBy(1_000)
    waitForPages("b2")
    assertEquals("b2" to 1, where)
    // The series' list has its books: the series isn't asked for.
    assertEquals(emptyList(), seriesAsked)
  }

  @Test
  fun aSeriesRowsBookContinuesIntoTheNextVolumeInPlace() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    // A series' row shows one of its books; the books after it come from the series.
    open(FixtureResults(listOf(ResultBook(THREE[0], "s1"), ResultBook(THREE[2]))))
    toEnd()
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription(nextVolumeLabel("Book two")).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithContentDescription(nextResultLabel("Book three")).assertExists()
    onNodeWithContentDescription(nextVolumeLabel("Book two")).performClick()
    mainClock.advanceTimeBy(1_000)
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Book two").fetchSemanticsNodes().isNotEmpty() }
    // The series is the last there is: Book two ends with no next volume. It's still the series' row: its next result
    // is the result after it.
    assertEquals("b2" to 0, where, "the reader shows the next book, still at the series row")
    toEnd()
    assertEquals(0, nextVolumes())
    onNodeWithContentDescription(nextResultLabel("Book three")).performClick()
    mainClock.advanceTimeBy(1_000)
    waitForPages("b3")
    assertEquals("b3" to 1, where)
  }

  @Test
  fun aBookNotInASeriesEndsWithTheNextResult() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.map { ResultBook(it) }))
    toEnd()
    onNodeWithText(FINISHED).assertExists()
    assertEquals(0, nextVolumes())
    onNodeWithContentDescription(nextResultLabel("Book two")).performClick()
    mainClock.advanceTimeBy(1_000)
    waitForPages("b2")
    assertEquals("b2" to 1, where)
    assertEquals(emptyList(), seriesAsked)
  }

  @Test
  fun theLastResultEndsWithBackToBooks() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.map { ResultBook(it) }), index = 2)
    toEnd()
    assertEquals(0, nextVolumes())
    assertEquals(0, nextResults())
    onNodeWithText(BACK_TO_BOOKS).performClick()
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    assertEquals(null, reader.session.value, "Back to Books closes the reader")
  }

  /** Where the current book's page [description] is in the window: the books either side have pages of that name too. */
  private fun ComposeUiTest.shownPage(description: String): Rect =
    onAllNodesWithContentDescription(description).fetchSemanticsNodes().map { node ->
      // With the zoom and the pager's offset, unclipped by the viewport.
      val coordinates = node.layoutInfo.coordinates
      val size = coordinates.size
      Rect(coordinates.localToRoot(Offset.Zero), coordinates.localToRoot(Offset(size.width.toFloat(), size.height.toFloat())))
    }.minBy { abs(it.center.x - WIDTH / 2f) }

  @Test
  fun aDragDownTheStripScrollsItAndStaysOnTheBook() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.map { ResultBook(it) }), index = 1)
    val before = shownPage("Page 1 of 2")
    // Mostly up, a little sideways, as a thumb drags.
    onRoot().performTouchInput { swipe(center + Offset(20f, 150f), center - Offset(20f, 150f), durationMillis = 300) }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals("b2" to 1, where)
    val after = shownPage("Page 1 of 2")
    assertTrue(after.top < before.top - 150f, "scrolled: ${before.top} → ${after.top}")
    assertEquals(before.left, after.left, 1f, "not moved sideways")
  }

  @Test
  fun aZoomedStripPansBeforeASwipeMovesOn() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.map { ResultBook(it) }), index = 1)
    onRoot().performTouchInput {
      val c = center
      pinch(c - Offset(20f, 0f), c - Offset(200f, 0f), c + Offset(20f, 0f), c + Offset(200f, 0f))
    }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals("b2" to 1, where, "a pinch isn't a swipe")
    val zoomed = shownPage("Page 1 of 2")
    // A sideways drag pans the zoomed strip: the same book, moved over.
    onRoot().performTouchInput { swipe(center, center - Offset(150f, 0f), durationMillis = 300) }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals("b2" to 1, where, "panned, not paged")
    assertTrue(shownPage("Page 1 of 2").left < zoomed.left - 100f, "panned left")
    // At the strip's edge, the next swipe is the pager's.
    repeat(4) {
      onRoot().performTouchInput { swipe(centerRight - Offset(20f, 0f), centerLeft + Offset(20f, 0f), durationMillis = 200) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
    }
    assertEquals("b3" to 2, where)
  }

  @Test
  fun escClosesTheReader() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    open(FixtureResults(THREE.map { ResultBook(it) }))
    key(Key.DirectionRight)
    waitForPages("b2")
    key(Key.Escape)
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    assertNull(reader.session.value)
    assertEquals(0, onAllNodesWithText("Book two").fetchSemanticsNodes().size)
  }

  private companion object {
    const val WIDTH = 600
    const val HEIGHT = 1000

    val THREE = listOf(
      BookDto(id = "b1", nameEn = "Book one", rating = "s", postCount = 2),
      BookDto(id = "b2", nameEn = "Book two", rating = "s", postCount = 2),
      BookDto(id = "b3", nameEn = "Book three", rating = "s", postCount = 2),
    )

    fun pageJson(id: String, sequence: Int) = """
      { "id": "$id", "md5": "md5-$id", "rating": "s", "status": "active", "sequence": $sequence,
        "file_type": "image/png", "width": 1000, "height": 1000, "sample_url": "https://fixture.invalid/$id.png" }
    """.trimIndent()

    fun bookJson(id: String): String {
      val book = THREE.first { it.id == id }
      return """
        { "id": "$id", "name_en": "${book.nameEn}", "rating": "s", "post_count": 2,
          "posts": [ ${pageJson("$id-0", 0)}, ${pageJson("$id-1", 1)} ] }
      """.trimIndent()
    }

    val SERIES_JSON = """
      { "data": { "series": { "id": "s1", "name_en": "Fixture series", "pool_count": 2, "books": [
        { "id": "b1", "name_en": "Book one", "rating": "s", "post_count": 2, "sequence": 1 },
        { "id": "b2", "name_en": "Book two", "rating": "s", "post_count": 2, "sequence": 2 }
      ] } } }
    """.trimIndent()
  }
}
