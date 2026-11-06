package dev.marzahn.sankaku.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import com.google.fonts.icons.refresh as refresh_icon
import kotlin.math.ceil
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.close
import com.google.fonts.icons.menu
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.ui.components.UPDATE_AVAILABLE_DOT
import dev.marzahn.sankaku.ui.components.UpdateDot
import dev.marzahn.sankaku.update.UpdateCheck
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.searchTags
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import dev.marzahn.sankaku.features.grid.SearchSummary
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.grid.ratingLocked
import dev.marzahn.sankaku.features.grid.withoutRating
import dev.marzahn.sankaku.ui.components.BackButton
import dev.marzahn.sankaku.ui.components.FullScreenSearchState
import dev.marzahn.sankaku.ui.components.fullScreenSearchAnchor
import dev.marzahn.sankaku.ui.components.ToggleIconButton
import dev.marzahn.sankaku.ui.components.spatialSpec
import org.koin.compose.koinInject

/**
 * The one bar under the tabs: back, menu, search, refresh (the account is in the [Sidebar]); on Android only menu and
 * search ([platformIsTouch]). The search shows the active grid's tags; [onOpenSearch] opens the search panel over
 * it, placed by the bounds [onSearchPlaced] reports, or on Android the full-screen search grown out of it
 * ([fullScreenSearch]).
 */
@Composable
fun TopBar(
  current: Route?,
  canGoBack: Boolean,
  menuOpen: Boolean,
  onMenu: () -> Unit,
  onOpenSearch: () -> Unit,
  onSearchPlaced: (Rect) -> Unit,
  fullScreenSearch: FullScreenSearchState,
  modifier: Modifier = Modifier,
  navigator: Navigator = koinInject(),
  session: AuthSession = koinInject(),
  privacy: PrivacySettings = koinInject(),
  tagsRepository: TagsRepository = koinInject(),
  overrides: BackOverrides = koinInject(),
  updates: UpdateCheck = koinInject(),
) {
  val known by tagsRepository.known.collectAsStateWithLifecycle()
  val signedIn by session.isSignedIn.collectAsStateWithLifecycle()
  val searched = current?.searchTags.orEmpty()
  // Ratings show as badges, not chips; a locked rating is G whatever the search had.
  val tags = withoutRating(searched)
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  val ratings = if (ratingLocked(signedIn, gOnly)) setOf(Rating.G) else Rating.inTags(searched)
  LaunchedEffect(tags) { tagsRepository.lookUp(tags) }

  Row(
    modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // The gap after Back is inside what shrinks: as the Row's spacing it outlived the button by a frame and the bar
    // jumped left by it when Back was finally removed.
    // Android has no Back button: system and predictive back do it.
    AnimatedVisibility(
      !platformIsTouch && (canGoBack || overrides.active),
      enter = expandHorizontally(spatialSpec()),
      exit = shrinkHorizontally(spatialSpec()),
    ) {
      Row {
        BackButton(onClick = { if (!overrides.back()) navigator.goBack() })
        Spacer(Modifier.width(4.dp))
      }
    }
    // Touch: after "Session expired" on the top line, a red badge stays on ☰ (the sidebar holds the account's Sign in)
    // until signed in again (`.scratch/android-feedback/`, C, row 5).
    val expired = platformIsTouch && rememberSessionExpired(session.state)
    // A newer release (#209) puts a dot on ☰ too, in the primary colour; the red expired badge wins over it.
    val update by updates.available.collectAsStateWithLifecycle()
    BadgedBox(
      badge = {
        val place = Modifier.offset(x = (-10).dp, y = 10.dp)
        when {
          expired -> Badge(place)
          update != null -> UpdateDot(place)
        }
      },
    ) {
      ToggleIconButton(checked = menuOpen, onCheckedChange = { onMenu() }) {
        Icon(if (menuOpen) close else menu, contentDescription = menuDescription(expired, update != null))
      }
    }
    Spacer(Modifier.width(4.dp))
    SearchSummary(
      tags = tags,
      ratings = ratings,
      known = known,
      me = session.signedInName(),
      signedOut = !signedIn,
      onOpen = onOpenSearch,
      modifier = Modifier
        .weight(1f)
        .widthIn(max = 840.dp)
        .onPlaced { onSearchPlaced(it.boundsInRoot()) }
        .fullScreenSearchAnchor(fullScreenSearch),
    )
    // Android refreshes a grid by pulling it down.
    if (!platformIsTouch) {
      Spacer(Modifier.width(4.dp))
      if (current is Route.Grid) RefreshButton()
    }
  }
}

/**
 * Whether the session ran out and hasn't been signed in again since: true from [AuthState.SignedOut.Reason.Expired]
 * until [AuthState.SignedIn], whatever states pass between (a failed sign-in attempt keeps it).
 */
@Composable
internal fun rememberSessionExpired(state: StateFlow<AuthState>): Boolean {
  var expired by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(state) {
    state.collect {
      if (it == AuthState.SignedOut(AuthState.SignedOut.Reason.Expired)) expired = true
      if (it is AuthState.SignedIn) expired = false
    }
  }
  return expired
}

/**
 * Reloads the grid on screen past the page cache (also Cmd/Ctrl-R), so posts released since it loaded show. The icon
 * turns while the reload runs and finishes its turn when it ends.
 */
@Composable
private fun RefreshButton(refresh: GridRefresh = koinInject()) {
  val running by refresh.running.collectAsStateWithLifecycle()
  val turn = remember { Animatable(0f) }
  LaunchedEffect(running) {
    if (running) {
      while (true) turn.animateTo(turn.value + 360f, tween(700, easing = LinearEasing))
    } else {
      turn.animateTo(ceil(turn.value / 360f) * 360f, tween(300))
    }
  }
  IconButton(onClick = refresh::request) {
    Icon(refresh_icon, contentDescription = "Refresh (⌘R)", modifier = Modifier.graphicsLayer { rotationZ = turn.value })
  }
}

/** ☰'s description: the red badge's "session expired" (touch) outranks the dot's "update available" (#209). */
internal fun menuDescription(expired: Boolean, updateAvailable: Boolean): String = when {
  expired -> "Menu, session expired"
  updateAvailable -> "Menu, ${UPDATE_AVAILABLE_DOT.lowercase()}"
  else -> "Menu"
}
