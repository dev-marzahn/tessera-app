package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The test tag of [NewBadge]. */
const val NEW_BADGE_TAG = "new-badge"

/**
 * "New" on a Following cell whose post turned up late, below posts already read (#539): the posts above "You're caught
 * up" are new by where they are, a late one needs saying. Primary on its container, a pill as the "In tab" chip is;
 * silent here, the cell's description says "New".
 */
@Composable
fun NewBadge(modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(50),
    color = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    modifier = modifier.testTag(NEW_BADGE_TAG),
  ) {
    Text("New", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
  }
}
