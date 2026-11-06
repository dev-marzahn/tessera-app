package dev.marzahn.sankaku.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.marzahn.sankaku.features.settings.openSettingsFromSidebar
import dev.marzahn.sankaku.ui.components.UPDATE_AVAILABLE_DOT
import dev.marzahn.sankaku.ui.components.UpdateDot
import dev.marzahn.sankaku.update.UpdateCheck
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.tabs.SETTINGS_TITLE
import dev.marzahn.sankaku.ui.components.ModalNavigationRail
import dev.marzahn.sankaku.ui.openOnClick
import org.koin.compose.koinInject

/**
 * The modal wide navigation rail, over the whole window while [open]. A click opens the entry in the current tab; a
 * middle or Cmd/Ctrl-click in a new one. The grids come from [GridPresets], so they open a clean slate. Books arrives
 * with M3. At the foot: the G only switch (signed in), Settings (a tab on desktop, a page over the app on a phone, #205),
 * the account and the app's memory. It closes with a swipe, back or the scrim, through [onClose]. Every entry has an icon, and the one
 * whose preset is the search of [current], the active tab's screen, is selected ([isCurrent]).
 */
@Composable
fun Sidebar(
  open: Boolean,
  onClose: () -> Unit,
  current: Route? = null,
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
  // White (the lowest container), so it reads as a sheet over the grid rather than another tint of the window.
  ModalNavigationRail(open, onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
    Text("Tessera", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
    Entry("Browse", search, Route.Grid(emptyList()), navigator, onClose, selected = isCurrent(current, emptyList()))
    Entry(
      "For you",
      auto_awesome,
      Route.Grid(GridPresets.forYou),
      navigator,
      onClose,
      selected = isCurrent(current, GridPresets.forYou),
    )
    if (user != null) {
      Entry(
        "Following",
        group,
        Route.Grid(GridPresets.following),
        navigator,
        onClose,
        selected = isCurrent(current, GridPresets.following),
      )
      val favourites = GridPresets.favourites(user.name)
      Entry(
        "Favourites",
        favorite,
        Route.Grid(favourites),
        navigator,
        onClose,
        selected = isCurrent(current, favourites),
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
        selected = isCurrent(current, GridPresets.following),
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
      selected = isCurrent(current, GridPresets.popular),
    )
    Entry(
      "Top",
      trophy,
      Route.Grid(GridPresets.top),
      navigator,
      onClose,
      selected = isCurrent(current, GridPresets.top),
    )
    Spacer(Modifier.weight(1f))
    // One tap from any screen with the sidebar, for when someone is watching. Signed out the rating is locked anyway.
    if (user != null) {
      SwitchRow(
        label = "G only",
        help = "Show only G-rated posts",
        checked = gOnly,
        onCheckedChange = privacy::setGOnly,
        modifier = Modifier.padding(horizontal = 28.dp),
        // A debug run launched with `safeOnly` holds the lock on: the row shows it and can't change it.
        enabled = !privacy.gOnlyForced,
        icon = shield,
      )
    }
    // A tab on desktop, a page over the app on a phone, where a long-press opens it too (#205).
    Entry(
      SETTINGS_TITLE,
      settings,
      onOpen = {
        onClose()
        openSettingsFromSidebar(platformIsTouch, inNewTab = false, navigator, settingsController)
      },
      onOpenInNewTab = {
        if (platformIsTouch) onClose()
        openSettingsFromSidebar(platformIsTouch, inNewTab = true, navigator, settingsController)
      },
      selected = current is Route.Settings,
      // The dot ☰ shows for a newer release (#209) leads here, to Settings → Updates.
      dot = if (update != null) UPDATE_AVAILABLE_DOT else null,
    )
    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    AccountSection(open, onClose, Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp))
    MemoryUsage(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp))
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
  )
}

/** [selected] draws M3's active indicator: the secondary-container pill, with its on-colour for icon and label. */
@Composable
private fun Entry(
  label: String,
  icon: ImageVector,
  onOpen: () -> Unit,
  onOpenInNewTab: () -> Unit,
  selected: Boolean = false,
  dot: String? = null,
) {
  val colors = MaterialTheme.colorScheme
  // A plain row: NavigationDrawerItem's own click handler would swallow the middle click.
  Row(
    Modifier
      .padding(horizontal = 12.dp)
      .fillMaxWidth()
      .height(56.dp)
      .clip(CircleShape)
      .background(if (selected) colors.secondaryContainer else Color.Transparent)
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
