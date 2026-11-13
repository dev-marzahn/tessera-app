package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import coil3.PlatformContext
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.ActiveTab
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
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
import dev.marzahn.sankaku.queue.FeedGap
import dev.marzahn.sankaku.queue.FeedMark
import dev.marzahn.sankaku.queue.FeedMarks
import dev.marzahn.sankaku.queue.FeedMarksStore
import dev.marzahn.sankaku.queue.FeedReached
import dev.marzahn.sankaku.queue.FeedReading
import dev.marzahn.sankaku.queue.TimeSpan
import dev.marzahn.sankaku.ui.components.NEW_BADGE_TAG
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import dev.marzahn.sankaku.shell.GridRefresh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import java.util.concurrent.atomic.AtomicInteger
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * #314: Following's headings in the real [GridScreen], on a fixture feed (no pictures) and marks set before it opens.
 * The feed: `n0`…`n11`, then `p0`…`p67`, a minute apart, newest first, 40 to a page; page 2's cursor is `c1`.
 */
@OptIn(ExperimentalTestApi::class)
class FeedMarksGridTest {
  private val now = Clock.System.now().epochSeconds
  private val ids = List(12) { "n$it" } + List(68) { "p$it" }
  private fun time(id: String) = now - 3_600 - ids.indexOf(id) * 60L
  private fun mark(id: String, cursor: String? = null, page: Int = 1) = FeedMark(id, time(id), cursor, page)

  private val cursors = CopyOnWriteArrayList<String?>()

  /** The followed tags' ids the mock lists (#539: Following's context). */
  private val FOLLOWED = listOf("t1", "t2")

  /** How often the followed tags were read, and, when set, what the next reads wait for. */
  private val followingsReads = AtomicInteger()
  @Volatile private var holdFollowings: CompletableDeferred<Unit>? = null

  private fun page(from: Int, next: String?) = ids.drop(from).take(40).joinToString(
    ",",
    """{"meta":{"next":${next?.let { "\"$it\"" } ?: "null"},"prev":null},"data":[""",
    "]}",
  ) { id ->
    """{"id":"$id","md5":"md5-$id","rating":"s","status":"active","file_type":"image/png","width":300,"height":300,""" +
      """"preview_width":300,"preview_height":300,"created_at":{"json_class":"Time","s":${time(id)},"n":0}}"""
  }

