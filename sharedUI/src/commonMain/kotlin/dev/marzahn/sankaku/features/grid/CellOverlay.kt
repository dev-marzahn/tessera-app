package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.components.AlreadyTab
import dev.marzahn.sankaku.ui.components.FavouriteBadgeButton
import dev.marzahn.sankaku.ui.components.InTabChip
import dev.marzahn.sankaku.ui.components.InTabWash
import dev.marzahn.sankaku.ui.components.KbdBadge
import dev.marzahn.sankaku.ui.components.SeenBadge
import dev.marzahn.sankaku.ui.components.Shortcuts

/** A favourites grid: its tags hold `fav:<name>`, so a post unfavourited there stays, dimmed, until the next reload. */
fun isFavouritesSearch(tags: List<String>): Boolean = tags.any { it.startsWith("fav:") }

/**
 * What a grid cell draws over its image. [unfavourited] (a favourites grid's post whose override says not favourited)
 * dims the cell and offers Undo, which [onToggleFavourite] does. The heart in the corner is badge and button in one
 * ([FavouriteBadgeButton]): a favourited post's badge grows into the button while [hovered], and a post that isn't
 * favourited shows it only then. [openInTab] (some tab shows the post; the grid greys its image) lays a
 * light wash over it and an "In tab" chip in the bottom-left corner, which no other mark uses, so what's already
 * queued shows while scrolling; with [alreadyTab] the chip says "Already tab N" (or "Kept again"). A [seen] post
 * (#182) has a [SeenBadge] before the chip. The unfavourited scrim is darker and wins. [keyHints] (the keyboard cursor's cell) shows E and Space in the bottom-right corner.
 */
@Composable
fun CellOverlay(
  favourited: Boolean,
  unfavourited: Boolean,
  hovered: Boolean,
  onToggleFavourite: () -> Unit,
  modifier: Modifier = Modifier,
  openInTab: Boolean = false,
  alreadyTab: AlreadyTab? = null,
  seen: Boolean = false,
  keyHints: Boolean = false,
) {
  Box(modifier.fillMaxSize()) {
    if (unfavourited) {
      UnfavouritedScrim(onUndo = onToggleFavourite)
    } else if (openInTab) {
      InTabWash()
    }
    if (seen || openInTab) {
      // The bottom-left corner: the seen badge, then the chip beside it. In a phone's narrowest cell (80 dp, #468)
      // the chip drops its icon to fit (`inTabChipShowsIcon`).
      Row(
        Modifier.align(Alignment.BottomStart).padding(6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (seen) SeenBadge()
        if (openInTab) InTabChip(Modifier, alreadyTab)
      }
    }
    if (keyHints) {
      Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
      ) {
        Row(Modifier.height(24.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          KbdBadge(Shortcuts.OpenInTab.label)
          KbdBadge("␣")
        }
      }
    }
    // Kept over the scrim, so the pop of the click that unfavourited it plays.
    FavouriteBadgeButton(
      favourited = favourited,
      expanded = hovered,
      onToggle = onToggleFavourite,
      modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
    )
  }
}

/** Dims the cell in place, so the posts around it don't move, with "Unfavourited · Undo" over it. */
@Composable
private fun UnfavouritedScrim(onUndo: () -> Unit) {
  Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
    contentAlignment = Alignment.Center,
  ) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface) {
      Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Unfavourited ·", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onUndo) {
          Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
        }
      }
    }
  }
}
