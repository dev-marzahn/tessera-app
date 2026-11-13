package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.ui.components.CountPillText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #501: where an item stands in its block, and what the pill and TalkBack say of it. */
class BlockPlaceTest {
  private val tabs = TabsController()

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")

  init {
    // Browse, then Landscape with p1, p2 and p3 kept, then Cat with none.
    tabs.open(Route.Grid(listOf("landscape")), background = false)
    tabs.openInBlock(listOf(post("p1")))
    tabs.openInBlock(listOf(post("p2")))
    tabs.openInBlock(listOf(post("p3")))
    tabs.open(Route.Grid(listOf("cat")), background = true)
  }

  private fun all() = tabs.tabs.value

  private fun place(title: String) = all().let { tabs -> blockPlace(tabs, tabs.indexOfFirst { it.title == title }) }

  @Test
  fun aKeptPostIsCountedAmongItsBlocksKeptPosts() {
    assertEquals(listOf("Browse", "Landscape", "p1", "p2", "p3", "Cat"), all().map { it.title })
    assertEquals(BlockPlace.Kept("Landscape", 1, 3, QueueKind.KeptPost), place("p1"))
    assertEquals(BlockPlace.Kept("Landscape", 3, 3, QueueKind.KeptPost), place("p3"))
    assertEquals(true, (place("p3") as BlockPlace.Kept).last)
  }

  @Test
  fun aSourceWithKeptPostsSaysHowManyFollowAndOneWithoutHasNoPlace() {
    assertEquals(BlockPlace.Head("Landscape", 3), place("Landscape"))
    assertNull(place("Cat"))
    assertNull(place("Browse"))
  }

  @Test
  fun aClosedSourcesKeptPostsAreNamedByIt() {
    tabs.close(all().single { it.title == "Landscape" }.id)

    // Named as the source was; TalkBack adds that it's closed (the pill's room is short).
    assertEquals(BlockPlace.Kept("Landscape", 2, 3, QueueKind.KeptPost, closed = true), place("p2"))
    assertEquals("Queue, Landscape, closed, kept post 2 of 3, item 3 of 5", pillText(place("p2"), 3, 5, feed = null, card = null).spoken)
  }

  @Test
  fun aFeedHeadsItsKeptPostsAsASourceDoes() {
    val feeds = TabsController()
    feeds.pinFeeds(listOf(Feed.Following))
    feeds.activate(feeds.tabs.value.single { it.title == "Following" }.id)
    feeds.openInBlock(listOf(post("f1")))
    val list = feeds.tabs.value

    val feedIndex = list.indexOfFirst { it.title == "Following" }
    assertEquals(BlockPlace.Head("Following", 1), blockPlace(list, feedIndex))
    // Its pane says how many follow, as a source's does.
    assertEquals(
      "Queue, feed Following, after 1 item, 1 kept item follows",
      queueItemDescription(countedPosition(list, feedIndex), countedItems(list), list[feedIndex], null, blockPlace(list, feedIndex)),
    )
    assertEquals(BlockPlace.Kept("Following", 1, 1, QueueKind.KeptPost), blockPlace(list, list.indexOfFirst { it.title == "f1" }))
  }

  @Test
  fun thePillSaysTheBlocksPlaceAndTalkBackSaysTheQueuesToo() {
    assertEquals(
      CountPillText("Landscape", "2 / 3", "Queue, Landscape, kept post 2 of 3, item 4 of 6"),
      pillText(place("p2"), position = 4, count = 6, feed = null, card = null),
    )
    assertEquals(
      CountPillText("Landscape", "3 kept", "Queue, Landscape, 3 kept items follow, item 2 of 6"),
      pillText(place("Landscape"), position = 2, count = 6, feed = null, card = null),
    )
    // Nothing else changes: a source with no kept posts, and a feed, which isn't counted.
    assertEquals(CountPillText(null, "6 / 6", "Queue, item 6 of 6"), pillText(null, 6, 6, feed = null, card = null))
    assertEquals(CountPillText(null, "Following", "Queue, Following, after 6 items"), pillText(null, 6, 6, feed = "Following", card = null))
  }

  @Test
  fun onABlocksLastItemThePillSaysWhereMovingOnGoes() {
    val last = place("p3")

    assertEquals("Last from Landscape · next: Cat", blockEndLine(last, "Cat"))
    assertEquals(
      "Queue, Landscape, kept post 3 of 3, item 5 of 6, last, next Cat",
      pillText(last, 5, 6, feed = null, card = null, next = "Cat").spoken,
    )
    assertEquals("Last from Landscape · next: End of the queue", blockEndLine(last, END_PANE))
    // Not on any other item, and not with nothing to move on to.
    assertNull(blockEndLine(place("p2"), "p3"))
    assertNull(blockEndLine(place("Landscape"), "p1"))
    assertNull(blockEndLine(last, null))
  }

  @Test
  fun onACardThePillNamesItWithTheCountNotTheItemBehindIt() {
    assertEquals(CountPillText("End", "6", "Queue, end, after 6 items"), pillText(null, 6, 6, feed = "Following", card = UpNextCard.End))
    val offer = UpNextCard.Pickup(TabId("old"), "Old", "", null)
    assertEquals(CountPillText("Up next", "6", "Queue, up next, after 6 items"), pillText(place("p3"), 5, 6, feed = null, card = offer))
  }

  @Test
  fun aPeekOfAnotherBlockSaysWhichOneIsLeft() {
    assertEquals("Leaving Landscape", leavingLabel(place("p3")))
    assertNull(leavingLabel(place("Cat")))
  }

  @Test
  fun eachItemsPaneSaysItsPlaceInItsBlock() {
    val list = all()
    fun describe(title: String, next: String? = null): String {
      val index = list.indexOfFirst { it.title == title }
      val tab = list[index]
      return queueItemDescription(countedPosition(list, index), countedItems(list), tab, tabs.sourceOf(tab), blockPlace(list, index), next)
    }

    assertEquals("Queue item 4 of 6, kept post 2 of 3 from Landscape", describe("p2", next = "p3"))
    assertEquals("Queue item 5 of 6, kept post 3 of 3 from Landscape, last, next Cat", describe("p3", next = "Cat"))
    assertEquals("Queue item 2 of 6, source Landscape, 3 kept items follow", describe("Landscape"))
    assertEquals("Queue item 6 of 6, source Cat", describe("Cat"))
  }
}
