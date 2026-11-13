package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.clickable
import dev.marzahn.sankaku.shell.ShortcutSheetController
import dev.marzahn.sankaku.ui.components.ButtonHint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.arrow_back
import com.google.fonts.icons.close
import com.google.fonts.icons.error
import dev.marzahn.sankaku.PlatformBackHandler
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.features.post.PlayerPreferences
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.QueueSettings
import dev.marzahn.sankaku.data.GridSettings
import dev.marzahn.sankaku.features.grid.gridColumns
import dev.marzahn.sankaku.gridScrollbarRoom
import dev.marzahn.sankaku.platformCellGap
import dev.marzahn.sankaku.platformGridEdge
import dev.marzahn.sankaku.data.StartOn
import dev.marzahn.sankaku.features.auth.AccountRow
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.features.firstlaunch.QueueIntro
import dev.marzahn.sankaku.features.firstlaunch.QueueIntroSettingsLabel
import dev.marzahn.sankaku.tabs.SETTINGS_TITLE
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.AppSnackbarHost
import dev.marzahn.sankaku.ui.components.AppTopBar
import dev.marzahn.sankaku.ui.components.KEY_SCROLL_SCREEN
import dev.marzahn.sankaku.ui.components.KeyFocusHolder
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.KeyScroll
import dev.marzahn.sankaku.ui.components.KeyScrollLine
import dev.marzahn.sankaku.ui.components.LocalKeyFocus
import dev.marzahn.sankaku.ui.components.ModalSideSheet
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** What the Hide in Recents switch says it does. Android 12 has only `FLAG_SECURE` for it, which blocks screenshots too. */
internal const val HideInRecentsHelp =
  "Shows a blank preview in the app switcher. On Android 12 this also blocks screenshots."

/** The one confirmation's words: no counts, no names, and what stays. Only Android keeps a video cache (#180). */
internal val ClearEverythingConfirmText = clearEverythingConfirmText(platformIsTouch)

/** [ClearEverythingConfirmText] on a phone ([touch]), which has the queue and no tabs (#249), or on desktop. */
internal fun clearEverythingConfirmText(touch: Boolean): String =
  (if (touch) "Empties the queue and Recently closed" else "Closes all tabs and the recently closed ones") +
    ", and clears the ${if (touch) "image and video caches" else "image cache"} " +
    "and the tags and searches the app remembers. You stay signed in. Posts you saved to Download/Tessera stay."

/** What the Always start in G only switch says it does. */
internal const val AlwaysStartGOnlyHelp = "Turns G only on each time the app starts. You can still turn it off."

/** What the Show For you in the queue switch says it does (#229). */
internal const val ForYouInQueueHelp = "Signed in, For you stays at the end of the queue, after Following."

/** What Post size says under its label: the columns its size makes in this window, or on a phone ([touch]) screen. */
internal fun postSizeHelp(columns: Int, touch: Boolean = false): String =
  "Larger posts, fewer columns. ${columnsLabel(columns)} " +
    if (touch) "on this screen." else "in this window."

/** "1 column" or "N columns": Post size's help and its slider's state. */
private fun columnsLabel(columns: Int): String = if (columns == 1) "1 column" else "$columns columns"

/** What the Start videos muted switch says it does (#524). */
internal const val StartVideosMutedHelp = "Mutes videos each time the app starts. Unmuting lasts until you close it."

/** What the Show queue buttons switch says it does (#178). */
internal const val ShowQueueButtonsHelp = "Shows ✕, ‹ and › beside the queue count, to close and move without swiping."

/** What the Left-handed switch says it does (#528). */
internal const val LeftHandedHelp = "Puts ♡ and ⋮ at the left end of a post's vote row, under your left thumb."

/** The desktop warning when the keychain can't be used (`TokenStorageStatus.InMemory`): the spec's words. */
internal const val InMemoryTokensWarning = "Can't store tokens; you'll need to sign in each launch"

/**
 * Settings' sections, in order (#205). Updates, Diagnostics and About are slots their tickets fill (`SectionSlots.kt`).
 * Queue and Accessibility are the switches #229, #178 and #528 added; Grid is the Post size slider (#445, the phone's #468);
 * Videos is Start videos muted (#524).
 */
