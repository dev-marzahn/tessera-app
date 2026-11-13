package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.json
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {
  private fun TestScope.limiter(vararg limits: Pair<String, RateLimiter.Limit>) =
    RateLimiter(testScheduler.timeSource, limits.toMap(), FixedClock)

  @Test
  fun requestsAboveTheRateWait() = runTest {
    val limiter = limiter("a" to RateLimiter.Limit(perSecond = 2.0, burst = 2))

    limiter.acquire("a")
    limiter.acquire("a")
    assertEquals(0, currentTime, "the burst passes at once")

    limiter.acquire("a")
    assertEquals(500, currentTime)
    limiter.acquire("a")
    assertEquals(1000, currentTime)
  }

  @Test
  fun hostsWithoutALimitPassStraightThrough() = runTest {
    val limiter = limiter("a" to RateLimiter.Limit(perSecond = 1.0, burst = 1))

    repeat(100) { limiter.acquire("b") }

    assertEquals(0, currentTime)
  }

  @Test
  fun rateLimitPausesOnlyThatHost() = runTest {
    val limiter = limiter()

    limiter.onRateLimited("a", "5")
    limiter.acquire("b")
    assertEquals(0, currentTime)

    limiter.acquire("a")
    assertEquals(5000, currentTime)
  }

  @Test
  fun retryAfterAcceptsSecondsAndHttpDates() = runTest {
    val limiter = limiter()

    assertEquals(12.seconds, limiter.parseRetryAfter("12"))
    // Sankaku's own 429s send fractions (2026-11-12): these must not fall back to the 30 s default.
    assertEquals(1.seconds + 966.milliseconds, limiter.parseRetryAfter("1.966"))
    assertEquals(10.seconds, limiter.parseRetryAfter("Thu, 29 Oct 2026 12:00:10 GMT"))
    assertEquals(null, limiter.parseRetryAfter("soon"))
    assertEquals(null, limiter.parseRetryAfter(null))
    // Only digits with an optional fraction.
    for (other in listOf("NaN", "Infinity", "1e30", "0x1p3", "-5", ".5")) assertEquals(null, limiter.parseRetryAfter(other), other)
  }

  @Test
  fun retryAfterIsHeldBetweenOneSecondAndTenMinutes() = runTest {
    val limiter = limiter()

    // Sankaku sent 0 and 0.006 while its window reset 1 to 3 s later.
    assertEquals(1.seconds, limiter.parseRetryAfter("0"))
    assertEquals(1.seconds, limiter.parseRetryAfter("0.352"))
    assertEquals(1.seconds, limiter.parseRetryAfter("Thu, 29 Oct 2026 11:00:00 GMT"))
    assertEquals(10.minutes, limiter.parseRetryAfter("86400"))
    assertEquals(10.minutes, limiter.parseRetryAfter("9".repeat(400)))
    assertEquals(10.minutes, limiter.parseRetryAfter("Thu, 29 Oct 2099 12:00:00 GMT"))
  }

  @Test
  fun missingRetryAfterPausesFor30Seconds() = runTest {
    val limiter = limiter()

    assertEquals(30.seconds, limiter.onRateLimited("a", null))
    limiter.acquire("a")
    assertEquals(30_000, currentTime)
  }

  @Test
  fun aFractionalRetryAfterPausesOnlyThatLong() = runTest {
    val limiter = limiter()

    limiter.onRateLimited("a", "1.5")
    limiter.acquire("a")

    assertEquals(1500, currentTime)
  }

  @Test
  fun aShorterLaterPauseKeepsTheRunningOne() = runTest {
    val limiter = limiter()

    limiter.onRateLimited("a", "5")
    limiter.onRateLimited("a", "1")
    limiter.acquire("a")
    assertEquals(5000, currentTime)

    limiter.onRateLimited("a", "1")
    limiter.onRateLimited("a", "3")
    limiter.acquire("a")
    assertEquals(8000, currentTime)
  }

  @Test
  fun a429ResponsePausesItsHost() = runTest {
    val limiter = limiter()
    var first = true
    val h = Harness(limiter = limiter) {
      if (first) {
        first = false
        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3"))
      } else {
        json("{}")
      }
    }

    h.mainClient.get("https://v.sankakucomplex.com/data/x.jpg?e=1")
    val before = currentTime
    limiter.acquire("v.sankakucomplex.com")

    assertEquals(3000, currentTime - before)
  }

  /**
   * The tag limit's 429 has neither `Retry-After` nor `x-ratelimit-*`, unlike the rate limit's (`docs/api.md`): it
   * refuses one search, so the host isn't paused (#520).
   */
  @Test
  fun theTagLimit429DoesNotPauseTheApi() = runTest {
    val limiter = limiter()
    val h = Harness(limiter = limiter) {
      json("""{"success":false,"code":"snackbar__anonymous_tags-limit","param":"4"}""", HttpStatusCode.TooManyRequests)
    }

    runCatching { h.posts.keyset(listOf("a", "b", "c", "d", "e")) }
    val before = currentTime
    limiter.acquire(API_HOST)

    assertEquals(0, currentTime - before)
  }

  /** Decided by the body's code, not the missing headers: any other 429 from the API still pauses it (#520 review). */
  @Test
  fun aRateLimit429WithoutHeadersStillPausesTheApi() = runTest {
    val limiter = limiter()
    val h = Harness(limiter = limiter) {
      json("""{"success":false,"code":"snackbar__account_regular_rate-limit"}""", HttpStatusCode.TooManyRequests)
    }

    runCatching { h.posts.keyset(emptyList()) }
    val before = currentTime
    limiter.acquire(API_HOST)

    assertEquals(30_000, currentTime - before)
  }
}
