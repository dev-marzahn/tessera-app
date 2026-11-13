package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.api.ErrorAction
import dev.marzahn.sankaku.api.advice
import com.google.fonts.icons.cloud_off
import com.google.fonts.icons.error
import com.google.fonts.icons.favorite
import com.google.fonts.icons.favorite_filled
import com.google.fonts.icons.wifi
import com.google.fonts.icons.wifi_off
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppLoadingIndicator(
  modifier: Modifier = Modifier,
  size: Dp = 48.dp,
  color: Color = LoadingIndicatorDefaults.indicatorColor,
) {
  LoadingIndicator(modifier.size(size), color = color)
}

/** For a screen or page that's loading, rather than a button. */
val LargeIndicatorSize = 96.dp

/**
 * The favourite button wherever a post is shown, with its key: an unfilled heart toggle (filled and in the primary
 * colour when on), with a spring "pop" on every change, favouriting and unfavouriting alike (also when the change
 * comes from the key). The screen showing it handles the key, through `postShortcuts`. A grid cell, where the key does
 * nothing, uses [FavouriteBadgeButton] instead.
 */
@Composable
fun FavouriteButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FavouriteToggle(checked, onCheckedChange)
    KbdBadge(Shortcuts.Favourite.label)
  }
}

/**
 * A grid cell's heart, both badge and button. At rest a favourited post shows a small filled badge; while [expanded]
 * (the cell is hovered) it grows into a button that unfavourites. Not favourited, the same element scales in while
 * [expanded], outlined on a light container, and grows the same way. It grows from its top-end corner, so its place
 * in the cell's corner stays put. Clickable only while [expanded]; at rest it's the "Favourited" image. Changes pop
 * as [FavouriteButton]'s do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavouriteBadgeButton(favourited: Boolean, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  val corner = TransformOrigin(1f, 0f)
  AnimatedVisibility(
    visible = favourited || expanded,
    enter = scaleIn(fastSpatialSpec(), transformOrigin = corner) + fadeIn(effectsSpec()),
    exit = scaleOut(fastSpatialSpec(), transformOrigin = corner) + fadeOut(effectsSpec()),
    modifier = modifier,
  ) {
    val size by animateDpAsState(if (expanded) 40.dp else 26.dp, fastSpatialSpec())
    val iconSize by animateDpAsState(if (expanded) 24.dp else 16.dp, fastSpatialSpec())
    // Filled in the primary colour when favourited, so the heart reads on light and busy images alike.
    val container by animateColorAsState(
      if (favourited) MaterialTheme.colorScheme.primary
      else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
      effectsSpec(),
    )
    val content by animateColorAsState(
      if (favourited) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
      effectsSpec(),
    )
    val scale = rememberPop(favourited)
    Surface(
      shape = CircleShape,
      color = container,
      contentColor = content,
      shadowElevation = 2.dp,
      modifier = Modifier
        .graphicsLayer {
          scaleX = scale.value
          scaleY = scale.value
          transformOrigin = corner
        }
        .size(size)
        .then(
          if (expanded) Modifier.toggleable(value = favourited, role = Role.Checkbox, onValueChange = { onToggle() })
          else Modifier
        ),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = if (favourited) favorite_filled else favorite,
          contentDescription = when {
            !expanded -> "Favourited"
            favourited -> "Unfavourite"
            else -> "Favourite"
          },
          modifier = Modifier.size(iconSize),
        )
      }
    }
  }
}

/** The scale of a heart that pops on every change of [checked] after the first composition: up on, down off. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberPop(checked: Boolean): Animatable<Float, AnimationVector1D> {
  val scale = remember { Animatable(1f) }
  var first by remember { mutableStateOf(true) }
  val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
  LaunchedEffect(checked) {
    if (first) {
      first = false
      return@LaunchedEffect
    }
    scale.animateTo(if (checked) 1.25f else 0.8f, spring)
    scale.animateTo(1f, spring)
  }
  return scale
}

/**
 * A heart toggle with no key: [FavouriteButton]'s heart, for what has no ♡ key (a book, #383). Each time [rejected]
 * goes up (a failed toggle), it shakes back with an error outline ([Modifier.rejected]).
 */