internal enum class SettingsSection(val title: String) {
  Account("Account"),
  Privacy("Privacy"),
  Queue("Queue"),
  Grid("Grid"),
  Videos("Videos"),
  Keyboard("Keyboard"),
  Accessibility("Accessibility"),
  Storage("Storage"),
  Updates("Updates"),
  Diagnostics("Diagnostics"),
  About("About"),
}

/**
 * The sections drawn on a phone ([touch]) or desktop: Accessibility's switches are for the count pill and the viewer's
 * sheet, which only a phone has; a slot is drawn once its ticket has filled it.
 */
internal fun shownSections(
  touch: Boolean,
  updates: Boolean = UPDATES_FILLED,
  diagnostics: Boolean = DIAGNOSTICS_FILLED,
  about: Boolean = ABOUT_FILLED,
): List<SettingsSection> = SettingsSection.entries.filter {
  when (it) {
    SettingsSection.Accessibility -> touch
    // The keyboard shortcuts sheet (#521): only the desktop has the keys.
    SettingsSection.Keyboard -> !touch
    SettingsSection.Updates -> updates
    SettingsSection.Diagnostics -> diagnostics
    SettingsSection.About -> about
    else -> true
  }
}

/** What Storage's second wipe is called: "Clear saved tabs" on desktop, "Clear the queue" on a phone (#249). */
internal fun clearSavedTabsLabel(touch: Boolean): String = if (touch) "Clear the queue" else "Clear saved tabs"

/** Its confirmation's words: what goes and what stays. */
internal fun clearSavedTabsConfirmText(touch: Boolean): String =
  (if (touch) "Empties the queue and Recently closed." else "Closes all tabs and the recently closed ones.") +
    " The cache, the posts you've seen, your searches and settings stay."

/** What Clear cache's confirmation says: only Android keeps a video cache (#180). */
internal fun clearCacheConfirmText(touch: Boolean): String =
  "Deletes the ${if (touch) "images and videos" else "images"} the app has downloaded; they load again when shown. " +
    "${if (touch) "The queue" else "Your tabs"}, your searches and settings stay."

/**
 * Desktop: Settings as a side sheet over the current tab while [SettingsController.isOpen] (#460), not a tab: the tab
 * strip, the active tab, its scroll and its cursor stay as they were. A heading with a close button, a hint row for
 * the keys, then the sections. Esc, Back (the mouse button), the scrim and ✕ close it; [onSidebarKey] gets each key
 * the sheet doesn't take, so G opens the sidebar over it as from any screen (#416). Clear everything closes it, as it
 * closes the phone's page.
 */
@Composable
fun SettingsSheet(onSidebarKey: (KeyEvent) -> Boolean, controller: SettingsController = koinInject()) {
  val isOpen by controller.isOpen.collectAsStateWithLifecycle()
  ModalSideSheet(isOpen, onClose = controller::close, title = SETTINGS_TITLE, modifier = Modifier.onKeyEvent(onSidebarKey)) {
    Row(
      Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        SETTINGS_TITLE,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.weight(1f).semantics { heading() },
      )
      ButtonHint("Close settings (${Shortcuts.Back.label})") {
        IconButton(onClick = controller::close) { Icon(close, contentDescription = "Close settings") }
      }
    }
    FlowRow(
      Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      KeyHint("W S", "scroll")
      KeyHint("Tab", "next setting")
      KeyHint(Shortcuts.Back.label, "close")
    }
    SettingsColumn(onClearedEverything = controller::close, Modifier.weight(1f))
  }
}

/**
 * The sheet's sections, scrolling (#205, #460).
 *
 * Keys (#423): W/S and ↑/↓ scroll a line (the reader's 120 dp), with Shift a screen, wherever focus is in the sheet.
 * Its [KeyFocusHolder] has the keys as it opens, and the first Tab from it lands on the first setting under the
 * account row; Shift-Tab from there reaches Sign out, and so does Tab once it has gone round the sheet. Only that first
 * Tab skips the account row: the holder takes the keys back (the sidebar closing over it) armed again. The holder sits
 * outside the scrolling column, so taking the keys back doesn't scroll the sheet to it.
 */
