package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.Immutable
import dev.marzahn.sankaku.queue.FeedHeading
import dev.marzahn.sankaku.queue.PlacedHeading

/**
 * A grid's items (#314): its posts, with a full-span feed heading ([PlacedHeading]) before some of them. Everything
 * that talks to the lazy grid (scrolling, the keyboard cursor, the place it saves) goes through [itemOf] and [postOf],
 * since a heading takes an item of its own. A heading before a post that isn't loaded yet isn't an item. With [None]
 * (every grid but a feed's) posts and items are the same.
 */
@Immutable
class GridItems(headings: List<PlacedHeading>) {
  // Each heading with the item it takes: the heading before post p, the k-th, takes item p + k.
  private val placed: List<Pair<Int, PlacedHeading>> =
    headings.sortedBy { it.before }.mapIndexed { k, heading -> heading.before + k to heading }

  /** How many items [posts] posts make: the posts, and the headings before them. */
  fun count(posts: Int): Int = posts + placed.count { (_, heading) -> heading.before < posts }

  /** The item of the post at [post]. */
  fun itemOf(post: Int): Int = post + placed.count { (_, heading) -> heading.before <= post }

  /** The post at item [item], or null for a heading. */
  fun postOf(item: Int): Int? {
    var before = 0
    for ((at, _) in placed) {
      if (at == item) return null
      if (at < item) before++
    }
    return item - before
  }

  /** The heading at item [item], if it is one. */
  fun headingAt(item: Int): PlacedHeading? = placed.firstOrNull { it.first == item }?.second

  /** The item of the first heading [which] picks, or null. */
  fun itemOfHeading(which: (FeedHeading) -> Boolean): Int? = placed.firstOrNull { which(it.second.heading) }?.first

  companion object {
    val None = GridItems(emptyList())
  }
}
