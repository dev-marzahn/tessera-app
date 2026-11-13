package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #502: blocks, where an item stands in its block, what's left, and what leaving a block says. */
class BlockProgressTest {
  private val tabs = TabsController()
  private val seen = mutableSetOf<String>()
  private val isSeen: (TabState) -> Boolean = { it.kind.isKept && it.title in seen }

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")

  private fun keep(id: String, from: String) = tabs.openInBlock(listOf(Route.Grid(listOf(from), at = id), post(id)))

  private fun id(title: String) = tabs.tabs.value.single { it.title == title }.id

  private fun progress(title: String) = blockProgress(tabs.tabs.value, id(title), isSeen)

  /** Browse, then Landscape with l1–l3 kept, then Cat with c1. */
  private fun queue() {
    tabs.open(Route.Grid(listOf("landscape")), background = false)
    keep("l1", "landscape")
    keep("l2", "landscape")
    keep("l3", "landscape")
    tabs.open(Route.Grid(listOf("cat")), background = false)
    keep("c1", "cat")
  }

  @Test
  fun blocksAreAHeadAndTheKeptItemsAfterIt() {
    queue()
    val all = tabs.tabs.value
    val blocks = queueBlocks(all).map { block -> block.indices.map { all[it].title } }
    assertEquals(listOf(listOf("Browse"), listOf("Landscape", "l1", "l2", "l3"), listOf("Cat", "c1")), blocks)
  }

  @Test
  fun aKeptItemSaysItsPlaceInItsBlockAndItsHeadWhatsLeft() {
    queue()
    seen += "l1"
    assertEquals(BlockProgress("Landscape", position = 2, kept = 3, left = 2, closed = false, feed = false), progress("l2"))
    assertEquals("2 / 3 · from Landscape", blockChipText(progress("l2")!!))
    assertEquals("Landscape · 2 left", blockChipText(progress("Landscape")!!))
    assertEquals("Kept item 2 of 3 from Landscape, 2 left", blockChipSpoken(progress("l2")!!))
    seen += listOf("l2", "l3")
    assertEquals("Landscape · all seen", blockChipText(progress("Landscape")!!))
    // A grid nothing was kept from has no chip.
    assertNull(progress("Browse"))
  }

  @Test
  fun aClosedSourcesKeptPostsAreABlockOfTheirOwn() {
    queue()
    tabs.close(id("Landscape"))
    val p = progress("l3")!!
    assertEquals("3 / 3 · from Landscape (closed)", blockChipText(p))
    assertEquals(true, p.closed)
  }

  @Test
  fun whatsLeftOfTheQueueCountsUnseenKeptItemsOnly() {
    queue()
    assertEquals(4, keptLeft(tabs.tabs.value, isSeen))
    seen += listOf("l1", "c1")
    assertEquals(2, keptLeft(tabs.tabs.value, isSeen))
  }

  @Test
  fun closingABlockClosesItsHeadAndItsKeptItemsAsOneEntry() {
    queue()
    val ids = blockIds(tabs.tabs.value, id("Landscape"))
    assertEquals(listOf("Landscape", "l1", "l2", "l3"), ids.map { i -> tabs.tabs.value.single { it.id == i }.title })
    val batch = tabs.closeAll(ids)!!
    assertEquals(4, batch.size)
    assertEquals(listOf("Browse", "Cat", "c1"), tabs.tabs.value.map { it.title })
    // Z brings the whole block back.
    tabs.reopenClosed()
    assertEquals(listOf("Browse", "Landscape", "l1", "l2", "l3", "Cat", "c1"), tabs.tabs.value.map { it.title })
  }

  @Test
  fun leavingASourceWhoseKeptItemsWereAllSeenOffersToCloseIt() {
    queue()
    seen += listOf("l1", "l2", "l3")
    val landscape = id("Landscape")
    val said = leavingBlock(tabs.tabs.value, landscape, hadKept = true, leftBefore = 2, now = id("Cat"), emptySet(), isSeen)
    assertEquals(LeavingBlock.Done(landscape, "Landscape", seen = 3), said)
    // Once only.
    assertEquals(
      LeavingBlock.None,
      leavingBlock(tabs.tabs.value, landscape, hadKept = true, leftBefore = 1, now = id("Cat"), setOf(landscape), isSeen),
    )
    // Not while something is left in it, and not moving within it.
    seen -= "l3"
    assertEquals(LeavingBlock.None, leavingBlock(tabs.tabs.value, landscape, true, 2, id("Cat"), emptySet(), isSeen))
    assertEquals(LeavingBlock.None, leavingBlock(tabs.tabs.value, landscape, true, 2, id("l3"), emptySet(), isSeen))
  }

