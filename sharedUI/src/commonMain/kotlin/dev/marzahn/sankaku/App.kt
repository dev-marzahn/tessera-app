package dev.marzahn.sankaku

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.marzahn.sankaku.shell.LocalChromePadding
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import dev.marzahn.sankaku.shell.LocalTabBarSlide
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.navigation3.scene.Scene
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import dev.marzahn.sankaku.shell.LocalQueuePageVisibility
import dev.marzahn.sankaku.shell.SidebarKey
import dev.marzahn.sankaku.shell.ShortcutSheet
import dev.marzahn.sankaku.shell.ShortcutSheetController
import dev.marzahn.sankaku.shell.shellSidebarKey
import dev.marzahn.sankaku.shell.LocalShellTransitions
import dev.marzahn.sankaku.shell.LocalUnderQueuePage
import dev.marzahn.sankaku.shell.LocalQueuePageOpening
import androidx.compose.runtime.withFrameNanos
import dev.marzahn.sankaku.shell.aboveSharedTransitions
import dev.marzahn.sankaku.shell.LocalPostHandoff
import dev.marzahn.sankaku.shell.PostHandoff
import dev.marzahn.sankaku.shell.PostHandoffHost
import dev.marzahn.sankaku.shell.MATCH_FRAMES
import dev.marzahn.sankaku.shell.animationsOff
import dev.marzahn.sankaku.ui.components.spatialFadeSpec
import dev.marzahn.sankaku.ui.components.effectsSpec
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.QueueSettings
import dev.marzahn.sankaku.data.FirstLaunch
import dev.marzahn.sankaku.features.firstlaunch.FirstLaunchScreen
import dev.marzahn.sankaku.features.firstlaunch.QueueIntro
import dev.marzahn.sankaku.features.firstlaunch.QueueIntroScreen
import dev.marzahn.sankaku.features.crash.CrashNotice
import dev.marzahn.sankaku.log.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import dev.marzahn.sankaku.features.auth.SignInDialog
import dev.marzahn.sankaku.shell.SidebarNavigator
import dev.marzahn.sankaku.features.settings.SettingsController
import dev.marzahn.sankaku.features.settings.SettingsPage
import dev.marzahn.sankaku.features.settings.SettingsSheet
import dev.marzahn.sankaku.features.books.BookReader
import dev.marzahn.sankaku.features.books.BookReaderPage
import androidx.compose.runtime.SideEffect
import dev.marzahn.sankaku.features.tag.TagPageHost
import dev.marzahn.sankaku.features.tag.TagPages
import dev.marzahn.sankaku.features.tag.rememberTagPageShown
import dev.marzahn.sankaku.features.tag.underTagPage
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.shell.BackOverrides
import dev.marzahn.sankaku.shell.RetryOnWindowFocus
import dev.marzahn.sankaku.shell.LocalOpenSearch
import dev.marzahn.sankaku.shell.SearchOverlay
import dev.marzahn.sankaku.shell.Sidebar
import dev.marzahn.sankaku.shell.TopBar
import dev.marzahn.sankaku.shell.rememberMenuBadge
import dev.marzahn.sankaku.tabs.LocalTabEntry
import dev.marzahn.sankaku.tabs.rememberTabStates
import dev.marzahn.sankaku.tabs.QueueBar
import dev.marzahn.sankaku.tabs.LocalOpenTabList
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.wayBackText
import dev.marzahn.sankaku.tabs.QueuePage
import dev.marzahn.sankaku.tabs.QueueFeeds
import dev.marzahn.sankaku.tabs.QueueStart
import dev.marzahn.sankaku.tabs.backsLikeALonePost
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.marzahn.sankaku.tabs.QueueSwipeHost
import dev.marzahn.sankaku.tabs.UpNextState
import dev.marzahn.sankaku.tabs.shownSearch
import dev.marzahn.sankaku.tabs.TabEntry
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.tabs.TabStrip
import dev.marzahn.sankaku.tabs.TabSwitcher
import dev.marzahn.sankaku.tabs.BlockChip
import dev.marzahn.sankaku.tabs.BlockOffer
import dev.marzahn.sankaku.tabs.LeavingBlockMessages
import dev.marzahn.sankaku.tabs.NewTab
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.tabs.ClosedTabUndo
import dev.marzahn.sankaku.tabs.ClosedTabUndoWindow
import dev.marzahn.sankaku.ui.components.AppSnackbarHost
import dev.marzahn.sankaku.ui.components.AppSnackbarVisuals
import dev.marzahn.sankaku.ui.components.KeyFocus
import dev.marzahn.sankaku.ui.components.LocalKeyFocus
import dev.marzahn.sankaku.ui.components.ConnectivityBanner
import dev.marzahn.sankaku.ui.components.connectivityBannerState
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.endsTipRest
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.isPlain
import dev.marzahn.sankaku.ui.components.tabStep
import dev.marzahn.sankaku.ui.components.rememberFullScreenSearchState
import dev.marzahn.sankaku.ui.components.SampleScreen
import dev.marzahn.sankaku.ui.components.rememberHideOnScroll
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI

/**
 * Until the first-launch screen is accepted (#210), it's all there is: no shell, no grid, so no request reaches
 * Sankaku before Continue. [onQuit] is its Quit: it exits the app.
 */
