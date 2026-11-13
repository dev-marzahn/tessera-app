package dev.marzahn.sankaku.features.grid

import androidx.paging.LoadState
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the viewer's trailing page shows for the list's next page (#524). */
class ViewerEndStateTest {
  @Test
  fun theTrailingPageFollowsTheNextPagesLoad() {
    assertEquals(ViewerEnd.Loading, viewerEnd(LoadState.Loading))
    // More to come, not asked for yet: getOrNull near the end asks, so it's on its way.
    assertEquals(ViewerEnd.Loading, viewerEnd(LoadState.NotLoading(endOfPaginationReached = false)))
    // With the failure itself, for its advice (#520).
    val failure = IllegalStateException("offline")
    assertEquals(ViewerEnd.Failed(failure), viewerEnd(LoadState.Error(failure)))
    assertEquals(ViewerEnd.NoMore, viewerEnd(LoadState.NotLoading(endOfPaginationReached = true)))
  }
}