@Composable
internal fun SettingsColumn(onClearedEverything: () -> Unit, modifier: Modifier = Modifier) {
  val scroll = rememberScrollState()
  val keyScroll = remember(scroll) { KeyScroll(scroll) }
  val scope = rememberCoroutineScope()
  val line = with(LocalDensity.current) { KeyScrollLine.toPx() }
  val firstSetting = remember { FocusRequester() }
  // Whether Tab from the holder skips to the first setting: until the holder first lets go of the keys.
  var skipAccount by remember { mutableStateOf(true) }
  var holderFocused by remember { mutableStateOf(false) }
  val returns = LocalKeyFocus.current.returns
  LaunchedEffect(returns) { skipAccount = true }
  Box(
    modifier.fillMaxWidth().onKeyEvent { event ->
      val step = settingsKeyStep(event, scroll.viewportSize, line)
      if (step != null) keyScroll.by(step, scope)
      step != null
    },
  ) {
    KeyFocusHolder(
      Modifier
        .onFocusChanged { state ->
          if (holderFocused && !state.isFocused) skipAccount = false
          holderFocused = state.isFocused
        }
        .focusProperties { if (skipAccount) next = firstSetting },
    )
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(scroll)
        .testTag(SettingsScrollTag)
        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
    ) {
      SettingsSections(touch = false, onClearedEverything, firstSetting = Modifier.focusRequester(firstSetting))
    }
  }
}

internal const val SettingsScrollTag = "settings-scroll"

/**
 * How far a key scrolls Settings, in px: S and ↓ a [line] down, W and ↑ up, with Shift a screen of [viewport]. Null
 * for any other key, and with Cmd, Ctrl or Alt, so the shell's chords pass.
 */
internal fun settingsKeyStep(event: KeyEvent, viewport: Int, line: Float): Float? {
  if (event.type != KeyEventType.KeyDown || event.isCommand || event.isAltPressed) return null
  val step = if (event.isShiftPressed) viewport * KEY_SCROLL_SCREEN else line
  return when {
    Shortcuts.Down.matches(event.key) -> step
    Shortcuts.Up.matches(event.key) -> -step
    else -> null
  }
}

/**
 * Touch: Settings as a page over the whole app while [SettingsController.isOpen] (#205), not a queue item: the phone's
 * queue holds what's being looked through, and Settings is never that. A top app bar with Back and the title, then the
 * sections. The back arrow and system back close it, and the item under it is as it was. Its messages ("Cache
 * cleared") show on its own top line, on [snackbar], since the app's is under the page.
 */
@Composable
fun SettingsPage(snackbar: SnackbarHostState, controller: SettingsController = koinInject()) {
  val isOpen by controller.isOpen.collectAsStateWithLifecycle()
  if (!isOpen) return
  PlatformBackHandler(enabled = true, onBack = controller::close)
  Box(Modifier.fillMaxSize()) {
    Scaffold(
      // A pane, so accessibility services announce it and treat it as the screen.
      modifier = Modifier.semantics { paneTitle = SETTINGS_TITLE },
      topBar = {
        AppTopBar(
          title = SETTINGS_TITLE,
          navigationIcon = {
            IconButton(onClick = controller::close) { Icon(arrow_back, contentDescription = "Back") }
          },
        )
      },
    ) { padding ->
      Column(
        Modifier
          .fillMaxSize()
          .padding(padding)
          .consumeWindowInsets(padding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        // Clear everything leaves an empty queue: the page closes on it, as the dialog did, so the Browse grid shows.
        SettingsSections(touch = true, onClearedEverything = controller::close)
      }
    }
    AppSnackbarHost(
      snackbar,
      swipeable = true,
      Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).zIndex(1f),
    )
  }
}

/**
 * Every shown section ([shownSections]) under its heading, with a divider between them. [firstSetting] goes on the
 * first setting under the account row.
 */
