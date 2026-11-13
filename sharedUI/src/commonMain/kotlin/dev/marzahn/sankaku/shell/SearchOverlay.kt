package dev.marzahn.sankaku.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.isBooks
import dev.marzahn.sankaku.data.BookSearch
import dev.marzahn.sankaku.searchTags
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.RecentSearches
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.grid.GridCursorRequest
import dev.marzahn.sankaku.features.grid.FullScreenSearch
import dev.marzahn.sankaku.features.grid.SearchPanel
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.FullScreenSearchState
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.components.spatialSpec
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** The signed-in user's name, which the search's favourites filter needs; null while signed out or restoring. */
@Composable
internal fun AuthSession.signedInName(): String? =
  (state.collectAsStateWithLifecycle().value as? AuthState.SignedIn)?.user?.name

/** Opens the search over the active tab, its draft the screen's search: the empty grid's Edit search (#135). */
val LocalOpenSearch = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Whether submitting [next] over [current] (whose search is [searched]) opens a grid: a changed search, or on a lone
 * post its own search too, since nothing is under the post (#181). That jumps to the search's source when it's open,
 * and else opens a new source in front of the post's block (`TabsController.search`).
 */
internal fun opensSearch(next: List<String>, searched: List<String>, current: Route?): Boolean =
  next != searched || current is Route.Post || current is Route.BookSeries

/**
 * What a search submitted over [current] opens: over a Books screen, a Books search (#380), keeping its order and
 * English only ([BookSearch.submitted]); anywhere else, a grid.
 */
internal fun searchRoute(next: List<String>, current: Route?): Route =
  if (current?.isBooks == true) Route.Books(BookSearch.submitted(next, current.searchTags)) else Route.Grid(next)

/** How long a submitted search's cursor request waits for the grid it opens (see [GridCursorRequest]). */
private const val CURSOR_REQUEST_FRAMES = 5

/**
 * The search panel over a dimmed screen, opened from the top bar's search at [anchor] (bounds in the root) and as
 * wide as it. On a touch screen it's the [FullScreenSearch] instead, grown out of the search line that [fullScreen]
 * anchors. Submitting opens the new grid with `goTo`, so Back returns to the previous search.
 */
@Composable
fun SearchOverlay(
  open: Boolean,
  current: Route?,
  anchor: Rect,
  fullScreen: FullScreenSearchState,
  onClose: () -> Unit,
  navigator: Navigator = koinInject(),
  cursorRequest: GridCursorRequest = koinInject(),
  session: AuthSession = koinInject(),
  privacy: PrivacySettings = koinInject(),
  tags: TagsRepository = koinInject(),
  recent: RecentSearches = koinInject(),
  signIn: SignInController = koinInject(),
) {
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  // The locked Rating chip's actions (#523). Sign-in opens over the search, which stays open behind it.
  val onSignIn = { signIn.open() }
  val onTurnOffGOnly = if (privacy.gOnlyForced) null else ({ privacy.setGOnly(false) })
  var origin by remember { mutableStateOf(Offset.Zero) }
  val scope = rememberCoroutineScope()
  val signedIn by session.isSignedIn.collectAsStateWithLifecycle()
  // The favourites filter's tag holds the signed-in user's name (`fav:<name>`).
  val me = session.signedInName()
  val known by tags.known.collectAsStateWithLifecycle()
  // Recent searches can hold tags no page or suggestion has taught the app yet.
  LaunchedEffect(open) { if (open) tags.lookUp(recent.searches.value.flatten()) }
  val searched = current?.searchTags.orEmpty()
  if (platformIsTouch) {
    // No grid cursor on a touch screen, so no cursor request either.
    FullScreenSearch(
      state = fullScreen,
      open = open,
      initial = searched,
      signedIn = signedIn,
      safeOnly = gOnly,
      recent = recent,
      suggest = tags::suggest,
      known = known,
      me = me,
      onSignIn = onSignIn,
      onTurnOffGOnly = onTurnOffGOnly,
      onSubmit = { next ->
        onClose()
        if (opensSearch(next, searched, current)) navigator.goTo(searchRoute(next, current))
      },
      onClose = onClose,
    )
    return
  }
  val scrimAlpha by animateFloatAsState(if (open) 1f else 0f, effectsSpec())
  // Darker over a dark theme, where the sheet is only a little lighter than the grid.
  val scrimDim = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.55f else 0.40f
  Box(Modifier.fillMaxSize().onPlaced { origin = it.positionInRoot() }) {
    if (scrimAlpha > 0f) {
      Box(
        Modifier
          .fillMaxSize()
          .graphicsLayer { alpha = scrimAlpha }
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimDim))
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
      )
    }
    val width = with(LocalDensity.current) { anchor.width.toDp() }
    AnimatedVisibility(
      visible = open,
      enter = fadeIn(effectsSpec()) + expandVertically(spatialSpec(), expandFrom = Alignment.Top),
      exit = fadeOut(effectsSpec()) + shrinkVertically(spatialSpec(), shrinkTowards = Alignment.Top),
      modifier = Modifier
        .offset { IntOffset((anchor.left - origin.x).roundToInt(), (anchor.top - origin.y).roundToInt()) }
        .width(width)
        .widthIn(min = 320.dp),
    ) {
      SearchPanel(
        initial = searched,
        signedIn = signedIn,
        safeOnly = gOnly,
        recent = recent,
        suggest = tags::suggest,
        known = known,
        me = me,
        onSignIn = onSignIn,
        onTurnOffGOnly = onTurnOffGOnly,
        onSubmit = { next ->
          onClose()
          if (opensSearch(next, searched, current)) {
            // The new grid starts with its keyboard cursor on the first cell; it composes within a frame or two.
            cursorRequest.request()
            navigator.goTo(searchRoute(next, current))
            scope.launch {
              repeat(CURSOR_REQUEST_FRAMES) { withFrameNanos {} }
              cursorRequest.lapse()
            }
          }
        },
        onClose = onClose,
      )
    }
  }
}
