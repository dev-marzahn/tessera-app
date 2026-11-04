package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.arrow_back
import com.google.fonts.icons.error
import dev.marzahn.sankaku.PlatformBackHandler
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.QueueSettings
import dev.marzahn.sankaku.data.StartOn
import dev.marzahn.sankaku.features.auth.AccountRow
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.tabs.SETTINGS_TITLE
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.AppSnackbarHost
import dev.marzahn.sankaku.ui.components.AppTopBar
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

/** What the Show queue buttons switch says it does (#178). */
internal const val ShowQueueButtonsHelp = "Shows ✕, ‹ and › beside the queue count, to close and move without swiping."

/** The desktop warning when the keychain can't be used (`TokenStorageStatus.InMemory`): the spec's words. */
internal const val InMemoryTokensWarning = "Can't store tokens; you'll need to sign in each launch"

/**
 * Settings' sections, in order (#205). Updates, Diagnostics and About are slots their tickets fill (`SectionSlots.kt`).
 * Queue and Accessibility are the switches #229 and #178 added.
 */
internal enum class SettingsSection(val title: String) {
  Account("Account"),
  Privacy("Privacy"),
  Queue("Queue"),
  Accessibility("Accessibility"),
  Storage("Storage"),
  Updates("Updates"),
  Diagnostics("Diagnostics"),
  About("About"),
}

/**
 * The sections drawn on a phone ([touch]) or desktop: Accessibility's one switch is for the count pill, which only a
 * phone has; a slot is drawn once its ticket has filled it.
 */
internal fun shownSections(
  touch: Boolean,
  updates: Boolean = UPDATES_FILLED,
  diagnostics: Boolean = DIAGNOSTICS_FILLED,
  about: Boolean = ABOUT_FILLED,
): List<SettingsSection> = SettingsSection.entries.filter {
  when (it) {
    SettingsSection.Accessibility -> touch
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
 * Settings as a desktop tab (`Route.Settings`, #205): the sections in a column of reading width, under a heading. The
 * top bar and the tab strip stay; Back and Esc close the tab.
 */
@Composable
fun SettingsScreen() {
  Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
      Text(
        SETTINGS_TITLE,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
      )
      SettingsSections(touch = false)
    }
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

/** Every shown section ([shownSections]) under its heading, with a divider between them. */
@Composable
internal fun ColumnScope.SettingsSections(touch: Boolean, onClearedEverything: () -> Unit = {}) {
  shownSections(touch).forEachIndexed { index, section ->
    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SectionHeading(section.title)
    when (section) {
      SettingsSection.Account -> AccountSettings(touch)
      SettingsSection.Privacy -> PrivacySettings(touch, onClearedEverything)
      SettingsSection.Queue -> QueueSettings()
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
 * [onClearedEverything] runs as it's confirmed.
 */
@Composable
private fun PrivacySettings(touch: Boolean, onClearedEverything: () -> Unit, privacy: PrivacySettings = koinInject()) {
  val hideInRecents by privacy.hideInRecents.collectAsStateWithLifecycle()
  val startOn by privacy.startOn.collectAsStateWithLifecycle()
  val alwaysStartGOnly by privacy.alwaysStartGOnly.collectAsStateWithLifecycle()
  var confirming by remember { mutableStateOf(false) }
  // The app's scope: the page closes on the confirm (phone), and the wipe closes this tab itself (desktop), so a scope
  // of this screen's would cancel the wipe halfway.
  val scope = koinInject<CoroutineScope>()
  // Only Android has a Recents screen, and only touch has the queue page to start on.
  if (touch) {
    SwitchRow("Hide in Recents", HideInRecentsHelp, hideInRecents, privacy::setHideInRecents)
    StartOnChoice(startOn, privacy::setStartOn)
  }
  SwitchRow("Always start in G only", AlwaysStartGOnlyHelp, alwaysStartGOnly, privacy::setAlwaysStartGOnly)
  ActionRow("Clear everything…", help = null, onClick = { confirming = true }, destructive = true)
  if (confirming) {
    ClearEverythingConfirm(scope = scope, onDismiss = { confirming = false }, onConfirm = onClearedEverything)
  }
}

/** Queue: Show For you in the queue (#229). */
@Composable
private fun QueueSettings(queue: QueueSettings = koinInject()) {
  val forYouInQueue by queue.forYouInQueue.collectAsStateWithLifecycle()
  SwitchRow("Show For you in the queue", ForYouInQueueHelp, forYouInQueue, queue::setForYouInQueue)
}

/** Accessibility (touch): Show queue buttons (#178). */
@Composable
private fun AccessibilitySettings(accessibility: AccessibilitySettings = koinInject()) {
  val showQueueButtons by accessibility.showQueueButtons.collectAsStateWithLifecycle()
  SwitchRow("Show queue buttons", ShowQueueButtonsHelp, showQueueButtons, accessibility::setShowQueueButtons)
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