@Composable
internal fun ColumnScope.SettingsSections(
  touch: Boolean,
  onClearedEverything: () -> Unit = {},
  firstSetting: Modifier = Modifier,
) {
  shownSections(touch).forEachIndexed { index, section ->
    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SectionHeading(section.title)
    when (section) {
      SettingsSection.Account -> AccountSettings(touch)
      SettingsSection.Privacy -> PrivacySettings(touch, onClearedEverything, firstSetting)
      SettingsSection.Queue -> QueueSettings()
      SettingsSection.Grid -> GridSettings(touch)
      SettingsSection.Videos -> VideoSettings()
      SettingsSection.Keyboard -> KeyboardSettings()
      SettingsSection.Accessibility -> AccessibilitySettings()
      SettingsSection.Storage -> StorageSettings(touch)
      SettingsSection.Updates -> UpdatesSettings()
      SettingsSection.Diagnostics -> DiagnosticsSettings()
      SettingsSection.About -> AboutSettings()
    }
  }
}

@Composable
private fun SectionHeading(title: String) {
  Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
  )
}

/**
 * Account: the avatar and name with Sign out (asking for a second tap, #134), or Sign in when signed out; and on
 * desktop, while the keychain can't be used, the warning that tokens are kept in memory only.
 */
@Composable
private fun AccountSettings(
  touch: Boolean,
  session: AuthSession = koinInject(),
  signIn: SignInController = koinInject(),
  storage: TokenStorageStatus = koinInject(),
  settings: SettingsController = koinInject(),
) {
  val state by session.state.collectAsStateWithLifecycle()
  val user = (state as? AuthState.SignedIn)?.user
  val scope = rememberCoroutineScope()
  Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
    if (user != null) {
      AccountRow(user.name, user.avatarUrl, open = true, onSignOut = { scope.launch { session.signOut() } })
    } else {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Not signed in", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { signIn.open() }) { Text("Sign in") }
      }
    }
  }
  // Followed tags (#314): what Following shows, with Unfollow. Signed in only, as following is.
  if (user != null) {
    var showingFollowed by rememberSaveable { mutableStateOf(false) }
    ActionRow(FollowedTagsTitle, help = FollowedTagsHelp, onClick = { showingFollowed = true })
    if (showingFollowed) {
      FollowedTagsPage(
        onDismiss = { showingFollowed = false },
        onOpenTag = {
          showingFollowed = false
          settings.close()
        },
      )
    }
  }
  if (!touch && storage == TokenStorageStatus.InMemory) {
    Row(
      Modifier.fillMaxWidth().padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
      Text(InMemoryTokensWarning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
  }
}

/**
 * Privacy: Hide in Recents and Start on (Android), Always start in G only, and Clear everything, which asks once.
 * [onClearedEverything] runs as it's confirmed. [firstRow] goes on the section's first row.
 */
@Composable
private fun PrivacySettings(
  touch: Boolean,
  onClearedEverything: () -> Unit,
  firstRow: Modifier,
  privacy: PrivacySettings = koinInject(),
) {
  val hideInRecents by privacy.hideInRecents.collectAsStateWithLifecycle()
  val startOn by privacy.startOn.collectAsStateWithLifecycle()
  val alwaysStartGOnly by privacy.alwaysStartGOnly.collectAsStateWithLifecycle()
  var confirming by remember { mutableStateOf(false) }
  // The app's scope: the page closes on the confirm (phone), and the wipe closes this tab itself (desktop), so a scope
  // of this screen's would cancel the wipe halfway.
  val scope = koinInject<CoroutineScope>()
  // Only Android has a Recents screen, and only touch has the queue page to start on.
  if (touch) {
    SwitchRow("Hide in Recents", HideInRecentsHelp, hideInRecents, privacy::setHideInRecents, firstRow)
    StartOnChoice(startOn, privacy::setStartOn)
  }
  SwitchRow(
    "Always start in G only",
    AlwaysStartGOnlyHelp,
    alwaysStartGOnly,
    privacy::setAlwaysStartGOnly,
    if (touch) Modifier else firstRow,
  )
  ActionRow("Clear everything…", help = null, onClick = { confirming = true }, destructive = true)
  if (confirming) {
    ClearEverythingConfirm(scope = scope, onDismiss = { confirming = false }, onConfirm = onClearedEverything)
  }
}

/** Keyboard (#521, desktop): opens the keyboard shortcuts sheet over Settings, as ? does elsewhere. */
@Composable
private fun KeyboardSettings(shortcuts: ShortcutSheetController = koinInject()) {
  ActionRow("Keyboard shortcuts", help = KeyboardShortcutsHelp, onClick = shortcuts::open)
}

/** What Settings' Keyboard shortcuts row says it opens. */
internal val KeyboardShortcutsHelp = "Every key and where it works. ${Shortcuts.Help.label} opens it from anywhere."

/**
 * Queue: Show For you in the queue (#229), and on a phone How the queue works (#559), which shows the "Your queue"
 * page over Settings again.
 */
@Composable
private fun QueueSettings(queue: QueueSettings = koinInject(), intro: QueueIntro = koinInject()) {
  val forYouInQueue by queue.forYouInQueue.collectAsStateWithLifecycle()
  SwitchRow("Show For you in the queue", ForYouInQueueHelp, forYouInQueue, queue::setForYouInQueue)
  if (platformIsTouch) ActionRow(QueueIntroSettingsLabel, help = null, onClick = intro::show)
}

/**
 * Grid: Post size, the narrowest a grid column may be, from small to large in the platform's steps
 * (`GridSettings.sizes`). Under it, how many columns that makes in this window, or on this screen on a phone
 * ([touch]); every grid follows the slider as it moves.
 */
@Composable
private fun GridSettings(touch: Boolean, grid: GridSettings = koinInject()) {
  val saved by grid.cellWidth.collectAsStateWithLifecycle()
  val sizes = grid.sizes
  val cellWidth = saved ?: sizes.default
  val windowWidth = LocalWindowInfo.current.containerSize.width
  val columns = with(LocalDensity.current) {
    // The grid spans the window (the sidebar is modal), less its edges and the scrollbar's room.
    val gridWidth = windowWidth - (platformGridEdge * 2 + gridScrollbarRoom).roundToPx()
    gridColumns(gridWidth, cellWidth.dp.roundToPx(), platformCellGap.roundToPx())
  }
  val help = postSizeHelp(columns, touch)
  Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Text("Post size", style = MaterialTheme.typography.bodyLarge)
    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
      value = cellWidth.toFloat(),
      onValueChange = { grid.setCellWidth(it.roundToInt()) },
      valueRange = sizes.min.toFloat()..sizes.max.toFloat(),
      steps = (sizes.max - sizes.min) / sizes.step - 1,
      modifier = Modifier.fillMaxWidth().testTag(PostSizeSliderTag).semantics {
        contentDescription = "Post size"
        stateDescription = columnsLabel(columns)
      },
    )
  }
}

