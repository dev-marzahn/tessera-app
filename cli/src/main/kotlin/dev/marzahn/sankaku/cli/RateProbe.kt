package dev.marzahn.sankaku.cli

import dev.marzahn.sankaku.api.API_BASE
import dev.marzahn.sankaku.api.API_HOST
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.HeaderSet
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.probe
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.SignInResult
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import java.util.Collections
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `--rate-probe`: finds `sankakuapi.com`'s rate limit for the signed-in test account. Sends the app's grid request
 * (keyset, POSTS, `rating:s`, `limit=40`) at rising rates, open loop (each request on its own schedule, up to 64 in
 * flight, so the server's latency doesn't cap the rate), with no client buckets. Stops at the first 429, or at a level
 * where more than 10% of requests fail otherwise, then measures how long the 429 lasts. Prints counts, statuses,
 * latencies and rate-limit header values only: never tokens, queries or post fields.
 */
class RateProbe(
  private val env: Map<String, String>,
  /** Requests per second, [LEVEL_SECONDS] each (`--levels=2,4,6`). */
  private val levels: List<Double> = LEVELS,
  /** Requests sent at once before the levels, after 20 s idle (`--burst=20`); 0 skips it. */
  private val burst: Int = 0,
  private val levelSeconds: Int = LEVEL_SECONDS,
  /** A client bucket to try against the server (`--limiter=6,15`); null sends with none. */
  private val bucket: RateLimiter.Limit? = null,
) {
  private data class Sample(val level: Int, val startMs: Long, val latencyMs: Long, val status: String)

  private val engine = OkHttp.create {
    config { dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 }) }
  }

  // No buckets; a 429 would pause the host, which the probe wants to see for itself, so the pause is zero.
  private val client = createAuthClient(
    engine, RequestLog(), RateLimiter(limits = listOfNotNull(bucket?.let { API_HOST to it }).toMap(), defaultPause = 0.seconds),
    ConnectivityState(),
  )
  private val store = InMemoryTokenStore()
  private val session = AuthSession(AuthApi(client), store)
  private val start = TimeSource.Monotonic.markNow()
  private val samples = Collections.synchronizedList(mutableListOf<Sample>())
  private var request = 0

  suspend fun run() {
    val login = env["SANKAKU_LOGIN"].orEmpty()
    val password = env["SANKAKU_PASSWORD"].orEmpty()
    if (login.isBlank() || password.isBlank()) return println("SANKAKU_LOGIN / SANKAKU_PASSWORD not set")
    val signIn = session.signIn(login, password)
    if (signIn != SignInResult.Ok) return println("Sign-in failed: ${signIn::class.simpleName}")
    val token = session.currentBearerTokens()?.accessToken ?: return println("No token after sign-in")

    val first = send(token, level = 0)
    println("Warm-up: ${first.status}; rate headers: ${rateHeaders(first.headers).ifEmpty { "none" }}")
    if (burst > 0) {
      // After 20 s idle, so any bucket is full: [burst] requests at once.
      delay(20_000)
      coroutineScope { repeat(burst) { launch { send(token, level = 1000) } } }
      val sent = samples.filter { it.level == 1000 }.sortedBy { it.startMs }
      println("Burst of $burst at once: ${sent.groupingBy { it.status }.eachCount()}")
      firstLimitedPrinted = false
      samples.clear()
      delay(20_000)
    }
    println("Level: target/s · sent · achieved/s · statuses · latency p50/p95/max ms")

    var limited: Sample? = null
    for (rate in levels) {
      val levelStart = nowMs()
      coroutineScope {
        val jobs = mutableListOf<Job>()
        val count = (rate * levelSeconds).toInt()
        for (i in 0 until count) {
          val due = levelStart + (i * 1000L / rate).toLong()
          val wait = due - nowMs()
          if (wait > 0) delay(wait)
          if (samples.any { it.status == "429" }) break
          jobs += launch { send(token, rate.toInt()) }
        }
        jobs.joinAll()
      }
      val level = samples.filter { it.level == rate.toInt() }
      printLevel(rate, level, nowMs() - levelStart)
      limited = samples.firstOrNull { it.status == "429" }
      if (limited != null) break
      val failed = level.count { it.status != "200" }
      if (failed * 10 > level.size) {
        println("Stopped: $failed of ${level.size} failed at $rate/s without a 429.")
        break
      }
    }

    if (limited == null) {
      println("No 429 up to ${levels.last()}/s (${samples.size} requests in ${nowMs() / 1000} s).")
      return
    }
    val at = limited.startMs
    val window = { s: Int -> samples.count { it.startMs in (at - s * 1000)..at } }
    println(
      "First 429 at ${at / 1000.0} s, request #${samples.sortedBy { it.startMs }.indexOf(limited) + 1}: " +
        "${window(1)} sent in the second before it, ${window(10)} in 10 s, ${window(60)} in 60 s.",
    )
    measurePause(token)
  }

  /** After the first 429: one request every 5 s until one isn't a 429, for up to 10 minutes. */
  private suspend fun measurePause(token: String) {
    val from = nowMs()
    while (nowMs() - from < 600_000) {
      delay(5_000)
      val raw = send(token, level = -1)
      val elapsed = (nowMs() - from) / 1000
      if (raw.status != 429) {
        println("Recovered after about $elapsed s (status ${raw.status}).")
        return
      }
      println("Still 429 after $elapsed s; rate headers: ${rateHeaders(raw.headers).ifEmpty { "none" }}")
    }
    println("Still rate-limited after 10 minutes.")
  }

  private data class Sent(val status: Int, val headers: Map<String, String>)

  private var firstLimitedPrinted = false

  private suspend fun send(token: String, level: Int): Sent {
    val tag = TAGS[(request++) % TAGS.size]
    val startMs = nowMs()
    return try {
      val raw = client.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = token,
        query = mapOf("limit" to "40", "page" to "1", "hide_posts_in_books" to "in-larger-tags", "tags" to "rating:s $tag"),
      )
      samples += Sample(level, startMs, nowMs() - startMs, raw.status.toString())
      if (raw.status == 429 && !firstLimitedPrinted) {
        firstLimitedPrinted = true
        val headers = rateHeaders(raw.headers.filterKeys { !it.startsWith("browsing-limit") }).ifEmpty { "none" }
        println("429: hint '${raw.hint}'; headers: $headers; date=${raw.headers["date"]}, local epoch ${System.currentTimeMillis() / 1000}")
        val trailing = listOf(1, 2, 3, 5, 10).joinToString(", ") { s -> "${samples.count { it.startMs in (startMs - s * 1000)..startMs }} in $s s" }
        println("Sent before it: $trailing")
      }
      Sent(raw.status, raw.headers)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      samples += Sample(level, startMs, nowMs() - startMs, e::class.simpleName ?: "error")
      Sent(-1, emptyMap())
    }
  }

  private fun printLevel(rate: Double, level: List<Sample>, elapsedMs: Long) {
    val latencies = level.map { it.latencyMs }.sorted()
    fun pct(p: Double) = latencies.getOrElse(((latencies.size - 1) * p).toInt()) { 0 }
    val statuses = level.groupingBy { it.status }.eachCount().entries.joinToString(" ") { "${it.key}×${it.value}" }
    val achieved = "%.1f".format(level.size * 1000.0 / elapsedMs.coerceAtLeast(1))
    println("$rate · ${level.size} · $achieved · $statuses · ${pct(0.5)}/${pct(0.95)}/${latencies.lastOrNull() ?: 0}")
  }

  private fun rateHeaders(headers: Map<String, String>): String =
    headers.filterKeys { RATE_HEADER.containsMatchIn(it) }.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }

  private fun nowMs() = start.elapsedNow().inWholeMilliseconds

  fun close() = client.close()

  private companion object {
    const val LEVEL_SECONDS = 15
    val LEVELS = listOf(2.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0)
    val RATE_HEADER = Regex("rate|limit|retry|cf-cache")

    // Rotated so repeated requests aren't one cached query.
    val TAGS = listOf(
      "blue_hair", "long_hair", "short_hair", "blonde_hair", "black_hair", "red_eyes", "blue_eyes", "green_eyes",
      "smile", "looking_at_viewer", "outdoors", "sky", "flower", "hat", "school_uniform", "twintails",
    )
  }
}
