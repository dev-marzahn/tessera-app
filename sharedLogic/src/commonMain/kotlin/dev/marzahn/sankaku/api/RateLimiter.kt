package dev.marzahn.sankaku.api

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Client-side token buckets per host, so the app never reaches Sankaku's own limit.
 * Hosts without an entry pass straight through, except while paused by a 429.
 * A 429 without a readable `Retry-After` pauses its host for [defaultPause].
 */
class RateLimiter(
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  private val limits: Map<String, Limit> = DEFAULT_LIMITS,
  private val clock: Clock = Clock.System,
  private val defaultPause: Duration = DEFAULT_PAUSE,
) {
  data class Limit(val perSecond: Double, val burst: Int)

  private class Bucket(var tokens: Double, var last: ComparableTimeMark)

  private val mutex = Mutex()
  private val buckets = mutableMapOf<String, Bucket>()
  private val pausedUntil = mutableMapOf<String, ComparableTimeMark>()

  /** Suspends while [host] is paused, then until one of its tokens is available. */
  suspend fun acquire(host: String) {
    while (true) {
      val wait = mutex.withLock {
        val now = timeSource.markNow()
        val pause = pausedUntil[host]
        if (pause != null && pause > now) {
          pause - now
        } else {
          pausedUntil.remove(host)
          val limit = limits[host] ?: return
          val bucket = buckets.getOrPut(host) { Bucket(limit.burst.toDouble(), now) }
          val refill = (now - bucket.last).inWholeNanoseconds / 1e9 * limit.perSecond
          bucket.tokens = (bucket.tokens + refill).coerceAtMost(limit.burst.toDouble())
          bucket.last = now
          if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return
          }
          ((1.0 - bucket.tokens) / limit.perSecond).seconds
        }
      }
      delay(wait)
    }
  }

  /** Pauses [host] for [retryAfter], or [defaultPause] when it's missing or unreadable. Returns the pause. */
  suspend fun onRateLimited(host: String, retryAfter: String?): Duration {
    val pause = parseRetryAfter(retryAfter) ?: defaultPause
    mutex.withLock { pausedUntil[host] = timeSource.markNow() + pause }
    return pause
  }

  /** `Retry-After` is either delta-seconds or an HTTP date. */
  internal fun parseRetryAfter(value: String?): Duration? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { return it.coerceAtLeast(0).seconds }
    val at = runCatching { text.fromHttpToGmtDate().timestamp }.getOrNull() ?: return null
    return (at - clock.now().toEpochMilliseconds()).coerceAtLeast(0).milliseconds
  }

  companion object {
    val DEFAULT_PAUSE = 30.seconds

    /** Starting values until discovery step 9 records Sankaku's limits. */
    val DEFAULT_LIMITS = mapOf(
      API_HOST to Limit(perSecond = 2.0, burst = 5),
      "s.sankakucomplex.com" to Limit(perSecond = 4.0, burst = 8),
    )
  }
}

/** Installed on both clients, so it sees API and image traffic. */
internal fun rateLimiterPlugin(limiter: RateLimiter): ClientPlugin<Unit> = createClientPlugin("RateLimiter") {
  onRequest { request, _ -> limiter.acquire(request.url.host) }
  onResponse { response ->
    if (response.status == HttpStatusCode.TooManyRequests) {
      limiter.onRateLimited(response.call.request.url.host, response.headers[HttpHeaders.RetryAfter])
    }
  }
}