internal const val PostSizeSliderTag = "post-size-slider"

/** Videos: Start videos muted (#524). */
@Composable
private fun VideoSettings(player: PlayerPreferences = koinInject()) {
  SwitchRow("Start videos muted", StartVideosMutedHelp, player.startMuted, player::changeStartMuted)
}

/** Accessibility (touch): Show queue buttons (#178) and Left-handed (#528). */
@Composable
private fun AccessibilitySettings(accessibility: AccessibilitySettings = koinInject()) {
  val showQueueButtons by accessibility.showQueueButtons.collectAsStateWithLifecycle()
  val leftHanded by accessibility.leftHanded.collectAsStateWithLifecycle()
  SwitchRow("Show queue buttons", ShowQueueButtonsHelp, showQueueButtons, accessibility::setShowQueueButtons)
  SwitchRow("Left-handed", LeftHandedHelp, leftHanded, accessibility::setLeftHanded)
}

/**
 * Storage: the disk caches' size in use, read as the section shows and after each clear, with Clear cache; and Clear
 * saved tabs (Clear the queue on a phone). Each asks once.
 */
@Composable
private fun StorageSettings(
  touch: Boolean,
  cache: ClearCache = koinInject(),
  savedTabs: ClearSavedTabs = koinInject(),
  messages: Messages = koinInject(),
) {
  var cleared by remember { mutableIntStateOf(0) }
  var inUse by remember { mutableStateOf<Long?>(null) }
  LaunchedEffect(cleared) { inUse = runCatching { cache.bytesInUse() }.getOrNull() }
  var confirming by remember { mutableStateOf<StorageWipe?>(null) }
  // The app's scope, so leaving Settings mid-wipe doesn't stop it halfway.
  val scope = koinInject<CoroutineScope>()
  ActionRow(
    "Clear cache…",
    help = "Cache in use: ${inUse?.let(::storageSize) ?: "…"}",
    onClick = { confirming = StorageWipe.Cache },
    modifier = Modifier.testTag(CacheRowTag),
  )
  ActionRow("${clearSavedTabsLabel(touch)}…", help = null, onClick = { confirming = StorageWipe.SavedTabs })
  when (confirming) {
    StorageWipe.Cache -> ConfirmDialog(
      title = "Clear cache?",
      text = clearCacheConfirmText(touch),
      confirm = "Clear cache",
      onDismiss = { confirming = null },
      onConfirm = {
        scope.launch {
          cache.run()
          cleared++
          messages.show("Cache cleared")
        }
      },
    )
    StorageWipe.SavedTabs -> ConfirmDialog(
      title = "${clearSavedTabsLabel(touch)}?",
      text = clearSavedTabsConfirmText(touch),
      confirm = clearSavedTabsLabel(touch),
      onDismiss = { confirming = null },
      onConfirm = {
        savedTabs.run()
        messages.show(if (touch) "Queue cleared" else "Tabs cleared")
      },
    )
    null -> Unit
  }
}

