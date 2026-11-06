package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.queue.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Tabs as queue items (#174): their kind, one source per search, touched and kept-at. */
class TabsQueueTest {
  private var now = Instant.parse("2026-11-01T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val tabs = TabsController(clock = clock)
  private val navigator = TabsNavigator(tabs)
  private val artist = Route.Grid(listOf("artist_x"))
  private val byPopularity = Route.Grid(listOf("order:popularity", "artist_x"))

  private fun tabOf(route: Route) = tabs.tabs.value.single { it.top == route }

  @Test
  fun aGridIsASourceAFeedIsAFeedAndAPostIsAKeptPost() {
    assertEquals(QueueKind.Source, queueKind(artist))
    assertEquals(QueueKind.Source, queueKind(Route.Grid(emptyList())))
    assertEquals(QueueKind.Feed, queueKind(Route.Grid(listOf("feed:following"))))
    assertEquals(QueueKind.Feed, queueKind(Route.Grid(listOf("feed:for-you", "rating:s"))))
    assertEquals(QueueKind.KeptPost, queueKind(Route.Post("p1", null, emptyList())))
  }

  @Test
  fun aScreenThatIsNeitherIsOther() {
    // Books and Settings have no routes yet (Route is sealed: a new one must choose its kind in `queueKind`).
    assertEquals(QueueKind.Other, queueKind(null))
  }

  @Test
  fun theKindFollowsNavigation() {
    val tab = tabs.activeTab
    assertEquals(QueueKind.Source, tab.kind)

    tab.backStack.add(Route.Post("p1", "m1", emptyList()))
    assertEquals(QueueKind.KeptPost, tab.kind)

    tab.backStack.removeAt(tab.backStack.lastIndex)
    assertEquals(QueueKind.Source, tab.kind)

    navigator.goTo(Route.Grid(listOf("feed:following")))
    assertEquals(QueueKind.Feed, tabs.activeTab.kind)
  }

  @Test
  fun openingTheSameTagsInAnotherOrderGoesToTheOpenSource() {
    tabs.open(byPopularity, background = true)
    val source = tabOf(byPopularity)
    val count = tabs.tabs.value.size

    navigator.goTo(artist)

    assertEquals(count, tabs.tabs.value.size)
    assertEquals(source.id, tabs.active.value)
    // It opens as it is: same order, same back stack.
    assertEquals(listOf<Route>(byPopularity), source.backStack.toList())
  }

  @Test
  fun aRatingVariantIsTheSameSource() {
    tabs.open(artist, background = true)
    val count = tabs.tabs.value.size

    tabs.open(Route.Grid(listOf("-rating:e", "artist_x")), background = false)
    tabs.open(Route.Grid(listOf("rating:s", "artist_x")), background = true)

    assertEquals(count, tabs.tabs.value.size)
    assertEquals(tabOf(artist).id, tabs.active.value)
  }

  @Test
  fun aBackgroundOpenOfAnOpenSourceAddsNothingAndSaysWhere() {
    tabs.open(byPopularity, background = true)

    val position = navigator.openInNewTab(artist)

    assertEquals(tabs.tabs.value.indexOf(tabOf(byPopularity)) + 1, position)
    assertEquals(2, tabs.tabs.value.size)
    // Background: the active tab stays.
    assertEquals(QueueKind.Source, tabs.activeTab.kind)
    assertEquals(listOf<Route>(Route.Grid(emptyList())), tabs.activeTab.backStack.toList())
  }

  @Test
  fun differentContentTagsMakeANewSource() {
    tabs.open(artist, background = true)

    tabs.open(Route.Grid(listOf("artist_x", "blue_hair")), background = true)
    tabs.open(Route.Grid(listOf("artist_y")), background = true)

    assertEquals(4, tabs.tabs.value.size)
  }

  @Test
  fun aNewOrderOnTheSourceShownRunsInIt() {
    navigator.goTo(artist)
    val tab = tabs.activeTab

    navigator.goTo(byPopularity)

    assertEquals(1, tabs.tabs.value.size)
    assertEquals(byPopularity, tab.top)
  }

  @Test
  fun aSourceIsFoundByItsKey() {
    tabs.open(byPopularity, background = true)

    assertSame(tabOf(byPopularity), tabs.sourceFor(SourceKey.of(listOf("ARTIST_X"))!!))
    assertNull(tabs.sourceFor(SourceKey.of(listOf("artist_y"))!!))
  }

  @Test
  fun aFeedStillOpensBesideASourceWithTheSameKey() {
    val followingByPopularity = Route.Grid(listOf("feed:following", "order:popularity"))
    tabs.open(followingByPopularity, background = true)

    tabs.open(Route.Grid(listOf("feed:following")), background = true)

    assertEquals(3, tabs.tabs.value.size)
  }

  @Test
  fun openingFollowingTwiceGivesOneItem() {
    val following = Route.Grid(listOf("feed:following"))
    tabs.open(following, background = true)
    val count = tabs.tabs.value.size

    // Later, now and a search in place all go to the feed already in the queue (#185).
    assertSame(tabOf(following), tabs.open(following, background = true))
    assertSame(tabOf(following), tabs.open(following, background = false))
    assertEquals(count, tabs.tabs.value.size)
    assertEquals(tabOf(following).id, tabs.active.value)

    // The sidebar's Following, from another tab, goes to it too instead of replacing that tab's grid.
    tabs.activate(tabs.tabs.value.first { it.top != following }.id)
    navigator.goTo(following)
    assertEquals(count, tabs.tabs.value.size)
    assertEquals(tabOf(following).id, tabs.active.value)
    assertEquals(listOf<Route>(Route.Grid(emptyList())), tabs.tabs.value.first { it.top != following }.backStack.toList())
  }

  @Test
  fun aFeedWithARatingFilterIsTheSameFeed() {
    tabs.open(Route.Grid(listOf("feed:for-you")), background = true)
    val count = tabs.tabs.value.size

    tabs.open(Route.Grid(listOf("feed:for-you", "rating:s")), background = true)

    assertEquals(count, tabs.tabs.value.size)
  }

  @Test
  fun followingAndForYouAreTwoItems() {
    tabs.open(Route.Grid(listOf("feed:following")), background = true)
    val count = tabs.tabs.value.size

    tabs.open(Route.Grid(listOf("feed:for-you")), background = true)

    assertEquals(count + 1, tabs.tabs.value.size)
  }

  @Test
  fun browsePopularAndTopNeverMerge() {
    tabs.open(Route.Grid(listOf("order:popularity")), background = true)
    tabs.open(Route.Grid(listOf("order:quality")), background = true)
    tabs.open(Route.Grid(emptyList()), background = true)

    assertEquals(4, tabs.tabs.value.size)
  }

  @Test
  fun aKeptPostsSourceIsItsBlockHead() {
    navigator.goTo(artist)
    val source = tabs.activeTab
    navigator.openInNewTab(Route.Post("p1", "m1", artist.tags))
    val kept = tabs.tabs.value.single { it.kind == QueueKind.KeptPost }

    assertSame(source, tabs.sourceOf(kept))
    assertNull(tabs.sourceOf(source))
  }

  @Test
  fun aPostOnItsOwnHasNoSource() {
    tabs.open(Route.Post("p1", "m1", emptyList()), background = true)

    assertNull(tabs.sourceOf(tabs.tabs.value.single { it.kind == QueueKind.KeptPost }))
  }

  @Test
  fun showingOrScrollingATabTouchesIt() {
    tabs.open(artist, background = true)
    val tab = tabOf(artist)
    val opened = now

    now += 5.minutes
    assertEquals(opened, tab.touched)
    tabs.activate(tab.id)
    assertEquals(now, tab.touched)

    now += 5.minutes
    tabs.touch(tab.id)
    assertEquals(now, tab.touched)
  }

  @Test
  fun keepingAPostAgainResetsItsKeptAt() {
    navigator.goTo(artist)
    val post = Route.Post("p1", "m1", artist.tags)
    navigator.openInNewTab(post)
    val kept = tabs.tabs.value.single { it.top == post }
    val first = now
    assertEquals(first, kept.keptAt)

    now += 10.minutes
    // "Already tab N": the post is kept again where it is.
    assertEquals(tabs.tabs.value.indexOf(kept) + 1, navigator.openInNewTab(post))

    assertEquals(now, kept.keptAt)
  }

  @Test
  fun keepingASeenKeptPostAgainMakesItUnseenAgain() {
    val seen = SeenStore(InMemoryTextFile(), clock)
    navigator.goTo(artist)
    val post = Route.Post("p1", "m1", artist.tags)
    navigator.openInNewTab(post)
    val kept = tabs.tabs.value.single { it.top == post }
    assertFalse(kept.seenSinceKept(seen))
    assertFalse(tabs.keptPostSeen("p1", seen))

    now += 10.minutes
    seen.record("p1", "m1")
    assertTrue(kept.seenSinceKept(seen))
    assertTrue(tabs.keptPostSeen("p1", seen))

    // Asked before the open, which keeps it again: "Kept again".
    now += 1.minutes
    val wasSeen = tabs.keptPostSeen("p1", seen)
    navigator.openInNewTab(post)

    assertTrue(wasSeen)
    assertFalse(kept.seenSinceKept(seen))
    assertFalse(tabs.keptPostSeen("p1", seen))
    // The store still holds it as seen; only the grid's mark (#262) and the queue page's "seen" end.
    assertTrue(seen.isSeen("p1", "m1"))
  }

  @Test
  fun markUnseenMakesASeenKeptPostUnseenAndAnExactCopyToo() {
    val seen = SeenStore(InMemoryTextFile(), clock)
    navigator.goTo(artist)
    val post = Route.Post("p1", "m1", artist.tags)
    navigator.openInNewTab(post)
    val kept = tabs.tabs.value.single { it.top == post }
    now += 10.minutes
    // An exact copy under another id, by md5.
    seen.record("p2", "m1")
    assertTrue(kept.seenSinceKept(seen))

    now += 1.minutes
    tabs.keepAgain(kept.id)

    assertFalse(kept.seenSinceKept(seen))
    // Another kind of tab is never "seen since kept".
    assertFalse(tabs.activeTab.seenSinceKept(seen))
  }

  @Test
  fun aPostOpenedInATabIsKeptFromThen() {
    val tab = tabs.activeTab
    assertNull(tab.keptAt)

    now += 1.minutes
    tab.backStack.add(Route.Post("p1", "m1", emptyList()))
    tabs.touch(tab.id)

    assertEquals(now, tab.keptAt)
  }

  /** #181: a post tapped open is a lone post, kept from then, in the block of the tab it was opened from. */
  @Test
  fun aLonePostIsKeptFromItsSource() {
    navigator.goTo(artist)
    val source = tabs.activeTab
    now += 1.minutes

    navigator.goTo(Route.Post("p1", "m1", artist.tags))

    val lone = tabs.activeTab
    assertEquals(QueueKind.KeptPost, lone.kind)
    assertEquals(now, lone.keptAt)
    assertSame(source, tabs.sourceOf(lone))
    assertNull(source.keptAt)
  }

  @Test
  fun aReopenedPostKeepsItsKeptAtAndSession() {
    navigator.goTo(artist)
    navigator.openInNewTab(Route.Post("p1", "m1", artist.tags))
    val kept = tabs.tabs.value.single { it.kind == QueueKind.KeptPost }
    val keptAt = kept.keptAt

    tabs.close(kept.id)
    now += 1.minutes
    tabs.reopenClosed()

    val back = tabs.tabs.value.single { it.kind == QueueKind.KeptPost }
    assertEquals(keptAt, back.keptAt)
    assertEquals(kept.addedIn, back.addedIn)
  }
}
