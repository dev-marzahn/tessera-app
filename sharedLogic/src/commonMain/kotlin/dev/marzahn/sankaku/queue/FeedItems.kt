package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.api.Feed

/**
 * Which feeds are queue items (#229; spec: Tabs → Queue → Items). A feed item is a source that persists (#327): it is
 * always there while signed in, never counted in "n / N", never closed and never faded. It goes at the end of the queue
 * when it's added, and is an ordinary item from then on: nothing keeps it last. The queue (`TabsController`) keeps the
 * rule; this is the rule itself, with no UI, so the tests cover it.
 */
object FeedItems {
  /**
   * The feeds that are queue items, in the order they're added at the end of the queue: Following while [signedIn],
   * then For you if [forYouInQueue] ("Show For you in the queue", off by default). None signed out.
   */
  fun pinned(signedIn: Boolean, forYouInQueue: Boolean): List<Feed> = when {
    !signedIn -> emptyList()
    forYouInQueue -> listOf(Feed.Following, Feed.ForYou)
    else -> listOf(Feed.Following)
  }

  /**
   * The feeds whose tabs leave the queue when the feed items go from [before] to [after]: each one no longer pinned
   * (signing out, For you's setting turned off), and Following whenever it isn't pinned, since it is only ever a feed
   * item and can't load signed out (a Following restored from a file saved before a sign-out). A For you opened while
   * it isn't pinned stays.
   */
  fun leaving(before: List<Feed>, after: List<Feed>): Set<Feed> =
    (before.filterNot { it in after } + listOfNotNull(Feed.Following.takeIf { it !in after })).toSet()

  /**
   * The feeds of [pinned] to add to the queue, in [pinned]'s order, given the feeds it holds already ([present]): each
   * goes at the end of the queue as it's added (#327). One already there (opened from the sidebar, or restored) becomes
   * the item where it stands.
   */
  fun added(pinned: List<Feed>, present: Collection<Feed>): List<Feed> = pinned.filter { it !in present }
}
