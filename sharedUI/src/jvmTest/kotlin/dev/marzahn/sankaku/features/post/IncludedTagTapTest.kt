package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
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
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import okio.Path.Companion.toOkioPath
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #313: on a post opened from a `scenery` grid, a click (a tap on the phone) on the Scenery chip, which the search
 * already includes, runs no search and says "Already searching Scenery": the grid keeps its route, order and place.
 * A plain chip still opens its popular search "now"; a middle-click and the menu are unchanged. The real
 * [PostDetails] with a real [TabsController] and [TabsNavigator]; fixture tags, no network.
 */
@OptIn(ExperimentalTestApi::class)
class IncludedTagTapTest {
  private val grid = Route.Grid(listOf("scenery"))
  private val place = GridPlace(post = "fixture-place")
  private val tabs = TabsController().apply {
    open(grid, background = false)
    activeTab.markTouched(grid)
    activeTab.reportPlace(grid, place)
  }
  private val tabCount = tabs.tabs.value.size
  private val shown = mutableListOf<String>()

  /** [TabsNavigator], recording which way each route went. */
  private val calls = mutableListOf<String>()
  private val tabsNavigator = TabsNavigator(tabs)
  private val navigator = object : Navigator by tabsNavigator {
    override fun goTo(route: Route) {
      calls += "now $route"
      tabsNavigator.goTo(route)
    }

    override fun openInNewTab(route: Route): Int? {
      calls += "new tab $route"
      return tabsNavigator.openInNewTab(route)
    }
  }

  private val post = PostDto(
    id = "fixture-post",
    md5 = "fixture-md5",
    rating = "s",
    status = "active",
    fileType = "image/png",
    tags = listOf(TagDto(name = "Scenery", tagName = "scenery"), TagDto(name = "Sky", tagName = "sky")),
  )

  @BeforeTest
  fun startFixtureKoin() {
    val cache = Files.createTempDirectory("included-tag-tap").toFile()
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

  private fun ComposeUiTest.showPost() {
    setContent {
      SankakuTheme {
        // The app's own Messages, which the top line shows.
        val messages = koinInject<Messages>()
        LaunchedEffect(messages) { messages.messages.collect { message: Message -> shown += message.text } }
        PostDetails(post, searchTags = grid.tags, navigator = navigator, tabs = tabs)
      }
    }
    // The recommendations' request fails on Ktor's own dispatcher, which waitForIdle doesn't wait for. Until it does,
    // their loading box is 48 px taller and the chips sit lower: a click aimed at a chip before then landed beside it
    // once the box shrank (the CI flake).
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Couldn't load recommendations").fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
  }

  private fun assertGridKept() {
    assertEquals(tabCount, tabs.tabs.value.size, "no tab opened")
    assertEquals(listOf(grid), tabs.activeTab.backStack.toList(), "the grid's route and order")
    assertEquals(place, tabs.activeTab.place, "the grid's place")
  }

  @Test
  fun aClickOnAnIncludedChipRunsNoSearchAndSaysSo() = runDesktopComposeUiTest {
    showPost()
    onNodeWithText("Scenery").performClick()
    waitForIdle()

    assertEquals(emptyList(), calls)
    assertGridKept()
    assertEquals(listOf("Already searching Scenery"), shown)
  }

  @Test
  fun aClickOnAPlainChipStillOpensItsPopularSearchNow() = runDesktopComposeUiTest {
    showPost()
    onNodeWithText("Sky").performClick()
    waitForIdle()

    val popular = Route.Grid(listOf("order:popularity", "sky"))
    assertEquals(listOf("now $popular"), calls)
    assertEquals(popular, tabs.activeTab.top)
    assertEquals(emptyList(), shown)
  }

  @Test
  fun aMiddleClickOnAnIncludedChipStillGoesToANewTab() = runDesktopComposeUiTest {
    showPost()
    onNodeWithText("Scenery").performMouseInput {
      moveTo(center)
      press(MouseButton.Tertiary)
      release(MouseButton.Tertiary)
    }
    waitForIdle()

    // As before, it goes to openInNewTab; its popular search's source is the grid shown (one source per search), so
    // the navigator adds no tab and the grid stays as it was.
    assertEquals(listOf("new tab ${Route.Grid(listOf("order:popularity", "scenery"))}"), calls)
    assertGridKept()
    assertEquals(emptyList(), shown)
  }

  @Test
  fun theIncludedChipsMenuOffersSearchOnlyThisTagButNotAdd() = runDesktopComposeUiTest {
    showPost()
    onNodeWithText("Scenery").performMouseInput { rightClick() }
    waitForIdle()

    onNodeWithText("Search only this tag").assertExists()
    onNodeWithText("Exclude from search").assertExists()
    onNodeWithText("Remove from search").assertExists()
    onNodeWithText("Add to search").assertDoesNotExist()
    assertEquals(emptyList(), calls)
  }
}
