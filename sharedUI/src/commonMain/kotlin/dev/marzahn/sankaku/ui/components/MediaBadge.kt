package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The mark of a post that isn't a still image, on a grid cell and on a recommended post: an [icon] and a [text] (▶
 * and a video's length "01:05", "GIF", ⊘ and "Flash", #524), either may be left out, white on a dark scrim chip, so it
 * reads on light and dark images alike. A screen reader reads [description]. It goes in the top-left corner, which no
 * other mark uses: the favourite heart is top right, the "In tab" chip bottom left and the cursor's key hints bottom
 * right.
 */
@Composable
fun MediaBadge(icon: ImageVector?, text: String?, description: String, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(50),
    color = Color.Black.copy(alpha = 0.6f),
    contentColor = Color.White,
    modifier = modifier.semantics { contentDescription = description },
  ) {
    Row(
      // At least 24 dp, taller with large text rather than cutting the text off (#525).
      Modifier.heightIn(min = 24.dp).padding(start = if (icon == null) 9.dp else 5.dp, end = if (text == null) 5.dp else 9.dp, top = 1.dp, bottom = 1.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
      if (text != null) Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
  }
}