@Composable
fun App(onQuit: () -> Unit) {
  val flags = koinInject<DebugFlags>()
  val loader = koinInject<ImageLoader>()
  setSingletonImageLoaderFactory { loader }
  PlatformHideInRecents(koinInject<PrivacySettings>().hideInRecents.collectAsStateWithLifecycle().value)
  val firstLaunch = koinInject<FirstLaunch>()
  val accepted by firstLaunch.accepted.collectAsStateWithLifecycle()
  val crash = koinInject<CrashReports>().pending.collectAsStateWithLifecycle().value
  val queueIntro = koinInject<QueueIntro>()
  val introShown by queueIntro.shown.collectAsStateWithLifecycle()
  SankakuTheme {
    when {
      // On a phone, Continue goes on to the "Your queue" page (#559), once: this is the install's first launch.
      !accepted -> FirstLaunchScreen(
        onContinue = {
          firstLaunch.accept()
          if (platformIsTouch) queueIntro.show()
        },
        onQuit = onQuit,
      )
      // After the first-launch screen, never before it (#208): see CrashNotice.
      crash != null -> CrashNotice(crash)
      flags.sample -> SampleScreen()
      else -> {
        CrashOnPurpose(flags.crash)
        // A tip that just left holds the next back until the user's next touch ends, so that touch isn't spent on
        // dismissing a tip that came at once (#538). Around everything the shell draws, the reader and sidebar too.
        Box(Modifier.fillMaxSize().endsTipRest(koinInject())) {
          // Under the "Your queue" page (#559) the shell is out of the accessibility tree; it loads meanwhile.
          Box(if (introShown) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { Shell() }
          if (introShown) {
            PlatformBackHandler(enabled = true, onBack = queueIntro::close)
            QueueIntroScreen(onStart = queueIntro::close)
          }
        }
      }
    }
  }
}

/**
 * Debuggable Android builds and unpackaged desktop runs only (#208): [DebugFlags.crash] throws 3 s after the shell
 * shows, to check crash capture against a real crash. The message holds a URL, which never reaches the crash file.
 */
@Composable
private fun CrashOnPurpose(where: DebugCrash?) {
  if (where == null) return
  var due by remember { mutableStateOf(false) }
  LaunchedEffect(where) {
    delay(3_000)
    when (where) {
      DebugCrash.Ui -> due = true
      // A scope of its own with no handler: the failure reaches the worker thread's uncaught-exception handler.
      DebugCrash.Thread -> CoroutineScope(Dispatchers.Default).launch { throw DebugCrashException() }
    }
  }
  if (due) throw DebugCrashException()
}

private class DebugCrashException : IllegalStateException("Debug crash at https://sankakuapi.com/posts/123?tags=secret")

/** Tab strip, one top bar, the active tab's content; the sidebar, dialogs, banners and snackbars over it. */
@Composable
private fun Shell(
  tabs: TabsController = koinInject(),
  navigator: Navigator = koinInject(),
  connectivity: ConnectivityState = koinInject(),
  overrides: BackOverrides = koinInject(),
  undo: ClosedTabUndo = koinInject(),
  start: QueueStart = koinInject(),
  upNext: UpNextState = koinInject(),
  settings: SettingsController = koinInject(),
  reader: BookReader = koinInject(),
  queueSettings: QueueSettings = koinInject(),
  newTab: NewTab = koinInject(),
) {
  // Touch: the first session's start, before anything below composes, so the active tab never shows for a frame
  // before the queue page covers it. Later calls, from a recreated activity, do nothing.
  if (platformIsTouch) remember(start) { start.launched() }
  // Signed in, Following is always a queue item (#229), added at the end (#327); signing in or out, or the setting,
  // changes that.
  QueueFeeds(tabs)
  val tabList by tabs.tabs.collectAsStateWithLifecycle()
  val activeId by tabs.active.collectAsStateWithLifecycle()
  val active = tabList.firstOrNull { it.id == activeId } ?: return
  val offline by connectivity.offline.collectAsStateWithLifecycle()
  val rejected by connectivity.rejected.collectAsStateWithLifecycle()
  val unreachable by connectivity.unreachable.collectAsStateWithLifecycle()
  if (!platformIsTouch) RetryOnWindowFocus(connectivity)
  var sidebarOpen by rememberSaveable { mutableStateOf(false) }
  // Opened with G (#416), the sidebar's keys select an entry; the menu button opens it without.
  var sidebarByKey by remember { mutableStateOf(false) }
  // G opened or closed the sidebar and is still down: its repeats mustn't close or reopen it. Its release comes to the
  // shell's keys or the rail's, whichever has the focus then.
  var sidebarKeyHeld by remember { mutableStateOf(false) }
  var searchOpen by rememberSaveable { mutableStateOf(false) }
  // The tab switcher (#461, desktop), opened with C. It has the keys in its own dialog, the C that opened it included.
  var switcherOpen by remember { mutableStateOf(false) }
  // The C that opened it is still down as far as the shell knows; a quick tap's release can reach the shell before the
  // switcher has the focus, and the switcher must then not wait for it.
  var switcherKeyHeld by remember { mutableStateOf(false) }
  // Its queue list (#502) rather than its cards: Q in it changes it, and it opens as last left, after a restart too
  // (#542). The chip by the search line opens the list on the current item's block.
  val switcherList by queueSettings.switcherList.collectAsStateWithLifecycle()
  // The keyboard shortcuts sheet (#521, desktop): ?, the strip's ⋮ and Settings open it.
  val shortcutSheet = koinInject<ShortcutSheetController>()
  // The switcher open on its queue list, by a click: the block chip's and the strip's "+N" (#521).
  val openQueueList = {
    queueSettings.setSwitcherList(true)
    switcherKeyHeld = false
    switcherOpen = true
  }
  // X is down and has closed a tab (the shell's keys, below).
  var closeHeld by remember { mutableStateOf(false) }
  var searchBounds by remember { mutableStateOf(Rect.Zero) }
  val fullScreenSearch = rememberFullScreenSearchState()
  val snackbar = remember { SnackbarHostState() }
  MessagesEffect(snackbar)
  // Desktop (#502): leaving a finished block offers to close it (Shift+X), and the last kept item left says so.
  val blockOffer = remember { BlockOffer() }
  val messages = koinInject<Messages>()
  if (!platformIsTouch) LeavingBlockMessages(blockOffer)
  // Touch: under the Settings page (#205) the app is out of the accessibility tree, so nothing finds it there.
  val settingsOpen by settings.isOpen.collectAsStateWithLifecycle()
  val settingsPageOpen = platformIsTouch && settingsOpen
  // The sidebar opened over the Settings sheet (#460) or the phone's queue page (#478): a grid or search it opens closes
  // them.
  val sidebarNavigator = remember(navigator, settings, start) { SidebarNavigator(navigator, settings, start::closePage) }
  // ☰'s badge, the same on the top bar and the queue page (#478).
  val menuBadge = rememberMenuBadge()
  val toggleSidebar = {
    sidebarByKey = false
    sidebarOpen = !sidebarOpen
  }
  // Under the book reader (#381) too, on both platforms: it covers the whole window.
  val readerOpen = reader.shown.collectAsStateWithLifecycle().value != null
  // A tag's page (#486) covers everything, the reader too: what's under it has none of the keys or the accessibility
  // tree while it shows (#522).
  val tagPages = koinInject<TagPages>()
  val tagPageShown = rememberTagPageShown(tagPages)
  // Nor its tips: their popups would come over the page. Nor over the "Your queue" page (#559), drawn over the shell.
  val coveredTips = koinInject<Tips>()
  val introShown by koinInject<QueueIntro>().shown.collectAsStateWithLifecycle()
  SideEffect { coveredTips.covered = tagPageShown || introShown }
  // The panel edits the active tab's search, so it closes when another tab comes forward, unless that's a new tab
  // (Cmd/Ctrl-T or +, #542), whose search opens.
  var searchTabId by remember { mutableStateOf(active.id) }
  LaunchedEffect(active.id, newTab.searchFor) {
    if (active.id != searchTabId) {
      searchTabId = active.id
      searchOpen = false
    }
    if (newTab.take(active.id)) searchOpen = true
  }
  // The Up next and End of the queue cards (#216, #230) stand past an item: any other item coming forward takes them
  // away, and so does a search run in this one (from the card's own search line). A card that came up past the item
  // now shown (a drag down at the end of the queue, #326) stays.
  val upNextCard = upNext.card
  LaunchedEffect(active.id, active.top) { upNext.itemShown(active.id, active.top) }
  // A card has a page of its own (#230): its search line shows the faded source's search, or none, not the grid's
  // it stands past. The search panel opens on that search too.
  val shownRoute = if (platformIsTouch) shownSearch(upNextCard, active.top) else active.top
  // Closing the panel removes the focused field, which leaves nothing focused and every key unheard (`/` included),
  // so focus comes back to the root, then to the screen's own keys (the grid's cursor, the viewer's) if it has any.
  val rootFocus = remember { FocusRequester() }
  val keyFocus = remember { KeyFocus() }
  var searchWasOpen by remember { mutableStateOf(false) }
  LaunchedEffect(searchOpen) {
    if (!searchOpen && searchWasOpen) {
      rootFocus.requestFocus()
      keyFocus.returnToContent()
    }
    searchWasOpen = searchOpen
  }
  // The sidebar holds the focus in its own dialog; closed, the keys go back to the screen (#416).
  var sidebarWasOpen by remember { mutableStateOf(false) }
  LaunchedEffect(sidebarOpen) {
    if (!sidebarOpen && sidebarWasOpen) {
      rootFocus.requestFocus()
      keyFocus.returnToContent()
    }
    sidebarWasOpen = sidebarOpen
  }
  // So does the tab switcher (#461): closed, the keys go to the screen of the tab it left on.
  var switcherWasOpen by remember { mutableStateOf(false) }
  LaunchedEffect(switcherOpen) {
    if (!switcherOpen && switcherWasOpen) {
      rootFocus.requestFocus()
      keyFocus.returnToContent()
    }
    switcherWasOpen = switcherOpen
  }
  // Plain G opens the sidebar on the desktop (#416), from the screen or the Settings sheet; while it's open its dialog
  // has the keys, G included. The G that closed it, held, repeats here: it doesn't open it again until it's let go.
  val sidebarKeys = { event: KeyEvent, searchOpen: Boolean ->
    if (event.key != Shortcuts.Sidebar.key || event.type == KeyEventType.KeyUp) sidebarKeyHeld = false
    val key = shellSidebarKey(event.key, event.isPlain, sidebarKeyHeld, searchOpen, platformIsTouch)
    if (event.type == KeyEventType.KeyDown && key == SidebarKey.Open) {
      sidebarKeyHeld = true
      sidebarByKey = true
      sidebarOpen = true
    }
    event.type == KeyEventType.KeyDown && key != SidebarKey.None
  }
  // Desktop: the Settings sheet (#460) holds the focus in its own popup; closed, the keys go back to the screen, whose
  // cursor and scroll are as they were.
  var settingsWasOpen by remember { mutableStateOf(false) }
  LaunchedEffect(settingsOpen) {
    if (!platformIsTouch && !settingsOpen && settingsWasOpen) {
      rootFocus.requestFocus()
      keyFocus.returnToContent()
    }
    settingsWasOpen = settingsOpen
  }

  // Touch: the queue's count pill at the bottom instead of the strip (#178). On a grid it leaves while the grid scrolls
  // down and comes back on scrolling up. Another item, screen or item count (a long-press that queued a post ticks it
  // up) slides it back in, on the spring it settles with after a scroll (#300).
  val onGrid = active.top is Route.Grid
  val hideTabBar = rememberHideOnScroll()
  LaunchedEffect(active.id, active.top, tabList.size) { hideTabBar.show() }
  // While Undo grows out of the pill after a close, the pill is held up: it doesn't hide on scroll
  // (`.scratch/android-feedback/`, C, row 3).
  val undoPending = undo.pending.collectAsStateWithLifecycle().value != null
  LaunchedEffect(undoPending) { if (undoPending) hideTabBar.show() }
  // A post (the viewer over a grid, or a lone post) covers the top bar and draws the pill itself, over its picture. The
  // viewer moves the pill up from the grid's line as it opens and back down as it closes, then hands it back (#103); it
  // closes into a shown pill, so the pill comes back shown, at once since the bar isn't drawn meanwhile. Whether it's
  // covered is kept on the tab, so a grid shown again with its viewer open is covered from its first frame, and one
  // left behind no longer covers it (#287).
  // The Up next card replaces the content, post or not, and keeps the bar and the pill (#216).
  val covered = platformIsTouch && upNextCard == null && active.chromeCovered
  LaunchedEffect(covered) { if (covered) hideTabBar.showAtOnce() }
  val tabBarShown = platformIsTouch && !covered
  // Touch: the queue page (#177), over the pill. A start (a launch, or a return after 5 minutes away) opens it or a Browse grid, as Start on says.
  val queuePageOpen = platformIsTouch && start.pageOpen
  // Its opening and closing are a transition (#320), so a lone post's picture can move between the post and its row.
  val pageTransitions = rememberQueuePageTransitions(queuePageOpen)
  if (platformIsTouch) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { start.left() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { start.returned() }
    // Another item than the one a start landed on (a swipe, ‹ ›, a row tapped), or the Up next card, is a use of the
    // queue, which unblurs the queue page for the rest of the process (#264).
    LaunchedEffect(activeId) { start.shown(activeId) }
    LaunchedEffect(upNextCard != null) { if (upNextCard != null) start.interact() }
  }
  // Opened by the user once, the tab list tip has nothing left to teach (#140). The page opening by itself at a start
  // doesn't count.
  val tips = koinInject<Tips>()
  val openQueuePage = {
    tips.used(Tip.TabList)
    start.interact()
    start.openPage()
  }
  // The tab list tip waits for a second tab to appear; the count at launch is where it starts from.
  LaunchedEffect(tabList.size) { tips.counted(tabList.size) }
  // The first way back (#559): on the top line, once, that this is where the user left off. A phone's tip only.
  LaunchedEffect(tabs, tips, messages) {
    tabs.wayBacks.collect { name ->
      if (tips.pending(Tip.WayBack)) {
        tips.used(Tip.WayBack)
        messages.show(wayBackText(name))
      }
    }
  }
  // Touch: the tab's content fills the window, over the top bar, and keeps out of the room the chrome takes.
  // Starts at the top bar's usual height (a 48 dp row and 4 dp above and below), so the first frame is in place.
  var chromeHeight by remember { mutableStateOf(56.dp) }
  // Over a grid, ↑ Top floats on the bar's line in the right corner and slides with it as it hides.
  val overGrid by rememberUpdatedState(tabBarShown && onGrid)
  val tabBarSlide: () -> Float = remember(hideTabBar) { { if (overGrid) hideTabBar.hiddenBy else 0f } }
  val density = LocalDensity.current
  // Touch: a post opened from a sheet's thumbnail, as one picture (#321).
  val postHandoff = remember { if (platformIsTouch) PostHandoff() else null }
  // The queue page opening over it ends it: the post's picture goes to its row on the page's transition instead.
  LaunchedEffect(queuePageOpen) { if (queuePageOpen) postHandoff?.end() }

  // Paused under the queue page, which has its own inline Undo: what's left of the window is the pill's once it closes.
  // The Settings page (#205) covers the pill too, so it pauses the window as the queue page does.
  if (platformIsTouch) ClosedTabUndoWindow(paused = queuePageOpen || settingsPageOpen)

  Scaffold(
    // Desktop: at the bottom. Touch: the top line instead (drawn below, over everything), so nothing covers the
    // count pill and its Undo, or a post's ♡ and ★.
    snackbarHost = { if (!platformIsTouch) AppSnackbarHost(snackbar, swipeable = false) },
    modifier = (if (settingsPageOpen || readerOpen) Modifier.clearAndSetSemantics {} else Modifier).underTagPage(tagPageShown, tagPages),
  ) { padding ->
    // The tab's content, with the keys and the sidebar's opener. On desktop it sits below the top bar; on touch it
    // fills the window, so the viewer can cover the top bar, and gets the chrome's room as LocalChromePadding.
    val content = @Composable { chromePadding: PaddingValues, tabStates: SaveableStateHolder ->
      CompositionLocalProvider(
        LocalKeyFocus provides keyFocus,
        LocalChromePadding provides chromePadding,
        LocalTabBarSlide provides tabBarSlide,
        LocalOpenTabList provides openQueuePage,
        LocalOpenSearch provides { searchOpen = true },
        LocalPostHandoff provides postHandoff,
      ) {
        // Touch: sideways swipes move between queue items (S1, #181).
        // Past the last item the host shows the Up next card in place of the tab, as the queue page does (#216), and a
        // swipe back takes it away (#248).
        // A post opened from a sheet's thumbnail keeps the post it came from a moment, fading over it (#321).
        if (platformIsTouch) {
          // A swipe between items is what the swipe tip teaches (#501): done once used.
          QueueSwipeHost(active, undo = undo, onMoved = { tips.used(Tip.QueueSwipe) }, content = {
            PostHandoffHost(active, tabList, postHandoff) { item -> ActiveTab(item, tabs, navigator, tabStates) }
          })
        } else {
          ActiveTab(active, tabs, navigator, tabStates)
        }
      }
    }
    // Touch: also the one shared-transition scope for the tab's content and the queue page (#320).
    ShellBox(
      shared = platformIsTouch,
      Modifier
        .fillMaxSize()
        .then(if (tabBarShown && onGrid && !undoPending) Modifier.nestedScroll(hideTabBar.connection) else Modifier)
        // Like the top bar's Back: an open viewer closes first. Esc lands here when focus left the viewer (the sidebar
        // was used over it), and only navigated, so the viewer stayed open. The sidebar handles its own Esc: it
        // opens in a dialog that holds the focus.
        .platformBackKeys { if (!overrides.back()) navigator.goBack() }
        .onKeyEvent { event ->
          // X closes once per press: held, its repeats would close tab after tab. Any other key lets go too, in case
          // the release went elsewhere.
          if (event.key != Shortcuts.CloseTab.key || event.type == KeyEventType.KeyUp) closeHeld = false
          if (event.key == Shortcuts.TabSwitcher.key && event.type == KeyEventType.KeyUp) switcherKeyHeld = false
          if (sidebarKeys(event, searchOpen)) return@onKeyEvent true
          if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
          when {
            // ? (Shift-/) opens the keyboard shortcuts sheet on the desktop (#521), not the search; its sheet has the
            // keys while it's open, and its own ? closes it.
            event.key == Shortcuts.Help.key && event.isShiftPressed && !event.isCommand && !searchOpen && !platformIsTouch ->
              true.also { shortcutSheet.open() }
            event.key == Shortcuts.Search.key && !searchOpen -> true.also { searchOpen = true }
            event.key == Shortcuts.Find.key && event.isCommand && !searchOpen -> true.also { searchOpen = true }
            // Plain Z only: Cmd/Ctrl-Z is undo. The search panel holds the only text field, so Z typed there never
            // reaches here.
            event.key == Shortcuts.ReopenTab.key && !searchOpen && !event.isCommand -> true.also { tabs.reopenClosed() }
            // Plain C opens the tab switcher on the desktop (#461), not while the search panel is open (its text field
            // would type it) or the sidebar (whose dialog has the keys). Its dialog takes the keys, C's repeats too.
            event.key == Shortcuts.TabSwitcher.key && event.isPlain && !searchOpen && !platformIsTouch ->
              true.also {
                switcherKeyHeld = true
                switcherOpen = true
              }
            // Shift-A/D and plain X: the grid, the viewer and the reader pass them on.
            event.tabStep != null && !searchOpen -> true.also { tabs.cycle(forward = event.tabStep == true) }
            event.key == Shortcuts.CloseTab.key && !searchOpen && event.isPlain ->
              true.also {
                if (!closeHeld) tabs.close(tabs.active.value)
                closeHeld = true
              }
            // Shift+X takes "Done with …?"'s offer while it shows (#502), once per press.
            event.key == Shortcuts.CloseBlock.key && event.isShiftPressed && !event.isCommand && !event.isAltPressed &&
              !searchOpen && !platformIsTouch && blockOffer.pending != null ->
              true.also {
                if (!closeHeld) blockOffer.take(tabs, messages)
                closeHeld = true
              }
            // Esc reaches here when the panel's input didn't get focus (it was opened by a click on the bar), and
            // must close the panel rather than go back.
            event.key == Shortcuts.Back.key && searchOpen -> true.also { searchOpen = false }
            else -> false
          }
        }
        .focusRequester(rootFocus)
        .focusable(),
    ) {
      // Touch: the content is drawn after the top bar, so a post covers it, but over a grid the bar must take its own
      // touches: the grid's queue swipe (#181) fills the window under the bar and would win every hit test there. While
      // nothing covers the bar (a post, the queue page, the search panel) it's lifted above the content; it holds no
      // touches outside its own pieces, so the grid below it still gets everything else. The top line stays above it.
      // Lifted, it's also above a viewer's card closing into its cell, as it was while the grid had its own scope.
      val barOnTop = platformIsTouch && !covered && !queuePageOpen && !searchOpen
      Column(Modifier.fillMaxSize().padding(padding).zIndex(if (barOnTop) 1f else 0f).aboveSharedTransitions(enabled = barOnTop)) {
        // A "+N" count opens the switcher's queue list (#521), as the block chip does: with many tabs it's the overview
        // the strip can't be (#502).
        if (platformHasTabs && !platformIsTouch) {
          TabStrip(seen = koinInject(), onNewTab = newTab::open, onShowAll = openQueueList, onShortcuts = shortcutSheet::open)
        }
        // The search panel opens over the top bar and the content, in the top bar's search's place.
        Box(Modifier.fillMaxSize()) {
          Column {
            // Under a post the top bar is covered: out of the accessibility tree, so nothing finds it there.
            Column(
              Modifier
                .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } }
                .then(if (covered || queuePageOpen) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
              TopBar(
                current = shownRoute,
                // A lone post (or a kept book, #385, or a series alone in its tab, #479) goes back to the previous queue
                // item (#181).
                canGoBack = active.backStack.size > 1 || (active.backsLikeALonePost && tabList.indexOf(active) > 0),
                menuOpen = sidebarOpen,
                menuBadge = menuBadge,
                onMenu = toggleSidebar,
                onOpenSearch = { searchOpen = true },
                onSearchPlaced = { searchBounds = it },
                fullScreenSearch = fullScreenSearch,
                // Desktop (#502): where the item stands in its block, "3 / 8 · from Landscape".
                beforeSearch = {
                  if (!platformIsTouch) {
                    BlockChip(active.id, onOpen = openQueueList)
                  }
                },
              )
              ConnectivityBanner(connectivityBannerState(offline, unreachable, rejected))
            }
            if (!platformIsTouch) TabContent(shown = true, tabList) { content(PaddingValues(), it) }
          }
          if (!platformIsTouch) {
            SearchOverlay(searchOpen, active.top, searchBounds, fullScreen = fullScreenSearch, onClose = { searchOpen = false })
          }
        }
      }
      if (platformIsTouch) {
        // Drawn after the top bar, so a post covers it; a grid keeps below it through the padding. Not composed under
        // the open queue page, as a tab in the background isn't: it neither loads nor sets the system bars there, and
        // comes back with its view models and saved state. It's there only while the page opens and from the moment it
        // starts to close (#320), so a lone post's picture can move between it and its row.
        TabContent(shown = true, tabList) { holder ->
          UnderQueuePage(pageTransitions) {
            content(
              PaddingValues(
                top = padding.calculateTopPadding() + chromeHeight,
                bottom = padding.calculateBottomPadding(),
              ),
              holder,
            )
          }
        }
        SearchOverlay(searchOpen, shownRoute, searchBounds, fullScreen = fullScreenSearch, onClose = { searchOpen = false })
      }
      // Over everything, as a Scaffold's FAB is. It reaches down to the window's bottom, so on scroll it slides out
      // past the navigation bar; its row sits on BottomRowLayout's room above the bar.
      if (tabBarShown && !queuePageOpen) {
        QueueBar(
          hideOnScroll = hideTabBar.takeIf { onGrid },
          onOpenQueue = openQueuePage,
          bottom = BottomRowLayout.WindowRoom,
          modifier = Modifier.align(Alignment.BottomCenter).aboveSharedTransitions(),
        )
      }
      if (platformIsTouch) {
        // Touch: the queue page covers the tab and its bars; Back or Continue closes it.
        QueuePageAndTopLine(snackbar, pageTransitions) { onListTop ->
          QueuePage(
            onDismiss = start::closePage,
            onSwipeClosed = undo::opened,
            onMenu = toggleSidebar,
            menuBadge = menuBadge,
            onListTop = onListTop,
            leaving = !queuePageOpen,
          )
        }
      }
    }
  }
  // The rail opens over the whole window, tabs and top bar included, with its own scrim.
  Sidebar(
    open = sidebarOpen,
    onClose = { sidebarOpen = false },
    current = active.top,
    byKey = sidebarByKey,
    keyHeld = sidebarKeyHeld,
    onClosedByKey = {
      sidebarKeyHeld = true
      sidebarOpen = false
    },
    onCloseKeyUp = { sidebarKeyHeld = false },
    navigator = sidebarNavigator,
  )
  // Over the whole window too, opened only by C.
  if (!platformIsTouch) {
    TabSwitcher(
      open = switcherOpen,
      onClose = { switcherOpen = false },
      keyHeld = switcherKeyHeld,
      list = switcherList,
      onList = queueSettings::setSwitcherList,
      seen = koinInject(),
      clearViewed = koinInject(),
    )
  }
  // Touch: Settings is a page over everything (#205); sign-in, opened from its Account section, comes over it.
  // Desktop: a side sheet over the tab (#460), with the screen's key focus so its holder takes the keys back when the
  // sidebar over it closes.
  if (platformIsTouch) {
    SettingsPage(snackbar)
  } else {
    CompositionLocalProvider(LocalKeyFocus provides keyFocus) {
      SettingsSheet(onSidebarKey = { sidebarKeys(it, false) })
    }
    // After Settings, so the Keyboard row's sheet comes over it; Esc goes back to Settings.
    ShortcutSheet(shortcutSheet)
  }
  // A book (#381) covers everything, the phone's Settings page too; sign-in still comes over it.
  // Its count pill opens the queue page (#483), which the reader closes for.
  Box(Modifier.underTagPage(tagPageShown, tagPages)) {
    CompositionLocalProvider(LocalOpenTabList provides openQueuePage) { BookReaderPage(reader) }
  }
  // A tag's page (#486) comes over the reader, whose book sheet opens it, with the window's messages over it (#522).
  // Gone, it hands the keys back to the screen, as the sidebar does; the reader takes them back itself.
  TagPageHost(
    tagPages,
    snackbar = snackbar,
    onKeysBack = {
      // Read now, not as composed: a row's search closes the reader in the same moment the page goes.
      if (reader.shown.value == null) {
        rootFocus.requestFocus()
        keyFocus.returnToContent()
      }
    },
  )
  SignInDialog()
}