  @Test
  fun closingTheLastKeptItemOfABlockOffersTheSource() {
    queue()
    seen += listOf("l1", "l2")
    tabs.closeAll(listOf(id("l3")))
    val said = leavingBlock(tabs.tabs.value, id("Landscape"), hadKept = true, leftBefore = 2, now = id("Cat"), emptySet(), isSeen)
    assertEquals(LeavingBlock.Done(id("Landscape"), "Landscape", seen = 2), said)
  }

  @Test
  fun aSourceNothingWasKeptFromIsNeverOffered() {
    queue()
    assertEquals(LeavingBlock.None, leavingBlock(tabs.tabs.value, id("Browse"), hadKept = false, 4, id("Landscape"), emptySet(), isSeen))
  }

  @Test
  fun usingUpTheLastKeptItemSaysTheQueueIsDone() {
    queue()
    seen += listOf("l1", "l2", "l3", "c1")
    val said = leavingBlock(tabs.tabs.value, id("Cat"), hadKept = true, leftBefore = 1, now = id("Browse"), emptySet(), isSeen)
    assertEquals(LeavingBlock.QueueDone, said)
    // Said once: nothing was left before either.
    assertEquals(
      LeavingBlock.None,
      leavingBlock(tabs.tabs.value, id("Browse"), hadKept = false, leftBefore = 0, now = id("Landscape"), setOf(id("Cat")), isSeen),
    )
  }

  @Test
  fun theQueueIsDoneEvenWhenTheCloseLandsInItsOwnBlock() {
    queue()
    seen += listOf("l1", "l2", "l3")
    // c1, the queue's last item, closes and lands on Cat, its own head.
    tabs.closeAll(listOf(id("c1")))
    val said = leavingBlock(tabs.tabs.value, id("Cat"), hadKept = true, leftBefore = 1, now = id("Cat"), emptySet(), isSeen)
    assertEquals(LeavingBlock.QueueDone, said)
  }

  @Test
  fun aFeedIsNeverOffered() {
    tabs.open(Route.Grid(listOf("feed:following")), background = false)
    keep("f1", "feed:following")
    tabs.open(Route.Grid(listOf("cat")), background = false)
    keep("c1", "cat")
    seen += "f1"
    val said = leavingBlock(tabs.tabs.value, id("Following"), hadKept = true, leftBefore = 2, now = id("Cat"), emptySet(), isSeen)
    assertEquals(LeavingBlock.None, said)
  }

  @Test
  fun aClosedSourcesBlockClosesWhole() {
    queue()
    tabs.close(id("Landscape"))
    val (batch, title) = tabs.closeBlock(id("l2"))!!
    assertEquals("Landscape (closed)", title)
    assertEquals(3, batch.size)
    assertEquals(listOf("Browse", "Cat", "c1"), tabs.tabs.value.map { it.title })
  }

  @Test
  fun theStripUnderlinesBlocksWithKeptItems() {
    queue()
    val strip = stripBlocks(tabs.tabs.value)
    assertEquals(listOf(false, true, true, true, true, true, true), strip.map { it.underlined })
    assertEquals(listOf(0, 0, 0, 0, 0, 1, 1), strip.map { it.block })
    assertEquals(listOf(false, true, true, true, false, true, false), strip.map { it.joinsNext })
    assertEquals(listOf(false, true, false, false, false, true, false), strip.map { it.startsBlock })
  }

  @Test
  fun theListsSelectionStopsAtItsEnds() {
    val a = TabId("a")
    val b = TabId("b")
    assertEquals(b, queueListStep(listOf(a, b), a, down = true))
    assertNull(queueListStep(listOf(a, b), b, down = true))
    assertNull(queueListStep(listOf(a, b), a, down = false))
  }
}
