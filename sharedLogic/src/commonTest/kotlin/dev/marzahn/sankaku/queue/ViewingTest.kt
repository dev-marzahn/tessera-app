package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Seen by moving on (#174): only from a post that was fully shown as the current, settled page. */
class ViewingTest {
  private val seen = SeenStore(InMemoryTextFile(), FixedClock)
  private val viewing = Viewing(seen)

  @Test
  fun movingOnFromAFullyShownPostMakesItSeen() {
    viewing.current("p1", "aa11")
    viewing.loaded("p1")

    viewing.current("p2", null)

    assertTrue(seen.isSeen("p1", "aa11"))
    assertFalse(seen.isSeen("p2"))
  }

  @Test
  fun movingOnBeforeTheImageLoadedDoesNot() {
    viewing.current("p1", null)

    viewing.current("p2", null)

    assertFalse(seen.isSeen("p1"))
  }

  @Test
  fun aNeighbourLoadedBeforeItWasSteppedToCountsOnceItIsCurrent() {
    viewing.current("p1", null)
    viewing.loaded("p2")
    viewing.current("p2", null)

    viewing.leave()

    assertFalse(seen.isSeen("p1"))
    assertTrue(seen.isSeen("p2"))
  }

  @Test
  fun closingTheViewerOnAFullyShownPostMakesItSeen() {
    viewing.current("p1", null)
    viewing.loaded("p1")

    viewing.leave()

    assertTrue(seen.isSeen("p1"))
  }

  @Test
  fun aPostOnlyLoadedButNeverCurrentIsNotSeen() {
    // A grid's cells scrolled past never reach the viewer; a page beside the current one loads but isn't current.
    viewing.loaded("p3")
    viewing.leave()

    assertEquals(0, seen.size)
  }

  @Test
  fun stayingOnAPostRecordsNothingYet() {
    viewing.current("p1", null)
    viewing.loaded("p1")
    viewing.current("p1", null)

    assertFalse(seen.isSeen("p1"))
  }
}
