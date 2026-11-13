package dev.marzahn.sankaku.features.tag

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.ui.components.AppSnackbarHost
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.PlatformContext
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
import dev.marzahn.sankaku.di.postModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
import dev.marzahn.sankaku.features.books.BookReader
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabId
import dev.marzahn.sankaku.tabs.TabStateStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** #486: a tag's page from a fake `/tag-and-wiki`: what it shows, where its rows lead, and closing it. */
@OptIn(ExperimentalTestApi::class)
class TagPageTest {
  private val opened = mutableListOf<Route>()
  private val later = mutableListOf<Route>()
  private var closed = 0
  private var failing = false
  private var unreachable = false
  private var hanging = false
  private var pageRequests = 0
  private var pageJson = PAGE_JSON
  private val linked = mutableListOf<String>()
  private val navigator = object : Navigator {
    override fun goTo(route: Route) {
      opened += route
    }

    override fun goBack() = Unit

    override fun replaceTop(route: Route) = Unit

    override fun openInNewTab(route: Route): Int? = null.also { later += route }
  }

  private fun startFixtureKoin(signedIn: Boolean) {
    val cache = Files.createTempDirectory("tag-page").toFile()
    val now = Clock.System.now()
    val tokens = if (signedIn) StoredTokens("access", "refresh", now + 7.days, now + 90.days, UserSummary(id = "42", name = "tester", avatarUrl = null)) else null
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
          if (hanging) awaitCancellation()
          if (unreachable) throw IOException("unreachable")
          if (failing) return@MockEngine respond("""{ "code": "oops" }""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
          val body = when (request.url.encodedPath) {
            "/tag-and-wiki/name/fixture_artist" -> pageJson.also { pageRequests++ }
            "/tag-and-wiki/name/other_tag" -> OTHER_JSON
            else -> """{ "meta": {}, "data": [] }"""
          }
          respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
      }
    }
    startKoin { modules(sharedModule, platform, authModule, booksModule, postModule, shellModule, fixtures) }
  }

  @AfterTest
  fun stop() = stopKoin()

  private fun ComposeUiTest.showPage(signedIn: Boolean = false) {
    startFixtureKoin(signedIn)
    setContent { TagPage(SEED, onClose = { closed++ }, navigator = navigator, onOpenTag = { linked += it.searchName }) }
    // Loaded (its counts) or failed: Posts shows from the start.
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("42 books").fetchSemanticsNodes().isNotEmpty() || onAllNodesWithText("Retry", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  @Test
  fun showsTheTagsTypeNameCountsAndWiki() = runDesktopComposeUiTest {
    showPage()
    // The opener's name: the page's `name` is the search key.
    onNodeWithText("Fixture Artist").assertExists()
    onNodeWithText("Artist").assertExists()
    onNodeWithText("12.3k posts").assertExists()
    onNodeWithText("42 books").assertExists()
    // DText's wiki links read as their text.
    onNodeWithText("Draws fixtures.", substring = true).assertExists()
    assertTrue(onAllNodesWithText(FOLLOW).fetchSemanticsNodes().isEmpty(), "no Follow signed out")
  }

  @Test
  fun signedInItOffersFollow() = runDesktopComposeUiTest {
    showPage(signedIn = true)
    onNodeWithText(FOLLOW).assertExists()
  }

  @Test
  fun postsOpensAGridForTheTagKeepingThePageAndClosingTheReader() = runDesktopComposeUiTest {
    showPage()
    val reader = GlobalContext.get().get<BookReader>()
    reader.open("b1")
    onNodeWithText(POSTS).performClick()
    waitForIdle()
    assertEquals(listOf<Route>(Route.Grid(listOf("order:popularity", "fixture_artist"))), opened)
    assertEquals(0, closed, "the page stays with its item, for Back to return to")
    assertNull(reader.session.value, "the reader closed for the search")
  }

  @Test
  fun booksOpensABooksSearchForTheTag() = runDesktopComposeUiTest {
    showPage()
    onNodeWithText(BOOKS).performClick()
    waitForIdle()
    assertEquals(listOf<Route>(Route.Books(listOf("fixture_artist"))), opened)
    assertEquals(0, closed)
  }

  @Test
  fun thePageShowsOnlyOverItsItemShowingWhatItDid() {
    val grid = Route.Grid(listOf("smile"))
    val page = OpenTagPage(SEED, TabId("a"), grid)
    assertTrue(tagPageShown(page, TabId("a"), grid))
    assertTrue(!tagPageShown(page, TabId("b"), grid), "another item, the search it opened")
    assertTrue(!tagPageShown(page, TabId("a"), Route.Grid(listOf("other"))), "its item moved on")
    assertTrue(tagPageShown(OpenTagPage(SEED, null, null), TabId("b"), grid), "no queue: always")
  }

  @Test
  fun backCloses() = runDesktopComposeUiTest {
    showPage()
    onNodeWithContentDescription("Back").performClick()
    waitForIdle()
    assertEquals(1, closed)
    assertTrue(opened.isEmpty())
  }

  @Test
  fun aFailureSaysWhyKeepsTheNameAndRetryReloads() = runDesktopComposeUiTest {
    failing = true
    showPage()
    onNodeWithText("Fixture Artist").assertExists()
    onNodeWithText("Sankaku is having trouble. Try again in a moment.").assertExists()
    assertTrue(onAllNodesWithText("42 books").fetchSemanticsNodes().isEmpty(), "no counts until it loads")

    failing = false
    onNodeWithText("Retry").performClick()
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("42 books").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("42 books").assertExists()
  }

  @Test
  fun failedItStillOffersPostsAndBooks() = runDesktopComposeUiTest {
    // #522: the rows need only the tag's name.
    failing = true
    showPage()
    onNodeWithText(POSTS).performClick()
    onNodeWithText(BOOKS).performClick()
    waitForIdle()
    assertEquals(listOf(Route.Grid(listOf("order:popularity", "fixture_artist")), Route.Books(listOf("fixture_artist"))), opened)
  }

  @Test
  fun loadingItAlreadyOffersPostsAndBooks() = runDesktopComposeUiTest {
    hanging = true
    startFixtureKoin(signedIn = false)
    setContent { TagPage(SEED, onClose = { closed++ }, navigator = navigator) }
    waitForIdle()
    onNodeWithText(POSTS).performClick()
    waitForIdle()
    assertEquals(listOf<Route>(Route.Grid(listOf("order:popularity", "fixture_artist"))), opened)
  }

  @Test
  fun failedForWantOfAConnectionItLoadsAgainOnceBackOnline() = runDesktopComposeUiTest {
    unreachable = true
    showPage()
    onNodeWithText("Retry").assertExists()
    unreachable = false
    GlobalContext.get().get<ConnectivityState>().requestRetry()
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("42 books").fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun hiddenBackOnlineItWaitsToBeShownBeforeLoadingAgain() = runDesktopComposeUiTest {
    // REVIEW.md: nothing loads from what isn't on screen.
    unreachable = true
    startFixtureKoin(signedIn = false)
    var shown by mutableStateOf(true)
    setContent { TagPage(SEED, onClose = { closed++ }, shown = shown, navigator = navigator) }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
    shown = false
    waitForIdle()
    unreachable = false
    GlobalContext.get().get<ConnectivityState>().requestRetry()
    waitForIdle()
    assertEquals(0, pageRequests, "no load while hidden")
    shown = true
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("42 books").fetchSemanticsNodes().isNotEmpty() }
    assertEquals(1, pageRequests)
  }

  /**
   * #522: the page as the shell has it: a stand-in for the window under it (a focusable screen with keys, some text and
   * the window's message host), and [TagPageHost] over it with the shell's messages.
   */
  private fun ComposeUiTest.showOverShell(sheet: Boolean = false): TagPages {
    startFixtureKoin(signedIn = false)
    val pages = GlobalContext.get().get<TagPages>()
    setContent {
      shellScope = rememberCoroutineScope()
      val shown = rememberTagPageShown(pages)
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().underTagPage(shown, pages)) {
          Box(
            Modifier
              .testTag(SHELL)
              .size(10.dp)
              .focusRequester(shellFocus)
              .onFocusChanged { shellFocused = it.isFocused }
              // Presses: the release of the Esc that closed the page comes here once it's gone, as the shell's does.
              .onKeyEvent {
                if (it.type == KeyEventType.KeyDown) shellKeys += it.key
                true
              }
              .focusable(),
          )
          Text(SHELL_TEXT)
          Button(onClick = {}) { Text("Shell button") }
          AppSnackbarHost(snackbar, swipeable = false)
        }
        TagPageHost(pages, snackbar = snackbar, sheet = sheet, onKeysBack = {
          handedBack++
          shellFocus.requestFocus()
        })
      }
      LaunchedEffect(Unit) { shellFocus.requestFocus() }
    }
    waitForIdle()
    pages.open(SEED)
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("42 books").fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
    return pages
  }

  private val shellFocus = FocusRequester()
  private var shellFocused = false
  private val shellKeys = mutableListOf<Key>()
  private var handedBack = 0
  private val snackbar = SnackbarHostState()
  private lateinit var shellScope: CoroutineScope

  private fun ComposeUiTest.press(key: Key) {
    // To what has the focus: a button Tab lands on shows its tooltip (#521), a popup root of its own beside the window's.
    onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
    waitForIdle()
  }

  @Test
  fun itTakesTheKeysAsItOpens() = runDesktopComposeUiTest {
    showOverShell()
    assertTrue(!shellFocused, "the page has the keys")
    onNode(isFocused()).assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Fixture Artist"))
  }

  @Test
  fun escClosesThePageAndHandsTheKeysBack() = runDesktopComposeUiTest {
    val pages = showOverShell()
    press(Key.Escape)
    assertNull(pages.shown.value)
    assertTrue(shellKeys.isEmpty(), "the screen under it heard nothing")
    assertEquals(1, handedBack)
    assertTrue(shellFocused, "the keys are the screen's again")
  }

  @Test
  fun escThatReachesTheScreenUnderItClosesThePageNotTheScreen() = runDesktopComposeUiTest {
    // A menu closing gave the focus back to the screen under the page (the issue's first Esc).
    val pages = showOverShell()
    shellFocus.requestFocus()
    waitForIdle()
    press(Key.Escape)
    assertNull(pages.shown.value)
    assertTrue(shellKeys.isEmpty(), "the screen under it heard nothing")
    assertTrue(shellFocused)
  }

  @Test
  fun anyOtherKeyThatReachesTheScreenUnderItBringsTheKeysBackToThePage() = runDesktopComposeUiTest {
    val pages = showOverShell()
    shellFocus.requestFocus()
    waitForIdle()
    press(Key.D)
    assertTrue(shellKeys.isEmpty(), "D didn't page the screen under it")
    assertTrue(!shellFocused)
    assertEquals(SEED, pages.shown.value?.tag)
  }

  @Test
  fun tabStaysOnThePage() = runDesktopComposeUiTest {
    showOverShell()
    repeat(12) {
      press(Key.Tab)
      assertTrue(!shellFocused, "Tab ${it + 1} left the page")
    }
  }

  @Test
  fun theScreenUnderItIsOutOfTheAccessibilityTreeWhileItShows() = runDesktopComposeUiTest {
    val pages = showOverShell()
    assertTrue(onAllNodesWithText(SHELL_TEXT).fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithText("Shell button").fetchSemanticsNodes().isEmpty())
    pages.close()
    waitForIdle()
    onNodeWithText(SHELL_TEXT).assertExists()
  }

  @Test
  fun itsMessagesShowOverIt() = runDesktopComposeUiTest {
    showOverShell()
    shellScope.launch { snackbar.showSnackbar("Blacklisted fixture_artist", actionLabel = "Undo") }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Blacklisted fixture_artist").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Blacklisted fixture_artist").assertIsDisplayed()
    onNodeWithText("Undo").assertIsDisplayed()
  }

  @Test
  fun aLongPressOrMiddleClickOnPostsOpensItForLater() = runDesktopComposeUiTest {
    showPage()
    onNodeWithText(POSTS).performMouseInput {
      moveTo(center)
      press(MouseButton.Tertiary)
      release(MouseButton.Tertiary)
    }
    waitForIdle()
    assertEquals(listOf<Route>(Route.Grid(listOf("order:popularity", "fixture_artist"))), later)
    assertTrue(opened.isEmpty(), "nothing opened here")
  }

  @Test
  fun aLongPressOrMiddleClickOnBooksOpensItForLater() = runDesktopComposeUiTest {
    showPage()
    onNodeWithText(BOOKS).performMouseInput {
      moveTo(center)
      press(MouseButton.Tertiary)
      release(MouseButton.Tertiary)
    }
    waitForIdle()
    assertEquals(listOf<Route>(Route.Books(listOf("fixture_artist"))), later)
    assertTrue(opened.isEmpty(), "nothing opened here")
  }

  @Test
  fun hiddenWhileItsSearchIsShownItComesBackAsItWasWithNoSecondRequest() = runDesktopComposeUiTest {
    // #487 review: Back from the search it opened finds the page loaded, not loading it again.
    startFixtureKoin(signedIn = false)
    var shown by mutableStateOf(true)
    setContent { TagPage(SEED, onClose = { closed++ }, shown = shown, navigator = navigator) }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("42 books").fetchSemanticsNodes().isNotEmpty() }
    shown = false
    waitForIdle()
    assertTrue(onAllNodesWithText(POSTS).fetchSemanticsNodes().isEmpty(), "hidden: not in the tree")
    shown = true
    waitForIdle()
    onNodeWithText(POSTS).assertExists()
    assertEquals(1, pageRequests, "loaded once")
  }

  /** Where [part] of the text in [node] is laid out, in the node's own coordinates. */
  private fun at(node: SemanticsNodeInteraction, part: String): Offset {
    val layouts = mutableListOf<TextLayoutResult>()
    node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
    val layout = layouts.first()
    val from = layout.layoutInput.text.text.indexOf(part)
    assertTrue(from >= 0, "no \"$part\" in the text")
    return layout.getBoundingBox(from + part.length / 2).center
  }

  private fun ComposeUiTest.wiki(part: String) = onNodeWithText(part, substring = true, useUnmergedTree = true)

  @Test
  fun theWikiReadsAsDTextNeverRaw() = runDesktopComposeUiTest {
    // #522: headings, bullets and links, where the page showed `h4.`, `*` and `[i]` as typed.
    pageJson = pageWith("h4. Characters\n[i]Some umamusume[/i]\n* [[other_tag|another]]\n* Last")
    showPage()
    wiki("Characters\nSome umamusume\n• another\n• Last").assertExists()
    assertTrue(onAllNodesWithText("h4.", substring = true).fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithText("[i]", substring = true).fetchSemanticsNodes().isEmpty())
    // A bullet is a paragraph of its own, with no blank line around it.
    val layouts = mutableListOf<TextLayoutResult>()
    wiki("Characters").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
    assertEquals(4, layouts.first().lineCount)
  }

  @Test
  fun aWikiLinkOpensThatTagsPageInPlaceAndBackComesBackToThisOne() = runDesktopComposeUiTest {
    val pages = showOverShell()
    wiki("Draws fixtures.").let { node -> node.performMouseInput { click(at(node, "another")) } }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("7 posts").fetchSemanticsNodes().isNotEmpty() }
    assertEquals("other_tag", pages.shown.value?.tag?.searchName)
    // Named as the site names a tag known only by its key, until the page's own name... which the page never shows.
    onNodeWithText("Other Tag").assertExists()
    assertTrue(onAllNodesWithText("Fixture Artist").fetchSemanticsNodes().isEmpty(), "this page shows alone")

    onNodeWithContentDescription("Back").performClick()
    waitForIdle()
    assertEquals(SEED, pages.shown.value?.tag)
    onNodeWithText("42 books").assertExists()
    assertEquals(1, pageRequests, "as it was, with no second request")

    press(Key.Escape)
    assertNull(pages.shown.value)
    assertEquals(1, handedBack)
  }

  @Test
  fun aMiddleClickOnAWikiLinkOpensASearchForItForLater() = runDesktopComposeUiTest {
    showPage()
    wiki("Draws fixtures.").let { node ->
      node.performMouseInput {
        moveTo(at(node, "another"))
        press(MouseButton.Tertiary)
        release(MouseButton.Tertiary)
      }
    }
    waitForIdle()
    assertEquals(listOf<Route>(Route.Grid(listOf("order:popularity", "other_tag"))), later)
    assertTrue(opened.isEmpty(), "nothing opened here")
    assertEquals(listOf<String>(), linked, "nor the tag's page")
  }

  @Test
  fun onTouchALongPressOnAWikiLinkOpensASearchForItForLaterNotItsPage() = runDesktopComposeUiTest {
    // #522 emulator QA: the link's own tap ran on the release, opening the tag's page instead.
    startFixtureKoin(signedIn = false)
    setContent { TagPage(SEED, onClose = { closed++ }, navigator = navigator, onOpenTag = { linked += it.searchName }, touch = true) }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("42 books").fetchSemanticsNodes().isNotEmpty() }
    wiki("Draws fixtures.").let { node -> node.performTouchInput { longClick(at(node, "another")) } }
    waitForIdle()
    assertEquals(listOf<Route>(Route.Grid(listOf("order:popularity", "other_tag"))), later)
    assertEquals(listOf<String>(), linked, "not the tag's page")

    // A tap still opens it.
    wiki("Draws fixtures.").let { node -> node.performTouchInput { click(at(node, "another")) } }
    waitForIdle()
    assertEquals(listOf("other_tag"), linked)
  }

  @Test
  fun aRowWithNothingInItGoesOnceLoaded() = runDesktopComposeUiTest {
    // #522: "Books · 0 books" led to an empty search.
    pageJson = PAGE_JSON.replace("\"pool_count\": 42", "\"pool_count\": 0")
    showPageUntil("12.3k posts")
    onNodeWithText(POSTS).assertExists()
    assertTrue(onAllNodesWithText(BOOKS).fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun onTheDesktopItIsASideSheetOverAScrimThatClosesIt() = runDesktopComposeUiTest {
    val pages = showOverShell(sheet = true)
    val window = onRoot().getUnclippedBoundsInRoot()
    val page = onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Fixture Artist")).getUnclippedBoundsInRoot()
    assertEquals(480.dp, page.width)
    assertEquals(window.right, page.right, "at the window's right edge")
    assertEquals(window.height, page.height)

    // Two pages deep, a click on the scrim closes them both.
    wiki("Draws fixtures.").let { node -> node.performMouseInput { click(at(node, "another")) } }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("7 posts").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(TAG_PAGE_SCRIM).performMouseInput { click(Offset(10f, 10f)) }
    waitForIdle()
    assertNull(pages.shown.value)
    assertEquals(1, handedBack)
  }

  @Test
  fun onTouchItCoversTheWindow() = runDesktopComposeUiTest {
    showOverShell(sheet = false)
    val window = onRoot().getUnclippedBoundsInRoot()
    val page = onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Fixture Artist")).getUnclippedBoundsInRoot()
    assertEquals(window.width, page.width)
    assertTrue(onAllNodesWithTag(TAG_PAGE_SCRIM).fetchSemanticsNodes().isEmpty(), "no scrim")
  }

  private fun ComposeUiTest.showPageUntil(text: String) {
    startFixtureKoin(signedIn = false)
    setContent { TagPage(SEED, onClose = { closed++ }, navigator = navigator) }
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
  }

  @Test
  fun aLinkToAPageAlreadyOpenGoesBackToItRatherThanStackingAnother() {
    // #577 review: A → B → A → B… grew the stack without end, each page kept composed.
    val pages = TagPages()
    pages.open(SEED)
    pages.openLinked(WikiTag("other tag", "other_tag"))
    pages.openLinked(WikiTag("fixture artist", "fixture_artist"))
    assertEquals(listOf("fixture_artist"), pages.stack.value.map { it.tag.searchName })
    assertEquals(SEED, pages.shown.value?.tag, "as it was opened")
  }

  @Test
  fun aLinkToASectionNamesTheTagWithoutIt() {
    // #577 review: "[[Some Tag#Section]]" seeded the name "Some Tag#Section".
    val pages = TagPages()
    pages.open(SEED)
    pages.openLinked(WikiTag("Some Tag#Section", "some_tag"))
    assertEquals("Some Tag", pages.shown.value?.tag?.name)
  }

  @Test
  fun countsReadCompactly() {
    assertEquals("1 post", postsCount(1))
    assertEquals("12.3k posts", postsCount(12_345))
    assertEquals("1 book", booksCount(1))
    assertEquals("42 books", booksCount(42))
  }

  private companion object {
    const val SHELL = "shell"
    const val SHELL_TEXT = "The window under the page"
    val SEED = TagDto(name = "Fixture Artist", type = 1, tagName = "fixture_artist")
    val PAGE_JSON = """
      { "tag": { "id": "t1", "name": "fixture_artist", "tagName": "fixture_artist", "type": 1, "post_count": 12345,
        "pool_count": 42, "is_following": false },
        "wiki": { "title": "fixture_artist", "body": "Draws fixtures. See [[other_tag|another]]." } }
    """.trimIndent()
    val OTHER_JSON = """
      { "tag": { "id": "t2", "name": "other_tag", "tagName": "other_tag", "type": 4, "post_count": 7, "pool_count": 0 } }
    """.trimIndent()

    fun pageWith(wiki: String) = PAGE_JSON.replace("Draws fixtures. See [[other_tag|another]].", wiki.replace("\n", "\\n"))
  }
}