internal const val CacheRowTag = "settings-cache"

private enum class StorageWipe { Cache, SavedTabs }

/** Asks once before a wipe: [confirm] in the error colour runs [onConfirm]; Cancel or outside the dialog doesn't. */
@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = {
      TextButton(
        onClick = {
          onDismiss()
          onConfirm()
        },
      ) { Text(confirm, color = MaterialTheme.colorScheme.error) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * A row that does something when tapped: [label], and [help] under it. The whole row is the button, 56 dp tall at
 * least; a [destructive] one's label is in the error colour.
 */
@Composable
internal fun ActionRow(
  label: String,
  help: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  destructive: Boolean = false,
) {
  Column(
    modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(vertical = 8.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      label,
      style = MaterialTheme.typography.bodyLarge,
      color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (help != null) {
      Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/**
 * "Start on": what the app opens on at a launch, or on coming back after more than 5 minutes away (#177). A radio
 * group, each choice a row a screen reader reads as one.
 */
@Composable
private fun StartOnChoice(selected: StartOn, onSelect: (StartOn) -> Unit) {
  Column(Modifier.selectableGroup()) {
    Text("Start on", style = MaterialTheme.typography.bodyLarge)
    Text(
      "After a launch, or coming back after more than 5 minutes",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StartOn.entries.forEach { choice ->
      Row(
        Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .selectable(selected = choice == selected, role = Role.RadioButton, onClick = { onSelect(choice) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // The row is the control: the button only shows its state.
        RadioButton(selected = choice == selected, onClick = null)
        Text(
          when (choice) {
            StartOn.Queue -> "Queue"
            StartOn.Browse -> "Browse"
          },
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }
}

/**
 * Asks once before Clear everything (Settings, and the queue page's ⋮), then runs it in [scope] and says so. [scope]
 * is the caller's, and must outlive this dialog and whatever the wipe closes; the wipe runs on the main thread, as it
 * closes tabs. [onConfirm] runs as the user confirms, before the wipe.
 */
@Composable
internal fun ClearEverythingConfirm(
  scope: CoroutineScope,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit = {},
  clear: ClearEverything = koinInject(),
  messages: Messages = koinInject(),
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Clear everything?") },
    text = { Text(ClearEverythingConfirmText) },
    confirmButton = {
      TextButton(
        onClick = {
          onDismiss()
          onConfirm()
          scope.launch(Dispatchers.Main) {
            clear.run()
            messages.show("Everything cleared")
          }
        },
      ) { Text("Clear everything", color = MaterialTheme.colorScheme.error) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * A labelled switch: the whole row toggles it, and a screen reader reads it as one switch. An [icon] leads the row, as
 * on the sidebar's entries.
 */
@Composable
internal fun SwitchRow(
  label: String,
  help: String?,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  Row(
    modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // 12 dp from the icon to the label, as on the sidebar's entries, so their labels line up.
    Row(
      Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (icon != null) Icon(icon, contentDescription = null)
      Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (help != null) {
          Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
    // The row is the control: the switch only shows its state.
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}
