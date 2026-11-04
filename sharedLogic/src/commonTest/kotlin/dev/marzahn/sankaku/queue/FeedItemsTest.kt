package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.api.Feed
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedItemsTest {
  @Test
  fun signedOutThereAreNoFeedItems() {
    assertEquals(emptyList(), FeedItems.pinned(signedIn = false, forYouInQueue = false))
    assertEquals(emptyList(), FeedItems.pinned(signedIn = false, forYouInQueue = true))
  }

  @Test
  fun signedInFollowingIsAlwaysAnItemAndForYouOnlyWhenTurnedOn() {
    assertEquals(listOf(Feed.Following), FeedItems.pinned(signedIn = true, forYouInQueue = false))
    assertEquals(listOf(Feed.Following, Feed.ForYou), FeedItems.pinned(signedIn = true, forYouInQueue = true))
  }

  @Test
  fun followingLeavesWheneverItIsNotPinnedAndForYouOnlyWhenItWas() {
    val both = listOf(Feed.Following, Feed.ForYou)
    assertEquals(setOf(Feed.Following, Feed.ForYou), FeedItems.leaving(both, emptyList()))
    assertEquals(setOf(Feed.ForYou), FeedItems.leaving(both, listOf(Feed.Following)))
    // Signed out from the start: a restored Following goes, a For you opened by hand stays.
    assertEquals(setOf(Feed.Following), FeedItems.leaving(emptyList(), emptyList()))
    assertEquals(emptySet(), FeedItems.leaving(emptyList(), listOf(Feed.Following)))
  }

  @Test
  fun onlyTheFeedsNotInTheQueueAreAddedInTheirOrder() {
    val both = listOf(Feed.Following, Feed.ForYou)
    assertEquals(both, FeedItems.added(both, emptySet()))
    // For you turned on later: only For you is added, at the end.
    assertEquals(listOf(Feed.ForYou), FeedItems.added(both, setOf(Feed.Following)))
    // A For you opened from the sidebar becomes the item where it stands.
    assertEquals(listOf(Feed.Following), FeedItems.added(both, setOf(Feed.ForYou)))
    assertEquals(emptyList(), FeedItems.added(emptyList(), setOf(Feed.Following)))
  }
}
