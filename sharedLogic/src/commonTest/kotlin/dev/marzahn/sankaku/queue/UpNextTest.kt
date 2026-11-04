package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.queue.UpNext.Offer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** #216, #230: the order of Up next's offers, with every time set rather than waited for. */
class UpNextTest {
  private val now = Instant.parse("2026-11-10T12:00:00Z")

  private fun faded(id: String, ago: Duration) = UpNext.Faded(id, now - ago)

  private fun order(lastChance: String? = null, faded: List<UpNext.Faded> = emptyList(), passed: Set<String> = emptySet()) =
    UpNext.offers(lastChance, faded, passed).map { it.id }

  @Test
  fun aLastChanceOutranksTheOtherFadedSources() {
    val offers = UpNext.offers(
      lastChance = "soon-gone",
      faded = listOf(faded("a", 2.days), faded("soon-gone", 25.days)),
    )
    assertEquals(listOf(Offer.LastChance("soon-gone"), Offer.Pickup("a")), offers)
  }

  @Test
  fun theSourceThatFadedFirstComesFirstWhateverTheOrderGiven() {
    val faded = listOf(faded("recent", 1.days), faded("oldest", 9.days), faded("middle", 4.days))
    assertEquals(listOf("oldest", "middle", "recent"), order(faded = faded))
  }

  @Test
  fun sourcesThatFadedAtTheSameMomentKeepTheOrderGiven() {
    val faded = listOf(faded("first", 2.days), faded("second", 2.days), faded("third", 2.days))
    assertEquals(listOf("first", "second", "third"), order(faded = faded))
  }

  @Test
  fun skippingMovesToTheNextFadedSource() {
    val faded = listOf(faded("oldest", 9.days), faded("newer", 2.days), faded("chance", 26.days))
    var passed = emptySet<String>()
    val shown = mutableListOf<String>()
    while (true) {
      val next = order(lastChance = "chance", faded = faded, passed = passed).firstOrNull() ?: break
      shown += next
      passed = passed + next
    }
    // The Last chance once, not again as a pickup.
    assertEquals(listOf("chance", "oldest", "newer"), shown)
  }

  @Test
  fun aLastChanceAlreadyShownIsNotOfferedAgain() {
    assertEquals(emptyList(), order(lastChance = "faded", faded = listOf(faded("faded", 25.days)), passed = setOf("faded")))
  }

  @Test
  fun nothingFadedIsNothingToOffer() {
    assertEquals(emptyList(), order())
  }
}