/**
 * Touch: the queue page, while [pageOpen], and the top line (`.scratch/android-feedback/`, C) for the words no control
 * on screen can show: a failure's reason, Session expired, Saved · Open for a post no longer shown, Cleared N · Undo.
 * Under the status bar on every screen but the queue page. There it sits where the page's list starts, as [page]
 * reports it from the window's top: below the header and Clear viewed (or find), so "Cleared 3 · Undo" doesn't hide
 * Find, G only and ⋮ (#309). Drawn after the page, so it shows over it. One host in one place, so a message keeps its
 * window as the page opens or closes: M3's host draws nothing when it's moved with `movableContentOf`.
 *
 * The page fades in and out on [transitions]' page (#320), at the pace of the spatial spring a lone post's picture moves
 * on between the post and its row, and is composed only while it's open or on its way in or out. The top line is above
 * a running shared transition too, as it's above every screen.
 *
 * The row's end of that picture ([LocalQueuePageVisibility]) is shown with the post leaving, not with the page: in
 * the frames the page is there before the post leaves, both were shown, and the row, as the one coming in, took the
 * picture's place at once, so it moved from nowhere.
 */
@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun BoxScope.QueuePageAndTopLine(
  snackbar: SnackbarHostState,
  transitions: QueuePageTransitions,
  page: @Composable (onListTop: (Dp) -> Unit) -> Unit,
) {
  val pageOpen = transitions.page.targetState
  // Unspecified until the page has placed its list; the header's height can change while it's shut (Clear viewed, a
  // large font), so each open measures again.
  var listTop by remember { mutableStateOf(Dp.Unspecified) }
  val fade = spatialFadeSpec<Float>()
  val rowTransition = transitions.under.createChildTransition(label = "queue row picture") { postGone ->
    if (postGone) EnterExitState.Visible else EnterExitState.PostExit
  }
  val rowVisibility = remember(rowTransition) { RowVisibility(rowTransition) }
  transitions.page.AnimatedVisibility(visible = { it }, enter = fadeIn(fade), exit = fadeOut(fade)) {
    CompositionLocalProvider(LocalQueuePageVisibility provides rowVisibility) { page { listTop = it } }
  }
  LaunchedEffect(pageOpen) { if (!pageOpen) listTop = Dp.Unspecified }
  val place = if (pageOpen && listTop.isSpecified) {
    Modifier.padding(top = listTop)
  } else {
    Modifier.windowInsetsPadding(WindowInsets.statusBars)
  }
  AppSnackbarHost(snackbar, swipeable = true, Modifier.align(Alignment.TopCenter).then(place).zIndex(2f).aboveSharedTransitions())
}

