package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.queue.FeedHeading
import dev.marzahn.sankaku.queue.PlacedHeading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #314: a feed's headings are items of the grid of their own, so the grid maps posts to items and back. */
class GridItemsTest {
  private val items = GridItems(
    listOf(
      PlacedHeading(0, FeedHeading.New(3, more = false, gapLeft = null)),
      PlacedHeading(3, FeedHeading.CaughtUp),
      PlacedHeading(5, FeedHeading.Continue(2, offer = false)),
    ),
  )

  @Test
  fun withNoHeadingsPostsAreItems() {
    assertEquals(7, GridItems.None.itemOf(7))
    assertEquals(7, GridItems.None.postOf(7))
    assertEquals(10, GridItems.None.count(10))
  }

  @Test
  fun eachHeadingTakesTheItemBeforeItsPost() {
    // Items: H0 p0 p1 p2 H3 p3 p4 H5 p5 p6 …
    assertEquals(listOf(1, 2, 3, 5, 6, 8, 9), (0..6).map(items::itemOf))
    assertEquals(listOf(null, 0, 1, 2, null, 3, 4, null, 5, 6), (0..9).map(items::postOf))
    assertEquals(FeedHeading.CaughtUp, items.headingAt(4)?.heading)
    assertNull(items.headingAt(5))
    assertEquals(13, items.count(10))
  }

  @Test
  fun headingsPastTheLoadedPostsArentItems() {
    val short = GridItems(listOf(PlacedHeading(0, FeedHeading.CaughtUp), PlacedHeading(40, FeedHeading.CaughtUp)))

    assertEquals(11, short.count(10))
    assertEquals(9, short.postOf(10))
  }

  @Test
  fun headingKeysAreDistinctNegativeLongs() {
    // A lazy item's key must fit in an Android Bundle (a data class crashed the phone's grid) and mustn't be a String,
    // which the reading order takes for a post id.
    val placed = listOf(
      PlacedHeading(0, FeedHeading.New(3, more = false, gapLeft = null)),
      PlacedHeading(0, FeedHeading.CaughtUp),
      PlacedHeading(0, FeedHeading.Continue(2, offer = false)),
      PlacedHeading(1, FeedHeading.New(3, more = false, gapLeft = null)),
    )
    val keys = placed.map(::headingKey)

    assertEquals(keys.size, keys.toSet().size)
    keys.forEach { assertTrue(it < 0) }
  }

  @Test
  fun theItemOfAHeading() {
    assertEquals(7, items.itemOfHeading { it is FeedHeading.Continue })
    assertNull(GridItems.None.itemOfHeading { true })
  }
}
