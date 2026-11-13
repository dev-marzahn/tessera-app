package dev.marzahn.sankaku.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.marzahn.sankaku.api.dto.UserSummary
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.auto_awesome
import com.google.fonts.icons.favorite
import com.google.fonts.icons.group
import com.google.fonts.icons.local_fire_department
import com.google.fonts.icons.menu_book
import com.google.fonts.icons.search
import com.google.fonts.icons.settings
import com.google.fonts.icons.shield
import com.google.fonts.icons.trophy
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.data.GridPresets
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.features.auth.AccountSection
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.settings.SettingsController
import dev.marzahn.sankaku.features.settings.SwitchRow
import dev.marzahn.sankaku.ui.components.UPDATE_AVAILABLE_DOT
import dev.marzahn.sankaku.ui.components.UpdateDot
import dev.marzahn.sankaku.update.UpdateCheck
import dev.marzahn.sankaku.tabs.SETTINGS_TITLE
import dev.marzahn.sankaku.tabs.BOOKS_TITLE
import dev.marzahn.sankaku.isBooks
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.OncePerPress
import dev.marzahn.sankaku.ui.components.ModalNavigationRail
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isPlain
import dev.marzahn.sankaku.ui.components.keyRing
import dev.marzahn.sankaku.ui.openOnClick
import org.koin.compose.koinInject

/**
 * The modal wide navigation rail, over the whole window while [open]. A click opens the entry in the current tab; a
 * middle or Cmd/Ctrl-click in a new one. The grids come from [GridPresets], so they open a clean slate, as Books does
 * (#380). At the foot: the G only switch (signed in), Settings (a side sheet over the tab on desktop, #460; a page over
 * the app on a phone, #205), the account and the app's memory. It closes with a swipe, back or the scrim, through
 * [onClose]. Every entry has an icon, and the one whose preset is the search of [current], the active tab's screen, is
 * selected ([isCurrent]); while Settings is open, Settings is.
 *
 * [byKey]: opened with [Shortcuts.Sidebar] (#416). The keys' selection then starts on the selected entry (Browse when
 * none is) and W/S move it through every entry, G only and the account included; Space opens, E opens in a new tab, and
 * Esc or G closes. G closes through [onClosedByKey], and only while not [keyHeld] (still down from opening or closing
 * it); its release, when the rail hears it, comes through [onCloseKeyUp]. A hint row under the title says so.
 */
@Composable
fun Sidebar(
  open: Boolean,
  onClose: () -> Unit,
  current: Route? = null,
  byKey: Boolean = false,
  keyHeld: Boolean = false,
  onClosedByKey: () -> Unit = onClose,
  onCloseKeyUp: () -> Unit = {},
  navigator: Navigator = koinInject(),
  session: AuthSession = koinInject(),
  signIn: SignInController = koinInject(),
  privacy: PrivacySettings = koinInject(),
  settingsController: SettingsController = koinInject(),
  updates: UpdateCheck = koinInject(),
) {
  val user = (session.state.collectAsStateWithLifecycle().value as? AuthState.SignedIn)?.user
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  val update by updates.available.collectAsStateWithLifecycle()
  // Settings open over the tab (#460) is the entry that's selected, not the tab's.
  val settingsOpen by settingsController.isOpen.collectAsStateWithLifecycle()
  val selectedRoute = current.takeUnless { settingsOpen }
  val anyCurrent = settingsOpen || hasEntry(selectedRoute, user?.name)
  // White (the lowest container), so it reads as a sheet over the grid rather than another tint of the window.
  // Opened by key, an entry takes focus rather than the column, and G closes it again.
  ModalNavigationRail(
    open,
    onClose,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    closeKey = Shortcuts.Sidebar.key,
    focusColumn = !byKey,
    closeKeyHeld = keyHeld,
    onClosedByKey = onClosedByKey,
    onCloseKeyUp = onCloseKeyUp,
  ) {
    CompositionLocalProvider(LocalOpenedByKey provides (open && byKey)) {
      // The entries and G only scroll when the rail is short (landscape, large text); Settings and the account stay
      // pinned under them (#525). With room to spare the column fills it, so G only still sits at the foot.
      BoxWithConstraints(Modifier.weight(1f)) {
        val room = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = room)) {
          SidebarEntries(byKey, user, selectedRoute, anyCurrent, gOnly, navigator, onClose, session, signIn, privacy)
        }
      }
      // A side sheet over the tab on desktop (#460), a page over the app on a phone (#205): never a tab, so a middle or
      // Cmd/Ctrl-click (a long-press on a phone) opens it too.
      val openSettings = {
        onClose()
        settingsController.open()
      }
      Entry(
        SETTINGS_TITLE,
        settings,
        onOpen = openSettings,
        onOpenInNewTab = openSettings,
        selected = settingsOpen,
        // The dot ☰ shows for a newer release (#209) leads here, to Settings → Updates.
        dot = if (update != null) UPDATE_AVAILABLE_DOT else null,
      )
      HorizontalDivider(Modifier.padding(horizontal = 24.dp))
      // The last stop for W/S: Sign in, or Sign out, whose question takes the focus so a second Space confirms.
      AccountSection(open, onClose, Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp).keyRing(CircleShape))
      MemoryUsage(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp))
    }
  }
}

