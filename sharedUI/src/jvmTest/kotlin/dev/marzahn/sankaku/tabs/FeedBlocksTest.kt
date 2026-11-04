package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.FeedItems
import dev.marzahn.sankaku.queue.SeenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * #265: posts kept from Following or For you sit under that feed as their source, in its block, as a source grid's
 * kept posts do (#161). A feed is a source that persists (#327): its block stands where it was added or moved to, and
 * placement around it follows the general rules.
 */
class FeedBlocksTest {
  private val start = Instant.parse("2026-11-01T12:00:00Z")
  private var now = start
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock, seen = seen)
  private val following = Route.Grid(listOf(Feed.Following.tag))
  private val forYou = Route.Grid(listOf(Feed.ForYou.tag))

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  private fun post(id: String) = Route.Post(id, "md5$id", listOf("a"), label = "Post $id", rating = "s")

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun tab(title: String) = tabs.tabs.value.single { it.title == title }

  private fun signIn(forYouInQueue: Boolean = false) = tabs.pinFeeds(FeedItems.pinned(signedIn = true, forYouInQueue))

  private fun signOut() = tabs.pinFeeds(FeedItems.pinned(signedIn = false, forYouInQueue = false))

  // A long-press on a cell of the grid shown: the post is kept in the background, as `openInNewTab` does.
  private fun keepFrom(feed: TabState, vararg ids: String) {
    tabs.activate(feed.id)
    ids.forEach { TabsNavigator(tabs).openInNewTab(post(it)) }
  }

  // Browse, the source "a" with its kept post p1, and the source "b"; signed in, so Following is added at the end.
  private fun queue(forYouInQueue: Boolean = false) {
    tabs.open(grid("a"), background = false)
    tabs.openInBlock(listOf(post("p1")))
    tabs.open(grid("b"), background = true)
    signIn(forYouInQueue)
  }

  @Test
  fun keepingFromFollowingPutsThePostAfterItsOtherKeptPostsAheadOfThePositionShown() {
    queue()
    val feed = tab("Following")

    keepFrom(feed, "f1", "f2")

    assertEquals(listOf("Browse", "A", "Post p1", "B", "Following", "Post f1", "Post f2"), titles())
    assertSame(feed, tabs.sourceOf(tab("Post f2")))
    // Keeping doesn't move away from the feed, and moving on reaches the kept posts next, in order.
    assertSame(feed, tabs.activeTab)
    assertTrue(tabs.next())
    assertEquals("Post f1", tabs.activeTab.title)
    assertTrue(tabs.next())
    assertEquals("Post f2", tabs.activeTab.title)
    // The last kept post is the end of the queue here, since nothing was added after Following.
    assertFalse(tabs.next())
  }

  @Test
  fun keepingFromForYouPutsThePostInForYousBlock() {
    queue(forYouInQueue = true)
    keepFrom(tab("Following"), "f1")
    val feed = tab("For you")

    keepFrom(feed, "y1", "y2")

    assertEquals(listOf("Browse", "A", "Post p1", "B", "Following", "Post f1", "For you", "Post y1", "Post y2"), titles())
    assertSame(feed, tabs.sourceOf(tab("Post y1")))
    assertTrue(tabs.next())
    assertEquals("Post y1", tabs.activeTab.title)
  }

  @Test
  fun aFeedAndItsBlockAreNoLongerKeptLast() {
    queue()
    keepFrom(tab("Following"), "f1")

    // #327: "later" goes to the end of the queue, after Following's block; a post kept elsewhere goes in its own block,
    // and a search opened now from a feed's kept post goes in front of the feed's block.
    tabs.open(grid("c"), background = true)
    tabs.activate(tab("A").id)
    tabs.openInBlock(listOf(post("p2")))
    tabs.activate(tab("Post f1").id)
    tabs.search(grid("d"))

    assertEquals(listOf("Browse", "A", "Post p1", "Post p2", "B", "D", "Following", "Post f1", "C"), titles())
    // Moving on past the search returns to the post it was opened from.
    assertEquals("D", tabs.activeTab.title)
    assertTrue(tabs.next())
    assertEquals("Post f1", tabs.activeTab.title)
    // And on through the feed's block to what was opened for later.
    assertTrue(tabs.next())
    assertEquals("C", tabs.activeTab.title)
  }

  @Test
  fun aKeptPostOfAFeedIsCountedAndItsPositionSkipsTheFeed() {
    queue()
    keepFrom(tab("Following"), "f1")

    // Browse, A, p1, B and f1; Following isn't counted.
    assertEquals(5, countedItems(tabs.tabs.value))
    val index = tabs.tabs.value.indexOf(tab("Post f1"))
    assertEquals(5, countedPosition(tabs.tabs.value, index))
    assertEquals(4, countedPosition(tabs.tabs.value, tabs.tabs.value.indexOf(tab("B"))))
    // "Already item 5" for it, as the pill counts.
    assertEquals(5, TabsNavigator(tabs).openInNewTab(post("f1")))
  }

  @Test
  fun upNextStandsPastTheLastItemEvenAFeedsKeptPost() {
    queue()
    keepFrom(tab("Following"), "f1")

    // #327: no longer before the feeds, but past the last item, whatever it is.
    tabs.activate(tab("B").id)
    assertFalse(tabs.upNextAhead())
    tabs.activate(tab("Following").id)
    assertFalse(tabs.upNextAhead())
    tabs.activate(tab("Post f1").id)
    assertTrue(tabs.upNextAhead())
    assertNull(tabs.neighbour(forward = true))
  }

  @Test
  fun aSearchFromAFeedsKeptPostUnwindsToItWithNoUpNextBetween() {
    // A faded source, so Up next has something to offer.
    tabs.open(grid("old"), background = true)
    now = start + 4.days
    tabs.sweep()
    assertEquals(1, tabs.faded.value.size)
    queue()
    keepFrom(tab("Following"), "f1")
    tabs.activate(tab("Post f1").id)

    tabs.search(grid("d"))

    // Moving on returns to the post: Up next would stand in the way and its Skip would go nowhere.
    assertFalse(tabs.upNextAhead())
    assertEquals("Post f1", tabs.neighbour(forward = true)?.title)
    val upNext = noUpNext(tabs)
    assertEquals(Ahead.Item, upNext.ahead())
    upNext.forward()
    assertEquals("Post f1", tabs.activeTab.title)
  }

  @Test
  fun signingOutLeavesTheKeptPostsAndSigningInTakesThemBack() {
    queue()
    keepFrom(tab("Following"), "f1", "f2")

    signOut()
    // "from Following (closed)", as a closed source's (#184), and with no source.
    assertEquals(listOf("Browse", "A", "Post p1", "B", "Post f1", "Post f2"), titles())
    assertEquals(listOf(following.tags, following.tags), listOf(tab("Post f1"), tab("Post f2")).map { it.closedSource })
    assertNull(tabs.sourceOf(tab("Post f1")))

    signIn()
    val feed = tab("Following")
    assertEquals(listOf("Browse", "A", "Post p1", "B", "Following", "Post f1", "Post f2"), titles())
    assertSame(feed, tabs.sourceOf(tab("Post f1")))
    assertNull(tab("Post f2").closedSource)
  }

  @Test
  fun aSourceOnTheFeedsSearchDoesNotTakeItsPosts() {
    queue()
    keepFrom(tab("Following"), "f1")
    signOut()

    // `feed:following order:popularity` is a search, not the feed (#185).
    tabs.open(grid("feed:following", "order:popularity"), background = true)

    assertEquals(following.tags, tab("Post f1").closedSource)
  }

  @Test
  fun closingAForYouThatIsNoItemLeavesItsKeptPostsAndUndoBringsThemBack() {
    queue()
    tabs.open(forYou, background = true)
    val feed = tab("For you")
    keepFrom(feed, "y1")

    assertEquals("Close For you · 1 kept stay", closeLabel(feed, tabs.keptPostsOf(feed)))
    val closed = tabs.close(feed.id)!!
    assertEquals(forYou.tags, tab("Post y1").closedSource)

    tabs.reopen(closed)
    assertEquals("For you", tabs.sourceOf(tab("Post y1"))?.title)
    assertNull(tab("Post y1").closedSource)
  }

  @Test
  fun aForYouClosedThenPinnedComesBackAsTheItemWithItsPosts() {
    queue()
    tabs.open(forYou, background = true)
    keepFrom(tab("For you"), "y1")
    val closed = tabs.close(tab("For you").id)!!

    // The setting turned on meanwhile: the feed item takes the posts back, and reopening adds no second For you.
    signIn(forYouInQueue = true)
    val item = tab("For you")
    assertSame(item, tabs.sourceOf(tab("Post y1")))
    tabs.reopen(closed)

    assertEquals(1, tabs.tabs.value.count { it.feed == Feed.ForYou })
    assertSame(item, tabs.sourceOf(tab("Post y1")))
    assertEquals(listOf("Following", "For you", "Post y1"), titles().takeLast(3))
  }

  @Test
  fun clearViewedClearsTheFeedsSeenKeptPostsAndLeavesTheFeed() {
    queue()
    keepFrom(tab("Following"), "f1", "f2")
    tabs.activate(tab("B").id)
    now += 1.minutes
    seen.record("f1", "md5f1")

    tabs.clearViewed(seen)

    assertEquals(listOf("Browse", "A", "Post p1", "B", "Following", "Post f2"), titles())
  }

  @Test
  fun aFeedsKeptPostsNeverFade() {
    queue()
    keepFrom(tab("Following"), "f1")
    tabs.activate(tab("Browse").id)

    now = start + 4.days
    tabs.sweep()

    // A and B faded (with p1); the feed and its kept post stay.
    assertEquals(listOf("Browse", "Following", "Post f1"), titles())
  }

  @Test
  fun aFeedsKeptPostLeavesItsBlockAsAnyKeptPostDoes() {
    queue(forYouInQueue = true)
    keepFrom(tab("For you"), "y1", "y2")
    val y2 = tab("Post y2")

    // #327: out of its block either way, as from a source's block.
    assertEquals(-1, tabs.moveInQueue(y2.id, down = false))
    // Dragged out above For you's block, it stands just before it, with no source.
    assertEquals(-1, tabs.moveInQueue(y2.id, down = false))
    assertEquals(listOf("B", "Following", "Post y2", "For you", "Post y1"), titles().takeLast(5))
    assertNull(y2.block)
    assertNull(tabs.sourceOf(y2))
    // Down past the last place it leaves in place.
    val y1 = tab("Post y1")
    assertEquals(0, tabs.moveInQueue(y1.id, down = true))
    assertNull(tabs.sourceOf(y1))
  }

  @Test
  fun aFeedMovesWithItsBlockLikeAnyBlock() {
    queue(forYouInQueue = true)
    keepFrom(tab("Following"), "f1")

    // #327: a feed is a source that persists, so it moves as one, its kept posts with it.
    assertEquals(-2, tabs.moveInQueue(tab("For you").id, down = false))
    assertEquals(listOf("Browse", "A", "Post p1", "B", "For you", "Following", "Post f1"), titles())
    assertEquals(-1, tabs.moveInQueue(tab("Following").id, down = false))
    assertEquals(listOf("Browse", "A", "Post p1", "B", "Following", "Post f1", "For you"), titles())
    assertEquals(-1, tabs.moveInQueue(tab("Following").id, down = false))
    assertEquals(listOf("Browse", "A", "Post p1", "Following", "Post f1", "B", "For you"), titles())
    assertSame(tab("Following"), tabs.sourceOf(tab("Post f1")))
  }

  @Test
  fun aFileSavedSinceFeedsStoodLastLoadsAndSavesBackUnchanged() {
    // As #229 saved a post kept from Following: on its own, before the feed, with no block.
    val text = """{"version":2,"session":"s1","activeIndex":0,"tabs":[""" +
      """{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}],"kind":"source","touched":1000,"addedIn":"s1"},""" +
      """{"id":"k","routes":[{"type":"dev.marzahn.sankaku.Route.Post","id":"p1","md5":null,"originTags":[]}],"kind":"post","touched":1000,"addedIn":"s1","keptAt":1000},""" +
      """{"id":"f","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["feed:following"]}],"kind":"feed","touched":1000,"addedIn":"s1"}""" +
      """]}"""
    val file = InMemoryTextFile(text)
    val store = FileTabStateStore(file)
    val before = store.load()!!
    val clock = object : Clock {
      override fun now() = Instant.fromEpochSeconds(1000)
    }
    val restored = TabsController(store, clock = clock)
    restored.pinFeeds(listOf(Feed.Following))

    // The loose post stays where it was: nothing guesses it was kept from the feed.
    assertEquals(before, restored.saved())
    store.save(restored.saved())
    assertEquals(before, store.load())
  }

  @Test
  fun aPostKeptFromAFeedIsSavedWithItsBlockAndNothingElse() {
    queue()
    val feed = tab("Following")
    keepFrom(feed, "f1")

    val saved = tabs.saved().tabs.last()
    assertEquals(feed.id.value, saved.block)
    assertNull(saved.closedSource)
  }
}
