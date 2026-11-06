package dev.marzahn.sankaku.features.settings

import coil3.ImageLoader
import coil3.PlatformContext
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.RecentSearches
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Clear everything (#164) wipes the seen store and the copies of `tabs.json` too (#174), and the videos played (#180). */
class ClearEverythingTest {
  private val scope = CoroutineScope(SupervisorJob())

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun itEmptiesTheSeenStoreAndTheTabFiles() = runBlocking {
    val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
    val log = RequestLog(Clock.System)
    val limiter = RateLimiter(limits = emptyMap())
    val connectivity = ConnectivityState()
    val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), Clock.System), InMemoryTokenStore())
    val client = createMainClient(engine, log, limiter, connectivity, session)
    val tabsFile = InMemoryTextFile()
    val backup = InMemoryTextFile("a v1 file")
    val broken = InMemoryTextFile("a broken file")
    val tabStore = FileTabStateStore(tabsFile, backup, broken)
    val tabs = TabsController(tabStore)
    tabs.open(Route.Grid(listOf("artist_x")), background = true)
    val seenFile = InMemoryTextFile()
    val seen = SeenStore(seenFile)
    seen.record("p1", "aa11")
    var videosCleared = false
    val clear = ClearEverything(
      tabs,
      tabStore,
      RecentSearches(),
      TagsRepository(TagsApi(client)),
      PostsRepository(PostsApi(client), PostStateStore(), session, scope),
      ImageLoader.Builder(PlatformContext.INSTANCE).build(),
      seen,
      clearVideos = { videosCleared = true },
    )

    clear.run()

    assertEquals(0, seen.size)
    assertEquals(0, SeenStore(seenFile).size)
    assertEquals("{}", tabsFile.read())
    assertNull(backup.read())
    assertNull(broken.read())
    assertEquals(1, tabs.tabs.value.size)
    assertTrue(videosCleared)
  }

  @Test
  fun signedInFollowingIsMadeAgainWithoutItsPlaceAndTheFileHoldsNoPlace() = runBlocking {
    val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
    val log = RequestLog(Clock.System)
    val limiter = RateLimiter(limits = emptyMap())
    val connectivity = ConnectivityState()
    val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), Clock.System), InMemoryTokenStore())
    val client = createMainClient(engine, log, limiter, connectivity, session)
    val tabStore = FileTabStateStore(InMemoryTextFile())
    val tabs = TabsController(tabStore)
    tabs.pinFeeds(listOf(Feed.Following))
    val following = Route.Grid(listOf(Feed.Following.tag))
    tabs.tabs.value.single { it.feed == Feed.Following }.reportPlace(following, GridPlace("p9", cursor = "c", page = 3))
    val clear = ClearEverything(
      tabs,
      tabStore,
      RecentSearches(),
      TagsRepository(TagsApi(client)),
      PostsRepository(PostsApi(client), PostStateStore(), session, scope),
      ImageLoader.Builder(PlatformContext.INSTANCE).build(),
      SeenStore(InMemoryTextFile()),
      clearVideos = {},
    )

    clear.run()

    assertEquals(listOf("Browse", "Following"), tabs.tabs.value.map { it.title })
    assertTrue(tabs.saved().tabs.all { it.place == null })
  }
}
