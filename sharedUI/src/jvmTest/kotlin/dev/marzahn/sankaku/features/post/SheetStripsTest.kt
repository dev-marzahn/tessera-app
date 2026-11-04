package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
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
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.gridModule
import dev.marzahn.sankaku.di.postModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
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
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #342: the details' Parent and Children strips, and the favourite heart on every strip's thumbnails. The real
 * [PostDetails] with a real [TabsController], [TabsNavigator] and [PostStateStore], over a fake API that counts what
 * it's asked: the parent (`id:`), the children (`parent:<id>`) and the recommendations. Signed out, so each strip asks
 * with the locked `rating:s`. Fixture posts only, no network, and nothing drawn is compared: state and semantics.
 */
@OptIn(ExperimentalTestApi::class)
class SheetStripsTest {
  private val tabs = TabsController().apply { open(Route.Grid(listOf("scenery")), background = false) }
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

  /** The strips' requests, as "<path> <tags>"; the full post's tags (`/posts/<id>`) are left out. */
  private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private var childCount = 2

  private val post = PostDto(id = POST, md5 = "fixture-md5", rating = "s", status = "active", parentId = PARENT, hasChildren = true)

  @BeforeTest
  fun startFixtureKoin() {
    val cache = Files.createTempDirectory("sheet-strips").toFile()
    val platform = module {
      single<TokenStore> { InMemoryTokenStore() }
      single { TokenStorageStatus.InMemory }
      single { DebugFlags() }
      single(CACHE_DIR) { cache.toOkioPath() }
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, PRIVACY_FILE, ACCESSIBILITY_FILE, QUEUE_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, FIRST_LAUNCH_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<SaveFolder> { DirectorySaveFolder(cache, opener = {}) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    val fixtures = module {
      single<HttpClientEngine> {
        MockEngine { request ->
          val path = request.url.encodedPath
          val tags = request.url.parameters["tags"].orEmpty()
          if (path.startsWith("/posts/")) return@MockEngine respondError(HttpStatusCode.NotFound)
          asked += "$path $tags"
          val body = when {
            path == "/v2/posts" -> "[${postJson(RECOMMENDED, favourited = true)}]"
            tags.startsWith("id:$PARENT") -> page(postJson(PARENT, favourited = true))
            tags.startsWith("parent:$POST") ->
              page(*(1..childCount).map { postJson("$CHILD$it", favourited = false, parent = POST) }.toTypedArray())
            else -> page()
          }
          respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
      }
      single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, fixtures) }
  }

  @AfterTest
  fun stop() = stopKoin()

  private val store get() = KoinPlatform.getKoin().get<PostStateStore>()

  private fun ComposeUiTest.showPost(shown: PostDto = post) {
    setContent { SankakuTheme { PostDetails(shown, navigator = navigator, tabs = tabs) } }
    awaitStrips()
  }

  // The strips' requests answer on Ktor's own dispatcher, which waitForIdle doesn't wait for.
  private fun ComposeUiTest.awaitStrips() {
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Recommended").fetchSemanticsNodes().isNotEmpty() }
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithContentDescription("Recommended post", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
  }

  private fun ComposeUiTest.awaitLabel(label: String) =
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }

  @Test
  fun aPostWithAParentAndChildrenShowsThemAsThumbnailsInOneRequestEach() = runDesktopComposeUiTest {
    showPost()
    awaitLabel("Parent post, favourited")
    awaitLabel("Child post")

    onNodeWithText("Parent").assertExists()
    onNodeWithText("Children").assertExists()
    assertEquals(2, onAllNodesWithContentDescription("Child post").fetchSemanticsNodes().size)
    assertEquals(
      listOf("/v2/posts recommended_for_post:$POST rating:s", "/v2/posts/keyset id:$PARENT rating:s", "/v2/posts/keyset parent:$POST rating:s"),
      asked.sorted(),
      "one request per strip, none per thumbnail",
    )
  }

  @Test
  fun aPostWithNeitherShowsNoParentOrChildrenAndAsksNothingForThem() = runDesktopComposeUiTest {
    showPost(post.copy(parentId = null, hasChildren = false))

    assertTrue(onAllNodesWithText("Parent").fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithText("Children").fetchSemanticsNodes().isEmpty())
    assertEquals(listOf("/v2/posts recommended_for_post:$POST rating:s"), asked.toList())
  }

  @Test
  fun theStripsAskNothingWhileTheDetailsAreClosedAndOnceEachWhenOpened() = runDesktopComposeUiTest {
    // A closed panel or a sheet at peek: the details are composed, but none of them is on screen.
    var open by mutableStateOf(false)
    setContent {
      SankakuTheme {
        Box(if (open) Modifier else Modifier.height(0.dp)) { PostDetails(post, navigator = navigator, tabs = tabs) }
      }
    }
    waitForIdle()
    Thread.sleep(300)
    waitForIdle()
    assertEquals(emptyList(), asked.toList(), "nothing asked while closed")

    open = true
    awaitStrips()
    awaitLabel("Child post")
    // Closed and opened again: the strips come from memory.
    open = false
    waitForIdle()
    open = true
    waitForIdle()
    assertEquals(3, asked.size, "the parent, the children and the recommendations, once each: $asked")
  }

  @Test
  fun everyStripsHeartFollowsTheSharedFavouriteState() = runDesktopComposeUiTest {
    showPost()
    awaitLabel("Parent post, favourited")
    awaitLabel("Child post")
    awaitLabel("Recommended post, favourited")
    assertEquals(2, onAllNodesWithContentDescription("Child post").fetchSemanticsNodes().size, "not favourited, no heart")

    // As the viewer's favourite does: an override in the store every grid reads.
    store.beginToggle("${CHILD}1", true, 1)
    store.beginToggle(PARENT, false, 0)
    store.beginToggle(RECOMMENDED, false, 0)
    waitForIdle()

    onNodeWithContentDescription("Child post, favourited").assertExists()
    assertEquals(1, onAllNodesWithContentDescription("Child post").fetchSemanticsNodes().size)
    onNodeWithContentDescription("Parent post").assertExists()
    onNodeWithContentDescription("Recommended post").assertExists()
    assertTrue(onAllNodesWithContentDescription("favourited", substring = true).fetchSemanticsNodes().size == 1)
  }

  @Test
  fun aClickOpensTheParentHereAndAMiddleClickInABackgroundTabOnce() = runDesktopComposeUiTest {
    showPost()
    awaitLabel("Parent post, favourited")
    val parent = Route.Post(PARENT, MD5, emptyList(), null, "s")

    onNodeWithContentDescription("Parent post, favourited").performScrollTo().performMouseInput { middleClick() }
    waitForIdle()
    assertEquals(listOf("new tab $parent"), calls)
    val tabCount = tabs.tabs.value.size

    // In a tab now: greyed with the chip, and a second middle-click opens nothing but says where it is.
    onNodeWithText("In tab", useUnmergedTree = true).assertExists()
    onNodeWithContentDescription("Parent post, favourited").performMouseInput { middleClick() }
    waitForIdle()
    assertEquals(tabCount, tabs.tabs.value.size, "no second tab")
    onNodeWithText("Already tab", substring = true, useUnmergedTree = true).assertExists()

    calls.clear()
    onNodeWithContentDescription("Parent post, favourited").performClick()
    waitForIdle()
    assertEquals(listOf("now $parent"), calls)
  }

  @Test
  fun aClickOpensAChildHereAndAFullStripEndsInATileOpeningThemAll() = runDesktopComposeUiTest {
    childCount = 20
    showPost()
    awaitLabel("Child post")

    onAllNodesWithContentDescription("Child post")[0].performScrollTo().performClick()
    waitForIdle()
    assertEquals(listOf("now ${Route.Post("${CHILD}1", MD5, emptyList(), null, "s")}"), calls)

    calls.clear()
    onNode(hasScrollToKeyAction() and hasAnyDescendant(hasContentDescription("Child post"))).performScrollToKey("more")
    onNodeWithText("All").performClick()
    waitForIdle()
    assertEquals(listOf("now ${Route.Grid(listOf("parent:$POST"))}"), calls)
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExists() = assertExists()

  private fun androidx.compose.ui.test.MouseInjectionScope.middleClick() {
    moveTo(center)
    press(MouseButton.Tertiary)
    release(MouseButton.Tertiary)
  }

  private companion object {
    const val POST = "fixture-post"
    const val PARENT = "fixture-parent"
    const val CHILD = "fixture-child-"
    const val RECOMMENDED = "fixture-recommended"
    const val MD5 = "0123456789abcdef0123456789abcdef"

    fun postJson(id: String, favourited: Boolean, parent: String? = null) = """
      { "id": "$id", "md5": "$MD5", "rating": "s", "status": "active",
        "preview_url": "https://fixture.invalid/preview.jpg", "file_url": "https://fixture.invalid/file.png",
        "file_type": "image/png", "is_favorited": $favourited${parent?.let { ", \"parent_id\": \"$it\"" } ?: ""} }
    """.trimIndent()

    fun page(vararg posts: String) = """{ "meta": { "next": null, "prev": null }, "data": [${posts.joinToString(",")}] }"""
  }
}
