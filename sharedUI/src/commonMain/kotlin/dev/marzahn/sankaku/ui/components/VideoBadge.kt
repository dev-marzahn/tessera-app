package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.play_arrow_filled

/**
 * The mark of a video post on a grid cell and on a recommended post: a play icon and its [duration] ("01:05") when
 * the post has one, white on a dark scrim chip, so it reads on light and dark images alike. It goes in the top-left
 * corner, which no other mark uses: the favourite heart is top right, the "In tab" chip bottom left and the cursor's
 * key hints bottom right.
 */
@Composable
fun VideoBadge(duration: String?, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(50),
    color = Color.Black.copy(alpha = 0.6f),
    contentColor = Color.White,
    modifier = modifier.semantics { contentDescription = if (duration == null) "Video" else "Video, $duration" },
  ) {
    Row(
      Modifier.height(24.dp).padding(start = 5.dp, end = if (duration == null) 5.dp else 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Icon(play_arrow_filled, contentDescription = null, modifier = Modifier.size(16.dp))
      if (duration != null) {
        Text(duration, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
      }
    }
  }
}