/** A kept row's picture's visibility on the queue page: shown once the lone post under the page has left for it. */
private class RowVisibility(override val transition: Transition<EnterExitState>) : AnimatedVisibilityScope

/** [QueuePageAndTopLine] on transitions of its own, opening and closing with [pageOpen]. */
@Composable
internal fun BoxScope.QueuePageAndTopLine(
  snackbar: SnackbarHostState,
  pageOpen: Boolean,
  page: @Composable (onListTop: (Dp) -> Unit) -> Unit,
) = QueuePageAndTopLine(snackbar, rememberQueuePageTransitions(pageOpen), page)

/**
 * The queue page's opening and closing (#320): [page] for the page itself, and [under] for the tab's content under it.
 * Closing, both turn at once. Opening, they're staged, so a lone post's picture leaves for its row from where it is:
 * 1. [opening]: the post attaches its picture's shared element, alone, so it's the element's last target.
 * 2. [MATCH_FRAMES] frames later, [page] turns: the page and its rows are composed. The current row's picture isn't
 *    shown yet ([QueuePageAndTopLine]), so the match is set up from the picture, the target, and its bounds.
 * 3. [MATCH_FRAMES] frames later, [under] turns: the post leaves and the row's picture is shown, and it moves from the
 *    picture's bounds to the row's.
 *
 * All at once, the row's element was there first, and with no earlier target the move started from the row itself (or
 * the window's corner): the picture didn't move, and the thumbnail flew in from the side.
 */