/** The sidebar's title and entries, then G only at the foot of the room ([Sidebar]'s scrolling part). */
@Composable
private fun ColumnScope.SidebarEntries(
  byKey: Boolean,
  user: UserSummary?,
  selectedRoute: Route?,
  anyCurrent: Boolean,
  gOnly: Boolean,
  navigator: Navigator,
  onClose: () -> Unit,
  session: AuthSession,
  signIn: SignInController,
  privacy: PrivacySettings,
) {
  Text(
    "Tessera",
    style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = if (byKey) 8.dp else 24.dp),
  )
  if (byKey) SidebarKeyHints(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp))
  Entry(
    "Browse",
    search,
    Route.Grid(emptyList()),
    navigator,
    onClose,
    selected = isCurrent(selectedRoute,emptyList()),
    startHere = !anyCurrent,
  )
  Entry(
    "For you",
    auto_awesome,
    Route.Grid(GridPresets.forYou),
    navigator,
    onClose,
    selected = isCurrent(selectedRoute,GridPresets.forYou),
  )
  if (user != null) {
    Entry(
      "Following",
      group,
      Route.Grid(GridPresets.following),
      navigator,
      onClose,
      selected = isCurrent(selectedRoute,GridPresets.following),
    )
    val favourites = GridPresets.favourites(user.name)
    Entry(
      "Favourites",
      favorite,
      Route.Grid(favourites),
      navigator,
      onClose,
      selected = isCurrent(selectedRoute,favourites),
    )
  } else {
    // Favourites needs the account's name, so its route is made once signed in; with no name no tab matches it.
    SignInEntry(
      "Following",
      group,
      "to see Following",
      signIn,
      navigator,
      onClose,
      selected = isCurrent(selectedRoute,GridPresets.following),
    ) {
      Route.Grid(GridPresets.following)
    }
    SignInEntry("Favourites", favorite, "to see your favourites", signIn, navigator, onClose) {
      (session.state.value as? AuthState.SignedIn)?.let { Route.Grid(GridPresets.favourites(it.user.name)) }
    }
  }
  Entry(
    "Popular",
    local_fire_department,
    Route.Grid(GridPresets.popular),
    navigator,
    onClose,
    selected = isCurrent(selectedRoute,GridPresets.popular),
  )
  Entry(
    "Top",
    trophy,
    Route.Grid(GridPresets.top),
    navigator,
    onClose,
    selected = isCurrent(selectedRoute,GridPresets.top),
  )
  // A clean slate too: Popular, every language (#380). Selected on any Books screen.
  Entry(BOOKS_TITLE, menu_book, Route.Books(), navigator, onClose, selected = selectedRoute?.isBooks == true)
  Spacer(Modifier.weight(1f))
  // One tap from any screen with the sidebar, for when someone is watching. Signed out the rating is locked anyway.
  // A stop for W/S too, where Space switches it; held on by `safeOnly`, it's disabled and the keys pass it by.
  if (user != null) {
    SwitchRow(
      label = "G only",
      help = "Show only G-rated posts",
      checked = gOnly,
      onCheckedChange = privacy::setGOnly,
      // 28 dp in all, as before; the ring sits 12 dp in, where the entries' pills are.
      modifier = Modifier.padding(horizontal = 12.dp).keyRing(CircleShape).padding(horizontal = 16.dp),
      // A debug run launched with `safeOnly` holds the lock on: the row shows it and can't change it.
      enabled = !privacy.gOnlyForced,
      icon = shield,
    )
  }
}

