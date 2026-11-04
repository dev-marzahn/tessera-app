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
    assertEquals(10.seconds, limiter.parseRetryAfter("Thu, 29 Oct 2026 12:00:10 GMT"))
    assertEquals(null, limiter.parseRetryAfter("soon"))
    assertEquals(null, limiter.parseRetryAfter(null))
  }

  @Test
  fun missingRetryAfterPausesFor30Seconds() = runTest {
    val limiter = limiter()

    assertEquals(30.seconds, limiter.onRateLimited("a", null))
    limiter.acquire("a")
    assertEquals(30_000, currentTime)
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
}
