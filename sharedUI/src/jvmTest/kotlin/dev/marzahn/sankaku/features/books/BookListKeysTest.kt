package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.runtime.CompositionLocalProvider
import dev.marzahn.sankaku.ui.components.KeyFocus
import dev.marzahn.sankaku.ui.components.LocalKeyFocus
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.withKeyDown
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
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.PlayerPreferences
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.Messages
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #417: the Books list's keyboard cursor over fixture books, no network. WASD move it, Space opens the reader, E keeps
 * the book and F favourites it; closing the reader gives the list its keys back, with the cursor on the book it closed
 * on. State and semantics only: the row under the cursor is the selected one.
 */
@OptIn(ExperimentalTestApi::class)
class BookListKeysTest {
  private val reader = BookReader { _, _ -> }
  private val keyFocus = KeyFocus()
  private val kept = mutableListOf<Route>()
  private val opened = mutableListOf<Route>()
  // Keys that no screen took and reached the window, as the shell's X, Z, `/` and Shift+A/D do.
  private val shellKeys = mutableListOf<Key>()
  private var listJson = LIST_JSON

  private fun startFixtureKoin() {
    // zoomimage checks its gestures run on the main thread; a UI test sends them from the test's thread.
    setMainThreadChecker { true }
    val cache = Files.createTempDirectory("book-keys").toFile()
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
            path == "/poolseriesv2" -> if ((request.url.parameters["page"] ?: "1") == "1") listJson else EMPTY_LIST_JSON
            path == "/graphql" -> SERIES_JSON
            path.startsWith("/pools/") -> bookJson(path.removePrefix("/pools/"))
            else -> """{ "meta": {}, "data": [] }"""
          }
          respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
      }
      single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
      single { PlayerPreferences(get(PLAYER_FILE)) }
      single { TabsController() }
      single<Navigator> {
        object : Navigator {
          override fun goTo(route: Route) {
            opened += route
          }

          override fun goBack() = Unit

          override fun replaceTop(route: Route) = Unit

          override fun openInNewTab(route: Route): Int? = null.also { kept += route }
        }
      }
    }
    startKoin { modules(sharedModule, platform, booksModule, authModule, fixtures, module { single { Messages() } }) }
  }

  @AfterTest
  fun stop() = stopKoin()

  private fun ComposeUiTest.showList(series: Boolean = false) {
    startFixtureKoin()
    setContent {
      CompositionLocalProvider(LocalKeyFocus provides keyFocus) {
        Box(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown) shellKeys += it.key; false }) {
          if (series) BookSeriesScreen(Route.BookSeries("s1", "Fixture series"), reader = reader) else BookListScreen(Route.Books(), reader = reader)
          BookReaderPage(reader)
        }
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(BOOK_ROW_TAG).fetchSemanticsNodes().size >= 3 }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
  }

  private fun ComposeUiTest.key(key: Key, shift: Boolean = false) {
    onRoot().performKeyInput { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
  }

  /** The title of the row under the cursor, if any. */
  private fun ComposeUiTest.cursorRow(): String? =
    onAllNodes(hasTestTag(BOOK_ROW_TAG) and isSelected()).fetchSemanticsNodes().singleOrNull()?.let { node ->
      node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }.first { it.startsWith("Book ") }
    }

  /** The title of the row that has the focus itself (not its ♡), if any. */
  private fun ComposeUiTest.focusedRow(): String? =
    onAllNodes(hasTestTag(BOOK_ROW_TAG) and isFocused()).fetchSemanticsNodes().singleOrNull()?.let { node ->
      node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }.first { it.startsWith("Book ") }
    }

  /** #418: the focused row, if a row has the focus, is the cursor's. */
  private fun ComposeUiTest.selection(): String? {
    val cursor = cursorRow()
    focusedRow()?.let { assertEquals(cursor, it, "focus and the cursor's ring are on different rows") }
    return cursor
  }

  private fun ComposeUiTest.waitForPages(id: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithContentDescription("Page 1 of 2").fetchSemanticsNodes().isNotEmpty() && reader.shown.value?.id == id
    }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
  }

  @Test
  fun wasdMoveTheCursorOverTheRows() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    assertNull(cursorRow(), "no cursor before a key")
    key(Key.S)
    assertEquals("Book 1", cursorRow(), "the first press shows it on the first row")
    key(Key.S)
    key(Key.S)
    assertEquals("Book 3", cursorRow())
    key(Key.W)
    assertEquals("Book 2", cursorRow())
    // One column: sideways goes nowhere, and A/D aren't the shell's either.
    key(Key.D)
    assertEquals("Book 2", cursorRow())
    // Shift+S jumps a screen.
    key(Key.S, shift = true)
    val jumped = cursorRow()!!.removePrefix("Book ").toInt()
    assertTrue(jumped >= 5, "a screen down: Book $jumped")
    key(Key.W, shift = true)
    assertTrue(cursorRow()!!.removePrefix("Book ").toInt() < jumped)
  }

  @Test
  fun tabOntoARowPutsTheCursorThereAndSpaceOpensIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    // The filters above the list come first.
    repeat(10) { if (focusedRow() == null) key(Key.Tab) }
    assertEquals("Book 1", selection(), "Tab onto a row takes the cursor")
    assertEquals("Book 1", focusedRow())
    // Each row is two stops, the row and its ♡: on the ♡ the cursor stays on its row.
    key(Key.Tab)
    assertEquals("Book 1", selection())
    key(Key.Tab)
    assertEquals("Book 2", selection())
    assertEquals("Book 2", focusedRow())
    key(Key.Tab, shift = true)
    key(Key.Tab, shift = true)
    assertEquals("Book 1", selection(), "Shift-Tab takes it back")
    key(Key.Tab)
    key(Key.Tab)
    key(Key.Spacebar)
    waitForPages("b2")
  }

  @Test
  fun wasdMovesTheFocusWithTheCursorAndTabGoesOnFromIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    key(Key.S)
    key(Key.S)
    key(Key.S)
    assertEquals("Book 3", selection())
    assertEquals("Book 3", focusedRow(), "the cursor took the focus with it")
    key(Key.Tab)
    key(Key.Tab)
    assertEquals("Book 4", selection(), "Tab goes on from the cursor's row")
    key(Key.Spacebar)
    waitForPages("b4")
    key(Key.Escape)
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    assertEquals("Book 4", selection())
    assertEquals("Book 4", focusedRow(), "closing the reader gives the focus back to the cursor's row")
    // What the search panel closing does: the frame's KeyFocusHolder asks for the keys; they go to the cursor's row.
    runOnIdle { keyFocus.returnToContent() }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals("Book 4", focusedRow())
  }

  @Test
  fun spaceOnARowsHeartIsTheHeartsNotTheRows() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    repeat(10) { if (focusedRow() == null) key(Key.Tab) }
    assertEquals("Book 1", focusedRow())
    // On to the row's ♡: the cursor stays on its row, and Space is the ♡'s (signed out, it asks to sign in).
    key(Key.Tab)
    assertNull(focusedRow())
    assertEquals("Book 1", selection())
    key(Key.Spacebar)
    assertTrue(GlobalContext.get().get<SignInController>().isOpen.value, "Space didn't reach the ♡")
    assertNull(reader.session.value, "Space on the ♡ opened the book")
  }

  @Test
  fun keysComingBackLeaveAScrolledAwayCursorRowUnfocused() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    key(Key.S)
    assertEquals("Book 1", focusedRow())
    onNode(hasScrollToIndexAction()).performScrollToIndex(8)
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    runOnIdle { keyFocus.returnToContent() }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    // Back in view later, the cursor's row doesn't take the focus from what has it.
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertNull(focusedRow(), "a stale request took the focus when the row came back")
  }

  @Test
  fun twoColumnsMoveSideways() = runDesktopComposeUiTest(width = 1200, height = HEIGHT) {
    showList()
    key(Key.S)
    assertEquals("Book 1", cursorRow())
    key(Key.D)
    assertEquals("Book 2", cursorRow())
    key(Key.S)
    assertEquals("Book 4", cursorRow())
    key(Key.A)
    assertEquals("Book 3", cursorRow())
  }

  @Test
  fun spaceOpensTheReaderAndEscGivesTheKeysBackOnTheBookItClosedOn() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    key(Key.S)
    key(Key.S)
    key(Key.Spacebar)
    waitForPages("b2")
    assertEquals(1, reader.session.value?.index)
    // A/D in the reader move to another book: the cursor follows it there.
    key(Key.D)
    waitForPages("b3")
    key(Key.Escape)
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    assertNull(reader.session.value)
    assertEquals("Book 3", cursorRow())
    // The list has the keys again at once: WASD move, and what it doesn't take reaches the shell.
    key(Key.S)
    assertEquals("Book 4", cursorRow())
    shellKeys.clear()
    key(Key.X)
    key(Key.Z)
    key(Key.Slash)
    key(Key.D, shift = true)
    key(Key.A, shift = true)
    assertEquals(listOf(Key.X, Key.Z, Key.Slash, Key.D, Key.A), shellKeys.filterNot { it == Key.ShiftLeft })
    assertEquals("Book 4", cursorRow(), "Shift+A/D are the shell's tab steps, not cursor moves")
  }

  @Test
  fun fOnASeriesRowIsTheShells() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    // A series' row comes first: it has no ♡, so F there isn't the list's.
    listJson = """{ "series": { "data": [ { "id": "s1", "name_en": "Book series", "rating": "s", "poolCount": 3,
      "firstPool": ${bookItem(9)} } ] }, "pools": { "meta": {}, "data": [ ${(1..3).joinToString { bookItem(it) }} ] } }"""
    showList()
    key(Key.S)
    assertEquals("Book series", cursorRow())
    shellKeys.clear()
    key(Key.F)
    assertEquals(listOf(Key.F), shellKeys)
    val signIn = GlobalContext.get().get<SignInController>()
    assertFalse(signIn.isOpen.value)
    // A book's row takes it.
    key(Key.S)
    shellKeys.clear()
    key(Key.F)
    assertEquals(emptyList(), shellKeys)
    assertTrue(signIn.isOpen.value)
  }

  @OptIn(InternalComposeUiApi::class)
  @Test
  fun aHeldSpaceOpensOnce() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    // A series' row opens its books in place, which the test's navigator records without leaving the list.
    listJson = """{ "series": { "data": [ { "id": "s1", "name_en": "Book series", "rating": "s", "poolCount": 3,
      "firstPool": ${bookItem(9)} } ] }, "pools": { "meta": {}, "data": [ ${(1..3).joinToString { bookItem(it) }} ] } }"""
    showList()
    key(Key.S)
    // Held, Space's auto-repeats arrive as more key downs before its key up, as the desktop sends them.
    val scene = (this as SkikoComposeUiTest).scene
    listOf(KeyEventType.KeyDown, KeyEventType.KeyDown, KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
      runOnIdle { scene.sendKeyEvent(KeyEvent(Key.Spacebar, type)) }
    }
    waitForIdle()
    assertEquals(1, opened.size, "held, Space opens once")
    // Released, the next press is a new one.
    key(Key.Spacebar)
    assertEquals(2, opened.size)
  }

  @OptIn(InternalComposeUiApi::class)
  @Test
  fun aHeldSpaceOpensTheReaderWithoutPagingThroughIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    key(Key.S)
    // Held: Space and its auto-repeats, which the desktop sends as presses with no release between. It opens on its
    // release, which the list sees, so the reader gets none of them (#433 review).
    val scene = (this as SkikoComposeUiTest).scene
    repeat(4) { runOnIdle { scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown)) } }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertNull(reader.session.value, "not open while Space is down")
    runOnIdle { scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp)) }
    waitForPages("b1")
    assertEquals(0, reader.session.value?.index)
    val top = shownPageTop()
    // The reader takes the focus a frame or more after it opens; under load that can be after the pages are in.
    // Its pane (the node with the pane title) is the one focused, not the list's.
    waitUntil(timeoutMillis = 10_000) {
      onAllNodes(isFocused() and SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNodes().isNotEmpty()
    }
    // Released, Space is the reader's again: a screen down. A screen can take page 1 out of sight, the end card (#503)
    // giving the strip room to scroll.
    key(Key.Spacebar)
    waitUntil(timeoutMillis = 5_000) { shownPageTopOrNull().let { it == null || it < top - 100f } }
  }

  @OptIn(InternalComposeUiApi::class)
  @Test
  fun aMoveWhileSpaceIsDownCancelsTheOpen() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    key(Key.S)
    val scene = (this as SkikoComposeUiTest).scene
    runOnIdle { scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown)) }
    key(Key.S)
    assertEquals("Book 2", cursorRow())
    runOnIdle { scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp)) }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertNull(reader.session.value, "Space went down on another row: nothing opens")
  }

  /**
   * The top of the open book's first page in the window, or null once it's scrolled out of the strip. The books either
   * side have one too, a window's width off to the sides, and only those: the nearest page to the window's left edge
   * was the next book's once the open book's had scrolled away (#582).
   */
  private fun ComposeUiTest.shownPageTop(): Float = shownPageTopOrNull()!!

  private fun ComposeUiTest.shownPageTopOrNull(): Float? =
    onAllNodesWithContentDescription("Page 1 of 2").fetchSemanticsNodes().map { node ->
      node.layoutInfo.coordinates.localToRoot(Offset.Zero)
    }.filter { abs(it.x) < WIDTH / 2f }.minByOrNull { abs(it.x) }?.y

  @Test
  fun aCursorScrolledAwayComesBackOnTheFirstShownRow() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    key(Key.S)
    assertEquals("Book 1", cursorRow())
    // Scrolled (as the wheel does) past Book 1, the next press shows the cursor on a row in view, without jumping back.
    onNode(hasScrollToIndexAction()).performScrollToIndex(6)
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    key(Key.S)
    val shown = cursorRow()!!.removePrefix("Book ").toInt()
    assertTrue(shown > 3, "on a row in view: Book $shown")
    val row = onAllNodes(hasTestTag(BOOK_ROW_TAG) and isSelected())[0].getBoundsInRoot()
    assertTrue(row.top >= 0.dp && row.bottom <= HEIGHT.dp, "wholly in view: $row")
  }

  @Test
  fun aSeriesRowsHaveTheCursorToo() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList(series = true)
    key(Key.S)
    key(Key.S)
    assertEquals("Book 2", cursorRow())
    key(Key.E)
    assertEquals(listOf("b2"), kept.map { (it as Route.Book).id })
    key(Key.Spacebar)
    waitForPages("b2")
    key(Key.D)
    waitForPages("b3")
    key(Key.Escape)
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    assertNull(reader.session.value)
    assertEquals("Book 3", cursorRow())
    key(Key.W)
    assertEquals("Book 2", cursorRow())
  }

  @Test
  fun eKeepsTheBookAndFFavouritesIt() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
    showList()
    // Without a cursor, E and F are the shell's.
    key(Key.E)
    assertEquals(emptyList(), kept)
    key(Key.S)
    key(Key.S)
    key(Key.E)
    assertEquals(listOf("b2"), kept.map { (it as Route.Book).id })
    assertEquals("Book 2", cursorRow(), "the cursor stays")
    // Signed out, F asks to sign in to favourite, as the row's ♡ does.
    key(Key.F)
    assertTrue(GlobalContext.get().get<SignInController>().isOpen.value)
  }

  private companion object {
    const val WIDTH = 600
    const val HEIGHT = 800
    const val BOOKS = 12

    fun bookItem(n: Int) = """{ "id": "b$n", "name_en": "Book $n", "rating": "s", "status": "active", "post_count": 2 }"""

    val LIST_JSON = """{ "series": { "data": [] }, "pools": { "meta": {}, "data": [ ${(1..BOOKS).joinToString { bookItem(it) }} ] } }"""
    const val EMPTY_LIST_JSON = """{ "series": { "data": [] }, "pools": { "meta": {}, "data": [] } }"""

    val SERIES_JSON = """
      { "data": { "series": { "id": "s1", "name_en": "Fixture series", "pool_count": 3, "books": [
        ${(1..3).joinToString { """{ "id": "b$it", "name_en": "Book $it", "rating": "s", "post_count": 2, "sequence": $it }""" }}
      ] } } }
    """.trimIndent()

    fun pageJson(id: String, sequence: Int) = """
      { "id": "$id", "md5": "md5-$id", "rating": "s", "status": "active", "sequence": $sequence,
        "file_type": "image/png", "width": 1000, "height": 1000, "sample_url": "https://fixture.invalid/$id.png" }
    """.trimIndent()

    fun bookJson(id: String): String = """
      { "id": "$id", "name_en": "Book ${id.removePrefix("b")}", "rating": "s", "post_count": 2,
        "posts": [ ${pageJson("$id-0", 0)}, ${pageJson("$id-1", 1)} ] }
    """.trimIndent()
  }
}
