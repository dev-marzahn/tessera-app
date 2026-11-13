package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Pulling [content] down past the threshold calls [onRefresh]; M3's round indicator (a white disc with the arrow,
 * as the Android mockups draw it) follows the pull and turns while [refreshing]. With [enabled] false it's a plain
 * box (desktop, which has a Refresh button).
 */
@Composable
fun PullToRefresh(
  refreshing: Boolean,
  onRefresh: () -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  if (enabled) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier, content = content)
  } else {
    Box(modifier, content = content)
  }
}
