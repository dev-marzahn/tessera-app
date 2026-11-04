package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.snapshots.Snapshot
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Which grid cells show as seen (#182, #262): only posts kept from that grid and seen since they were kept. */
class SeenFromGridTest {
  private var now = Instant.parse("2026-11-02T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock)
  private val navigator = TabsNavigator(tabs)
  private val artist = Route.Grid(listOf("artist_x"))
  private val other = Route.Grid(listOf("artist_y"))

  private fun gridOf(route: Route.Grid) = tabs.tabs.value.single { it.top == route }

  private fun keepFrom(grid: Route.Grid, id: String, md5: String) {
    navigator.goTo(grid)
    navigator.openInNewTab(Route.Post(id, md5, grid.tags))
  }

  @Test
  fun aPostKeptFromTheGridAndThenSeenIsSeenThere() {
    keepFrom(artist, "p1", "aa11")
    assertFalse(tabs.seenFromGrid(gridOf(artist), "p1", seen))

    now += 1.minutes
    seen.record("p1", "aa11")

    assertTrue(tabs.seenFromGrid(gridOf(artist), "p1", seen))
  }

  @Test
  fun aPostSeenButNotKeptIsNotSeenInAGrid() {
    navigator.goTo(artist)
    seen.record("p2", "bb22")

    assertFalse(tabs.seenFromGrid(gridOf(artist), "p2", seen))
  }

  @Test
  fun aPostKeptFromAnotherSourceIsNotSeenInThisGrid() {
    // In a tab of its own: an untouched grid would be replaced in place.
    tabs.open(artist, background = true)
    keepFrom(other, "p3", "cc33")
    now += 1.minutes
    seen.record("p3", "cc33")

    assertTrue(tabs.seenFromGrid(gridOf(other), "p3", seen))
    assertFalse(tabs.seenFromGrid(gridOf(artist), "p3", seen))
  }

  @Test
  fun aPostSeenOnlyBeforeItWasKeptIsNotSeen() {
    seen.record("p4", "dd44")
    now += 1.minutes
    keepFrom(artist, "p4", "dd44")

    assertFalse(tabs.seenFromGrid(gridOf(artist), "p4", seen))
  }

  @Test
  fun anExactCopyOfTheKeptPostSeenUnderAnotherIdCounts() {
    keepFrom(artist, "p5", "ee55")
    now += 1.minutes
    seen.record("p9", "EE55")

    assertTrue(tabs.seenFromGrid(gridOf(artist), "p5", seen))
  }

  @Test
  fun keptAgainAndMarkUnseenEndTheMark() {
    keepFrom(artist, "p6", "ff66")
    now += 1.minutes
    seen.record("p6", "ff66")
    assertTrue(tabs.seenFromGrid(gridOf(artist), "p6", seen))

    // Kept again from the grid: a long-press on the post again.
    now += 1.minutes
    navigator.goTo(artist)
    navigator.openInNewTab(Route.Post("p6", "ff66", artist.tags))
    assertFalse(tabs.seenFromGrid(gridOf(artist), "p6", seen))

    now += 1.minutes
    seen.record("p6", "ff66")
    assertTrue(tabs.seenFromGrid(gridOf(artist), "p6", seen))

    // Mark unseen, on the queue page.
    now += 1.minutes
    tabs.keepAgain(tabs.tabs.value.single { (it.top as? Route.Post)?.id == "p6" }.id)
    assertFalse(tabs.seenFromGrid(gridOf(artist), "p6", seen))
  }

  @Test
  fun aClosedKeptPostIsNotSeen() {
    keepFrom(artist, "p7", "gg77")
    now += 1.minutes
    seen.record("p7", "gg77")

    tabs.close(tabs.tabs.value.single { (it.top as? Route.Post)?.id == "p7" }.id)

    assertFalse(tabs.seenFromGrid(gridOf(artist), "p7", seen))
  }

  @Test
  fun theBlockAKeptPostIsInIsObservable() {
    keepFrom(artist, "p8", "hh88")
    now += 1.minutes
    seen.record("p8", "hh88")
    val kept = tabs.tabs.value.single { (it.top as? Route.Post)?.id == "p8" }

    assertTrue(tabs.seenFromGrid(gridOf(artist), "p8", seen))

    // A cell reading the mark is told when the post leaves its block, even with the tab order unchanged: the block is
    // snapshot state.
    var observed = false
    val read = Snapshot.takeSnapshot(readObserver = { observed = true })
    read.enter { kept.block }
    read.dispose()

    assertTrue(observed)
  }
}
