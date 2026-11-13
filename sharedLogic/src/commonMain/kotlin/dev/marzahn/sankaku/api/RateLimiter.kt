package dev.marzahn.sankaku.api

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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

  /**
   * Pauses [host] for [retryAfter], or [defaultPause] when it's missing or unreadable. Returns the pause. A pause
   * already running that ends later is kept: requests in flight at the first 429 can bring shorter ones.
   */
  suspend fun onRateLimited(host: String, retryAfter: String?): Duration {
    val pause = parseRetryAfter(retryAfter) ?: defaultPause
    mutex.withLock {
      val until = timeSource.markNow() + pause
      val running = pausedUntil[host]
      if (running == null || until > running) pausedUntil[host] = until
    }
    return pause
  }

  internal fun parseRetryAfter(value: String?): Duration? = retryAfterPause(value, clock)

  companion object {
    val DEFAULT_PAUSE = 30.seconds

    /**
     * The shortest pause after a 429. Sankaku's `Retry-After` can be `0` while its `x-ratelimit-reset` is 1 to 3 s
     * ahead (`docs/api.md` → Rate limits), so a request sent at once could meet another 429.
     */
    val MIN_PAUSE = 1.seconds

    /** The longest: a bogus `Retry-After` (a huge number, a date years ahead) must not pause a host for good. */
    val MAX_PAUSE = 10.minutes

    /**
     * `sankakuapi.com` allows 15 requests in any ~2 s, for a free and a paid account alike (`:cli --rate-probe`,
     * 2026-11-12, `docs/api.md` → Rate limits), so burst + 2 × rate stays at 15: 6/s burst 3 sent 480 requests
     * without a 429, where 5/s burst 15 met one within 5 s. `s.` wasn't measured. A 429 still pauses its host.
     */
    val DEFAULT_LIMITS = mapOf(
      API_HOST to Limit(perSecond = 6.0, burst = 3),
      "s.sankakucomplex.com" to Limit(perSecond = 12.0, burst = 30),
    )
  }
}

/**
 * How long a 429 with this `Retry-After` pauses its host, between [RateLimiter.MIN_PAUSE] and
 * [RateLimiter.MAX_PAUSE]; null when it's missing or unreadable. The value is either delta-seconds (Sankaku sends
 * fractions, such as `0.352`; only digits with an optional fraction are read) or an HTTP date.
 */
internal fun retryAfterPause(value: String?, clock: Clock = Clock.System): Duration? {
  val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val pause = if (DELTA_SECONDS.matches(text)) {
    // A long run of digits is Infinity as a Double; the cap below bounds it.
    (text.toDouble() * 1000).let { if (it >= Long.MAX_VALUE) Duration.INFINITE else ceil(it).toLong().milliseconds }
  } else {
    val at = runCatching { text.fromHttpToGmtDate().timestamp }.getOrNull() ?: return null
    (at - clock.now().toEpochMilliseconds()).milliseconds
  }
  return pause.coerceIn(RateLimiter.MIN_PAUSE, RateLimiter.MAX_PAUSE)
}

private val DELTA_SECONDS = Regex("""\d+(\.\d+)?""")

/** Installed on both clients, so it sees API and image traffic. */
internal fun rateLimiterPlugin(limiter: RateLimiter): ClientPlugin<Unit> = createClientPlugin("RateLimiter") {
  onRequest { request, _ -> limiter.acquire(request.url.host) }
  onResponse { response ->
    // `sankakuapi.com` refuses a search past the tag limit with a 429 too (step d27): that refuses one search, so
    // nothing pauses (#520). Read from the body's code, which Ktor keeps for `checkResponse` to read again.
    if (response.status == HttpStatusCode.TooManyRequests && !isTagLimit(response)) {
      limiter.onRateLimited(response.call.request.url.host, response.headers[HttpHeaders.RetryAfter])
    }
  }
}

private suspend fun isTagLimit(response: HttpResponse): Boolean =
  response.call.request.url.host == API_HOST && tagLimit(response) != null
