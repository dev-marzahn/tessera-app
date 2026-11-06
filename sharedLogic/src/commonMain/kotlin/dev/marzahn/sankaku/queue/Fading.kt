package dev.marzahn.sankaku.queue

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * When a source fades and when a faded one is deleted (Glossary → Faded source; spec → Queue → Fading). Only the clock
 * rules: which items they apply to is the queue's (`TabsController.sweep`). Every function takes the time it is asked
 * at, so a test sets the clock rather than waiting.
 *
 * - A source not touched (opened or scrolled) for [FADE_AFTER] fades. It **faded at** `touched + 3 days`, not when the
 *   app noticed, so the app being closed for a week changes nothing.
 * - A faded source is deleted [KEEP_FADED] after it faded: 33 days after it was last touched.
 * - [LAST_CHANCE] before that, Up next may offer it once ("Last chance: artist_x · Keep / Let go"). Whether it is
 *   offered, shown or answered changes nothing here: no answer means delete.
 */
object Fading {
  /** A source untouched for this long fades. */
  val FADE_AFTER: Duration = 3.days

  /** A faded source is kept this long, from when it faded. */
  val KEEP_FADED: Duration = 30.days

  /** How long before its deletion a faded source may be offered as a last chance. */
  val LAST_CHANCE: Duration = 7.days

  /** When a source last touched at [touched] fades. */
  fun fadesAt(touched: Instant): Instant = touched + FADE_AFTER

  /** Whether a source last touched at [touched] has gone unused for [FADE_AFTER] at [now]. */
  fun isFading(touched: Instant, now: Instant): Boolean = now >= fadesAt(touched)

  /** When a source that faded at [fadedAt] is deleted. */
  fun deletesAt(fadedAt: Instant): Instant = fadedAt + KEEP_FADED

  /** Whether a source that faded at [fadedAt] is to be deleted by [now]. */
  fun isDeleted(fadedAt: Instant, now: Instant): Boolean = now >= deletesAt(fadedAt)

  /**
   * The "Deleted in N days" count for a source that faded at [fadedAt]: whole days left, rounded up, so the last day
   * (and the hours before it) reads 1 and 30 reads at the moment it fades. 0 once it is due for deletion.
   */
  fun daysLeft(fadedAt: Instant, now: Instant): Int {
    val left = deletesAt(fadedAt) - now
    if (left <= Duration.ZERO) return 0
    val day = 1.days.inWholeSeconds
    return ((left.inWholeSeconds + day - 1) / day).toInt().coerceAtLeast(1)
  }

  /** "Deleted in 30 days", "Deleted in 1 day". */
  fun deletedIn(fadedAt: Instant, now: Instant): String {
    val days = daysLeft(fadedAt, now)
    return if (days == 1) "Deleted in 1 day" else "Deleted in $days days"
  }

  /** Whether a source that faded at [fadedAt] is within [LAST_CHANCE] of its deletion at [now] (and not yet deleted). */
  fun isLastChance(fadedAt: Instant, now: Instant): Boolean =
    !isDeleted(fadedAt, now) && deletesAt(fadedAt) - now <= LAST_CHANCE
}
