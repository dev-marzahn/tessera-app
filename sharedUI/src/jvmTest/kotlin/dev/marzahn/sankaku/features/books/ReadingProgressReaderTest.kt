package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import com.github.panpf.zoomimage.util.setMainThreadChecker
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import androidx.compose.runtime.remember
import dev.marzahn.sankaku.tabs.ClosedTabUndo
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.UpNextState
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.data.SavedReading
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * #384: reading progress through the real [BookReader] and [dev.marzahn.sankaku.data.ReadingProgress], signed in, over
 * a fake `/pools/{id}` that records each `PUT /pools/{id}/reading`. It's sent once per trigger (a close, a move to
 * another book, the app going to the background) and never per page; a saved place offers the chip.
 */
@OptIn(ExperimentalTestApi::class)
class ReadingProgressReaderTest {
  /** The sequence of each PUT, by book id, in the order sent. */
  private val puts = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
  private var bookJson = BookReaderTest.BOOK_JSON

  private val lifecycle = object : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
  }

  private fun startFixtureKoin(signedIn: Boolean = true, readings: String? = null) {
    setMainThreadChecker { true }
    val cache = Files.createTempDirectory("reading-progress").toFile()
    val now = Clock.System.now()
    val tokens = StoredTokens("access", "refresh", now + 7.days, now + 90.days, UserSummary(id = "42", name = "tester", avatarUrl = null))
    val platform = module {
      single<TokenStore> { InMemoryTokenStore(tokens.takeIf { signedIn }) }
      single { TokenStorageStatus.InMemory }
      single { DebugFlags() }
      single(CACHE_DIR) { cache.toOkioPath() }
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, PRIVACY_FILE, ACCESSIBILITY_FILE, QUEUE_FILE, GRID_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, FIRST_LAUNCH_FILE, OUTBOX_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TextFile>(READING_FILE) { InMemoryTextFile(readings) }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<SaveFolder> { DirectorySaveFolder(cache, opener = {}) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    val fixtures = module {
      single<HttpClientEngine> {
        MockEngine { request ->
          val path = request.url.encodedPath
          val body = when {
            request.method == HttpMethod.Put && path.endsWith("/reading") -> {
              val sequence = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["sequence"]!!.jsonPrimitive.content.toInt()
              puts += path.removePrefix("/pools/").removeSuffix("/reading") to sequence
              """{ "success": true }"""
            }
            path.startsWith("/pools/") -> bookJson
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

  private fun reader(): BookReader = KoinPlatform.getKoin().get()

  /** Opens the fixture book, waiting for its strip to show [page], where it opens. */
  private fun ComposeUiTest.openBook(json: String = BookReaderTest.BOOK_JSON, signedIn: Boolean = true, readings: String? = null, page: Int = 1) {
    bookJson = json
    startFixtureKoin(signedIn, readings)
    setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { BookReaderPage() }
    }
    runOnIdle { reader().open(BookReaderTest.BOOK, "Fixture") }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Page $page of 4").fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
  }

  private fun ComposeUiTest.press(key: Key, times: Int = 1) = repeat(times) {
    onRoot().performKeyInput { pressKey(key) }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
  }

  /** Waits for the sends to arrive, then a little longer, so one more would be counted too. */
  private fun ComposeUiTest.sent(count: Int): List<Pair<String, Int>> {
    waitUntil(timeoutMillis = 5_000) { puts.size >= count }
    Thread.sleep(200)
    waitForIdle()
    return puts.toList()
  }

  @Test
  fun progressIsSentOncePerTriggerAndNeverPerPage() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook()
    // Through every page to the end: nothing is sent on the way.
    press(Key.Spacebar, times = 6)
    Thread.sleep(200)
    assertTrue(puts.isEmpty(), "sent per page: $puts")

    // The app goes to the background: once, with the last page, the one taking most of the screen.
    runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
    assertEquals(listOf(BookReaderTest.BOOK to 3), sent(1))
    // Back, and to the background again without moving: nothing new to send.
    runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
    runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
    runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
    assertEquals(1, sent(1).size)

    // Back up to page 1 and closed: once more, with page 1.
    press(Key.PageUp, times = 6)
    press(Key.Escape)
    assertEquals(listOf(BookReaderTest.BOOK to 3, BookReaderTest.BOOK to 0), sent(2))
  }

  @Test
  fun aKeptBooksTabSendsItsProgressInTheBackgroundAndWhenLeft() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    bookJson = BookReaderTest.BOOK_JSON
    startFixtureKoin()
    var shown by mutableStateOf(true)
    setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
        // #385: a kept book's tab, with no tab entry around it here.
        if (shown) {
          val tabs = remember { TabsController() }
          KeptBookScreen(
            Route.Book(BookReaderTest.BOOK, "Fixture"),
            tabs = tabs,
            undo = remember { ClosedTabUndo(tabs) },
            upNext = remember { UpNextState(tabs, KoinPlatform.getKoin().get(), Messages()) },
          )
        }
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Page 1 of 4").fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
    // The tab keeps the keys only once it's clicked into.
    onRoot().performClick()
    press(Key.Spacebar, times = 6)
    Thread.sleep(200)
    assertTrue(puts.isEmpty(), "sent per page: $puts")

    runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
    assertEquals(listOf(BookReaderTest.BOOK to 3), sent(1))
    runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }

    // Back to page 1, then the tab leaves (closed, or another shown): once more.
    press(Key.PageUp, times = 6)
    runOnIdle { shown = false }
    assertEquals(listOf(BookReaderTest.BOOK to 3, BookReaderTest.BOOK to 0), sent(2))
  }

  @Test
  fun aMoveToAnotherBookSendsTheOneLeftOnce() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook()
    press(Key.Spacebar, times = 2)
    runOnIdle { reader().open("other", "Other") }
    val sent = sent(1)
    assertEquals(1, sent.size)
    assertEquals(BookReaderTest.BOOK, sent.single().first)
    assertTrue(sent.single().second > 0, "a page past the first: $sent")
    // The other book was only opened: closing it sends nothing.
    runOnIdle { reader().close() }
    assertEquals(1, sent(1).size)
  }

  // #503: a book with a saved place opens there, "Page 3 · Start over" under it; a look (opened and closed without
  // moving) keeps the place, signed in or out (#490).

  @Test
  fun aSavedBookOpensAtItsPlaceAndALookKeepsIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook(SAVED_BOOK_JSON, page = 3)
    assertEquals(0f, onNodeWithContentDescription("Page 3 of 4").getBoundsInRoot().top.value, 0.5f)
    onNodeWithText(resumeLabel(3)).assertExists()
    onNodeWithText(START_OVER).assertExists()
    press(Key.Escape)
    Thread.sleep(200)
    assertTrue(puts.isEmpty(), "a look overwrote the saved place: $puts")
    // Nothing saved this run either, over the server's place.
    assertEquals(null, savedSequence(signedIn = true))
  }

  @Test
  fun signedOutASavedBookOpensAtItsPlaceAndALookKeepsIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook(signedIn = false, readings = SAVED_READINGS, page = 3)
    assertEquals(0f, onNodeWithContentDescription("Page 3 of 4").getBoundsInRoot().top.value, 0.5f)
    onNodeWithText(resumeLabel(3)).assertExists()
    press(Key.Escape)
    assertEquals(2, savedSequence(signedIn = false))
  }

  @Test
  fun startOverGoesToPage1AndSavesIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook(SAVED_BOOK_JSON, page = 3)
    onNodeWithText(START_OVER).performClick()
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0f, onNodeWithContentDescription("Page 1 of 4").getBoundsInRoot().top.value, 0.5f)
    assertTrue(onAllNodesWithText(START_OVER).fetchSemanticsNodes().isEmpty(), "the chip goes once used")
    press(Key.Escape)
    assertEquals(listOf(BookReaderTest.BOOK to 0), sent(1))
  }

  @Test
  fun aBookOpenedOnItsOwnEndsWithFinishedAndClose() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    // #503: no list, so no next result and no next volume; the way back is the reader's close.
    openBook()
    press(Key.Spacebar, times = 8)
    onNodeWithText(FINISHED).assertExists()
    assertTrue(onAllNodesWithText(BACK_TO_BOOKS).fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithContentDescription("$NEXT_RESULT:", substring = true).fetchSemanticsNodes().isEmpty())
    onNodeWithText(CLOSE_BOOK).performClick()
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    assertEquals(null, reader().session.value)
  }

  @Test
  fun aFinishedBookOpensAtPage1WithNoChip() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook(FINISHED_BOOK_JSON)
    assertEquals(0f, onNodeWithContentDescription("Page 1 of 4").getBoundsInRoot().top.value, 0.5f)
    assertTrue(onAllNodesWithText(START_OVER).fetchSemanticsNodes().isEmpty())
  }

  // What's saved here (a book with no `reading` shows none from the server).
  private fun savedSequence(signedIn: Boolean): Int? =
    KoinPlatform.getKoin().get<ReadingProgress>().savedOf(BookDto(id = BookReaderTest.BOOK, postCount = 5), signedIn)?.sequence

  @Test
  fun signedInAfterStartOverThePlaceFollowsTheReader() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook(SAVED_BOOK_JSON, page = 3)
    onNodeWithText(START_OVER).performClick()
    mainClock.advanceTimeBy(1_000)
    press(Key.Spacebar)
    press(Key.Escape)
    assertEquals(listOf(BookReaderTest.BOOK to 1), sent(1))
  }

  @Test
  fun signedOutAfterStartOverThePlaceFollowsTheReader() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook(signedIn = false, readings = SAVED_READINGS, page = 3)
    onNodeWithText(START_OVER).performClick()
    mainClock.advanceTimeBy(1_000)
    press(Key.Spacebar)
    press(Key.Escape)
    assertEquals(1, savedSequence(signedIn = false))
  }

  @Test
  fun readingOnFromTheSavedPageSavesTheNewPlace() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    openBook(SAVED_BOOK_JSON, page = 3)
    press(Key.Spacebar, times = 6)
    assertTrue(onAllNodesWithText(resumeLabel(3)).fetchSemanticsNodes().isEmpty(), "the chip goes once the strip moves")
    press(Key.Escape)
    assertEquals(listOf(BookReaderTest.BOOK to 3), sent(1))
  }

  @Test
  fun aCoverShowsABarWhenStartedAndReadWhenFinished() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    startFixtureKoin()
    val book = BookDto(id = "b1", nameEn = "A book", postCount = 10)
    var reading: SavedReading? by mutableStateOf(SavedReading(sequence = 3, page = 4, pages = 10, finished = false))
    setContent {
      BookRow(book, book.rowText(reading), locked = true, signedIn = true, onOpen = {}, onOpenInNewTab = {}, modifier = Modifier.width(400.dp))
    }
    waitForIdle()
    assertEquals(1, onAllNodesWithTag(COVER_PROGRESS_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    assertTrue(onAllNodesWithTag(COVER_READ_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    onNodeWithText("Page 4 / 10", substring = true, useUnmergedTree = true).assertExists()

    reading = SavedReading(sequence = 9, page = 10, pages = 10, finished = true)
    waitForIdle()
    assertTrue(onAllNodesWithTag(COVER_PROGRESS_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    assertEquals(1, onAllNodesWithTag(COVER_READ_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)

    reading = null
    waitForIdle()
    assertTrue(onAllNodesWithTag(COVER_PROGRESS_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithTag(COVER_READ_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
  }

  private companion object {
    const val WIDTH = BookReaderTest.WIDTH
    const val HEIGHT = BookReaderTest.HEIGHT

    // The fixture book, saved at its third page (sequence 2).
    val SAVED_BOOK_JSON = BookReaderTest.BOOK_JSON.replaceFirst(
      "\"post_count\": 5,",
      "\"post_count\": 5, \"reading\": { \"current_page\": 3, \"sequence\": 2, \"percent\": 60 },",
    )

    // The fixture book, read: saved at its last page.
    val FINISHED_BOOK_JSON = BookReaderTest.BOOK_JSON.replaceFirst(
      "\"post_count\": 5,",
      "\"post_count\": 5, \"reading\": { \"current_page\": 5, \"sequence\": 4, \"percent\": 100 },",
    )

    // Signed out, the fixture book kept on this device at its third page.
    val SAVED_READINGS = """{ "version": 1, "books": [{ "i": "${BookReaderTest.BOOK}", "s": 2, "p": 3, "n": 4 }] }"""
  }
}
