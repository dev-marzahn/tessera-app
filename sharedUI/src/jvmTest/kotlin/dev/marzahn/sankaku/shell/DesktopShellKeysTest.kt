package dev.marzahn.sankaku.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.App
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.FirstLaunch
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.QueueSettings
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.appModules
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.NewTab
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.modeTag
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * #542, the whole desktop shell: Cmd/Ctrl-T (and the strip's +) brings a Browse tab with its search field focused, so
 * what's typed next goes into the search instead of acting as the grid's letter keys; C opens the tab switcher in the
 * mode it was last left in, which is saved; and Shift+X from the screen takes "Done with …?"'s offer.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopShellKeysTest {
  private val queueFile = InMemoryTextFile()

  @BeforeTest
  fun start() {
    // An earlier test class may have left one set (FirstLaunchGateTest starts the whole app too).
    SingletonImageLoader.reset()
    val cache = Files.createTempDirectory("new-tab-search").toFile().apply { deleteOnExit() }
    val platform = module {
      single<TokenStore> { InMemoryTokenStore() }
      single { TokenStorageStatus.InMemory }
      single { DebugFlags() }
      single(CACHE_DIR) { cache.toOkioPath() }
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, ACCESSIBILITY_FILE, GRID_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, READING_FILE, OUTBOX_FILE, FIRST_LAUNCH_FILE, PRIVACY_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TextFile>(QUEUE_FILE) { queueFile }
      single { CrashReports(InMemoryTextFile()) }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<SaveFolder> { DirectorySaveFolder(cache, opener = {}) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    // Nothing needs an answer here: every request is refused.
    val engine = module { single<HttpClientEngine> { MockEngine { respondError(HttpStatusCode.NotFound) } } }
    startKoin { modules(appModules(platform) + engine) }
    KoinPlatform.getKoin().get<FirstLaunch>().accept()
  }

  @AfterTest
  fun stop() {
    // The app sets Coil's process-wide loader as it starts: the next test (this class's or another's) starts its own.
    SingletonImageLoader.reset()
    stopKoin()
  }

  private val focusedField = hasSetTextAction() and isFocused()

  @Test
  fun aNewTabOpensWithItsSearchFocused() = runComposeUiTest {
    setContent { App(onQuit = {}) }
    waitForIdle()
    val tabs = KoinPlatform.getKoin().get<TabsController>()
    val before = tabs.active.value
    onAllNodes(focusedField).assertCountEquals(0)

    KoinPlatform.getKoin().get<NewTab>().open()
    waitForIdle()

    assertNotEquals(before, tabs.active.value)
    onAllNodes(focusedField).assertCountEquals(1)
  }

  @Test
  fun anotherTabComingForwardStillClosesTheSearch() = runComposeUiTest {
    setContent { App(onQuit = {}) }
    waitForIdle()
    val tabs = KoinPlatform.getKoin().get<TabsController>()
    val first = tabs.active.value
    KoinPlatform.getKoin().get<NewTab>().open()
    waitForIdle()
    onAllNodes(focusedField).assertCountEquals(1)

    tabs.activate(first)
    waitForIdle()

    onAllNodes(focusedField).assertCountEquals(0)
  }

  @Test
  fun theSwitcherOpensInTheModeItWasLastLeftIn() = runComposeUiTest {
    QueueSettings(queueFile).setSwitcherList(true)
    // Read at startup, as after a restart.
    stopKoin()
    start()
    assertEquals(true, KoinPlatform.getKoin().get<QueueSettings>().switcherList.value)
    setContent { App(onQuit = {}) }
    waitForIdle()

    onRoot().performKeyInput { pressKey(Key.C) }
    waitForIdle()
    onNodeWithTag(modeTag("Queue")).assertIsSelected()

    // Q back to the cards is saved too.
    onNodeWithTag(modeTag("Queue")).performKeyInput { pressKey(Key.Q) }
    waitForIdle()
    onNodeWithTag(modeTag("Cards")).assertIsSelected()
    assertEquals(false, QueueSettings(queueFile).switcherList.value)
  }

  // #502's claim, checked from the shell (#542): with the screen holding the keys, Shift+X while the offer shows closes
  // the block it names.
  @Test
  fun shiftXFromTheScreenTakesTheOffer() = runComposeUiTest {
    val koin = KoinPlatform.getKoin()
    val tabs = koin.get<TabsController>()
    val seen = koin.get<SeenStore>()
    fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")
    fun id(title: String) = tabs.tabs.value.single { it.title == title }.id
    tabs.open(Route.Grid(listOf("landscape")), background = false)
    tabs.openInBlock(listOf(Route.Grid(listOf("landscape"), at = "l1"), post("l1")))
    tabs.open(Route.Grid(listOf("cat")), background = false)
    tabs.openInBlock(listOf(Route.Grid(listOf("cat"), at = "c1"), post("c1")))
    setContent { App(onQuit = {}) }
    waitForIdle()
    // The kept post is seen, then the next block comes forward: Landscape is done with.
    tabs.activate(id("l1"))
    waitForIdle()
    // Seen after it was kept, on a clock that may not have moved yet.
    Thread.sleep(20)
    seen.record("l1", null)
    tabs.activate(id("Cat"))
    waitForIdle()
    onNodeWithText("Done with Landscape?", substring = true).assertExists()

    onAllNodes(isRoot())[0].performKeyInput {
      withKeyDown(Key.ShiftLeft) { pressKey(Key.X) }
    }
    waitForIdle()

    assertEquals(listOf("Cat", "c1"), tabs.tabs.value.map { it.title }.filter { it != "Browse" })
    onNodeWithText("Closed “Landscape”", substring = true).assertExists()
  }
}
