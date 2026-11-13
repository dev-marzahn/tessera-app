package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.snapshotFlow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.google.fonts.icons.check
import com.google.fonts.icons.expand_more
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.PageCursor
import dev.marzahn.sankaku.queue.FeedHeading
import dev.marzahn.sankaku.queue.FeedPost
import dev.marzahn.sankaku.queue.FeedReading
import dev.marzahn.sankaku.queue.PlacedHeading
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import dev.marzahn.sankaku.ui.components.TonalButton

/** "12 new", or "200+ new" while the newest post reached isn't loaded. */
internal fun newText(heading: FeedHeading.New): String =
  if (heading.more || heading.count > FeedReading.NEW_COUNT_LIMIT) {
    "${minOf(heading.count, FeedReading.NEW_COUNT_LIMIT)}+ new"
  } else {
    "${heading.count} new"
  }

/** The top heading's button: "Continue · 37 left", or "Continue" when the count isn't known. */
internal fun continueButtonText(left: Int?): String = if (left != null) "Continue · $left left" else "Continue"

/** The stop mark's heading after "Continue": "· 37 left from last time", or "· from last time". */
internal fun continueText(left: Int?): String = if (left != null) "· $left left from last time" else "· from last time"

internal const val CAUGHT_UP = "You're caught up"

/** The top "You're caught up"'s way to the followed tags (#539). */
internal const val FOLLOWED_TAGS = "Followed tags"

/** "N new"'s way to the late posts (#539): "2 further down", or "Further down" when they're all the new posts. */
internal fun furtherDownText(heading: FeedHeading.New): String =
  if (heading.count > heading.late) "${heading.late} further down" else "Further down"

/** The test tag of a feed heading: `feed-heading-new`, `-caught-up`, `-continue` or `-up-to-date`. */
internal fun feedHeadingTag(heading: FeedHeading): String = "feed-heading-" + when (heading) {
  is FeedHeading.New -> "new"
  FeedHeading.CaughtUp -> "caught-up"
  is FeedHeading.Continue -> "continue"
  is FeedHeading.UpToDate -> "up-to-date"
}

/**
 * A feed's heading in its grid (#314), full-span: "N new" (with "Continue · N left" when a gap is kept, and "N further
 * down" to the first late post, #539, [onFurtherDown] with its index), "You're caught up", the stop mark's "Continue · N
 * left from last time", which the 7-day offer gives Keep and Let go, or "You're caught up" at the top when nothing is
 * new, with Continue or "Followed tags" ([onFollowedTags]). Each is a heading for TalkBack.
 */
@Composable
internal fun FeedHeadingRow(
  placed: PlacedHeading,
  onContinue: () -> Unit,
  onOfferShown: () -> Unit,
  onKeep: () -> Unit,
  onLetGo: () -> Unit,
  modifier: Modifier = Modifier,
  onFurtherDown: (post: Int) -> Unit = {},
  onFollowedTags: () -> Unit = {},
) {
  val heading = placed.heading
  Row(
    modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp).testTag(feedHeadingTag(heading)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    when (heading) {
      is FeedHeading.New -> {
        Label(newText(heading))
        Rule()
        heading.firstLate?.let { late -> TextButton(onClick = { onFurtherDown(late) }) { Text(furtherDownText(heading)) } }
        if (heading.hasGap) TonalButton(continueButtonText(heading.gapLeft), expand_more, onContinue)
      }
      FeedHeading.CaughtUp -> {
        CaughtUpLabel()
        Rule()
      }
      is FeedHeading.UpToDate -> {
        CaughtUpLabel()
        Rule()
        if (heading.hasGap) {
          TonalButton(continueButtonText(heading.gapLeft), expand_more, onContinue)
        } else {
          TextButton(onClick = onFollowedTags) { Text(FOLLOWED_TAGS) }
        }
      }
      is FeedHeading.Continue -> {
        Row(Modifier.semantics(mergeDescendants = true) { heading() }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Label("Continue", semantics = false)
          Text(continueText(heading.left), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Rule()
        if (heading.offer) {
          LaunchedEffect(Unit) { onOfferShown() }
          TextButton(onClick = onKeep) { Text("Keep") }
          TextButton(onClick = onLetGo) { Text("Let go") }
        }
      }
    }
  }
}

/** A feed grid's loaded [posts] as the marks see them, each with the cursor of the page it came in on ([cursorOf]). */
internal fun feedPostsOf(posts: List<PostDto>, cursorOf: (String) -> PageCursor?): List<FeedPost> =
  posts.map { FeedPost(it.id, it.createdAt?.epochSeconds ?: 0, cursorOf(it.id) ?: PageCursor()) }

/** The first post at or just after the first visible item: a feed's heading (#314) may be the first item. */
internal fun LazyStaggeredGridState.firstPostIndex(gridItems: GridItems): Int =
  (firstVisibleItemIndex..firstVisibleItemIndex + 2).firstNotNullOfOrNull(gridItems::postOf) ?: 0

/**
 * A feed grid's part in its marks (#314): the posts on screen move them on ([GridViewModel.reach]), once the grid is
 * where it opens (not while it looks for its place). While "N new" can't count yet (the newest post reached isn't
 * loaded), the next pages load, up to [FeedReading.NEW_COUNT_LIMIT] posts.
 */
@Composable
internal fun FeedHeadingEffects(
  viewModel: GridViewModel,
  items: LazyPagingItems<PostDto>,
  gridState: LazyStaggeredGridState,
  gridItems: () -> GridItems,
  feedPosts: () -> List<FeedPost>,
) {
  if (viewModel.feed == null) return
  LaunchedEffect(viewModel, gridState) {
    // Settled as the place reports are (GridScreen): shown or given up, never while it's pending (#492).
    viewModel.lookup.map { it == null || it == PlaceLookup.GivenUp }
      .distinctUntilChanged()
      .collectLatest { settled ->
        if (!settled) return@collectLatest
        snapshotFlow {
          val posts = feedPosts()
          val shown = gridState.layoutInfo.visibleItemsInfo.mapNotNull { gridItems().postOf(it.index) }.filter { it < posts.size }
          if (shown.isEmpty()) null else shown.min() to shown.max()
        }
          .filterNotNull()
          .collect { (first, last) -> viewModel.reach(feedPosts(), first, last) }
      }
  }
  LaunchedEffect(items) {
    snapshotFlow {
      val counting = (gridItems().headingAt(0)?.heading as? FeedHeading.New)?.more == true
      val append = items.loadState.append
      items.itemCount.takeIf {
        counting && it in 1 until FeedReading.NEW_COUNT_LIMIT && append is LoadState.NotLoading && !append.endOfPaginationReached
      }
    }
      .filterNotNull()
      // Bounded: the list may have reloaded shorter since the count was read (#472).
      .collect { items.getOrNull(it - 1) }
  }
}

@Composable
private fun CaughtUpLabel() {
  Icon(check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
  Text(
    CAUGHT_UP,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    modifier = Modifier.semantics { heading() },
  )
}

@Composable
private fun Label(text: String, semantics: Boolean = true) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = if (semantics) Modifier.semantics { heading() } else Modifier,
  )
}

@Composable
private fun RowScope.Rule() {
  Box(Modifier.weight(1f).height(1.dp)) { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
}
