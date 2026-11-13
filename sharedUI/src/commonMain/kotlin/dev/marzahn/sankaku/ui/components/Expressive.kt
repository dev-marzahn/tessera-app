package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.google.fonts.icons.arrow_upward

/** A keyboard key, shown next to the action it triggers so shortcuts are discoverable. */
@Composable
fun KbdBadge(key: String, modifier: Modifier = Modifier) {
  Surface(
    shape = MaterialTheme.shapes.extraSmall,
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
  ) {
    // At least square, so one-character keys (/, ↵, F) don't look squeezed next to wider ones (Tab, Esc). As tall as
    // a label's capitals and descenders, so it sits on the label's line instead of towering over it.
    Box(
      // At least that: large text makes it taller rather than cutting the key off (#525).
      Modifier.defaultMinSize(minWidth = KbdSize, minHeight = KbdSize).padding(horizontal = 4.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(key, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp).centredOnLine())
    }
  }
}

private val KbdSize = 16.dp

/** The filled button for a panel's main action (Search), with the expressive press shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier) { Text(text) }
}

/** A filled tonal button with a leading [icon]: a secondary action beside content (Edit search on an empty grid). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TonalButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
  FilledTonalButton(
    onClick = onClick,
    shapes = ButtonDefaults.shapes(),
    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    modifier = modifier,
  ) { IconAndText(text, icon) }
}

@Composable
private fun IconAndText(text: String, icon: ImageVector) {
  Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
  Spacer(Modifier.size(ButtonDefaults.IconSpacing))
  Text(text)
}

/** [TonalButton] in the primary colour, for the same action when it is the one to take (Clear viewed, suggested). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrimaryIconButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Button(
    onClick = onClick,
    shapes = ButtonDefaults.shapes(),
    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    modifier = modifier,
  ) { IconAndText(text, icon) }
}

/** An icon button that stays pressed while [checked] (the menu button while the sidebar is open). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToggleIconButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
  IconToggleButton(
    checked = checked,
    onCheckedChange = onCheckedChange,
    shapes = IconButtonDefaults.toggleableShapes(),
    content = content,
  )
}

/**
 * An icon button that's filled in the tonal colour while [checked], so a switch in a header reads as on at a glance (the
 * queue page's G only shield). Off, it's a plain icon button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TonalToggleIconButton(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  FilledTonalIconToggleButton(
    checked = checked,
    onCheckedChange = onCheckedChange,
    enabled = enabled,
    shapes = IconButtonDefaults.toggleableShapes(),
    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(containerColor = Color.Transparent),
    content = content,
  )
}

/** A key and what it does, as in a row of hints under a panel. [color] is the label's, for hints on a scrim. */
@Composable
fun KeyHint(key: String, label: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    KbdBadge(key)
    // Centring the line box leaves a lowercase label (its body between x-height and baseline) about 1.5 dp below
    // the key's middle; lifted, the body reads centred on the key.
    Text(
      label,
      style = MaterialTheme.typography.labelSmall.centredOnLine(),
      color = color,
      modifier = Modifier.offset(y = (-1.5).dp),
    )
  }
}

/** A label with its shortcut, for buttons and menu items. */
@Composable
fun LabelWithKey(label: String, key: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = LocalTextStyle.current.centredOnLine())
    KbdBadge(key)
  }
}

/** The expressive spatial spring, for movement (bounds, offsets), never for colour or alpha. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> spatialSpec(slow: Boolean = false): FiniteAnimationSpec<T> =
  if (slow) MaterialTheme.motionScheme.slowSpatialSpec() else MaterialTheme.motionScheme.defaultSpatialSpec()

/**
 * A scale that swells to [scale] and back on the spatial spring each time [key] changes to a non-null value: the "In
 * tab" chip's "Already tab N" (#174) and a tab Z reopened (#420). A change of [key] mid-swell, or to null, puts it back
 * at 1 at once, so a cut-short pulse never leaves it swollen.
 */
@Composable
fun rememberPulse(key: Any?, scale: Float): Animatable<Float, AnimationVector1D> {
  val pulse = remember { Animatable(1f) }
  val swell = spatialSpec<Float>()
  LaunchedEffect(key) {
    pulse.snapTo(1f)
    if (key == null) return@LaunchedEffect
    pulse.animateTo(scale, swell)
    pulse.animateTo(1f, swell)
  }
  return pulse
}