@Composable
fun HeartToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, rejected: Int = 0, enabled: Boolean = true) {
  FavouriteToggle(checked, onCheckedChange, modifier.rejected(rejected, CircleShape), enabled)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavouriteToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val scale = rememberPop(checked)
  // A standard (unfilled) toggle: the filled heart in the primary colour carries the state.
  IconToggleButton(
    checked = checked,
    onCheckedChange = onCheckedChange,
    enabled = enabled,
    colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = MaterialTheme.colorScheme.primary),
    shapes = IconButtonDefaults.toggleableShapes(),
    modifier = modifier.graphicsLayer {
      scaleX = scale.value
      scaleY = scale.value
    },
  ) {
    Icon(
      imageVector = if (checked) favorite_filled else favorite,
      contentDescription = if (checked) "Unfavourite" else "Favourite",
    )
  }
}

/** [subtitle], if any, sits under [title] in the smaller, muted style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  navigationIcon: @Composable () -> Unit = {},
  actions: @Composable RowScope.() -> Unit = {},
) {
  TopAppBar(
    title = {
      if (subtitle == null) {
        Text(title)
      } else {
        Column {
          Text(title)
          Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    },
    navigationIcon = navigationIcon,
    actions = actions,
    modifier = modifier,
  )
}

/** [retryEnabledAt] keeps Retry disabled until then, for example while a host is rate-limited. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ErrorState(
  message: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  retryEnabledAt: Instant? = null,
) {
  val secondsLeft = countdownTo(retryEnabledAt)
  MessageState(error, MaterialTheme.colorScheme.error, message, modifier) {
    Button(onClick = onRetry, enabled = secondsLeft <= 0, shapes = ButtonDefaults.shapes()) {
      Text(if (secondsLeft > 0) "Retry in $secondsLeft s" else "Retry")
    }
  }
}

/**
 * In a load's place when it failed: [failure]'s advice, its sentence and its next steps (#520). Retry counts down a 429's pause;
 * Edit search and Sign in show only when the screen passes them. A connection error in a grid is [GridOfflineState].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApiErrorState(
  failure: Throwable,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  onEditSearch: (() -> Unit)? = null,
  onSignIn: (() -> Unit)? = null,
  signedIn: Boolean = true,
) {
  val advice = remember(failure, signedIn) { failure.advice(signedIn) }
  val retryEnabledAt = remember(failure) { advice.retryAfterSeconds?.let { Clock.System.now() + it.seconds } }
  val secondsLeft = countdownTo(retryEnabledAt)
  val retry = ErrorButton(if (secondsLeft > 0) "Retry in $secondsLeft s" else "Retry", onRetry, enabled = secondsLeft <= 0)
  // The steps this screen can offer; with none of them, Retry, so there's always a way on.
  val buttons = advice.actions.mapNotNull { action ->
    when (action) {
      ErrorAction.Retry -> retry
      ErrorAction.EditSearch -> onEditSearch?.let { ErrorButton("Edit search", it) }
      ErrorAction.SignIn -> onSignIn?.let { ErrorButton("Sign in", it) }
    }
  }.ifEmpty { listOf(retry) }
  MessageState(error, MaterialTheme.colorScheme.error, advice.sentence, modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      buttons.forEachIndexed { index, button ->
        // The first step is the one to take: filled; the others outlined.
        if (index == 0) {
          Button(onClick = button.onClick, enabled = button.enabled, shapes = ButtonDefaults.shapes()) { Text(button.label) }
        } else {
          OutlinedButton(onClick = button.onClick, enabled = button.enabled, shapes = ButtonDefaults.shapes()) { Text(button.label) }
        }
      }
    }
  }
}

private class ErrorButton(val label: String, val onClick: () -> Unit, val enabled: Boolean = true)

/** Seconds left until [instant], ticking each second; 0 once it has passed or when it's null. */
@Composable
private fun countdownTo(instant: Instant?): Long {
  var secondsLeft by remember(instant) { mutableLongStateOf(secondsUntil(instant)) }
  LaunchedEffect(instant) {
    while (secondsLeft > 0) {
      delay(1.seconds)
      secondsLeft = secondsUntil(instant)
    }
  }
  return secondsLeft
}

