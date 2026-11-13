package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.visibility_off

/**
 * How much of a seen post's preview shows (#182): about 85%, so the cell reads as faded, not disabled. Only the image
 * takes it; the badges and chips over it stay at full strength, so their contrast is the one #131 set.
 */
const val SEEN_IMAGE_ALPHA = 0.85f

/** The seen badge's scrim: [Color.Black] at this alpha, as [VideoBadge]'s, so white on it reads on any image. */
const val SEEN_BADGE_SCRIM_ALPHA = 0.6f

/** Fades a seen post's image to [SEEN_IMAGE_ALPHA]; [seen] false leaves it alone. */
fun Modifier.seenImage(seen: Boolean): Modifier = if (seen) alpha(SEEN_IMAGE_ALPHA) else this

/**
 * The mark of a post the user has seen, on a grid cell: an eye-slash, white on the same dark scrim as [VideoBadge], so
 * dimming isn't the only sign (#182). It sits in the bottom-left corner, before the "In tab" chip.
 */
@Composable
fun SeenBadge(modifier: Modifier = Modifier) {
  Surface(
    shape = CircleShape,
    color = Color.Black.copy(alpha = SEEN_BADGE_SCRIM_ALPHA),
    contentColor = Color.White,
    modifier = modifier.semantics { contentDescription = "Seen" },
  ) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
      Icon(visibility_off, contentDescription = null, modifier = Modifier.size(16.dp))
    }
  }
}
