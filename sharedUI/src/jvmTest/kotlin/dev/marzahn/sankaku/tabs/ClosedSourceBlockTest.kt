package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #184: closing a source keeps its kept posts as a block "from artist_x (closed)", opening a source on that search puts it
 * back in front of them, and blocks move whole.
 */
class ClosedSourceBlockTest {
  private val file = InMemoryTextFile(null)
  private val store = FileTabStateStore(file)
  private val tabs = TabsController(store)
  private val artist = Route.Grid(listOf("artist_x"))

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id)

  private fun keep(id: String) = tabs.openInBlock(listOf(Route.Grid(listOf("artist_x"), at = id), post(id)))

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun tab(title: String) = tabs.tabs.value.single { it.title == title }

  // Browse, then artist_x with p1 and p2 kept, then other with p3 kept.
  private fun twoBlocks() {
    tabs.open(artist, background = false)
    keep("p1")
    keep("p2")
    tabs.open(Route.Grid(listOf("other")), background = false)
    tabs.openInBlock(listOf(post("p3")))
    assertEquals(listOf("Browse", "Artist X", "p1", "p2", "Other", "p3"), titles())
  }

  @Test
  fun closingASourceLeavesItsKeptPostsMarkedAsAClosedBlock() {
    twoBlocks()
    val head = tab("Artist X")

    val closed = tabs.close(head.id)

    assertNotNull(closed)
    assertEquals(2, closed.keptStay)
    assertEquals("Closed “Artist X” · 2 kept stay", closed.closedText)
    assertEquals(listOf("Browse", "p1", "p2", "Other", "p3"), titles())
    assertEquals(listOf("artist_x"), tab("p1").closedSource)
    assertEquals(listOf("artist_x"), tab("p2").closedSource)
    assertNull(tab("p3").closedSource)
    // They keep their block, so they stay together, and the closed source's name is what the row shows.
    assertEquals(head.id, tab("p1").head)
    assertEquals("from Artist X (closed)", closedBlockTitle(tab("p1").closedSource!!))
  }

  @Test
  fun aSourceWithNoKeptPostsClosesAsBefore() {
    tabs.open(artist, background = false)

    val closed = tabs.close(tabs.activeTab.id)

    assertNotNull(closed)
    assertEquals(0, closed.keptStay)
    assertEquals("Closed “Artist X”", closed.closedText)
  }

  @Test
  fun theCloseLabelSaysHowManyKeptPostsStay() {
    twoBlocks()

    assertEquals("Close Artist X · 2 kept stay", closeLabel(tab("Artist X"), tabs.keptPostsOf(tab("Artist X"))))
    assertEquals("Close", closeLabel(tab("Other"), 0))
    assertEquals("Close", closeLabel(tab("p1"), 0))
  }

  @Test
  fun undoBringsTheSourceBackInFrontOfItsKeptPosts() {
    twoBlocks()
    val closed = tabs.close(tab("Artist X").id)!!

    tabs.reopen(closed)

    assertEquals(listOf("Browse", "Artist X", "p1", "p2", "Other", "p3"), titles())
    assertEquals(tab("Artist X").id, tab("p1").block)
    assertNull(tab("p1").closedSource)
    assertNull(tab("p2").closedSource)
  }

  @Test
  fun holdingXToReopenDoesTheSame() {
    twoBlocks()
    tabs.close(tab("Artist X").id)

    tabs.reopenClosed()

    assertEquals(listOf("Browse", "Artist X", "p1", "p2", "Other", "p3"), titles())
    assertNull(tab("p1").closedSource)
  }

  @Test
  fun recentlyClosedKeepsTheEntryAndReopensIt() {
    twoBlocks()
    val closed = tabs.close(tab("Artist X").id)!!

    assertEquals(listOf(closed), tabs.closed.value)
    tabs.reopen(tabs.closed.value.single())

    assertEquals(tab("Artist X").id, tab("p2").block)
  }

  @Test
  fun aSourceOpenedLaterOnTheSameSearchGoesBackInFrontOfTheBlock() {
    twoBlocks()
    tabs.close(tab("Artist X").id)

    // From a tag, a search or a link in a new tab: the same search, and a different order is still the same source.
    tabs.open(Route.Grid(listOf("order:popularity", "artist_x")), background = true)

    val head = tabs.tabs.value[1]
    assertEquals(Route.Grid(listOf("order:popularity", "artist_x")), head.top)
    assertEquals(listOf("p1", "p2", "Other", "p3"), titles().drop(2))
    assertEquals(head.id, tab("p1").block)
    assertEquals(head.id, tab("p2").block)
    assertNull(tab("p1").closedSource)
    assertEquals(head, tabs.sourceFor(sourceKeyOf("artist_x")))
  }

  @Test
  fun aSourceOpenedNowOnTheSameSearchTakesTheBlockBackAndIsShown() {
    twoBlocks()
    tabs.close(tab("Artist X").id)

    // A search from a post opens in front of a block, here the closed one's.
    tabs.activate(tab("p3").id)
    tabs.search(artist)

    assertEquals(listOf("Browse", "Artist X", "p1", "p2", "Other", "p3"), titles())
    assertEquals(tab("Artist X").id, tabs.active.value)
    assertEquals(tab("Artist X").id, tab("p1").block)
    assertNull(tab("p2").closedSource)
  }

  @Test
  fun aSearchOnAnUntouchedGridDoesNotReplaceItWhenItTakesABlockBack() {
    twoBlocks()
    tabs.close(tab("Artist X").id)
    tabs.activate(tab("Browse").id)

    tabs.search(artist)

    // Browse is still there, untouched, and artist_x stands in front of its kept posts.
    assertEquals(listOf("Browse", "Artist X", "p1", "p2", "Other", "p3"), titles())
    assertEquals(tab("Artist X").id, tab("p2").block)
  }

  @Test
  fun anotherSearchLeavesTheClosedBlockAlone() {
    twoBlocks()
    tabs.close(tab("Artist X").id)

    tabs.open(Route.Grid(listOf("someone_else")), background = true)

    assertEquals(listOf("artist_x"), tab("p1").closedSource)
    assertEquals(2, tabs.closedBlock(listOf("artist_x")).size)
    assertTrue(tabs.closedBlock(listOf("someone_else")).isEmpty())
  }

  @Test
  fun newPostsKeptFromTheReopenedSourceGoToTheEndOfItsBlock() {
    twoBlocks()
    tabs.close(tab("Artist X").id)
    tabs.open(artist, background = false)

    keep("p4")

    assertEquals(listOf("Browse", "Artist X", "p1", "p2", "p4", "Other", "p3"), titles())
  }

  @Test
  fun aClosedBlockIsSavedAndStaysAfterARestart() {
    twoBlocks()
    tabs.close(tab("Artist X").id)
    store.save(tabs.saved())

    val after = TabsController(store)

    assertEquals(listOf("artist_x"), after.tabs.value.single { it.title == "p1" }.closedSource)
    assertEquals(2, after.closedBlock(listOf("artist_x")).size)
    after.open(artist, background = true)
    assertEquals(listOf("Browse", "Artist X", "p1", "p2", "Other", "p3"), after.tabs.value.map { it.title })
  }

  @Test
  fun aFileWithoutAClosedSourceReadsAsBefore() {
    twoBlocks()
    store.save(tabs.saved())

    assertTrue(!file.read()!!.contains("closedSource"))
    assertNull(TabsController(store).tabs.value.single { it.title == "p1" }.closedSource)
  }

  @Test
  fun aBulkCloseThatLeavesKeptPostsMarksThemToo() {
    twoBlocks()
    val p1 = tab("p1")

    tabs.activate(p1.id)
    tabs.closeAll(tabs.otherTabs())

    assertEquals(listOf("p1"), titles())
    assertEquals(listOf("artist_x"), tab("p1").closedSource)
  }

  @Test
  fun aSourceClosedWithItsKeptPostsLeavesNothingBehind() {
    twoBlocks()
    val head = tab("Artist X")

    tabs.closeAll(listOf(head.id, tab("p1").id, tab("p2").id))

    assertEquals(listOf("Browse", "Other", "p3"), titles())
    assertNull(tab("p3").closedSource)
  }

  // ---- blocks move together

  @Test
  fun aClosedBlockMovesWholeFromItsRow() {
    twoBlocks()
    val head = tab("Artist X").id
    tabs.close(head)
    assertEquals(listOf("Browse", "p1", "p2", "Other", "p3"), titles())

    // The row stands for the head's id.
    assertEquals(2, tabs.moveInQueue(head, down = true))

    assertEquals(listOf("Browse", "Other", "p3", "p1", "p2"), titles())
    assertEquals(head, tab("p1").block)
    assertEquals(listOf("artist_x"), tab("p2").closedSource)

    assertEquals(-2, tabs.moveInQueue(head, down = false))
    assertEquals(listOf("Browse", "p1", "p2", "Other", "p3"), titles())
  }

  @Test
  fun aClosedBlockCanMoveUpAndDownWhereTheNeighbourIsABlock() {
    twoBlocks()
    val head = tab("Artist X").id
    tabs.close(head)

    assertTrue(tabs.canMoveInQueue(head, down = false))
    assertTrue(tabs.canMoveInQueue(head, down = true))
    assertEquals(-1, tabs.moveInQueue(head, down = false))
    assertEquals(listOf("p1", "p2", "Browse", "Other", "p3"), titles())
    assertTrue(!tabs.canMoveInQueue(head, down = false))
  }

  @Test
  fun aPostInAClosedBlockMovesWithinItAndLeavesItAtTheEnds() {
    twoBlocks()
    tabs.close(tab("Artist X").id)

    // Within the block.
    assertEquals(1, tabs.moveInQueue(tab("p1").id, down = true))
    assertEquals(listOf("Browse", "p2", "p1", "Other", "p3"), titles())
    assertEquals(listOf("artist_x"), tab("p1").closedSource)

    // Out of it at the end: a detached post has no source, closed or not.
    assertEquals(0, tabs.moveInQueue(tab("p1").id, down = true))
    assertNull(tab("p1").block)
    assertNull(tab("p1").closedSource)
    assertEquals(listOf("Browse", "p2", "p1", "Other", "p3"), titles())
    // The rest is still the closed block.
    assertEquals(listOf("artist_x"), tab("p2").closedSource)
  }

  @Test
  fun theFirstPostOfAClosedBlockDraggedUpLeavesItToo() {
    twoBlocks()
    tabs.close(tab("Artist X").id)

    assertEquals(0, tabs.moveInQueue(tab("p1").id, down = false))

    assertNull(tab("p1").block)
    assertNull(tab("p1").closedSource)
    assertEquals(listOf("artist_x"), tab("p2").closedSource)
    assertEquals(1, tabs.closedBlock(listOf("artist_x")).size)
  }

  @Test
  fun undoAfterTheClosedBlockWasMovedPutsTheSourceBackInFrontOfItsPosts() {
    twoBlocks()
    val head = tab("Artist X").id
    val closed = tabs.close(head)!!
    tabs.moveInQueue(head, down = true)
    assertEquals(listOf("Browse", "Other", "p3", "p1", "p2"), titles())

    tabs.reopen(closed)

    assertEquals(listOf("Browse", "Other", "p3", "Artist X", "p1", "p2"), titles())
    assertEquals(tab("Artist X").id, tab("p2").block)
  }

  @Test
  fun aPostKeptFromAPostOfAClosedBlockJoinsTheClosedBlock() {
    twoBlocks()
    tabs.close(tab("Artist X").id)
    tabs.activate(tab("p1").id)

    tabs.openInBlock(listOf(post("p4")))

    assertEquals(listOf("Browse", "p1", "p2", "p4", "Other", "p3"), titles())
    assertEquals(listOf("artist_x"), tab("p4").closedSource)
    assertEquals(3, tabs.closedBlock(listOf("artist_x")).size)
  }

  @Test
  fun aPostClosedFromTheClosedBlockComesBackIntoTheSourceThatTookTheBlockBack() {
    twoBlocks()
    tabs.close(tab("Artist X").id)
    val closedPost = tabs.close(tab("p2").id)!!
    tabs.open(artist, background = true)

    tabs.reopen(closedPost)

    val head = tab("Artist X")
    assertEquals(head.id, tab("p2").block)
    assertNull(tab("p2").closedSource)
  }

  @Test
  fun aPostClosedFromTheClosedBlockComesBackIntoTheSourceThatUndoReopened() {
    twoBlocks()
    val closedSource = tabs.close(tab("Artist X").id)!!
    val closedPost = tabs.close(tab("p2").id)!!

    tabs.reopen(closedSource)
    tabs.reopen(closedPost)

    assertEquals(tab("Artist X").id, tab("p1").block)
    assertEquals(tab("Artist X").id, tab("p2").block)
    assertNull(tab("p2").closedSource)
    assertTrue(tabs.closedBlock(listOf("artist_x")).isEmpty())
  }

  @Test
  fun aLiveSourceStillMovesItsWholeBlockAndADetachedPostLosesItsSource() {
    twoBlocks()

    assertEquals(2, tabs.moveInQueue(tab("Artist X").id, down = true))
    assertEquals(listOf("Browse", "Other", "p3", "Artist X", "p1", "p2"), titles())

    assertEquals(0, tabs.moveInQueue(tab("p2").id, down = true))
    assertNull(tab("p2").block)
    assertNull(tabs.sourceOf(tab("p2")))
  }

  private fun sourceKeyOf(vararg tags: String) = dev.marzahn.sankaku.queue.SourceKey.of(tags.toList())!!
}