/** A large icon over a centred message and, under them, what [below] adds: the failed and offline states' layout. */
@Composable
private fun MessageState(
  icon: ImageVector,
  tint: Color,
  message: String,
  modifier: Modifier = Modifier,
  below: @Composable () -> Unit,
) {
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
    Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    below()
  }
}

private fun secondsUntil(instant: Instant?): Long =
  instant?.let { (it - Clock.System.now()).inWholeSeconds.coerceAtLeast(0) } ?: 0

/** What the banner under the top bar says about the connection (UX review #126, finding 1, A). */
enum class ConnectivityBannerState(val icon: ImageVector?, val text: String) {
  /** Sankaku rejected this client: the error colour, as it's a failure no retry fixes. */
  Rejected(null, "Sankaku is turning this app away right now · try again later"),

  /** Offline is a state, not an error: the inverse surface, saying what happens next. */
  Offline(cloud_off, "Offline · retries when you're back"),

  /** The connection is back, for [BACK_ONLINE_SHOWN] after [Offline], while the failed loads retry. */
  BackOnline(wifi, "Back online · reloading"),
}

/** How long "Back online" shows before the banner goes. */
val BACK_ONLINE_SHOWN = 2.seconds

/**
 * The banner for [offline] and [rejected]: Rejected over Offline, and BackOnline for [BACK_ONLINE_SHOWN] once
 * [offline] clears, if it was [unreachable] (a connection error, which is what the failed loads then retry). It's
 * BackOnline from the very frame [offline] clears, so the banner never blinks out in between. A 5xx doesn't set
 * [offline] (#520).
 */
@Composable
fun connectivityBannerState(offline: Boolean, unreachable: Boolean, rejected: Boolean): ConnectivityBannerState? {
  // Lags [offline] by the effect below, so the first frame after it clears still knows Sankaku was unreachable.
  var wasUnreachable by remember { mutableStateOf(offline && unreachable) }
  var holding by remember { mutableStateOf(false) }
  LaunchedEffect(offline, unreachable) {
    if (!offline && wasUnreachable) {
      holding = true
      wasUnreachable = false
      try {
        delay(BACK_ONLINE_SHOWN)
      } finally {
        holding = false
      }
    } else {
      wasUnreachable = offline && unreachable
    }
  }
  return when {
    rejected -> ConnectivityBannerState.Rejected
    offline -> ConnectivityBannerState.Offline
    holding || wasUnreachable -> ConnectivityBannerState.BackOnline
    else -> null
  }
}

/** The connection's banner under the top bar; it grows in and shrinks away, and changes colour in place. */
@Composable
fun ConnectivityBanner(state: ConnectivityBannerState?, modifier: Modifier = Modifier) {
  // The last state shown, for the banner's way out.
  val last = remember { object { var state = ConnectivityBannerState.Offline } }
  SideEffect { if (state != null) last.state = state }
  AnimatedVisibility(
    state != null,
    modifier,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
  ) {
    val shown = state ?: last.state
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (shown) {
      ConnectivityBannerState.Rejected -> colors.errorContainer to colors.onErrorContainer
      ConnectivityBannerState.Offline -> colors.inverseSurface to colors.inverseOnSurface
      ConnectivityBannerState.BackOnline -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Surface(
      color = animateColorAsState(container).value,
      contentColor = animateColorAsState(content).value,
      // Read out when it changes, as a screen reader can't see the colour.
      modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
      Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        shown.icon?.let { Icon(it, contentDescription = null, Modifier.size(18.dp)) }
        Text(shown.text, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

/**
 * In a grid's place while it can't load because Sankaku can't be reached: it says the grid loads by itself when the
 * connection is back (`RetryWhenBack` does that), and keeps Retry for the impatient.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GridOfflineState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
  MessageState(wifi_off, MaterialTheme.colorScheme.onSurfaceVariant, "You're offline", modifier) {
    Text(
      "This grid loads by itself when the connection is back.",
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text("Retry now") }
  }
}

@Composable
fun TagChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  AssistChip(onClick = onClick, label = { Text(name.replace('_', ' ')) }, modifier = modifier)
}
