package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #177: the queue page's reorder moves blocks whole and kept posts within them; a feed moves as any block (#327). */
class QueueReorderTest {
  private val tabs = TabsController()

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id)

  private fun keep(id: String, from: String) =
    tabs.openInBlock(listOf(Route.Grid(listOf(from), at = id), post(id)))

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun id(title: String) = tabs.tabs.value.single { it.title == title }.id

  private fun blockOf(title: String) = tabs.tabs.value.single { it.title == title }.block

  // Browse, then a with p1 and p2 kept, then b with p3 kept.
  private fun twoBlocks() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    keep("p1", "a")
    keep("p2", "a")
    tabs.open(Route.Grid(listOf("b")), background = false)
    keep("p3", "b")
    assertEquals(listOf("Browse", "A", "p1", "p2", "B", "p3"), titles())
  }

  @Test
  fun aHeadMovesItsWholeBlockPastTheNextBlock() {
    twoBlocks()

    assertEquals(2, tabs.moveInQueue(id("A"), down = true))

    assertEquals(listOf("Browse", "B", "p3", "A", "p1", "p2"), titles())
    assertEquals(id("A"), blockOf("p1"))
  }

  @Test
  fun aHeadMovesItsWholeBlockPastThePreviousBlock() {
    twoBlocks()

    assertEquals(-3, tabs.moveInQueue(id("B"), down = false))

    assertEquals(listOf("Browse", "B", "p3", "A", "p1", "p2"), titles())
    assertEquals(-1, tabs.moveInQueue(id("B"), down = false))
    assertEquals(listOf("B", "p3", "Browse", "A", "p1", "p2"), titles())
  }

  @Test
  fun theEndsCannotMoveFurther() {
    twoBlocks()

    assertNull(tabs.moveInQueue(id("Browse"), down = false))
    assertNull(tabs.moveInQueue(id("B"), down = true))
    assertFalse(tabs.canMoveInQueue(id("Browse"), down = false))
    assertTrue(tabs.canMoveInQueue(id("Browse"), down = true))
  }

  @Test
  fun aKeptPostMovesWithinItsBlock() {
    twoBlocks()

    assertEquals(1, tabs.moveInQueue(id("p1"), down = true))

    assertEquals(listOf("Browse", "A", "p2", "p1", "B", "p3"), titles())
    assertEquals(id("A"), blockOf("p1"))
  }

  @Test
  fun aKeptPostDraggedPastItsBlocksEndLeavesTheBlockInPlace() {
    twoBlocks()

    assertEquals(0, tabs.moveInQueue(id("p2"), down = true))

    assertEquals(listOf("Browse", "A", "p1", "p2", "B", "p3"), titles())
    assertNull(blockOf("p2"))
    assertEquals(id("A"), blockOf("p1"))
    // On its own now, it moves as a block of one.
    assertEquals(2, tabs.moveInQueue(id("p2"), down = true))
    assertEquals(listOf("Browse", "A", "p1", "B", "p3", "p2"), titles())
  }

  @Test
  fun aKeptPostDraggedAboveItsHeadLeavesTheBlockBeforeIt() {
    twoBlocks()

    assertEquals(-1, tabs.moveInQueue(id("p1"), down = false))

    assertEquals(listOf("Browse", "p1", "A", "p2", "B", "p3"), titles())
    assertNull(blockOf("p1"))
    assertEquals(id("A"), blockOf("p2"))
  }

  @Test
  fun aFeedMovesAndIsPassedLikeAnyBlock() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    tabs.open(Route.Grid(listOf("feed:following")), background = false)
    tabs.open(Route.Grid(listOf("b")), background = false)
    // A feed is a source that persists (#327): it stands where it was opened, and nothing keeps it last.
    assertEquals(listOf("Browse", "A", "Following", "B"), titles())

    assertEquals(1, tabs.moveInQueue(id("A"), down = true))
    assertEquals(listOf("Browse", "Following", "A", "B"), titles())
    assertEquals(1, tabs.moveInQueue(id("Following"), down = true))
    assertEquals(listOf("Browse", "A", "Following", "B"), titles())
    assertEquals(1, tabs.moveInQueue(id("Following"), down = true))
    assertNull(tabs.moveInQueue(id("Following"), down = true))
  }

  @Test
  fun startingOnBrowseOpensItInFrontOfTheCurrentBlockOnce() {
    twoBlocks()
    tabs.activate(id("p3"))

    tabs.showBrowse()

    assertEquals(listOf("Browse", "A", "p1", "p2", "Browse", "B", "p3"), titles())
    assertEquals(4, tabs.tabs.value.indexOf(tabs.activeTab))
    // Already on Browse: nothing is added.
    tabs.showBrowse()
    assertEquals(7, tabs.tabs.value.size)
  }
}
