package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #349: Back on a grid a search opened "now" from a queue item closes that grid, with Undo, and shows the item it was
 * opened from again, where it was. Nothing else in the queue moves.
 */
class BackClosesOpenedGridTest {
  private val file = InMemoryTextFile(null)
  private val store = FileTabStateStore(file)
  private val tabs = TabsController(store)
  private val navigator = TabsNavigator(tabs)
  private val undo = ClosedTabUndo(tabs)
  private val artist = Route.Grid(listOf("artist_x"))
  private val tag = Route.Grid(listOf("tag_t"))

  private fun post(id: String) = Route.Post(id, "m$id", artist.tags, label = id)

  private fun ids() = tabs.tabs.value.map { it.id }

  private fun tops() = tabs.tabs.value.map { it.top }

  // Browse, then artist_x with k1 kept, and the lone post p tapped open from it: [Browse, artist_x, p, k1], p shown.
  private fun onALonePost(check: Boolean = true): TabState {
    tabs.open(artist, background = false)
    navigator.openInNewTab(post("k1"))
    navigator.goTo(post("p"))
    if (check) assertEquals(listOf(Route.Grid(emptyList()), artist, post("p"), post("k1")), tops())
    return tabs.activeTab
  }

  @Test
  fun backOnAGridATagTapOpenedFromAPostClosesItAndReturnsToThePost() {
    val p = onALonePost()
    val before = ids()

    navigator.goTo(tag)
    val grid = tabs.activeTab
    assertEquals(tag, grid.top)
    assertSame(p, tabs.openerOf(grid))

    navigator.goBack()

    assertSame(p, tabs.activeTab)
    assertEquals(before, ids())
    assertEquals(tag, tabs.closed.value.last().top)
  }

  @Test
  fun undoReopensTheGridWhereItWasAndBackClosesItAgain() {
    val p = onALonePost()
    navigator.goTo(tag)
    val opened = tops()

    assertTrue(undo.closeBack(tabs.activeTab.id))
    assertSame(p, tabs.activeTab)
    assertNotNull(undo.pending.value)

    undo.undo()

    assertEquals(opened, tops())
    assertEquals(tag, tabs.activeTab.top)
    // Back still knows where it came from, and moving on past its block lands on the post again, as before the close.
    assertSame(p, tabs.openerOf(tabs.activeTab))
    assertSame(p, tabs.neighbour(forward = true))
    assertTrue(undo.closeBack(tabs.activeTab.id))
    assertSame(p, tabs.activeTab)
  }

  /**
   * From a grid's viewer (a touched grid) or a feed, and for "Search only this" and Child posts (the Children strip's
   * "All", #342): the same.
   */
  @Test
  fun fromATouchedGridOrAFeedAndForSearchOnlyThisAndChildPostsTheSame() {
    val feed = Route.Grid(listOf("feed:following"))
    val searchOnlyThis = Route.Grid(listOf("order:popularity", "tag_t"))
    val childPosts = Route.Grid(listOf("parent:123"))
    for (from in listOf(artist, feed)) {
      for (opened in listOf(tag, searchOnlyThis, childPosts)) {
        val tabs = TabsController()
        val navigator = TabsNavigator(tabs)
        tabs.open(from, background = false)
        val grid = tabs.activeTab
        // A post opened in its viewer touches it, so the search opens in front of it rather than in place.
        grid.markTouched(from)
        navigator.openInNewTab(post("k1"))
        val before = tabs.tabs.value.map { it.id }

        navigator.goTo(opened)
        assertEquals(opened, tabs.activeTab.top, "$from → $opened")
        navigator.goBack()

        // The same tab, so its view models (scroll, the viewer on its post, the sheet) are as they were left.
        assertSame(grid, tabs.activeTab, "$from → $opened")
        assertEquals(before, tabs.tabs.value.map { it.id }, "$from → $opened")
      }
    }
  }

  /** #184: kept posts from that grid stay where they stand, as a closed source's do, and Undo heads them again. */
  @Test
  fun keptPostsFromTheGridStayAsAClosedBlock() {
    val p = onALonePost()
    navigator.goTo(tag)
    val grid = tabs.activeTab
    navigator.openInNewTab(post("t1"))
    navigator.openInNewTab(post("t2"))
    assertEquals(listOf(Route.Grid(emptyList()), tag, post("t1"), post("t2"), artist, post("p"), post("k1")), tops())

    val closed = tabs.closeBack(grid.id)

    assertNotNull(closed)
    assertEquals(2, closed.keptStay)
    assertSame(p, tabs.activeTab)
    assertEquals(listOf(Route.Grid(emptyList()), post("t1"), post("t2"), artist, post("p"), post("k1")), tops())
    val kept = tabs.tabs.value.filter { it.top == post("t1") || it.top == post("t2") }
    assertTrue(kept.all { it.closedSource == tag.tags && it.block == grid.id })

    tabs.reopen(closed)

    assertEquals(listOf(Route.Grid(emptyList()), tag, post("t1"), post("t2"), artist, post("p"), post("k1")), tops())
    assertTrue(kept.all { it.closedSource == null && it.block == tabs.activeTab.id })
  }

  @Test
  fun aTouchedGridClosesToo() {
    val p = onALonePost()
    navigator.goTo(tag)
    tabs.activeTab.markTouched(tag)

    navigator.goBack()

    assertSame(p, tabs.activeTab)
  }