internal class QueuePageTransitions(val page: Transition<Boolean>, val under: Transition<Boolean>, val opening: Boolean)

@Composable
internal fun rememberQueuePageTransitions(pageOpen: Boolean): QueuePageTransitions {
  var stage by remember { mutableStateOf(if (pageOpen) OpeningStage.Open else OpeningStage.Shut) }
  LaunchedEffect(pageOpen) {
    if (!pageOpen) {
      stage = OpeningStage.Shut
    } else if (stage == OpeningStage.Shut && animationsOff()) {
      // Nothing moves (animator duration scale 0): the page opens at once.
      stage = OpeningStage.Open
    } else if (stage == OpeningStage.Shut) {
      stage = OpeningStage.Attaching
      repeat(MATCH_FRAMES) { withFrameNanos {} }
      stage = OpeningStage.PageIn
      repeat(MATCH_FRAMES) { withFrameNanos {} }
      stage = OpeningStage.Open
    }
  }
  // Closing turns both at once, before the effect has caught up.
  val shownStage = if (pageOpen) stage else OpeningStage.Shut
  return QueuePageTransitions(
    page = updateTransition(shownStage >= OpeningStage.PageIn, label = "queue page"),
    under = updateTransition(shownStage == OpeningStage.Open, label = "under the queue page"),
    opening = shownStage == OpeningStage.Attaching || shownStage == OpeningStage.PageIn,
  )
}

