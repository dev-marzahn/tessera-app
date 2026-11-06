package dev.marzahn.sankaku.ui.components

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the update dot means, for a screen reader (#209). */
const val UPDATE_AVAILABLE_DOT = "Update available"

/**
 * The small dot that says a newer release exists (#209): on ☰ and on the sidebar's Settings. In the primary colour,
 * so it isn't mistaken for the red "session expired" badge.
 */
@Composable
fun UpdateDot(modifier: Modifier = Modifier) {
  Badge(modifier, containerColor = MaterialTheme.colorScheme.primary)
}
