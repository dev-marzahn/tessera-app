package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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

  // #537: keeping the post the viewer shows. That showing came before the keep, so it doesn't count.
  private var now = Instant.parse("2026-11-13T12:00:00Z")
  private val ticking = SeenStore(InMemoryTextFile(), object : Clock {
    override fun now() = now.also { now += 1.seconds }
  })
  private val kept = Viewing(ticking)

  @Test
  fun movingOnFromThePostKeptRecordsNothing() {
    kept.current("p1", "aa11")
    kept.loaded("p1")

    kept.keeping("p1")
    kept.current("p2", null)
    kept.loaded("p2")
    kept.leave()

    assertFalse(ticking.isSeen("p1", "aa11"))
    assertTrue(ticking.isSeen("p2"))
  }

  @Test
  fun closingOnThePostKeptRecordsNothing() {
    kept.current("p1", null)

    kept.keeping("p1")
    // Loaded after the keep: still the showing it was kept from.
    kept.loaded("p1")
    kept.leave()

    assertFalse(ticking.isSeen("p1"))
  }

  @Test
  fun showingItAgainAfterTheKeepMakesItSeenSinceKept() {
    kept.current("p1", null)
    kept.loaded("p1")
    kept.keeping("p1")
    val keptAt = now
    now += 1.seconds

    kept.current("p2", null)
    kept.current("p1", null)
    kept.current("p2", null)

    assertTrue(ticking.seenSince("p1", null, keptAt))
  }

  @Test
  fun keepingAnotherPostLeavesTheShownOneAsItWas() {
    kept.current("p1", null)
    kept.loaded("p1")

    kept.keeping("p9")
    kept.leave()

    assertTrue(ticking.isSeen("p1"))
    assertFalse(ticking.isSeen("p9"))
  }
}
