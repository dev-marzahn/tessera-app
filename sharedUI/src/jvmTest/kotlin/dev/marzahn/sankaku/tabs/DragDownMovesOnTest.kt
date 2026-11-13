package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * #326: a drag down on a kept post closes it and moves on, to the item that takes its place, as ✕ does. At the end of
 * the queue it shows what moving on would show there, the Up next or End of the queue card, past the item before.
 */
class DragDownMovesOnTest {
  private val start = Instant.parse("2026-11-01T12:00:00Z")
  private var now = start
  private val clock = object : Clock {
    override fun now() = now
  }
  private val tabs = TabsController(clock = clock, seen = SeenStore(InMemoryTextFile(), clock))
  private val upNext = UpNextState(tabs, PrivacySettings(InMemoryTextFile()), Messages())
  private val undo = ClosedTabUndo(tabs)

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  private fun post(id: String) = Route.Post(id, "m$id", listOf("artist_x"), label = "Post $id", rating = "s")

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun tab(title: String) = tabs.tabs.value.single { it.title == title }

  // Browse, then the source artist_x with the kept posts A, B and C.
  private fun keptABC() {
    tabs.open(grid("artist_x"), background = false)
    listOf("A", "B", "C").forEach { tabs.openInBlock(listOf(post(it))) }
    assertEquals(listOf("Artist X", "Post A", "Post B", "Post C"), titles().takeLast(4))
  }

  // The App's effect on another item, or another search in it, coming forward.
  private fun shellSeesTheItem() = upNext.itemShown(tabs.activeTab.id, tabs.activeTab.top)

  @Test
  fun aDragDownCloseOfBShowsCAndBIsInRecentlyClosedWithUndo() {
    keptABC()
    tabs.activate(tab("Post B").id)

    undo.dragClose(tabs.activeTab.id, upNext)

    assertEquals("Post C", tabs.activeTab.title)
    assertNull(upNext.card)
    assertEquals(listOf("Browse", "Artist X", "Post A", "Post C"), titles())
    val closed = tabs.closed.value.last()
    assertEquals(post("B"), closed.top)
    assertSame(closed, undo.pending.value?.closed)
  }

  @Test
  fun undoShowsBAgainInItsPlace() {
    keptABC()
    tabs.activate(tab("Post B").id)

    undo.dragClose(tabs.activeTab.id, upNext)
    undo.undo()

    // A reopened tab has a new id; it's B again, active, in its place.
    assertEquals("Post B", tabs.activeTab.title)
    assertEquals(listOf("Browse", "Artist X", "Post A", "Post B", "Post C"), titles())
  }

  @Test
  fun closingTheLastItemShowsEndOfTheQueuePastTheOneBefore() {
    keptABC()
    tabs.activate(tab("Post C").id)

    undo.dragClose(tab("Post C").id, upNext)

    assertEquals("Post B", tabs.activeTab.title)
    assertEquals(UpNextCard.End, upNext.card)
    // The shell sees Post B come forward; the card came up past it, so it stays.
    shellSeesTheItem()
    assertEquals(UpNextCard.End, upNext.card)
    // Undo brings C back, and the card goes as any other item coming forward takes it.
    undo.undo()
    shellSeesTheItem()
    assertEquals("Post C", tabs.activeTab.title)
    assertNull(upNext.card)
  }

  @Test
  fun closingTheLastItemShowsUpNextWhenItOffersSomething() {
    // "Old" fades: last touched on day 0, swept on day 6.
    tabs.open(grid("old"), background = true)
    now = start + 6.days
    // Browse was looked at today, so it doesn't fade.
    tabs.activate(tabs.tabs.value.first().id)
    // Following added first, so the kept posts opened later stand after it and C is the last item (#327: Up next
    // stands past the last item, whatever it is, no longer before the feeds).
    tabs.pinFeeds(listOf(Feed.Following))
    keptABC()
    tabs.sweep()
    assertEquals(listOf("Old"), tabs.faded.value.map { it.title })
    assertEquals("Post C", titles().last())
    tabs.activate(tab("Post C").id)
    assertEquals(Ahead.UpNext, upNext.ahead())

    undo.dragClose(tab("Post C").id, upNext)

    assertEquals("Post B", tabs.activeTab.title)
    assertIs<UpNextCard.Pickup>(upNext.card)
    shellSeesTheItem()
    assertIs<UpNextCard.Pickup>(upNext.card)
  }

  @Test
  fun closingAKeptPostWithFollowingAfterItShowsFollowing() {
    keptABC()
    tabs.pinFeeds(listOf(Feed.Following))
    tabs.activate(tab("Post C").id)

    undo.dragClose(tab("Post C").id, upNext)

    assertEquals("Following", tabs.activeTab.title)
    assertNull(upNext.card)
  }

  @Test
  fun aSearchRunInTheItemTheCardStandsPastStillTakesItAway() {
    keptABC()
    tabs.activate(tab("Post C").id)
    undo.dragClose(tab("Post C").id, upNext)
    shellSeesTheItem()

    upNext.itemShown(tabs.activeTab.id, grid("other"))

    assertNull(upNext.card)
  }

  @Test
  fun theOnlyItemClosedLeavesABrowseGridAndNoCard() {
    undo.dragClose(tabs.activeTab.id, upNext)

    assertEquals(listOf("Browse"), titles())
    assertNull(upNext.card)
  }
}
