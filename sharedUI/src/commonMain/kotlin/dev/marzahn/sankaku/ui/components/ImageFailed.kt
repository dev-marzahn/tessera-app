package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.error
import com.google.fonts.icons.image
import com.google.fonts.icons.refresh

/**
 * In place of a post's image that didn't load, in a grid cell ([compact]) or the viewer, on the cell's own colour so
 * it never reads as "still loading". [noImage]: the post has no image to load, so "No preview" and no retry.
 * Otherwise "Couldn't load" with "↻ Retry" in error colours, as ⋮'s failed save shows it (`.scratch/android-feedback/`,
 * C). Its tap is its own: in a cell, the rest still opens the post.
 */
@Composable
fun ImageFailed(noImage: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
  val colors = MaterialTheme.colorScheme
  Surface(color = colors.surfaceContainerHighest, contentColor = colors.onSurfaceVariant, modifier = modifier) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
      // A short cell (a wide image) keeps only what matters: Retry, or the caption when there's nothing to retry.
      val short = maxHeight < SHORT_CELL
      Column(
        Modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
      ) {
        if (!short) Icon(if (noImage) image else error, contentDescription = null, modifier = Modifier.size(if (compact) 24.dp else 40.dp))
        if (!short || noImage) Text(
          when {
            noImage -> "No preview"
            compact -> "Couldn't load"
            else -> "Couldn't load the image"
          },
          style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!noImage) ImageRetryChip(onRetry)
      }
    }
  }
}

/** Below this, a cell has room for Retry alone: the icon, caption and pill need about 110 dp. */
private val SHORT_CELL = 120.dp

/**
 * A video that didn't play, over its preview in the viewer: "Couldn't play the video" with "↻ Retry", as an image's
 * failure shows it (#192), on a pill so it reads over any picture.
 */
@Composable
fun VideoFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Surface(
    shape = MaterialTheme.shapes.large,
    color = colors.surfaceContainerHighest.copy(alpha = 0.9f),
    contentColor = colors.onSurfaceVariant,
    modifier = modifier,
  ) {
    Column(
      Modifier.padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(error, contentDescription = null, modifier = Modifier.size(40.dp))
      Text(
        "Couldn't play the video",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      )
      ImageRetryChip(onRetry, description = "Retry playing the video")
    }
  }
}

/** "↻ Retry" in error colours, alone over an image that loaded only in part (the viewer's preview, not its sample). */
@Composable
fun ImageRetryChip(onRetry: () -> Unit, modifier: Modifier = Modifier, description: String = "Retry loading the image") {
  val colors = MaterialTheme.colorScheme
  Surface(
    onClick = onRetry,
    shape = CircleShape,
    color = colors.errorContainer,
    contentColor = colors.onErrorContainer,
    modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = description },
  ) {
    Row(
      Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(refresh, contentDescription = null, modifier = Modifier.size(18.dp))
      Text("Retry", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
  }
}
