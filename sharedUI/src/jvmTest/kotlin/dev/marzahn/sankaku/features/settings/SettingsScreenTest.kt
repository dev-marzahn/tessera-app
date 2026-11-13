package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.components.KEY_SCROLL_SCREEN
import dev.marzahn.sankaku.ui.components.SideSheetScrimTag
import dev.marzahn.sankaku.ui.components.SideSheetWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.marzahn.sankaku.WindowBackHandler
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performSemanticsAction
import dev.marzahn.sankaku.data.GridSettings
import dev.marzahn.sankaku.features.post.PlayerPreferences
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
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.PUBLIC_REPO_URL
import dev.marzahn.sankaku.shell.Sidebar
import dev.marzahn.sankaku.shell.ShortcutSheetController
import dev.marzahn.sankaku.shell.TopBar
import dev.marzahn.sankaku.shell.MenuBadge
import dev.marzahn.sankaku.shell.menuDescription
import dev.marzahn.sankaku.shell.rememberMenuBadge
import dev.marzahn.sankaku.ui.components.rememberFullScreenSearchState
import dev.marzahn.sankaku.update.UpdateCheck
import dev.marzahn.sankaku.update.createGitHubClient
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PostSizes
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
      listOf(KNOWN_TAGS_FILE, PLAYER_FILE, REOPEN_HINT_FILE, PRIVACY_FILE, ACCESSIBILITY_FILE, QUEUE_FILE, GRID_FILE, TIPS_FILE, SEEN_FILE, UPDATES_FILE, FIRST_LAUNCH_FILE, READING_FILE, OUTBOX_FILE)
        .forEach { name -> single<TextFile>(name) { InMemoryTextFile() } }
      single<TabStateStore> { FileTabStateStore(InMemoryTextFile()) }
      single<PlatformContext> { PlatformContext.INSTANCE }
    }
    val fixtures = module {
      single<HttpClientEngine> { MockEngine { respondError(HttpStatusCode.NotFound) } }
      single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
      // postModule's, which the rest of that module's view models would drag in.
      single { PlayerPreferences(get(PLAYER_FILE)) }
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

  /** The desktop's Settings sheet (#460), open. */
  @androidx.compose.runtime.Composable
  private fun OpenSheet() {
    val controller = koinInject<SettingsController>()
    remember(controller) { controller.open() }
    SettingsSheet(onSidebarKey = { false })
  }

  private fun heading(title: String) = hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

  private fun ComposeUiTest.assertHeadings(present: List<String>, absent: List<String>) {
    present.forEach { onNode(heading(it)).assertExists(it) }
    absent.forEach { onNode(heading(it)).assertDoesNotExist() }
  }

  @Test
  fun desktopShowsItsSectionsAndTheKeychainWarning() = runComposeUiTest {
    show { OpenSheet() }

    assertHeadings(
      present = listOf("Settings", "Account", "Privacy", "Queue", "Grid", "Videos", "Keyboard", "Storage", "Updates", "Diagnostics", "About"),
      // Accessibility is the phone's.
      absent = listOf("Accessibility"),
    )
    // Keyboard's one row opens the shortcut sheet (#521).
    onNodeWithText(KeyboardShortcutsHelp).assertExists()
    onNodeWithText("Keyboard shortcuts").performScrollTo().performClick()
    assertEquals(true, KoinPlatform.getKoin().get<ShortcutSheetController>().isOpen.value)
    onNodeWithText("Not signed in").assertExists()
    onNodeWithText("Sign in").assertExists()
    onNodeWithText(InMemoryTokensWarning).assertExists()
    onNodeWithText("Hide in Recents").assertDoesNotExist()
    onNodeWithText("Always start in G only").assertExists()
    onNodeWithText("Clear everything…").assertExists()
    onNodeWithText("Show For you in the queue").assertExists()
    onNodeWithText("Left-handed").assertDoesNotExist()
    onNodeWithText("Cache in use: Empty").assertExists()
    onNodeWithText("Clear saved tabs…").assertExists()
  }

  @Test
  fun postSizeHelpCountsTheColumns() {
    assertEquals("Larger posts, fewer columns. 1 column in this window.", postSizeHelp(1))
    assertEquals("Larger posts, fewer columns. 4 columns in this window.", postSizeHelp(4))
    // A phone's has no windows (#468).
    assertEquals("Larger posts, fewer columns. 3 columns on this screen.", postSizeHelp(3, touch = true))
  }

  @Test
  fun startVideosMutedIsOffAndItsSwitchKeepsIt() = runComposeUiTest {
    val player = KoinPlatform.getKoin().get<PlayerPreferences>()
    show { Column { SettingsSections(touch = true) } }

    // The whole row is the switch; it's below the test window, so it's toggled by its action.
    val row = onNode(hasText("Start videos muted") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
    row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    assertFalse(player.startMuted)
    row.performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
    assertTrue(player.startMuted)
  }

  @Test
  fun aPhoneShowsItsOwnRowsAndNoKeychainWarning() = runComposeUiTest {
    show { Column { SettingsSections(touch = true) } }

    assertHeadings(
      present = listOf("Account", "Privacy", "Queue", "Grid", "Videos", "Accessibility", "Storage", "Updates", "Diagnostics", "About"),
      // A phone has no keyboard shortcuts.
      absent = listOf("Keyboard"),
    )
    onNodeWithText("Hide in Recents").assertExists()
    onNodeWithText("Start on").assertExists()
    onNodeWithText("Post size").assertExists()
    onNodeWithText("Show queue buttons").assertExists()
    onNodeWithText("Left-handed").assertExists()
    onNodeWithText("Clear the queue…").assertExists()
    onNodeWithText(InMemoryTokensWarning).assertDoesNotExist()
  }

  @Test
  fun aPhonesPostSizeSliderHasThePhonesRangeAndSteps() = runComposeUiTest {
    // Android's GridSettings (#468): 80 to 200 dp in 10 dp steps.
    val grid = GridSettings(InMemoryTextFile(), PostSizes.Phone)
    KoinPlatform.getKoin().loadModules(listOf(module { single { grid } }), allowOverride = true)
    grid.setCellWidth(110)
    show { Column { SettingsSections(touch = true) } }

    val slider = onNodeWithTag(PostSizeSliderTag)
    slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(110f, 80f..200f, 11)))
    slider.performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
    waitForIdle()
    assertEquals(80, grid.cellWidth.value)
    slider.performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
    waitForIdle()
    assertEquals(200, grid.cellWidth.value)
  }

  @Test
  fun postSizeStartsAtTheDefaultAndTheSliderSetsIt() = runComposeUiTest {
    val grid = KoinPlatform.getKoin().get<GridSettings>()
    show { OpenSheet() }

    val slider = onNodeWithTag(PostSizeSliderTag)
    slider.performScrollTo()
    slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(220f, 160f..560f, 19)))
    slider.performSemanticsAction(SemanticsActions.SetProgress) { it(409f) }
    waitForIdle()

    assertEquals(400, grid.cellWidth.value)
    slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(400f, 160f..560f, 19)))
  }

  @Test
  fun clearSavedTabsAsksOnceAndCancelKeepsTheTabs() = runComposeUiTest {
    val tabs = KoinPlatform.getKoin().get<TabsController>()
    tabs.open(Route.Grid(listOf("artist_x")), background = true)
    show { OpenSheet() }

    onNodeWithText("Clear saved tabs…").performScrollTo().performClick()
    waitForIdle()
    onNodeWithText("Clear saved tabs?").assertExists()
    onNodeWithText("Cancel").performClick()
    waitForIdle()
    assertEquals(2, tabs.tabs.value.size)

    onNodeWithText("Clear saved tabs…").performScrollTo().performClick()
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
    show { OpenSheet() }

    onNodeWithText("Clear cache…").performScrollTo().performClick()
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
    show { OpenSheet() }

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
          menuBadge = rememberMenuBadge(updates = updates),
          onMenu = {},
          onOpenSearch = {},
          onSearchPlaced = {},
          fullScreenSearch = rememberFullScreenSearchState(),
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

  /** The Settings sheet as the desktop window holds it (Main.kt), with [window] delivering its keys. */
  private fun ComposeUiTest.showTheSettingsSheet(window: TestWindow) = show {
    val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
    DisposableEffect(dispatcher) {
      dispatcher.addInput(window)
      onDispose { dispatcher.removeInput(window) }
    }
    WindowBackHandler(enabled = true, onBack = { window.tabCloses++ })
    OpenSheet()
  }

  private val settingsOpen get() = KoinPlatform.getKoin().get<SettingsController>().isOpen.value

  @Test
  fun escOnAConfirmationClosesOnlyItAndFocusStaysOnItsRow() = runComposeUiTest {
    val window = TestWindow()
    showTheSettingsSheet(window)

    for ((row, title) in listOf(
      "Clear everything…" to "Clear everything?",
      "Clear cache…" to "Clear cache?",
      "Clear saved tabs…" to "Clear saved tabs?",
    )) {
      onNodeWithText(row).performScrollTo().performClick()
      waitForIdle()
      onNodeWithText(row).assertIsFocused()
      onNodeWithText(title).assertExists()

      window.pressEsc(this)

      onNodeWithText(title).assertDoesNotExist()
      assertEquals(0, window.tabCloses, "Esc on \"$title\" went back behind Settings")
      assertTrue(settingsOpen, "Esc on \"$title\" closed Settings")
      onNodeWithText(row).assertIsFocused()
    }
  }

  @Test
  fun escWithNoDialogOpenClosesTheSheetAndNothingBehindIt() = runComposeUiTest {
    val window = TestWindow()
    showTheSettingsSheet(window)

    // Fresh, with the keys on the sheet's holder.
    window.pressEsc(this)
    assertFalse(settingsOpen)
    onNodeWithText("Clear cache…").assertDoesNotExist()

    // And once a dialog has come and gone, with focus left on its row.
    KoinPlatform.getKoin().get<SettingsController>().open()
    waitForIdle()
    onNodeWithText("Clear cache…").performScrollTo().performClick()
    waitForIdle()
    onNodeWithText("Cancel").performClick()
    waitForIdle()
    window.pressEsc(this)
    assertFalse(settingsOpen)
    assertEquals(0, window.tabCloses)
  }

  @Test
  fun theScrimAndCloseButtonCloseTheSheet() = runComposeUiTest {
    show { OpenSheet() }

    onNodeWithContentDescription("Close settings").performClick()
    waitForIdle()
    assertFalse(settingsOpen)

    KoinPlatform.getKoin().get<SettingsController>().open()
    waitForIdle()
    // Left of the 480 dp sheet.
    onNodeWithTag(SideSheetScrimTag).performTouchInput { click(centerLeft + Offset(20f, 0f)) }
    waitForIdle()
    assertFalse(settingsOpen)
  }

  @OptIn(InternalComposeUiApi::class)
  @Test
  fun theMouseBackButtonClosesTheSheet() = runComposeUiTest {
    show { OpenSheet() }
    val scene = (this as SkikoComposeUiTest).scene

    // On the sheet itself, as the mouse's back button reaches it over a setting.
    val at = onNodeWithTag(SettingsScrollTag).getUnclippedBoundsInRoot()
    val position = with(density) { Offset((at.left + 40.dp).toPx(), (at.top + 40.dp).toPx()) }
    runOnIdle {
      scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Back)
      scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Back)
    }
    waitForIdle()

    assertFalse(settingsOpen)
  }

  @Test
  fun theSheetIs480DpAtTheRightEdge() = runComposeUiTest {
    show { OpenSheet() }

    val scrim = onNodeWithTag(SideSheetScrimTag).getUnclippedBoundsInRoot()
    val sheet = onNodeWithTag(SettingsScrollTag).getUnclippedBoundsInRoot()
    assertEquals(scrim.right, sheet.right)
    assertEquals(SideSheetWidth, sheet.right - sheet.left)
  }

  /** How far Settings is scrolled, and how far it can go. */
  private fun ComposeUiTest.scrolled(): Pair<Float, Float> {
    val range = onNodeWithTag(SettingsScrollTag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    return range.value() to range.maxValue()
  }

  /** The sheet's sections in a window shorter than they are, as at 1280 × 832 (#423). */
  private fun ComposeUiTest.showShortSettings() =
    show { Box(Modifier.size(480.dp, 400.dp)) { SettingsColumn(onClearedEverything = {}, Modifier.fillMaxSize()) } }

  @Test
  fun wAndSScrollSettingsToItsEndAndBack() = runComposeUiTest {
    showShortSettings()
    val keys = onNodeWithTag(SettingsScrollTag)
    val (top, end) = scrolled()
    assertEquals(0f, top)
    assertTrue(end > 0f, "the sections fit in 400 dp, so nothing scrolls")

    keys.performKeyInput { pressKey(Key.S) }
    waitForIdle()
    val line = scrolled().first
    assertTrue(line > 0f, "S didn't scroll")
    keys.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.S) } }
    waitForIdle()
    assertTrue(scrolled().first - line > line, "Shift+S scrolled no more than a line")

    repeat(40) { keys.performKeyInput { pressKey(Key.S) } }
    waitForIdle()
    assertEquals(end, scrolled().first, "S didn't reach the end")

    repeat(40) { keys.performKeyInput { pressKey(Key.W) } }
    waitForIdle()
    assertEquals(0f, scrolled().first, "W didn't come back to the top")

    // The arrows too, and the shell's chords pass.
    keys.performKeyInput { pressKey(Key.DirectionDown) }
    waitForIdle()
    assertEquals(line, scrolled().first)
    keys.performKeyInput { pressKey(Key.DirectionUp) }
    keys.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
    keys.performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.S) } }
    waitForIdle()
    assertEquals(0f, scrolled().first)
  }

  @Test
  fun wAndSStillScrollWithFocusOnASetting() = runComposeUiTest {
    showShortSettings()
    onNodeWithText("Clear cache…").performScrollTo().performClick()
    onNodeWithText("Cancel").performClick()
    waitForIdle()
    val at = scrolled().first
    assertTrue(at < scrolled().second, "Clear cache… is at the end, so S can't go further")

    onNodeWithText("Clear cache…").assertIsFocused().performKeyInput { pressKey(Key.S) }
    waitForIdle()
    assertTrue(scrolled().first > at, "S didn't scroll with focus on a row")
  }

  @Test
  fun theFirstTabLandsOnTheFirstSettingAndShiftTabReachesTheAccountRow() = runComposeUiTest {
    showShortSettings()
    val keys = onNodeWithTag(SettingsScrollTag)

    keys.performKeyInput { pressKey(Key.Tab) }
    waitForIdle()
    onNodeWithText("Always start in G only").assertIsFocused()
    onNodeWithText("Sign in").assertIsNotFocused()

    keys.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
    waitForIdle()
    onNodeWithText("Sign in").assertIsFocused()
  }

  @Test
  fun tabReachesTheAccountRowAfterTheSettings() = runComposeUiTest {
    showShortSettings()
    val keys = onNodeWithTag(SettingsScrollTag)
    keys.performKeyInput { pressKey(Key.Tab) }
    waitForIdle()
    onNodeWithText("Always start in G only").assertIsFocused()

    // On round the window, which here is the Settings alone: the account row is a stop again.
    var presses = 0
    while (presses < 40 && !onNodeWithText("Sign in").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }) {
      keys.performKeyInput { pressKey(Key.Tab) }
      waitForIdle()
      presses++
    }
    onNodeWithText("Sign in").assertIsFocused()
    // And Tab from the account row goes on to the first setting.
    keys.performKeyInput { pressKey(Key.Tab) }
    waitForIdle()
    onNodeWithText("Always start in G only").assertIsFocused()
  }

  @OptIn(InternalComposeUiApi::class)
  @Test
  fun theSettingsKeysAreLinesAndScreens() {
    fun step(key: Key, shift: Boolean = false, ctrl: Boolean = false, up: Boolean = false) = settingsKeyStep(
      KeyEvent(key, if (up) KeyEventType.KeyUp else KeyEventType.KeyDown, isShiftPressed = shift, isCtrlPressed = ctrl),
      viewport = 1000,
      line = 100f,
    )
    assertEquals(100f, step(Key.S))
    assertEquals(100f, step(Key.DirectionDown))
    assertEquals(-100f, step(Key.W))
    assertEquals(-100f, step(Key.DirectionUp))
    assertEquals(1000 * KEY_SCROLL_SCREEN, step(Key.S, shift = true))
    assertEquals(-1000 * KEY_SCROLL_SCREEN, step(Key.W, shift = true))
    assertEquals(null, step(Key.S, ctrl = true))
    assertEquals(null, step(Key.S, up = true))
    // Shift+A/D is the shell's.
    assertEquals(null, step(Key.D, shift = true))
  }

  @Test
  fun theMenusDescriptionSaysWhatItsBadgeMeans() {
    assertEquals("Menu", menuDescription(MenuBadge.None))
    assertEquals("Menu, update available", menuDescription(MenuBadge.Update))
    assertEquals("Menu, session expired", menuDescription(MenuBadge.Expired))
  }
}