/** What the keys do while the sidebar is open by key (#416), as the search panel lists its own. */
@Composable
private fun SidebarKeyHints(modifier: Modifier = Modifier) {
  FlowRow(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    KeyHint("W S", "move")
    KeyHint(Shortcuts.OpenHere.label, "open")
    KeyHint(Shortcuts.OpenInTab.label, "new tab")
    KeyHint(Shortcuts.Back.label, "close")
  }
}

/**
 * A signed-out entry: any click opens sign-in saying [reason], and once signed in opens [route] here, or in a new tab
 * for a middle or Cmd/Ctrl-click (a long-press on Android). [route] is made then, so it can use the account.
 */
@Composable
private fun SignInEntry(
  label: String,
  icon: ImageVector,
  reason: String,
  signIn: SignInController,
  navigator: Navigator,
  onClose: () -> Unit,
  selected: Boolean = false,
  route: () -> Route?,
) {
  fun open(inNewTab: Boolean) {
    onClose()
    signInThenOpen(signIn, navigator, reason, inNewTab, route)
  }
  Entry(
    label,
    icon,
    onOpen = { open(inNewTab = false) },
    onOpenInNewTab = { open(inNewTab = true) },
    selected = selected,
  )
}

/**
 * Whether [current] is the grid of the preset [tags], exactly: the same tags in any order, nothing added or missing.
 * A filter added on top (Favourites with `rating:g`) or a typed search is a search of its own, so it selects no
 * entry, and neither does a post, whose tab shows a post rather than a preset. The locked `rating:s` isn't in a
 * route's tags, so a signed-out Popular still matches.
 */
internal fun isCurrent(current: Route?, tags: List<String>): Boolean =
  current is Route.Grid && current.tags.toSet() == tags.toSet()

/**
 * Whether some sidebar entry is [current]'s, as the entries' own `selected` say: a preset grid (Favourites only with
 * the account's [userName]) or Books. Opened by key with none (a post, a typed search), the keys start on Browse (#416).
 */
internal fun hasEntry(current: Route?, userName: String?): Boolean {
  val presets = listOfNotNull(
    emptyList(),
    GridPresets.forYou,
    GridPresets.following,
    userName?.let { GridPresets.favourites(it) },
    GridPresets.popular,
    GridPresets.top,
  )
  return current?.isBooks == true || presets.any { isCurrent(current, it) }
}

/** What the shell's keys do with a key press, for the sidebar (#416). */
internal enum class SidebarKey {
  /** Not the sidebar's: other handlers may take it. */
  None,

  /** A repeat of the G that opened or closed the sidebar, still held: taken, and nothing happens. */
  Repeat,

  /** Opens the sidebar by key. */
  Open,
}

/**
 * The shell's G (#416): plain G opens the sidebar on the desktop while the search panel is closed (its text field
 * would type it). While [held] (still down from opening or closing the sidebar), its repeats do nothing.
 */