/** The expressive effects spring, for colour and alpha; [slow] for a fade that lingers. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> effectsSpec(slow: Boolean = false): FiniteAnimationSpec<T> =
  if (slow) MaterialTheme.motionScheme.slowEffectsSpec() else MaterialTheme.motionScheme.defaultEffectsSpec()

/**
 * An alpha that keeps pace with a move on the default spatial spring: that spring's stiffness, without its overshoot.
 * The effects spring is far stiffer, so a fade on it is nearly done before the move has begun (#298). Should a
 * pre-release make the spatial spec something other than a spring, it falls back to medium-low, about the expressive
 * default's stiffness.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> spatialFadeSpec(): FiniteAnimationSpec<T> {
  val stiffness = (MaterialTheme.motionScheme.defaultSpatialSpec<T>() as? SpringSpec<T>)?.stiffness ?: Spring.StiffnessMediumLow
  return spring(Spring.DampingRatioNoBouncy, stiffness)
}

/**
 * Shared-element bounds move on a spatial spring: the slow one to open the post viewer, the default one to close it.
 * They land once every edge is within [BOUNDS_LANDED] of its place: a spring's default ends within 0.01 px, which kept
 * the transition (and the overlay drawing over the cell's marks) running about 0.4 s after the card looked landed
 * (#324). Should a pre-release make the spatial spec something other than a spring, it's used as it is.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun spatialBoundsTransform(slow: Boolean = true): BoundsTransform {
  val spatial = spatialSpec<Rect>(slow)
  val spec = remember(spatial) {
    (spatial as? SpringSpec<Rect>)?.let { spring(it.dampingRatio, it.stiffness, BOUNDS_LANDED) } ?: spatial
  }
  return BoundsTransform { _, _ -> spec }
}

/** How near its place a shared element's every edge must be for its bounds spring to land: too little to see. */
private val BOUNDS_LANDED = Rect(1f, 1f, 1f, 1f)

/**
 * Shown while scrolling back up a long list; jumps to the top. [compact] (phones) is an icon-only [PillSizedTop]
 * labelled "Top" for screen readers; otherwise a small extended FAB reading "↑ Top". While [replaced] (something else
 * takes its corner, as a post's ⋮ does) it's hidden too; with [fade] it comes and goes by a fade instead of a scale,
 * so it crossfades in place with what replaces it.
 */
@Composable
fun ScrollToTopButton(
  visible: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  replaced: Boolean = false,
  fade: Boolean = false,
) {
  AnimatedVisibility(
    visible = visible && !replaced,
    enter = if (fade) fadeIn(effectsSpec()) else scaleIn(spatialSpec()),
    exit = if (fade) fadeOut(effectsSpec()) else scaleOut(spatialSpec()),
    modifier = modifier,
  ) {
    if (compact) {
      PillSizedTop(onClick = onClick)
    } else {
      SmallExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(arrow_upward, contentDescription = null) },
        text = { Text("Top") },
      )
    }
  }
}

/**
 * Touch: ↑ Top in the right corner of the phone's bottom row ([BottomRowLayout]), sized as the count pill and Undo
 * are (#261): a [BottomRowLayout.PieceDrawn] circle on the primary container with a FAB's shadow, in a
 * [BottomRowLayout.PieceTarget] touch target; the ripple keeps to the circle.
 */
@Composable
private fun PillSizedTop(onClick: () -> Unit) {
  val presses = remember { MutableInteractionSource() }
  Box(
    Modifier
      .size(BottomRowLayout.PieceTarget)
      .clickable(presses, indication = null, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = "Top" },
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      shadowElevation = FabElevation,
      modifier = Modifier.size(BottomRowLayout.PieceDrawn),
    ) {
      Box(Modifier.clip(CircleShape).indication(presses, ripple()), contentAlignment = Alignment.Center) {
        Icon(arrow_upward, contentDescription = null, modifier = Modifier.size(20.dp))
      }
    }
  }
}


/**
 * Moves an element in by [offsetX]/[offsetY] (and up from [fromScale]) on the spatial spring when [shown] turns true,
 * after [delayMillis]; it leaves at once on the fast spring. Opacity only ramps over the first part of the move, so
 * the motion reads as movement, not a fade. [fast] uses the fast spring on the way in too, for small badges.
 */
fun Modifier.entrance(
  shown: Boolean,
  delayMillis: Long = 0,
  offsetX: Dp = 0.dp,
  offsetY: Dp = 0.dp,
  fromScale: Float = 1f,
  fast: Boolean = false,
): Modifier = composed {
  val progress = remember { Animatable(if (shown) 1f else 0f) }
  val inSpec = if (fast) fastSpatialSpec<Float>() else spatialSpec<Float>()
  val outSpec = fastSpatialSpec<Float>()
  LaunchedEffect(shown) {
    if (shown) {
      delay(delayMillis)
      progress.animateTo(1f, inSpec)
    } else {
      progress.animateTo(0f, outSpec)
    }
  }
  graphicsLayer {
    val t = progress.value
    translationX = (1 - t) * offsetX.toPx()
    translationY = (1 - t) * offsetY.toPx()
    val scale = fromScale + (1 - fromScale) * t
    scaleX = scale
    scaleY = scale
    alpha = (t / 0.4f).coerceIn(0f, 1f)
  }
}

/** The expressive fast spatial spring, for small elements such as key badges. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()
