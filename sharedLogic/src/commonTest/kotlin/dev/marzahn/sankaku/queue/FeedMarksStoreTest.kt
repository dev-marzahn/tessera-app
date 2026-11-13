package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.storedTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The feed marks' store (#314): kept between runs, For you's dropped after 3 days, cleared on demand and on sign-out. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedMarksStoreTest {
  private class MovableClock(var now: Instant = Instant.parse("2026-11-12T12:00:00Z")) : Clock {
    override fun now() = now
  }

  private val clock = MovableClock()
  private val file = InMemoryTextFile()

  private fun marks(post: String) = FeedMarks(FeedMark(post, 1_794_000_000L, "c1", 2), at = clock.now.epochSeconds)

  @Test
  fun marksAreKeptBetweenRuns() {
    val gap = FeedGap(FeedMark("s", 2), FeedMark("u", 1), left = 3, since = 5)
    FeedMarksStore(file, clock).set(Feed.Following, marks("p1").copy(gap = gap))

    assertEquals(marks("p1").copy(gap = gap), FeedMarksStore(file, clock).get(Feed.Following))
    assertNull(FeedMarksStore(file, clock).get(Feed.ForYou))
  }

  @Test
  fun thePostsReachedAreKeptAndAFileFromBeforeThemStillLoads() {
    // #539: the posts reached and their spans; a file written before them has neither, and reads as having none.
    val reached = FeedReached(mapOf("p1" to 10L, "p2" to 9L), listOf(TimeSpan(9, 10, read = 11, context = "rating:s|1a")))
    FeedMarksStore(file, clock).set(Feed.Following, marks("p1").copy(reached = reached))
    assertEquals(reached, FeedMarksStore(file, clock).get(Feed.Following)?.reached)

    file.write("""{"version":1,"feeds":{"feed:following":{"top":{"post":"p1","time":10},"at":5}}}""")
    assertEquals(FeedReached(), FeedMarksStore(file, clock).get(Feed.Following)?.reached)
  }

  @Test
  fun forYouMarksAreDroppedAfterThreeDaysAndFollowingsAreKept() {
    val store = FeedMarksStore(file, clock)
    val forYou = marks("f")
    val following = marks("p")
    store.set(Feed.ForYou, forYou)
    store.set(Feed.Following, following)

    clock.now += 3.days - 1.hours
    assertEquals(forYou, store.get(Feed.ForYou))

    clock.now += 2.hours
    assertNull(store.get(Feed.ForYou))
    assertEquals(true, store.expired(Feed.ForYou))
    assertEquals(following, FeedMarksStore(file, clock).get(Feed.Following))

    clock.now += 300.days
    assertEquals(following, store.get(Feed.Following))
  }

  @Test
  fun clearForgetsEveryFeedsMarks() {
    val store = FeedMarksStore(file, clock)
    store.set(Feed.Following, marks("p"))

    store.clear()

    assertNull(store.get(Feed.Following))
    assertNull(FeedMarksStore(file, clock).get(Feed.Following))
  }

  @Test
  fun signingOutClearsTheMarks() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { error("no requests") }
    h.session.restore()
    val store = FeedMarksStore(file, clock)
    store.clearOnSignOut(h.session, backgroundScope)
    runCurrent()
    store.set(Feed.Following, marks("p"))

    h.session.signOut()
    runCurrent()

    assertNull(store.get(Feed.Following))
    assertNull(FeedMarksStore(file, clock).get(Feed.Following))
    assertEquals(1, store.clears.value)
  }

  @Test
  fun anUnreadableFileStartsEmpty() {
    assertNull(FeedMarksStore(InMemoryTextFile("{nope"), clock).get(Feed.Following))
  }
}
