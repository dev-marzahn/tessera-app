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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
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

/**
 * An M3 menu of detached [groups][MenuGroup], anchored to the composable around it. It works from the left hand (#6,
 * #443): as it opens, however it was opened, the chosen item ([chosenMenuItem], a [MenuChoice] that's selected) or else
 * the first takes the focus. W/S move like ↑/↓, and Space, Tab and Enter choose the focused item; Shift-Tab does
 * nothing, and Esc closes the menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupedMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  // The popup's own focus manager, which W/S move; known only once its content composes.
  var popupFocus by remember { mutableStateOf<FocusManager?>(null) }
  var inputMode by remember { mutableStateOf<InputModeManager?>(null) }
  DropdownMenuPopup(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = Modifier.onPreviewKeyEvent { event ->
      if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && event.isShiftPressed) return@onPreviewKeyEvent true
      val step = menuStep(event) ?: return@onPreviewKeyEvent false
      inputMode?.requestInputMode(InputMode.Keyboard)
      // Consumed even at the first or last item, where the focus can't move: a menu has nothing else W or S could do.
      popupFocus?.moveFocus(step)
      true
    },
  ) {
    val focus = LocalFocusManager.current
    val mode = LocalInputModeManager.current
    val chosen = remember { MenuFocus() }
    SideEffect {
      popupFocus = focus
      inputMode = mode
    }
    LaunchedEffect(Unit) {
      // Opened by a tap: no focus ring on a touch screen; a keyboard attached to it switches the mode back.
      if (mode.inputMode == InputMode.Touch) return@LaunchedEffect
      // A frame first, so the items are laid out and can take the focus.
      withFrameNanos { }
      mode.requestInputMode(InputMode.Keyboard)
      if (!chosen.attached || !chosen.requester.requestFocus()) focus.moveFocus(FocusDirection.Next)
    }
    CompositionLocalProvider(LocalMenuFocus provides chosen) { content() }
  }
}

/** W or S pressed with no modifier, as the menu's left-hand ↑ and ↓; else null. */
private fun menuStep(event: KeyEvent): FocusDirection? = when {
  event.type != KeyEventType.KeyDown || !event.isPlain -> null
  event.key == Key.W -> FocusDirection.Up
  event.key == Key.S -> FocusDirection.Down
  else -> null
}

/** The item a [GroupedMenu] focuses as it opens, if one is chosen. */
private class MenuFocus {
  val requester = FocusRequester()
  var attached = false
}

private val LocalMenuFocus = compositionLocalOf<MenuFocus?> { null }

/**
 * Marks the item that's chosen in its [GroupedMenu], so the menu opens with the focus on it rather than on the first
 * item. [MenuChoice] does this when selected; use it on an item of your own, such as the star the score menu shows.
 */
@Composable
fun Modifier.chosenMenuItem(): Modifier {
  val chosen = LocalMenuFocus.current ?: return this
  DisposableEffect(chosen) {
    chosen.attached = true
    onDispose { chosen.attached = false }
  }
  return focusRequester(chosen.requester)
}

/**
 * A/D move the focus left and right among the items in a row (#443): the search panel's filter chips, the score
 * menu's stars. Sideways only, so A on the first item stays there rather than leaving the row.
 */
@Composable
fun Modifier.sidewaysFocusKeys(): Modifier {
  val focus = LocalFocusManager.current
  return onPreviewKeyEvent { event ->
    val direction = when {
      event.type != KeyEventType.KeyDown || !event.isPlain -> null
      event.key == Key.A -> FocusDirection.Left
      event.key == Key.D -> FocusDirection.Right
      else -> null
    }
    direction?.let(focus::moveFocus)
    direction != null
  }
}

/** Tab, without Shift, chooses a focused menu item as Space and Enter do (#443): [onChoose] runs on its key down. */
fun Modifier.chooseOnTab(onChoose: () -> Unit): Modifier = onPreviewKeyEvent { event ->
  val tab = event.key == Key.Tab && !event.isShiftPressed && !event.isCommand && !event.isAltPressed
  if (tab && event.type == KeyEventType.KeyDown) onChoose()
  tab
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
  trailingIcon: (@Composable () -> Unit)? = null,
) {
  DropdownMenuItem(
    text = text,
    onClick = onClick,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    enabled = enabled,
    modifier = modifier.chooseOnTab { if (enabled) onClick() }.then(itemClip()),
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
    modifier = (if (selected) Modifier.chosenMenuItem() else Modifier).chooseOnTab(onClick).then(itemClip()),
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
    modifier = Modifier.chooseOnTab { onCheckedChange(!checked) }.then(itemClip()),
    shapes = itemShapes(),
    colors = itemColors(),
    leadingIcon = emptyIcon,
    checkedLeadingIcon = checkIcon,
  )
}
