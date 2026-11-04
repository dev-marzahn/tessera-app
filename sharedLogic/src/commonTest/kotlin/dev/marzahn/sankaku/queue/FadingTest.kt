package dev.marzahn.sankaku.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class FadingTest {
  private val touched = Instant.parse("2026-11-01T12:00:00Z")
  private val fadedAt = Fading.fadesAt(touched)

  @Test
  fun aSourceFadesAfterThreeDaysUntouched() {
    assertFalse(Fading.isFading(touched, touched + 3.days - 1.milliseconds))
    assertTrue(Fading.isFading(touched, touched + 3.days))
    assertEquals(touched + 3.days, fadedAt)
  }

  @Test
  fun aFadedSourceIsDeletedThirtyDaysAfterItFadedThirtyThreeAfterItWasTouched() {
    assertEquals(touched + 33.days, Fading.deletesAt(fadedAt))
    assertFalse(Fading.isDeleted(fadedAt, touched + 33.days - 1.milliseconds))
    assertTrue(Fading.isDeleted(fadedAt, touched + 33.days))
  }

  @Test
  fun theCountdownRoundsUpAndReadsOneOnTheLastDay() {
    assertEquals(30, Fading.daysLeft(fadedAt, fadedAt))
    assertEquals(30, Fading.daysLeft(fadedAt, fadedAt + 1.hours))
    assertEquals(29, Fading.daysLeft(fadedAt, fadedAt + 1.days))
    assertEquals(1, Fading.daysLeft(fadedAt, fadedAt + 29.days + 1.hours))
    assertEquals(1, Fading.daysLeft(fadedAt, fadedAt + 30.days - 1.milliseconds))
    assertEquals(0, Fading.daysLeft(fadedAt, fadedAt + 30.days))
    assertEquals(0, Fading.daysLeft(fadedAt, fadedAt + 40.days))
  }

  @Test
  fun theCountdownIsWorded() {
    assertEquals("Deleted in 30 days", Fading.deletedIn(fadedAt, fadedAt))
    assertEquals("Deleted in 2 days", Fading.deletedIn(fadedAt, fadedAt + 28.days))
    assertEquals("Deleted in 1 day", Fading.deletedIn(fadedAt, fadedAt + 29.days + 12.hours))
  }

  @Test
  fun lastChanceIsTheLastWeekBeforeDeletion() {
    assertFalse(Fading.isLastChance(fadedAt, fadedAt + 22.days))
    assertTrue(Fading.isLastChance(fadedAt, fadedAt + 23.days))
    assertTrue(Fading.isLastChance(fadedAt, fadedAt + 30.days - 1.milliseconds))
    assertFalse(Fading.isLastChance(fadedAt, fadedAt + 30.days))
  }
}