  @Test
  fun aSearchRunInTheGridInPlaceGoesBackFirstThenTheGridCloses() {
    val p = onALonePost()
    navigator.goTo(tag)
    val grid = tabs.activeTab
    val other = Route.Grid(listOf("tag_u"))
    // Untouched, so the search replaces it in place, in the same tab.
    navigator.goTo(other)
    assertSame(grid, tabs.activeTab)
    assertNull(tabs.openerOf(grid))

    navigator.goBack()
    assertSame(grid, tabs.activeTab)
    assertEquals(tag, grid.top)

    navigator.goBack()
    assertSame(p, tabs.activeTab)
  }

  @Test
  fun aSourceShownBecauseItsSearchWasOpenAgainKeepsTodaysBack() {
    tabs.open(tag, background = true)
    onALonePost(check = false)

    navigator.goTo(tag)
    val source = tabs.activeTab
    assertNull(tabs.openerOf(source))
    val before = ids()

    navigator.goBack()

    assertSame(source, tabs.activeTab)
    assertEquals(before, ids())
  }

  @Test
  fun aGridReachedAgainByItsSearchFromAnotherItemNoLongerCloses() {
    onALonePost()
    navigator.goTo(tag)
    val grid = tabs.activeTab
    tabs.activate(tabs.tabs.value.single { it.top == post("k1") }.id)

    // The same tag tapped on another post shows the grid as it is (#174): it's in the queue already.
    navigator.goTo(tag)
    assertSame(grid, tabs.activeTab)
    assertNull(tabs.openerOf(grid))
    val before = ids()

    navigator.goBack()

    assertSame(grid, tabs.activeTab)
    assertEquals(before, ids())
  }

  @Test
  fun fromALonePostOnAFeedBackReturnsToThePost() {
    val feed = Route.Grid(listOf("feed:following"))
    tabs.open(feed, background = false)
    navigator.goTo(post("f1"))
    val lone = tabs.activeTab
    val before = ids()

    navigator.goTo(tag)
    navigator.goBack()

    assertSame(lone, tabs.activeTab)
    assertEquals(before, ids())
  }

  @Test
  fun withTheItemItCameFromGoneBackKeepsTodaysBack() {
    val p = onALonePost()
    navigator.goTo(tag)
    val grid = tabs.activeTab
    tabs.close(p.id)
    tabs.activate(grid.id)
    val before = ids()

    navigator.goBack()

    assertSame(grid, tabs.activeTab)
    assertEquals(before, ids())
  }

  @Test
  fun theItemItCameFromReopenedUnderANewIdIsStillWhereBackGoes() {
    val p = onALonePost()
    navigator.goTo(tag)
    val grid = tabs.activeTab
    tabs.reopen(assertNotNull(tabs.close(p.id)))
    tabs.activate(grid.id)

    navigator.goBack()

    assertEquals(post("p"), tabs.activeTab.top)
  }

  @Test
  fun aGridFromBeforeTheLastClearViewedKeepsTodaysBack() {
    onALonePost()
    navigator.goTo(tag)
    val grid = tabs.activeTab
    tabs.sessions.startNew()

    assertNull(tabs.openerOf(grid))
  }

  @Test
  fun startOnBrowseIsNotOpenedFromAnItem() {
    onALonePost()
    tabs.showBrowse()

    assertNull(tabs.openerOf(tabs.activeTab))
  }

  @Test
  fun whereItWasOpenedFromIsSavedAndBackStillReturnsThereAfterARelaunch() {
    onALonePost()
    navigator.goTo(tag)
    store.save(tabs.saved())

    val relaunched = TabsController(FileTabStateStore(file))

    val grid = relaunched.activeTab
    assertEquals(tag, grid.top)
    assertEquals(post("p"), relaunched.openerOf(grid)?.top)
    TabsNavigator(relaunched).goBack()
    assertEquals(post("p"), relaunched.activeTab.top)
  }

  @Test
  fun aFileWithoutItLoadsAndSavesBackUnchangedAndItsGridsKeepTodaysBack() {
    val saved = """{"version":2,"session":"s1","activeIndex":0,"tabs":[""" +
      """{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["tag_t"]}],"kind":"source","touched":1000,"addedIn":"s1"},""" +
      """{"id":"b","routes":[{"type":"dev.marzahn.sankaku.Route.Post","id":"p","md5":"mp","originTags":["artist_x"]}],"kind":"post","touched":1000,"addedIn":"s1","keptAt":1000}""" +
      """]}"""
    val existing = InMemoryTextFile(saved)
    val existingStore = FileTabStateStore(existing)
    val before = existingStore.load()
    val clock = object : Clock {
      override fun now() = Instant.fromEpochSeconds(1000)
    }
    val controller = TabsController(existingStore, clock = clock)

    assertEquals(before, controller.saved())
    assertNull(controller.openerOf(controller.activeTab))
    existingStore.save(controller.saved())
    assertFalse(existing.read()!!.contains("openedFrom"))
    assertEquals(before, existingStore.load())
    // A tab with nothing opened from an item leaves it out of a new save too.
    val fresh = InMemoryTextFile(null)
    FileTabStateStore(fresh).save(TabsController().saved())
    assertFalse(fresh.read()!!.contains("openedFrom"))
  }

  @Test
  fun backOnAGridOpenedFromAnItemClosesItAtTheQueuesLevel() {
    assertEquals(QueueBack.Close, queueBack(cardUp = false, lonePost = false, previous = false, opened = true))
    assertEquals(QueueBack.Close, queueBack(cardUp = false, lonePost = false, previous = true, opened = true))
    // A card still goes back to the item it stands past.
    assertEquals(QueueBack.Previous, queueBack(cardUp = true, lonePost = false, previous = false, opened = true))
    assertEquals(QueueBack.Tab, queueBack(cardUp = false, lonePost = false, previous = true, opened = false))
  }
}
