package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.FeedItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * #327: "A feed is just a persistent source." A feed goes at the end of the queue when it's added (signing in, the For
 * you setting) and is an ordinary item afterwards: "now" goes in front of its block, "later" to the end of the queue
 * (after the feeds when they're last), and Up next and End of the queue stand past the last item, whatever it is.
 */
class FeedIsASourceTest {
  private val file = InMemoryTextFile()
  private val tabs = TabsController(FileTabStateStore(file))

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  private fun post(id: String) = Route.Post(id, "m$id", listOf("a"), label = "Post $id", rating = "s")

  private fun titles(controller: TabsController = tabs) = controller.tabs.value.map { it.title }

  private fun tab(title: String) = tabs.tabs.value.single { it.title == title }

  private fun pin(forYou: Boolean) = tabs.pinFeeds(FeedItems.pinned(signedIn = true, forYouInQueue = forYou))

  // Browse, Following with its kept posts f1 and f2, and For you: the owner's queue.
  private fun ownersQueue() {
    pin(forYou = true)
    tabs.activate(tab("Following").id)
    TabsNavigator(tabs).openInNewTab(post("f1"))
    TabsNavigator(tabs).openInNewTab(post("f2"))
    assertEquals(listOf("Browse", "Following", "Post f1", "Post f2", "For you"), titles())
  }

  @Test
  fun aSearchForLaterFromAFeedsKeptPostGoesAfterForYouAndMovingOnReachesIt() {
    ownersQueue()
    tabs.activate(tab("Post f1").id)

    // A tag opened for later (a long-press, middle-click) on one of Following's kept posts.
    assertNull(TabsNavigator(tabs).openInNewTab(grid("artist_x")))

    assertEquals(listOf("Browse", "Following", "Post f1", "Post f2", "For you", "Artist X"), titles())
    assertEquals("Post f1", tabs.activeTab.title)
    val reached = generateSequence { if (tabs.next()) tabs.activeTab.title else null }.toList()
    assertEquals(listOf("Post f2", "For you", "Artist X"), reached)
  }

  @Test
  fun aTagTappedOnAFeedsKeptPostGoesInFrontOfTheFeedsBlockAndUnwindsToThePost() {
    ownersQueue()
    tabs.activate(tab("Post f2").id)

    TabsNavigator(tabs).goTo(grid("artist_x"))

    assertEquals(listOf("Browse", "Artist X", "Following", "Post f1", "Post f2", "For you"), titles())
    assertEquals("Artist X", tabs.activeTab.title)
    // Moving on past its block lands back on the post it was opened from, as from any source.
    assertEquals("Post f2", tabs.neighbour(forward = true)?.title)
    assertTrue(tabs.next())
    assertEquals("Post f2", tabs.activeTab.title)
  }

  @Test
  fun signingInOrTurningOnForYouAddsTheFeedAtTheEnd() {
    tabs.open(grid("a"), background = true)
    pin(forYou = false)
    assertEquals(listOf("Browse", "A", "Following"), titles())

    // A later search goes after Following; For you, turned on afterwards, goes after that.
    tabs.open(grid("b"), background = true)
    pin(forYou = true)
    assertEquals(listOf("Browse", "A", "Following", "B", "For you"), titles())

    // Signing out still removes them (#229).
    tabs.pinFeeds(FeedItems.pinned(signedIn = false, forYouInQueue = true))
    assertEquals(listOf("Browse", "A", "B"), titles())
  }

  @Test
  fun aFeedComingBackTakesItsKeptPostsToTheEndWithIt() {
    ownersQueue()
    tabs.open(grid("b"), background = true)
    tabs.pinFeeds(emptyList())
    // Left behind as "from Following (closed)", where they stood.
    assertEquals(listOf("Browse", "Post f1", "Post f2", "B"), titles())

    pin(forYou = false)

    val feed = tab("Following")
    assertEquals(listOf("Browse", "B", "Following", "Post f1", "Post f2"), titles())
    assertSame(feed, tabs.sourceOf(tab("Post f1")))
    assertSame(feed, tabs.sourceOf(tab("Post f2")))
  }

  @Test
  fun openingAnOpenFeedInANewTabGivesAPositionOnThePillsScaleNeverZero() {
    // Only Following and Browse: Following moved first, so no counted item stands before it.
    pin(forYou = false)
    tabs.moveInQueue(tab("Following").id, down = false)
    assertEquals(listOf("Following", "Browse"), titles())

    // Nothing counted stands before it: 1, not "Already item 0".
    assertEquals(1, TabsNavigator(tabs).openInNewTab(grid(Feed.Following.tag)))
    // A source after it is counted as the pill counts it.
    tabs.open(grid("a"), background = true)
    assertEquals(2, TabsNavigator(tabs).openInNewTab(grid("a")))
    // Moved after Browse, the feed gives Browse's number, on the pill's scale.
    tabs.moveInQueue(tab("Following").id, down = true)
    assertEquals(listOf("Browse", "Following", "A"), titles())
    assertEquals(1, TabsNavigator(tabs).openInNewTab(grid(Feed.Following.tag)))
  }

  @Test
  fun upNextAndEndOfTheQueueStandPastTheLastItemWhateverItIs() {
    ownersQueue()
    // On For you, the last item: the cards are next.
    tabs.activate(tab("For you").id)
    assertTrue(tabs.upNextAhead())
    assertNull(tabs.neighbour(forward = true))
    // Not on a feed's kept post with more to come.
    tabs.activate(tab("Post f2").id)
    assertFalse(tabs.upNextAhead())

    // A later search after the feeds: the cards stand past it instead.
    tabs.open(grid("later"), background = true)
    tabs.activate(tab("For you").id)
    assertFalse(tabs.upNextAhead())
    tabs.activate(tab("Later").id)
    assertTrue(tabs.upNextAhead())
  }

  @Test
  fun aSavedQueueWithItemsAfterTheFeedsRoundTrips() {
    ownersQueue()
    tabs.open(grid("later"), background = true)
    val saved = tabs.saved()
    FileTabStateStore(file).save(saved)

    val restored = TabsController(FileTabStateStore(file))
    restored.pinFeeds(FeedItems.pinned(signedIn = true, forYouInQueue = true))

    assertEquals(listOf("Browse", "Following", "Post f1", "Post f2", "For you", "Later"), titles(restored))
    assertEquals(saved.tabs.map { it.id }, restored.saved().tabs.map { it.id })
    assertEquals(saved.tabs.map { it.block }, restored.saved().tabs.map { it.block })
  }

  @Test
  fun anOldFileWithAFeedInTheMiddleLoadsAsItWasAndIsNotRewritten() {
    // Following in the middle, with a source after it: #229's app never wrote this, but any order loads as it is. Touched
    // now, so nothing fades.
    val t = Clock.System.now().epochSeconds
    val text = """{"version":2,"session":"s","activeIndex":0,"tabs":[""" +
      """{"id":"x","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["x"]}],"kind":"source","touched":$t,"addedIn":"s"},""" +
      """{"id":"f","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["${Feed.Following.tag}"]}],"kind":"feed","touched":$t,"addedIn":"s"},""" +
      """{"id":"k","routes":[{"type":"dev.marzahn.sankaku.Route.Post","id":"k","md5":"mk","originTags":["a"],"label":"Post k"}],"block":"f","kind":"post","touched":$t,"addedIn":"s","keptAt":$t},""" +
      """{"id":"y","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["y"]}],"kind":"source","touched":$t,"addedIn":"s"}]}"""
    val old = InMemoryTextFile(text)
    val store = FileTabStateStore(old)
    val loaded = store.load()!!

    val restored = TabsController(FakeStore(loaded))
    restored.pinFeeds(listOf(Feed.Following))

    assertEquals(listOf("X", "Following", "Post k", "Y"), titles(restored))
    assertEquals(loaded.tabs.map { it.id }, restored.saved().tabs.map { it.id })
    assertEquals(text, old.read())
  }

  private class FakeStore(val saved: SavedTabs?) : TabStateStore {
    override fun load() = saved

    override fun save(tabs: SavedTabs) = Unit

    override fun clear() = Unit
  }
}