private enum class OpeningStage { Shut, Attaching, PageIn, Open }

/**
 * Touch: the tab's content under the queue page (#320). It's composed while the page is shut, and on [transitions]'
 * way: until the page has finished opening, and from the moment it starts to close, never under the open page.
 * Its visibility is [LocalUnderQueuePage], for a lone post's picture to move on, and [LocalQueuePageOpening] says the
 * page is about to open over it.
 */
@Composable
internal fun UnderQueuePage(transitions: QueuePageTransitions, content: @Composable () -> Unit) {
  transitions.under.AnimatedVisibility(visible = { hidden -> !hidden }, enter = EnterTransition.None, exit = ExitTransition.None) {
    CompositionLocalProvider(LocalUnderQueuePage provides this, LocalQueuePageOpening provides transitions.opening) { content() }
  }
}

/**
 * The shell's box. With [shared] (touch), also the one shared-transition scope ([LocalShellTransitions]) of everything
 * in it: the tab's content, the queue page and the chrome over them (#320).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ShellBox(shared: Boolean, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
  if (!shared) return Box(modifier, content = content)
  SharedTransitionScope { overlay ->
    Box(modifier.then(overlay)) {
      CompositionLocalProvider(LocalShellTransitions provides this@SharedTransitionScope) { content() }
    }
  }
}

/**
 * The tab's content while [shown], given the holder of each tab's saved UI state (a grid's scroll position, an open
 * viewer) by tab id. The holder is kept while the content isn't shown: the queue page takes the content out of
 * composition, and a holder inside the content went with it, so the grid came back at the top (#259). It keeps the
 * state of the [open] tabs only ([rememberTabStates]): a closed tab's state stayed in the activity's saved state for
 * good, which grew past the binder's limit (#389).
 */