internal fun shellSidebarKey(key: Key, plain: Boolean, held: Boolean, searchOpen: Boolean, touch: Boolean): SidebarKey =
  when {
    key != Shortcuts.Sidebar.key -> SidebarKey.None
    held -> SidebarKey.Repeat
    plain && !searchOpen && !touch -> SidebarKey.Open
    else -> SidebarKey.None
  }

/** Opens sign-in saying [reason]; once signed in, opens [route] here or in a new tab, unless it's null by then. */
internal fun signInThenOpen(
  signIn: SignInController,
  navigator: Navigator,
  reason: String,
  inNewTab: Boolean,
  route: () -> Route?,
) {
  signIn.open(reason) {
    val target = route() ?: return@open
    if (inNewTab) navigator.openInNewTab(target) else navigator.goTo(target)
  }
}

/** An entry for [route]: a click opens it here and closes the sidebar; a middle or Cmd/Ctrl-click in a new tab. */
@Composable
private fun Entry(
  label: String,
  icon: ImageVector,
  route: Route,
  navigator: Navigator,
  onClose: () -> Unit,
  selected: Boolean = false,
  startHere: Boolean = false,
) {
  Entry(
    label,
    icon,
    onOpen = {
      onClose()
      navigator.goTo(route)
    },
    onOpenInNewTab = { navigator.openInNewTab(route) },
    selected = selected,
    startHere = startHere,
  )
}

/**
 * Whether the sidebar was opened with [Shortcuts.Sidebar] (#416): the keys' selection then starts on the selected
 * entry, or the one marked `startHere` when none is, and the key hints show.
 */
internal val LocalOpenedByKey = compositionLocalOf { false }

/**
 * [selected] draws M3's active indicator: the secondary-container pill, with its on-colour for icon and label. With
 * the keys (#416) the entry is a stop for W/S, with the amber ring while it has focus; Space opens it as a click does
 * and [Shortcuts.OpenInTab] (E) as a middle-click does. Opened by key, the [selected] entry (or the one to [startHere]
 * when no entry is selected) takes focus.
 */
@Composable
internal fun Entry(
  label: String,
  icon: ImageVector,
  onOpen: () -> Unit,
  onOpenInNewTab: () -> Unit,
  selected: Boolean = false,
  dot: String? = null,
  startHere: Boolean = false,
) {
  val colors = MaterialTheme.colorScheme
  val focus = remember { FocusRequester() }
  val takesFocus = LocalOpenedByKey.current && (selected || startHere)
  val newTabPresses = remember { OncePerPress() }
  LaunchedEffect(takesFocus) { if (takesFocus) focus.requestFocus() }
  // A plain row: NavigationDrawerItem's own click handler would swallow the middle click.
  Row(
    Modifier
      .padding(horizontal = 12.dp)
      .fillMaxWidth()
      .height(56.dp)
      .keyRing(CircleShape)
      .clip(CircleShape)
      .background(if (selected) colors.secondaryContainer else Color.Transparent)
      // Once per press: held, E's repeats would open tab after tab. W/S move the focus on, and its release goes there.
      .onFocusChanged { if (!it.hasFocus) newTabPresses.letGo() }
      .onKeyEvent { event ->
        if (event.key != Shortcuts.OpenInTab.key || !event.isPlain) return@onKeyEvent false
        if (newTabPresses.isNewPress(event)) onOpenInNewTab()
        event.type == KeyEventType.KeyDown
      }
      .focusRequester(focus)
      .openOnClick(onOpen, onOpenInNewTab)
      // A tab, as M3's own rail items are, so "selected" says what is selected.
      .semantics {
        role = Role.Tab
        this.selected = selected
        // A screen reader says what the dot means, after the label.
        if (dot != null) stateDescription = dot
      }
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    val content = if (selected) colors.onSecondaryContainer else LocalContentColor.current
    Icon(icon, contentDescription = null, tint = content)
    Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    if (dot != null) UpdateDot()
  }
}
