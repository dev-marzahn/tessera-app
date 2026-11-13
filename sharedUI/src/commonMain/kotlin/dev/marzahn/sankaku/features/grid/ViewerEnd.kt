package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.shell.LocalOpenSearch
import dev.marzahn.sankaku.ui.components.ApiErrorState
import org.koin.compose.koinInject

/**
 * What lies past a grid's last loaded post in its viewer (#524): its trailing page says so. Next goes there from the
 * last post, as a swipe does on a phone.
 */
sealed interface ViewerEnd {
  /** The next page is loading, or will once asked: the trailing page turns into its first post when it comes. */
  data object Loading : ViewerEnd

  /** The next page failed to load with [error]: the trailing page gives its advice (#520) and Retry. */
  data class Failed(val error: Throwable) : ViewerEnd

  /** The grid has no more posts. */
  data object NoMore : ViewerEnd
}

/** The [ViewerEnd] for a list whose next page ([append]) is in that state. */
fun viewerEnd(append: LoadState): ViewerEnd = when {
  append is LoadState.Error -> ViewerEnd.Failed(append.error)
  append is LoadState.NotLoading && append.endOfPaginationReached -> ViewerEnd.NoMore
  else -> ViewerEnd.Loading
}

internal const val LoadingMorePosts = "Loading more posts"
internal const val NoMorePosts = "No more posts"
internal const val ViewerEndTag = "viewer-end"

/**
 * The viewer's trailing page past the last loaded post (#524, the owner's pick from the mockups): on the viewer's
 * black (the phone's, which draws it dark) or in the desktop card's image panel: the loading indicator with "Loading
 * more posts", the failure's advice with its steps (`ApiErrorState`, as the grid's footer), or "No more posts".
 */
@Composable
fun ViewerEndPage(end: ViewerEnd, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  // The theme's text colour, not what's around: on the phone's black that's the light theme's dark text.
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { EndPageContent(end, onRetry, modifier) }
}

@Composable
private fun EndPageContent(end: ViewerEnd, onRetry: () -> Unit, modifier: Modifier, signIn: SignInController = koinInject()) {
  Box(modifier.fillMaxSize().testTag(ViewerEndTag).semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
    when (end) {
      ViewerEnd.Loading -> Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        AppLoadingIndicator()
        Text(LoadingMorePosts, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      }
      // The failure's advice (#520), as the grid's footer gives it: its sentence and next steps (a 429's countdown,
      // Edit search, Sign in), never the exception's own text.
      is ViewerEnd.Failed -> ApiErrorState(end.error, onRetry = onRetry, onEditSearch = LocalOpenSearch.current, onSignIn = signIn::open)
      ViewerEnd.NoMore -> Text(NoMorePosts, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
  }
}