@Composable
internal fun TabContent(shown: Boolean, open: List<TabState>, content: @Composable (SaveableStateHolder) -> Unit) {
  val tabStates = rememberTabStates(open)
  if (shown) content(tabStates)
}

/**
 * Only the active tab is composed. Its view models live in the tab's own store, and its saved UI state (such as
 * scroll positions) is kept in [holder] by the tab id, so switching tabs keeps both. [holder] is [TabContent]'s, so
 * the queue page taking the content out of composition keeps the saved state too (#259).
 *
 * A search and Back within the tab cut to the new screen, as the top bar's search line does: Nav3's Android default,
 * a 700 ms linear crossfade, drew both grids for most of a second (#284). Desktop's default was already a cut. A
 * predictive Back fades the screen out with the gesture over the one under it, which is drawn as it is.
 */
@OptIn(KoinExperimentalAPI::class)
@Composable
internal fun ActiveTab(
  tab: TabState,
  tabs: TabsController,
  navigator: Navigator,
  holder: SaveableStateHolder,
  entryProvider: (NavKey) -> NavEntry<NavKey> = koinEntryProvider(),
) {
  key(tab.id) {
    holder.SaveableStateProvider(tab.id.value) {
      CompositionLocalProvider(LocalViewModelStoreOwner provides tab) {
        val transitions = inTabTransitions()
        NavDisplay(
          backStack = tab.backStack,
          onBack = navigator::goBack,
          entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberTabViewModelDecorator(tab) { tabs.active.value == tab.id },
          ),
          transitionSpec = transitions.push,
          popTransitionSpec = transitions.pop,
          predictivePopTransitionSpec = transitions.predictivePop,
          entryProvider = entryProvider,
        )
      }
    }
  }
}

