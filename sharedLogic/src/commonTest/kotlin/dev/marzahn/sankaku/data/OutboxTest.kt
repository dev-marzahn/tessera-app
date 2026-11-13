package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.data.PendingChange.Favourite
import dev.marzahn.sankaku.data.PendingChange.Follow
import dev.marzahn.sankaku.data.PendingChange.Vote
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
  private val file = InMemoryTextFile()
  private val outbox = Outbox(file) { "42" }

  private fun favourite(id: String, on: Boolean, was: Boolean = !on) = Favourite(id, on, if (on) 8 else 7, was, if (was) 8 else 7)

  @Test
  fun aFavouriteThenAnUnfavouriteCancelOut() = runTest {
    outbox.add(favourite("p1", on = true))
    outbox.add(favourite("p1", on = false))

    assertTrue(outbox.changes.value.isEmpty())
  }

  @Test
  fun eachPostKeepsOneChangeFromItsFirstState() = runTest {
    outbox.add(favourite("p1", on = true))
    outbox.add(Vote("p2", VoteState(3, 5, 20), was = VoteState(null, 4, 17)))
    outbox.add(Vote("p2", VoteState(5, 5, 22), was = VoteState(3, 5, 20)))

    assertEquals(
      listOf(favourite("p1", on = true), Vote("p2", VoteState(5, 5, 22), was = VoteState(null, 4, 17))),
      outbox.changes.value,
    )
  }

  @Test
  fun aVoteBackToTheFirstOneCancelsOut() = runTest {
    outbox.add(Vote("p2", VoteState(3, 5, 20), was = VoteState(null, 4, 17)))
    outbox.add(Vote("p2", VoteState(null, 4, 17), was = VoteState(3, 5, 20)))

    assertTrue(outbox.changes.value.isEmpty())
  }

  @Test
  fun aFollowThenAnUnfollowCancelOut() = runTest {
    outbox.add(Follow("t1", "Blue Hair", "blue_hair", following = true))
    outbox.add(Follow("t1", "Blue Hair", "blue_hair", following = false))

    assertTrue(outbox.changes.value.isEmpty())
  }

  @Test
  fun theChangesAndTheirAccountSurviveARestart() = runTest {
    outbox.add(favourite("p1", on = true))
    outbox.add(Follow("t1", "Blue Hair", "blue_hair", following = true))

    val again = Outbox(file) { "42" }

    assertEquals(outbox.changes.value, again.changes.value)
    assertEquals("42", again.owner)
  }

  @Test
  fun removingAChangeTheUserReplacedSinceKeepsTheNewOne() = runTest {
    val first = Vote("p2", VoteState(3, 5, 20), was = VoteState(null, 4, 17))
    outbox.add(first)
    outbox.add(Vote("p2", VoteState(5, 5, 22), was = VoteState(3, 5, 20)))

    outbox.remove(first)

    assertEquals(5, (outbox.changes.value.single() as Vote).vote.userVote)
  }

  @Test
  fun clearingForgetsEverythingOnDiskToo() = runTest {
    outbox.add(favourite("p1", on = true))

    outbox.clear()

    assertTrue(Outbox(file) { "42" }.changes.value.isEmpty())
    assertNull(outbox.owner)
  }

  @Test
  fun anUnreadableOrNewerFileStartsEmpty() = runTest {
    assertTrue(Outbox(InMemoryTextFile("{ not json")) { "42" }.changes.value.isEmpty())
    assertTrue(Outbox(InMemoryTextFile("""{"version":99,"owner":"42","changes":[]}""")) { "42" }.changes.value.isEmpty())
  }

  @Test
  fun nothingIsKeptSignedOut() = runTest {
    val signedOut = Outbox(file) { null }

    signedOut.add(favourite("p1", on = true))

    assertTrue(signedOut.changes.value.isEmpty())
  }
}