  private fun startFixtureKoin() {
    val cache = Files.createTempDirectory("feed-marks").toFile().apply { deleteOnExit() }
    val platform = module {
      // Signed in: Following needs a session.
      single<TokenStore> {
        val at = Clock.System.now()
        InMemoryTokenStore(StoredTokens("access", "refresh", at + 7.days, at + 90.days, UserSummary(id = "42", name = "tester", avatarUrl = null)))
      }
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
            val next = request.url.parameters["next"]
            cursors += next
            val body = if (next == "c1") page(40, null) else page(0, "c1")
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
          } else if (request.url.encodedPath == "/users/followings") {
            followingsReads.incrementAndGet()
            holdFollowings?.await()
            val list = FOLLOWED.joinToString(",") { """{"id":"$it","name":"tag_$it","tagName":"tag_$it","type":0,"is_following":true}""" }
            respond("""{"list":[$list]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
          } else {
            respondError(HttpStatusCode.NotFound)
          }
        }
      }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule, fixtures) }
  }

  @AfterTest
  fun stop() = stopKoin()

  private fun following(
    marks: FeedMarks?,
    height: Int = 900,
    place: GridPlace? = null,
    test: ComposeUiTest.(FeedMarksStore) -> Unit,
  ) {
    startFixtureKoin()
    val koin = GlobalContext.get()
    val store = koin.get<FeedMarksStore>()
    marks?.let { store.set(Feed.Following, it) }
    val tabs = koin.get<TabsController>()
    runBlocking { koin.get<AuthSession>().restore() }
    val route = Route.Grid(listOf(Feed.Following.tag))
    tabs.open(route, background = false)
    tabs.tabs.value.filter { it.top != route }.forEach { tabs.close(it.id) }
    place?.let { tabs.tabs.value.single { it.top == route }.reportPlace(route, it) }
    val navigator = TabsNavigator(tabs)
    // #582: the default effect dispatcher is unconfined, so an effect ran on whichever thread resumed it (a repository's
    // Default-dispatcher write, the EDT's Dispatchers.Main), outside the test's frames. One that measures, as Continue's
    // scrollToItem does, could then lay the grid out alongside the test (Compose's "multithreaded access to
    // SnapshotStateObserver"). A standard one queues every effect for the test's own frames, as the app's UI thread does.
    runDesktopComposeUiTest(width = 1200, height = height, effectContext = StandardTestDispatcher()) {
      setContent {
        SankakuTheme {
          val activeId by tabs.active.collectAsState()
          val active = tabs.tabs.collectAsState().value.first { it.id == activeId }
          ActiveTab(active, tabs, navigator, rememberSaveableStateHolder()) { key ->
            NavEntry<NavKey>(key) { if (key is Route.Grid) GridScreen(key) }
          }
        }
      }
      waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagPrefix("grid-cell-").isNotEmpty() }
      test(store)
    }
  }

  private fun ComposeUiTest.onAllNodesWithTagPrefix(prefix: String) =
    onAllNodes(SemanticsMatcher("tag starts with $prefix") {
      SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag].startsWith(prefix)
    }).fetchSemanticsNodes()

  @Test
  fun newPostsHaveNNewAtTheTopAndCaughtUpWhereTheReadPostsStart() = following(FeedMarks(mark("p0"), at = now - 86_400), height = 2000) {
    val new = onNodeWithTag("feed-heading-new").assertIsDisplayed()
    onNodeWithText("12 new").assertIsDisplayed()
    val caughtUp = onNodeWithTag("feed-heading-caught-up").getBoundsInRoot()
    val firstNew = onNodeWithTag(gridCellTag(0)).getBoundsInRoot()
    val lastNew = onNodeWithTag(gridCellTag(11)).getBoundsInRoot()
    val firstRead = onNodeWithTag(gridCellTag(12)).getBoundsInRoot()

    assertTrue(new.getBoundsInRoot().bottom <= firstNew.top, "N new is above the first new post")
    assertTrue(caughtUp.top >= lastNew.top && caughtUp.bottom <= firstRead.top, "caught up sits between the new and the read posts")
    assertTrue(caughtUp.right - caughtUp.left > (firstNew.right - firstNew.left) * 3, "full-span")
  }

  @Test
  fun withNewPostsFollowingOpensAtTheTopNotItsPlace() = following(
    FeedMarks(mark("p0"), at = now - 86_400),
    place = GridPlace("p20", time = time("p20")),
  ) {
    onNodeWithText("12 new").assertIsDisplayed()
    onNodeWithTag(gridCellTag(0)).assertIsDisplayed()
  }

  @Test
  fun withNothingNewFollowingOpensAtItsPlace() = following(
    FeedMarks(mark("n0"), at = now - 86_400),
    // On page 1: the 33rd post.
    place = GridPlace("p20", time = time("p20")),
  ) {
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagPrefix(gridCellTag(32)).isNotEmpty() }
    onNodeWithTag(gridCellTag(32)).assertIsDisplayed()
    assertTrue(onAllNodesWithTagPrefix("feed-heading-new").isEmpty())
  }

  // #539: the last visit read n0..p20 but p3 wasn't there yet: it was uploaded then, and turned up late.
  private fun readWithoutP3() = FeedMarks(
    mark("n0"),
    at = now - 86_400,
    reached = FeedReached(
      posts = ids.take(33).filter { it != "p3" }.associateWith(::time),
      // Read with the follows the mock lists, G only on or off (whichever the test's settings have).
      spans = listOf(true, false).map { gOnly ->
        TimeSpan(time("p20"), time("n0"), read = now - 60, context = FeedReading.context(searchedTags(listOf(Feed.Following.tag), true, gOnly), FOLLOWED))
      },
    ),
  )

  @Test
  fun withOtherFollowsALatePostIsntNew() = following(
    readWithoutP3().let { m -> m.copy(reached = m.reached.copy(spans = m.reached.spans.map { it.copy(context = "other|0") })) },
  ) {
    // #557 review: read while Following showed other tags, p3 may have been hidden then, not late.
    onNodeWithTag("feed-heading-up-to-date").assertIsDisplayed()
    assertEquals(0, onAllNodes(hasTestTag(NEW_BADGE_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
  }

  @Test
  fun aPostThatTurnedUpLateIsMarkedNewAndCounted() = following(readWithoutP3(), height = 2000, place = GridPlace("p30", time = time("p30"))) {
    // It opens at the top for it, not at the place, as for posts newer than the top.
    onNodeWithText("1 new").assertIsDisplayed()
    onNodeWithText("Further down").assertIsDisplayed()
    assertTrue(onAllNodesWithTagPrefix("feed-heading-caught-up").isEmpty())
    val late = onNodeWithTag(gridCellTag(15))
    late.assertIsDisplayed()
    onNode(hasTestTag(NEW_BADGE_TAG) and hasAnyAncestor(hasTestTag(gridCellTag(15))), useUnmergedTree = true).assertExists()
    assertTrue(late.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().contains("New"))
    // No other cell carries it.
    assertEquals(1, onAllNodes(hasTestTag(NEW_BADGE_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
  }

  @Test
  fun furtherDownScrollsToTheLatePost() = following(readWithoutP3(), height = 400) {
    onNode(hasText("Further down") and hasClickAction()).performClick()

    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagPrefix(gridCellTag(15)).isNotEmpty() }
    onNodeWithTag(gridCellTag(15)).assertIsDisplayed()
  }

  @Test
  fun refreshKeepsTheLatePostMarkedWhileTheFollowsAreReadAgain() = following(readWithoutP3(), height = 400) {
    onNodeWithText("1 new").assertIsDisplayed()
    val hold = CompletableDeferred<Unit>().also { holdFollowings = it }
    val before = followingsReads.get()
    val refresh = GlobalContext.get().get<GridRefresh>()

    runOnIdle { refresh.request() }
    waitUntil(timeoutMillis = 10_000) { followingsReads.get() > before }
    // The reload waits up to 2 s to start, then for its page: on a loaded CI runner that took over 5 s (#557 queue).
    waitUntil(timeoutMillis = 20_000) { !refresh.running.value }

    // #557 review: the follows are still being read; what they were a moment ago still holds, so nothing flickers.
    onNodeWithText("1 new").assertIsDisplayed()
    hold.complete(Unit)
  }

  @Test
  fun nothingNewSaysCaughtUpAtTheTopAndOpensTheFollowedTags()= following(FeedMarks(mark("n0"), at = now - 86_400)) {
    val caughtUp = onNodeWithTag("feed-heading-up-to-date").assertIsDisplayed().getBoundsInRoot()
    onNodeWithText(CAUGHT_UP).assertIsDisplayed()
    assertTrue(caughtUp.bottom <= onNodeWithTag(gridCellTag(0)).getBoundsInRoot().top, "above the first post")

    onNode(hasText(FOLLOWED_TAGS) and hasClickAction()).performClick()

    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(FOLLOWED_TAGS)).fetchSemanticsNodes().size >= 2 }
  }

  @Test
  fun aFirstVisitHasNoHeadingsAndMarksTheTop()= following(null) { store ->
    assertTrue(onAllNodesWithTagPrefix("feed-heading-").isEmpty())
    waitUntil(timeoutMillis = 5_000) { store.get(Feed.Following) != null }
    assertEquals("n0", store.get(Feed.Following)?.top?.post)
  }

  @Test
  fun seeingTheNewPostsMovesTheTopButNotPastWhatWasReached() = following(FeedMarks(mark("p0"), at = now - 86_400), height = 400) { store ->
    waitUntil(timeoutMillis = 5_000) { store.get(Feed.Following)?.top?.post == "n0" }
    // Only the first rows show: the rest of the new posts are a gap, which says how many are left.
    val gap = store.get(Feed.Following)?.gap
    assertEquals("p0", gap?.until?.post)
    assertTrue((gap?.left ?: 0) in 1..11, "left: ${gap?.left}")
  }

  @Test
  fun continueOnTheTopHeadingLandsAtTheStopMark() = following(
    FeedMarks(mark("n5"), FeedGap(stop = mark("p50", "c1", 2), until = mark("p60", "c1", 2), left = 9, since = now - 86_400), at = now - 86_400),
  ) {
    onNodeWithText("5 new").assertIsDisplayed()
    onNode(hasText("Continue · 9 left") and hasClickAction()).performClick()

    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTagPrefix("feed-heading-continue").isNotEmpty() }
    onNodeWithTag("feed-heading-continue").assertIsDisplayed()
    onNodeWithText("· 9 left from last time").assertIsDisplayed()
    assertTrue("c1" in cursors, "page 2 was asked for with the stop mark's cursor")
  }

  @Test
  fun theSevenDayOfferLetsTheGapGo() = following(
    // The gap's end is on page 2, so it isn't on screen: a gap seen through to its end is read, and closes.
    FeedMarks(mark("n5"), FeedGap(stop = mark("p2"), until = mark("p60", "c1", 2), left = 57, since = now - 8 * 86_400), at = now - 8 * 86_400),
    height = 1200,
  ) { store ->
    onNodeWithText("Let go").assertIsDisplayed()
    waitUntil(timeoutMillis = 5_000) { store.get(Feed.Following)?.gap?.offered == true }

    onNodeWithText("Let go").performClick()

    waitUntil(timeoutMillis = 5_000) { store.get(Feed.Following)?.gap == null }
    assertTrue(onAllNodesWithTagPrefix("feed-heading-continue").isEmpty())
  }

}
