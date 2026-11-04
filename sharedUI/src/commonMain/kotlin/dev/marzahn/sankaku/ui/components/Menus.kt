package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.MenuItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.arrow_drop_down
import com.google.fonts.icons.check

/**
 * A chip that opens a [GroupedMenu]: outlined until [selected], then filled in the secondary container, the colour its
 * menu gives the chosen item. Pill-shaped like the tag pills, and flat in every state (the default hover elevation
 * draws a shadow that reads as a bevel on the flat sheet).
 */
@Composable
fun MenuChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  dropdown: Boolean = enabled,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = label,
    enabled = enabled,
    shape = MaterialTheme.shapes.large,
    trailingIcon = if (dropdown) {
      { Icon(arrow_drop_down, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
    } else {
      null
    },
    elevation = FilterChipDefaults.filterChipElevation(
      elevation = 0.dp,
      pressedElevation = 0.dp,
      focusedElevation = 0.dp,
      hoveredElevation = 0.dp,
      draggedElevation = 0.dp,
      disabledElevation = 0.dp,
    ),
    modifier = modifier,
  )
}

/** An M3 menu of detached [groups][MenuGroup], anchored to the composable around it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupedMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
}

/**
 * Group [index] of [count] in a [GroupedMenu], a small gap from the one before; its outer corners are large where it
 * ends the menu. Items sit 4 dp in from its edges.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MenuGroup(index: Int, count: Int, content: @Composable ColumnScope.() -> Unit) {
  if (index > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
  DropdownMenuGroup(
    shapes = MenuDefaults.groupShape(index, count),
    contentPadding = PaddingValues(4.dp),
    content = content,
  )
}

// One shape for an item whether it's hovered, pressed or chosen, so nothing changes corners under the pointer.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun itemShapes(): MenuItemShapes =
  MenuDefaults.itemShapes(shape = MaterialTheme.shapes.medium, selectedShape = MaterialTheme.shapes.medium)

// The item clipped to that shape: the shape only rounds the item's container, and the hover and press layer drew
// square corners inside the rounded item.
@Composable
private fun itemClip(): Modifier = Modifier.clip(MaterialTheme.shapes.medium)

/** An item that does something and closes the menu, such as opening a search. */
@Composable
fun MenuAction(
  text: @Composable () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: (@Composable () -> Unit)? = null,
  enabled: Boolean = true,
) {
  DropdownMenuItem(
    text = text,
    onClick = onClick,
    leadingIcon = leadingIcon,
    enabled = enabled,
    modifier = modifier.then(itemClip()),
  )
}

// A chosen item keeps the group's colour and shows a check and its text in the primary colour: a filled item inside
// a filled group read as a pill inside a pill, worst in a one-item group.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun itemColors(): MenuItemColors = MenuDefaults.selectableItemColors(
  selectedContainerColor = Color.Transparent,
  selectedTextColor = MaterialTheme.colorScheme.primary,
  selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
)

private val emptyIcon: @Composable () -> Unit = { Spacer(Modifier.size(24.dp)) }
private val checkIcon: @Composable () -> Unit = { Icon(check, contentDescription = null) }

/** One of several choices (only one is on): picked by a click, tinted with a check while [selected]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MenuChoice(selected: Boolean, onClick: () -> Unit, text: @Composable () -> Unit) {
  DropdownMenuItem(
    selected = selected,
    onClick = onClick,
    text = text,
    modifier = itemClip(),
    shapes = itemShapes(),
    colors = itemColors(),
    leadingIcon = emptyIcon,
    selectedLeadingIcon = checkIcon,
  )
}

/** An item that toggles on and off, independently of the others, without closing the menu. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MenuCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: @Composable () -> Unit) {
  DropdownMenuItem(
    checked = checked,
    onCheckedChange = onCheckedChange,
    text = text,
    modifier = itemClip(),
    shapes = itemShapes(),
    colors = itemColors(),
    leadingIcon = emptyIcon,
    checkedLeadingIcon = checkIcon,
  )
}
