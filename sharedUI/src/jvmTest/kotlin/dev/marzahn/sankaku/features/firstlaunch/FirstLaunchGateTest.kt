package dev.marzahn.sankaku.features.firstlaunch

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.PlatformContext
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.App
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.FirstLaunch
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.appModules
import dev.marzahn.sankaku.features.crash.CrashDismissTag
import dev.marzahn.sankaku.features.crash.CrashNoticeTag
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #210: the whole app, as it starts, with every request it sends counted. On first launch only the first-launch screen
 * shows and nothing reaches Sankaku (nor its CDN) until Continue.
 */
@OptIn(ExperimentalTestApi::class)
class FirstLaunchGateTest {
  private val requests = AtomicInteger()
  private val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private val firstLaunchFile = InMemoryTextFile()

  private val crashFile = InMemoryTextFile()

  private fun start(firstLaunch: TextFile = firstLaunchFile, privacy: TextFile = InMemoryTextFile()) {
    val cache = Files.createTempDirectory("first-launch").toFile().apply { deleteOnExit() }
    val platform = module {
      single<TokenStore> { InMemoryTokenStore() }
      single { TokenStorageStatus.InMemory }
      single { DebugFlags() }
      single(CACHE_DIR) { cache.toOkioPath() }
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, ACCESSIBILITY_FILE, QUEUE_FILE, GRID_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, READING_FILE, OUTBOX_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TextFile>(FIRST_LAUNCH_FILE) { firstLaunch }
      single<TextFile>(PRIVACY_FILE) { privacy }
      single { CrashReports(crashFile) }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<SaveFolder> { DirectorySaveFolder(cache, opener = {}) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    // Every client (auth, main, images) is built on this engine; it answers nothing useful, only counts.
    val engine = module {
      single<HttpClientEngine> {
        MockEngine {
          requests.incrementAndGet()
          hosts += it.url.host
          respondError(HttpStatusCode.NotFound)
        }
      }
    }
    startKoin { modules(appModules(platform) + engine) }
  }

  @AfterTest
  fun stop() = stopKoin()

  @Test
  fun nothingIsRequestedUntilContinue() = runComposeUiTest {
    start()
    setContent { App(onQuit = {}) }
    waitForIdle()
    onNodeWithTag(FirstLaunchTag).assertIsDisplayed()
    onNodeWithText(FirstLaunchAbout).assertIsDisplayed()
    onNodeWithText(FirstLaunchRisk).assertIsDisplayed()
    onNodeWithText(FirstLaunchAge).assertIsDisplayed()
    // Long enough for anything the shell would start at launch (first pages, the account, previews) to have gone out.
    mainClock.advanceTimeBy(5_000)
    Thread.sleep(500)
    waitForIdle()
    assertEquals(0, requests.get())

    onNodeWithTag(FirstLaunchContinueTag).performClick()
    waitUntil(timeoutMillis = 10_000) { requests.get() > 0 }
    onNodeWithTag(FirstLaunchTag).assertDoesNotExist()
    assertTrue(KoinPlatform.getKoin().get<FirstLaunch>().accepted.value)
    // The update check (#209) waits for Continue too, then asks GitHub once; its 404 here is "no update".
    waitUntil(timeoutMillis = 10_000) { "api.github.com" in hosts }
  }

  /** #208: a crash before Continue (on this screen, say) is offered once the screen is accepted, never over it. */
  @Test
  fun aCrashNoticeWaitsForContinueThenDismissStartsTheApp() = runComposeUiTest {
    CrashReports(crashFile).record(IllegalStateException())
    start()
    setContent { App(onQuit = {}) }
    waitForIdle()
    onNodeWithTag(FirstLaunchTag).assertIsDisplayed()
    onNodeWithTag(CrashNoticeTag).assertDoesNotExist()

    onNodeWithTag(FirstLaunchContinueTag).performClick()
    waitForIdle()
    onNodeWithTag(CrashNoticeTag).assertIsDisplayed()
    // In place of the app: nothing of the shell has started, so nothing went to Sankaku.
    mainClock.advanceTimeBy(5_000)
    Thread.sleep(500)
    waitForIdle()
    assertTrue(hosts.none { "sankaku" in it }, hosts.toString())

    onNodeWithTag(CrashDismissTag).performClick()
    waitUntil(timeoutMillis = 10_000) { hosts.any { "sankaku" in it } }
    onNodeWithTag(CrashNoticeTag).assertDoesNotExist()
    assertNull(crashFile.read())
  }

  @Test
  fun quitExitsWithoutAcceptingOrLoading() = runComposeUiTest {
    start()
    var quits = 0
    setContent { App(onQuit = { quits++ }) }
    onNodeWithTag(FirstLaunchQuitTag).performClick()
    waitForIdle()

    assertEquals(1, quits)
    assertFalse(KoinPlatform.getKoin().get<FirstLaunch>().accepted.value)
    onNodeWithTag(FirstLaunchTag).assertIsDisplayed()
    assertEquals(0, requests.get())
    // The next launch asks again.
    assertFalse(FirstLaunch(firstLaunchFile).accepted.value)
  }

  @Test
  fun onceAcceptedTheNextLaunchGoesStraightToTheApp() = runComposeUiTest {
    FirstLaunch(firstLaunchFile).accept()
    start()
    setContent { App(onQuit = {}) }
    waitUntil(timeoutMillis = 10_000) { requests.get() > 0 }
    onNodeWithTag(FirstLaunchTag).assertDoesNotExist()
  }

  @Test
  fun continueLeavesTheGOnlyDefaultsAsTheyWere() = runComposeUiTest {
    start()
    setContent { App(onQuit = {}) }
    onNodeWithTag(FirstLaunchContinueTag).performClick()
    waitForIdle()

    val privacy = KoinPlatform.getKoin().get<PrivacySettings>()
    assertFalse(privacy.gOnlySwitch.value)
    assertFalse(privacy.alwaysStartGOnly.value)
  }

  @Test
  fun alwaysStartInGOnlyStillStartsInGOnlyPastTheScreen() = runComposeUiTest {
    val privacyFile = InMemoryTextFile().also { PrivacySettings(it).setAlwaysStartGOnly(true) }
    start(privacy = privacyFile)
    setContent { App(onQuit = {}) }
    onNodeWithTag(FirstLaunchContinueTag).performClick()
    waitForIdle()

    assertTrue(KoinPlatform.getKoin().get<PrivacySettings>().gOnly.value)
  }
}