/** [ActiveTab]'s `NavDisplay` specs, one value so the test runs the same ones (#284). */
internal class InTabTransitions(fade: FiniteAnimationSpec<Float>) {
  val push: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform =
    { EnterTransition.None togetherWith ExitTransition.None }
  val pop: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = push
  val predictivePop: AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform =
    { EnterTransition.None togetherWith fadeOut(fade) }
}

@Composable
internal fun inTabTransitions(): InTabTransitions {
  val fade = effectsSpec<Float>()
  return remember(fade) { InTabTransitions(fade) }
}

/**
 * Gives each back stack entry its view model store from [tab], so the view models (a grid's loaded posts) survive the
 * tab leaving the screen: switching tabs disposes every entry of the old tab, and nav3 pops them. The library's
 * decorator cleared its stores then (lifecycle 2.11 ignores its "only when active" check), so a grid shown again
 * started empty and flashed blank. A store is cleared only for an entry popped while its tab [isActive], and with
 * the tab when it closes.
 */
@Composable
private fun rememberTabViewModelDecorator(tab: TabState, isActive: () -> Boolean): NavEntryDecorator<NavKey> =
  remember(tab) {
    NavEntryDecorator(
      onPop = { key -> if (isActive()) tab.clearEntry(key) },
      decorate = { entry ->
        val owner = remember(entry.contentKey) { tab.entryOwner(entry.contentKey) }
        val tabEntry = remember(entry.contentKey) { TabEntry(tab, entry.contentKey) }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalTabEntry provides tabEntry) { entry.Content() }
      },
    )
  }

/** Shows [Messages] and a "Session expired" message with "Sign in" when a refresh is rejected. */
@Composable
private fun MessagesEffect(
  snackbar: SnackbarHostState,
  messages: Messages = koinInject(),
  session: AuthSession = koinInject(),
  signIn: SignInController = koinInject(),
) {
  LaunchedEffect(Unit) {
    launch {
      session.state.collect {
        if (it == AuthState.SignedOut(AuthState.SignedOut.Reason.Expired)) {
          messages.show(Message("Session expired", actionLabel = "Sign in", isError = true, onAction = signIn::open))
        }
      }
    }
    messages.messages.collect { message ->
      snackbar.currentSnackbarData?.dismiss()
      launch {
        // Long with an action, Short without, unless the message asks for Short; the host lengthens either by the
        // accessibility settings.
        val visuals = if (message.short) {
          AppSnackbarVisuals(message.text, message.actionLabel, SnackbarDuration.Short, message.isError)
        } else {
          AppSnackbarVisuals(message.text, message.actionLabel, isError = message.isError)
        }
        val result = snackbar.showSnackbar(visuals)
        if (result == SnackbarResult.ActionPerformed) message.onAction()
      }
    }
  }
}
