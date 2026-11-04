package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.queue.QueueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #177: what the queue page lists, in which order, and what G only leaves out. */
class QueueRowsTest {
  private val tabs = TabsController()

  private fun post(id: String, rating: String?) =
    Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = rating)

  private fun keep(id: String, from: String, rating: String? = "s") =
    tabs.openInBlock(listOf(Route.Grid(listOf(from), at = id), post(id, rating)))

  private fun rows(gOnly: Boolean = false, query: String? = null, known: Map<String, TabPost> = emptyMap()) =
    queueRows(tabs.tabs.value, emptyList(), inline = null, query, gOnly) { tab ->
      (tab.top as? Route.Post)?.let { known[it.id] }
    }

  /** Each row as text: "3 p1" for an item (indented with "  "; a feed has no number, as drawn), or the heading's words. */
  private fun List<QueueRow>.described() = map { row ->
    when (row) {
      is QueueRow.Item -> (if (row.indented) "  " else "") +
        (if (row.tab.kind == QueueKind.Feed) "" else "${row.position + 1} ") + row.tab.title
      is QueueRow.HiddenByGOnly -> "Hidden ${row.count}"
      is QueueRow.Inline -> "Closed"
      is QueueRow.ClosedBlock -> "[${row.title} · ${row.count}]"
    }
  }

  @Test
  fun aClosedSourcesKeptPostsStandUnderARowThatNamesIt() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a")
    keep("p2", "a")
    tabs.open(Route.Grid(listOf("b")), background = false)
    keep("p3", "b")
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)

    assertEquals(
      listOf("1 Browse", "[from A (closed) · 2]", "  2 p1", "  3 p2", "4 B", "  5 p3"),
      rows().described(),
    )
  }

  @Test
  fun theClosedRowIsListedOnlyWithAPostThatIs() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("g", "a", rating = "s")
    keep("elf", "a", rating = "e")
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)

    // G only leaves out the R18 post and keeps the row for the other.
    assertEquals(listOf("1 Browse", "[from A (closed) · 2]", "  2 g", "  3 elf"), rows().described())
    assertEquals(listOf("1 Browse", "[from A (closed) · 2]", "  2 g", "Hidden 1"), rows(gOnly = true).described())

    // Nothing of the block listed, no row.
    tabs.close(tabs.tabs.value.single { it.title == "g" }.id)
    assertEquals(listOf("1 Browse", "Hidden 1"), rows(gOnly = true).described())
    // Find lists the row with the posts it matches.
    assertEquals(listOf("[from A (closed) · 1]", "  2 elf"), rows(query = "elf").described())
    assertEquals(emptyList(), rows(query = "zzz").described())
  }

  @Test
  fun aDetachedPostIsNotUnderTheClosedRow() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a")
    keep("p2", "a")
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)
    tabs.moveInQueue(tabs.tabs.value.single { it.title == "p1" }.id, down = false)

    assertEquals(listOf("1 Browse", "2 p1", "[from A (closed) · 1]", "  3 p2"), rows().described())
  }

  @Test
  fun keptPostsAreIndentedUnderTheirSourceAndAFeedIsListedWhereItStands() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a")
    tabs.open(Route.Grid(listOf("feed:following")), background = false)
    // A feed heads a block (#265): a post kept from it is listed under it, numbered after the counted items before it,
    // since the feed isn't one. A feed is a source that persists (#327): there's no "Feeds" section, and "later" goes
    // after it.
    keep("p2", "feed:following")
    tabs.open(Route.Grid(listOf("b")), background = false)

    val rows = rows()
    assertEquals(listOf("1 Browse", "2 A", "  3 p1", "Following", "  4 p2", "5 B"), rows.described())
    // "Kept from Following", as #234's line and TalkBack say it.
    val kept = rows.filterIsInstance<QueueRow.Item>().single { it.tab.title == "p2" }
    assertEquals("Following", kept.from)
    assertEquals(BlockLine.End, kept.line)
    assertEquals(BlockLine.Head, rows.filterIsInstance<QueueRow.Item>().single { it.tab.title == "Following" }.line)
    assertEquals("Kept from Following", queueSubtitle(kept.tab, current = false, seen = false, rating = null, from = kept.from))
  }

  @Test
  fun gOnlyLeavesOutR15AndR18ItemsAndCountsThem() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("g", "a", rating = "s")
    keep("q", "a", rating = "q")
    keep("e", "a", rating = "e")
    tabs.open(Route.Grid(listOf("b", "rating:e")), background = false)

    assertEquals(listOf("1 Browse", "2 A", "  3 g", "  4 q", "  5 e", "6 B"), rows().described())
    assertEquals(listOf("1 Browse", "2 A", "  3 g", "Hidden 3"), rows(gOnly = true).described())
  }

  @Test
  fun aPostWhoseRatingIsntKnownCountsAsAdultUntilAPageTellsIt() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a", rating = null)

    assertEquals(listOf("1 Browse", "2 A", "Hidden 1"), rows(gOnly = true).described())
    val known = mapOf("p1" to TabPost(favourited = false, tags = emptyList(), rating = "s"))
    assertEquals(listOf("1 Browse", "2 A", "  3 p1"), rows(gOnly = true, known = known).described())
  }

  @Test
  fun aGridIsAdultOnlyWhenItsSearchLetsNoGPostThrough() {
    fun adult(vararg tags: String) = isAdult(TabState(TabId("t"), listOf(Route.Grid(tags.toList()))), null)

    assertFalse(adult("a"))
    assertFalse(adult("a", "rating:s"))
    assertFalse(adult("a", "-rating:e"))
    assertTrue(adult("a", "rating:q"))
    assertTrue(adult("a", "-rating:s"))
  }

  @Test
  fun aFindKeepsRealPositionsAndKeptPostsUnderTheirListedHead() {
    tabs.open(Route.Grid(listOf("blue_hair")), background = false)
    keep("p1", "blue_hair")
    tabs.open(Route.Grid(listOf("red")), background = false)

    assertEquals(listOf("2 Blue Hair"), rows(query = "blue hair").described())
    // Its head isn't listed, so the post isn't indented under the row above it.
    assertEquals(listOf("3 p1"), rows(query = "p1").described())
  }

  /** Each item as "title line from": its part of its block's line and the source it's kept from (#234). */
  private fun List<QueueRow>.lines() = filterIsInstance<QueueRow.Item>().map { "${it.tab.title} ${it.line} ${it.from}" }

  @Test
  fun aLineRunsFromEachHeadThroughItsListedKeptPosts() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a")
    keep("p2", "a", rating = "e")
    keep("p3", "a")
    tabs.open(Route.Grid(listOf("b")), background = false)
    keep("p4", "b", rating = "e")

    assertEquals(
      listOf("Browse null null", "A Head null", "p1 Through A", "p2 Through A", "p3 End A", "B Head null", "p4 End B"),
      rows().lines(),
    )
    // G only leaves out p2 and p4: the line skips p2, and B has nothing under it.
    assertEquals(
      listOf("Browse null null", "A Head null", "p1 Through A", "p3 End A", "B null null"),
      rows(gOnly = true).lines(),
    )
  }

  @Test
  fun aClosedSourcesKeptPostsAreKeptFromIt() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a")
    keep("p2", "a")
    tabs.close(tabs.tabs.value.single { it.title == "A" }.id)

    assertEquals(listOf("Browse null null", "p1 Through A (closed)", "p2 End A (closed)"), rows().lines())
  }

  @Test
  fun theInlineUndoRowDoesntEndTheLine() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a")
    keep("p2", "a")
    keep("p3", "a")
    val closedPost = tabs.close(tabs.tabs.value.single { it.title == "p2" }.id)!!

    val rows = queueRows(tabs.tabs.value, tabs.closed.value, inline = closedPost, query = null, gOnly = false) { null }
    assertEquals(listOf("Browse", "A", "  p1", "Closed", "  p3"), rows.described().map { it.replace(Regex("\\d+ "), "") })
    assertEquals(listOf("Browse null null", "A Head null", "p1 Through A", "p3 End A"), rows.lines())
  }

  @Test
  fun aKeptPostIsSpokenAsKeptFromItsSource() {
    val kept = TabState(TabId("k"), listOf(post("p1", "s")))

    assertEquals("Kept from Artist X · seen", queueSubtitle(kept, current = false, seen = true, rating = null, from = "Artist X"))
    assertEquals(
      "Kept from Artist X (closed) · current",
      queueSubtitle(kept, current = true, seen = false, rating = null, from = "Artist X (closed)"),
    )
  }

  @Test
  fun theSubtitleSaysTheKindPlaceSeenRatingAndCurrentInWords() {
    val kept = TabState(TabId("k"), listOf(post("p1", "q")))

    assertEquals("Kept · seen · R15 · current", queueSubtitle(kept, current = true, seen = true, rating = Rating.R15))
    assertEquals("Kept", queueSubtitle(kept, current = false, seen = false, rating = Rating.G))
    val source = TabState(TabId("s"), listOf(Route.Grid(listOf("a"))))
    assertEquals("Grid", queueSubtitle(source, current = false, seen = false, rating = null))
    val popular = TabState(TabId("o"), listOf(Route.Grid(listOf("order:popularity", "artist_x"))))
    assertEquals("Grid · popular", queueSubtitle(popular, current = false, seen = false, rating = null))
    val feed = TabState(TabId("f"), listOf(Route.Grid(listOf("feed:for-you"))))
    assertEquals("Feed · current", queueSubtitle(feed, current = true, seen = false, rating = null))
  }
}
