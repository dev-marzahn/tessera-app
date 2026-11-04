package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.marzahn.sankaku.WindowBackHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.PUBLIC_REPO_URL
import dev.marzahn.sankaku.shell.Sidebar
import dev.marzahn.sankaku.shell.TopBar
import dev.marzahn.sankaku.shell.menuDescription
import dev.marzahn.sankaku.ui.components.rememberFullScreenSearchState
import dev.marzahn.sankaku.update.UpdateCheck
import dev.marzahn.sankaku.update.createGitHubClient
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.di.authModule
import dev.marzahn.sankaku.di.sharedModule
import dev.marzahn.sankaku.di.shellModule
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.SETTINGS_TITLE
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.UPDATE_AVAILABLE_DOT
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
import org.koin.mp.KoinPlatform
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.test.assertTrue

/**
 * Settings' screen (#205): its sections by heading, the platform's rows, and Storage's wipes each asking once. State and
 * semantics only; how it looks is checked in the running app.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
  private val shown = mutableListOf<String>()

  @BeforeTest
  fun startFixtureKoin() {
    val cache = Files.createTempDirectory("settings-screen").toFile()
    val platform = module {
      single<TokenStore> { InMemoryTokenStore() }
      single { TokenStorageStatus.InMemory }
      single { DebugFlags() }
      single(CACHE_DIR) { cache.toOkioPath() }
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, PRIVACY_FILE, ACCESSIBILITY_FILE, QUEUE_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, FIRST_LAUNCH_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    val fixtures = module {
      single<HttpClientEngine> { MockEngine { respondError(HttpStatusCode.NotFound) } }
      single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
    }
    startKoin { modules(sharedModule, platform, shellModule, authModule, fixtures) }
  }

  @AfterTest
  fun stop() = stopKoin()

  private fun ComposeUiTest.show(content: @androidx.compose.runtime.Composable () -> Unit) {
    setContent {
      SankakuTheme {
        val messages = koinInject<Messages>()
        LaunchedEffect(messages) { messages.messages.collect { shown += it.text } }
        content()
      }
    }
    waitForIdle()
  }

  private fun heading(title: String) = hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

  private fun ComposeUiTest.assertHeadings(present: List<String>, absent: List<String>) {
    present.forEach { onNode(heading(it)).assertExists(it) }
    absent.forEach { onNode(heading(it)).assertDoesNotExist() }
  }

  @Test
  fun desktopShowsItsSectionsAndTheKeychainWarning() = runComposeUiTest {
    show { SettingsScreen() }

    assertHeadings(
      present = listOf("Settings", "Account", "Privacy", "Queue", "Storage", "Updates", "Diagnostics", "About"),
      // Accessibility is the phone's.
      absent = listOf("Accessibility"),
    )
    onNodeWithText("Not signed in").assertExists()
    onNodeWithText("Sign in").assertExists()
    onNodeWithText(InMemoryTokensWarning).assertExists()
    onNodeWithText("Hide in Recents").assertDoesNotExist()
    onNodeWithText("Always start in G only").assertExists()
    onNodeWithText("Clear everything…").assertExists()
    onNodeWithText("Show For you in the queue").assertExists()
    onNodeWithText("Cache in use: Empty").assertExists()
    onNodeWithText("Clear saved tabs…").assertExists()
  }

  @Test
  fun aPhoneShowsItsOwnRowsAndNoKeychainWarning() = runComposeUiTest {
    show { Column { SettingsSections(touch = true) } }

    assertHeadings(
      present = listOf("Account", "Privacy", "Queue", "Accessibility", "Storage", "Updates", "Diagnostics", "About"),
      absent = emptyList(),
    )
    onNodeWithText("Hide in Recents").assertExists()
    onNodeWithText("Start on").assertExists()
    onNodeWithText("Show queue buttons").assertExists()
    onNodeWithText("Clear the queue…").assertExists()
    onNodeWithText(InMemoryTokensWarning).assertDoesNotExist()
  }

  @Test
  fun clearSavedTabsAsksOnceAndCancelKeepsTheTabs() = runComposeUiTest {
    val tabs = KoinPlatform.getKoin().get<TabsController>()
    tabs.open(Route.Grid(listOf("artist_x")), background = true)
    show { SettingsScreen() }

    onNodeWithText("Clear saved tabs…").performClick()
    waitForIdle()
    onNodeWithText("Clear saved tabs?").assertExists()
    onNodeWithText("Cancel").performClick()
    waitForIdle()
    assertEquals(2, tabs.tabs.value.size)

    onNodeWithText("Clear saved tabs…").performClick()
    waitForIdle()
    // The dialog's button, not the row behind it.
    onNode(hasText("Clear saved tabs", substring = false)).performClick()
    waitForIdle()
    onNodeWithText("Clear saved tabs?").assertDoesNotExist()
    assertEquals(1, tabs.tabs.value.size)
    assertEquals(listOf("Tabs cleared"), shown)
  }

  @Test
  fun clearCacheAsksOnceThenSaysSo() = runComposeUiTest {
    show { SettingsScreen() }

    onNodeWithText("Clear cache…").performClick()
    waitForIdle()
    onNodeWithText(clearCacheConfirmText(touch = false)).assertExists()
    onNodeWithText("Clear cache").performClick()
    waitUntil(timeoutMillis = 5_000) { shown.isNotEmpty() }
    assertTrue("Cache cleared" in shown)
    onNodeWithText("Cache in use: Empty").assertExists()
  }

  /** An update check that already knows of `v999.0.0` from today's answer, so it asks nothing. */
  private fun knowsOfANewerRelease(): UpdateCheck {
    val file = InMemoryTextFile(
      """{ "lastCheckEpochMs": ${Clock.System.now().toEpochMilliseconds()}, "latestVersion": "999.0.0",
         "latestUrl": "$PUBLIC_REPO_URL/releases/tag/v999.0.0" }""",
    )
    return UpdateCheck(file, createGitHubClient(MockEngine { error("asked GitHub") }), Clock.System)
  }

  @Test
  fun updatesHasTheSwitchOnByDefaultAndNoRowWithoutANewerRelease() = runComposeUiTest {
    show { SettingsScreen() }

    onNode(heading("Updates")).assertExists()
    onNode(hasText(CHECK_FOR_UPDATES) and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
      .assertExists()
    onNodeWithText(CheckForUpdatesHelp).assertExists()
    onNodeWithText(UPDATE_AVAILABLE_DOT, substring = true).assertDoesNotExist()
  }

  @Test
  fun aNewerReleaseShowsTheRowThatOpensItsPageAndTheSwitchHidesIt() = runComposeUiTest {
    val updates = knowsOfANewerRelease()
    val opened = mutableListOf<String>()
    show { Column { UpdatesRows(updates, open = { opened += it }) } }

    onNodeWithText("Update available · 999.0.0").performClick()
    assertEquals(listOf("$PUBLIC_REPO_URL/releases/tag/v999.0.0"), opened)

    onNodeWithText(CHECK_FOR_UPDATES).performClick()
    waitForIdle()
    assertFalse(updates.enabled.value)
    onNodeWithText("Update available · 999.0.0").assertDoesNotExist()
  }

  @Test
  fun aNewerReleasePutsADotOnTheMenuAndOnSettings() = runComposeUiTest {
    val updates = knowsOfANewerRelease()
    show {
      Column {
        TopBar(
          current = null,
          canGoBack = false,
          menuOpen = false,
          onMenu = {},
          onOpenSearch = {},
          onSearchPlaced = {},
          fullScreenSearch = rememberFullScreenSearchState(),
          updates = updates,
        )
        Sidebar(open = true, onClose = {}, updates = updates)
      }
    }

    onNodeWithContentDescription("Menu, update available").assertExists()
    onNode(hasText(SETTINGS_TITLE) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, UPDATE_AVAILABLE_DOT))
      .assertExists()

    updates.setEnabled(false)
    waitForIdle()
    onNodeWithContentDescription("Menu").assertExists()
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, UPDATE_AVAILABLE_DOT)).assertDoesNotExist()
  }

  /**
   * The desktop window, as `ComposeSceneMediator` hands it a key: the scene first (its focused layer: an open dialog,
   * else the window's content), then the window's `onKeyEvent` (Main.kt sets none that takes Esc), then the back
   * dispatcher, whose last-added handler gets it.
   */
  private class TestWindow : NavigationEventInput() {
    var tabCloses = 0

    @OptIn(InternalComposeUiApi::class)
    fun pressEsc(test: ComposeUiTest) {
      val scene = (test as SkikoComposeUiTest).scene
      for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
        val event = KeyEvent(Key.Escape, type)
        test.runOnIdle {
          if (!scene.sendKeyEvent(event) && type == KeyEventType.KeyDown) dispatchOnBackCompleted()
        }
      }
      test.waitForIdle()
    }
  }

  /** The Settings tab as the desktop window holds it (Main.kt), with [window] delivering its keys. */
  private fun ComposeUiTest.showTheSettingsTab(window: TestWindow) = show {
    val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
    DisposableEffect(dispatcher) {
      dispatcher.addInput(window)
      onDispose { dispatcher.removeInput(window) }
    }
    WindowBackHandler(enabled = true, onBack = { window.tabCloses++ })
    SettingsScreen()
  }

  @Test
  fun escOnAConfirmationClosesOnlyItAndFocusStaysOnItsRow() = runComposeUiTest {
    val window = TestWindow()
    showTheSettingsTab(window)

    for ((row, title) in listOf(
      "Clear everything…" to "Clear everything?",
      "Clear cache…" to "Clear cache?",
      "Clear saved tabs…" to "Clear saved tabs?",
    )) {
      onNodeWithText(row).performClick()
      waitForIdle()
      onNodeWithText(row).assertIsFocused()
      onNodeWithText(title).assertExists()

      window.pressEsc(this)

      onNodeWithText(title).assertDoesNotExist()
      assertEquals(0, window.tabCloses, "Esc on \"$title\" closed the Settings tab")
      onNodeWithText(row).assertIsFocused()
    }
  }

  @Test
  fun escWithNoDialogOpenClosesTheSettingsTab() = runComposeUiTest {
    val window = TestWindow()
    showTheSettingsTab(window)

    // Fresh, with nothing focused.
    window.pressEsc(this)
    assertEquals(1, window.tabCloses)

    // And once a dialog has come and gone, with focus left on its row.
    onNodeWithText("Clear cache…").performClick()
    waitForIdle()
    onNodeWithText("Cancel").performClick()
    waitForIdle()
    window.pressEsc(this)
    assertEquals(2, window.tabCloses)
  }

  @Test
  fun theMenusDescriptionSaysWhatItsBadgeMeans() {
    assertEquals("Menu", menuDescription(expired = false, updateAvailable = false))
    assertEquals("Menu, update available", menuDescription(expired = false, updateAvailable = true))
    assertEquals("Menu, session expired", menuDescription(expired = true, updateAvailable = true))
  }
}
