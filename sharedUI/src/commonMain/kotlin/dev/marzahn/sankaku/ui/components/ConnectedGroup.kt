package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A connected button group (M3 expressive): members [ConnectedLeadingPane] and [ConnectedTrailingToggle], 2 dp apart,
 * so their inner corners read as one group. Each member's `rejected` count is a failed toggle's answer ([rejected]):
 * each time it goes up the member shakes with an error outline in its own shape.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

/**
 * The group's leading member when it isn't one button (a row of stars). [checked] does what a checked
 * [ConnectedTrailingToggle] does: the same container and content colours, and the same spring from the leading shape
 * to M3's connected checked shape (a pill).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedLeadingPane(
  modifier: Modifier = Modifier,
  checked: Boolean = false,
  rejected: Int = 0,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = connectedToggleColors()
  val container by animateColorAsState(if (checked) colors.checkedContainerColor else colors.containerColor, effectsSpec())
  val morph by animateFloatAsState(if (checked) 1f else 0f, fastSpatialSpec())
  val shape = morphedShape(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedButtonCheckedShape, morph)
  Surface(
    shape = shape,
    color = container,
    contentColor = if (checked) colors.checkedContentColor else colors.contentColor,
    modifier = modifier.rejected(rejected, shape).heightIn(min = ConnectedHeight),
  ) {
    // The same at both ends, so content inset alike at its start and end sits balanced (#104).
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
  }
}

/**
 * The group's trailing member as a tonal toggle: on, it fills with the secondary container. Its shape morphs on press
 * and when checked, as M3's connected toggles do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedTrailingToggle(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  rejected: Int = 0,
  content: @Composable RowScope.() -> Unit,
) {
  val shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
  ToggleButton(
    checked = checked,
    onCheckedChange = onCheckedChange,
    shapes = shapes,
    colors = connectedToggleColors(),
    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
    modifier = modifier.rejected(rejected, if (checked) shapes.checkedShape else shapes.shape).heightIn(min = ConnectedHeight),
    content = content,
  )
}

/** [from] on its way to [to] by [fraction], corner by corner, as M3's toggle buttons morph between their shapes. */
private fun morphedShape(from: Shape, to: CornerBasedShape, fraction: Float): Shape {
  if (from !is CornerBasedShape) return from
  return object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
      fun corner(a: CornerSize, b: CornerSize) = CornerSize(lerp(a.toPx(size, density), b.toPx(size, density), fraction))
      return RoundedCornerShape(
        topStart = corner(from.topStart, to.topStart),
        topEnd = corner(from.topEnd, to.topEnd),
        bottomEnd = corner(from.bottomEnd, to.bottomEnd),
        bottomStart = corner(from.bottomStart, to.bottomStart),
      ).createOutline(size, layoutDirection, density)
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedToggleColors() = ToggleButtonDefaults.tonalToggleButtonColors(
  containerColor = MaterialTheme.colorScheme.surfaceContainer,
  contentColor = MaterialTheme.colorScheme.onSurface,
  checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
  checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

private val ConnectedHeight = 48.dp
