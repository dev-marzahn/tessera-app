package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.google.fonts.icons.favorite
import com.google.fonts.icons.heart_broken
import com.google.fonts.icons.image
import com.google.fonts.icons.tab
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.KbdBadge
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.Shortcuts

/**
 * A desktop grid cell's right-click menu (#504, the owner's OK on the mockup): Open, with Space's badge, and Open in
 * background tab, with E's badge and "Middle-click" under it, so the keep a middle-click makes is named where a mouse user looks; then
 * Favourite or Unfavourite ([favourited]) in a group of its own, as the cell's own toggle. Choosing closes it.
 */
@Composable
internal fun CellMenu(
  favourited: Boolean,
  onDismiss: () -> Unit,
  onOpen: () -> Unit,
  onOpenInBackground: () -> Unit,
  onToggleFavourite: () -> Unit,
) {
  GroupedMenu(expanded = true, onDismissRequest = onDismiss) {
    MenuGroup(0, 2) {
      MenuAction(
        text = { Text("Open") },
        leadingIcon = { Icon(image, contentDescription = null) },
        trailingIcon = { KbdBadge(Shortcuts.OpenHere.label) },
        onClick = {
          onDismiss()
          onOpen()
        },
      )
      MenuAction(
        // "Middle-click" on a line of its own: beside E's badge it pushed the label onto two lines in M3's 280 dp menu.
        text = {
          Column {
            Text(openInBackgroundLabel(touch = false))
            Text(MIDDLE_CLICK, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        },
        leadingIcon = { Icon(tab, contentDescription = null) },
        trailingIcon = { KbdBadge(Shortcuts.OpenInTab.label) },
        onClick = {
          onDismiss()
          onOpenInBackground()
        },
      )
    }
    MenuGroup(1, 2) {
      MenuAction(
        text = { Text(if (favourited) "Unfavourite" else "Favourite") },
        leadingIcon = { Icon(if (favourited) heart_broken else favorite, contentDescription = null) },
        onClick = {
          onDismiss()
          onToggleFavourite()
        },
      )
    }
  }
}

internal const val MIDDLE_CLICK = "Middle-click"
