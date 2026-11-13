package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
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
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.booksModule
import dev.marzahn.sankaku.di.gridModule
import dev.marzahn.sankaku.di.postModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
import dev.marzahn.sankaku.features.grid.GridCursorRequest
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #421: a search from the tag list's keys in this tab (E, Q, Space, Backspace) asks for the grid it opens to start with
 * its cursor on the first cell, as Enter in the search panel does ([GridCursorRequest], which `GridScreen` takes as
 * it composes). With Shift the search goes to a background tab, and asks for nothing. The real [PostDetails] over
 * fixture tags, no network.
 */
@OptIn(ExperimentalTestApi::class)
class TagListKeySearchTest {
  private val tabs = TabsController()
  private val calls = mutableListOf<String>()
  private val navigator = object : Navigator {
    override fun goTo(route: Route) {
      calls += "now $route"
    }

    override fun goBack() = Unit

    override fun replaceTop(route: Route) = Unit

    override fun openInNewTab(route: Route): Int? = null.also { calls += "new tab $route" }
  }

  private val post = PostDto(
    id = "fixture-post",
    md5 = "fixture-md5",
    rating = "s",
    status = "active",
    fileType = "image/png",
    tags = listOf(TagDto(name = "Sky", tagName = "sky")),
  )

  private val cursorRequest get() = GlobalContext.get().get<GridCursorRequest>()

  @BeforeTest
  fun startFixtureKoin() {
    val cache = Files.createTempDirectory("tag-list-keys").toFile()
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
      single<HttpClientEngine> { MockEngine { respondError(HttpStatusCode.NotFound) } }
      single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule, fixtures) }
  }

  @AfterTest
  fun stop() = stopKoin()

  /** The post's details over a grid of [search], with the tag list holding the keys (T, in the screens around it). */
  private fun ComposeUiTest.showTagList(search: List<String>) {
    lateinit var focus: FocusRequester
    setContent {
      SankakuTheme {
        focus = remember { FocusRequester() }
        PostDetails(post, searchTags = search, tagListFocus = focus, navigator = navigator, tabs = tabs)
      }
    }
    // As in IncludedTagTapTest: the recommendations' failed request changes the layout until it lands.
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Couldn't load recommendations").fetchSemanticsNodes().isNotEmpty() }
    runOnIdle { focus.requestFocus() }
    waitForIdle()
  }

  private fun ComposeUiTest.press(key: Key, shift: Boolean = false) {
    onNode(isFocused()).performKeyInput {
      if (shift) keyDown(Key.ShiftLeft)
      pressKey(key)
      if (shift) keyUp(Key.ShiftLeft)
    }
    waitForIdle()
  }

  private fun searchesHereWithTheCursor(key: Key, search: List<String>, expected: List<String>) = runDesktopComposeUiTest {
    showTagList(search)
    press(key)

    assertEquals(listOf("now ${Route.Grid(expected)}"), calls)
    assertFalse(cursorRequest.take(search), "another grid (the one under the viewer, another tab) doesn't get it")
    assertTrue(cursorRequest.take(expected), "the grid it opens starts with its cursor")
    assertFalse(cursorRequest.take(expected), "only that grid gets it")
  }

  @Test
  fun eIncludesHereAndTheNewGridHasTheCursor() =
    searchesHereWithTheCursor(Key.E, listOf("scenery"), searchAfterKey(listOf("scenery"), "sky", TagKey.Include)!!)

  @Test
  fun qExcludesHereAndTheNewGridHasTheCursor() =
    searchesHereWithTheCursor(Key.Q, listOf("scenery"), searchAfterKey(listOf("scenery"), "sky", TagKey.Exclude)!!)

  @Test
  fun spaceSearchesOnlyThisTagHereAndTheNewGridHasTheCursor() =
    searchesHereWithTheCursor(Key.Spacebar, listOf("scenery"), SearchTags.searchForOpenedTag("sky"))

  @Test
  fun backspaceRemovesHereAndTheNewGridHasTheCursor() =
    searchesHereWithTheCursor(Key.Backspace, listOf("scenery", "sky"), listOf("scenery"))

  @Test
  fun withShiftTheSearchGoesToABackgroundTabWithoutTheCursor() = runDesktopComposeUiTest {
    showTagList(listOf("scenery"))
    press(Key.E, shift = true)

    val included = searchAfterKey(listOf("scenery"), "sky", TagKey.Include)!!
    assertEquals(listOf("new tab ${Route.Grid(included)}"), calls)
    assertFalse(cursorRequest.take(included), "a background tab's grid starts without the cursor")
  }
}
