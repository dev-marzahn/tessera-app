package dev.marzahn.sankaku.update

import dev.marzahn.sankaku.APP_VERSION
import dev.marzahn.sankaku.PUBLIC_REPO
import dev.marzahn.sankaku.PUBLIC_REPO_URL
import dev.marzahn.sankaku.data.TextFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** GitHub's REST API. It's the only host the app contacts besides Sankaku's (spec → Security and privacy). */
const val GITHUB_API_BASE = "https://api.github.com"

/** The mirror's newest release: `GET /repos/{owner}/{repo}/releases/latest`. 404 while it has none. */
const val LATEST_RELEASE_URL = "$GITHUB_API_BASE/repos/$PUBLIC_REPO/releases/latest"

/** How often the check asks at most. */
val UPDATE_CHECK_INTERVAL = 1.days

/**
 * A release version in the app's `vX.Y.Z` scheme (`v100.0.0`; the leading `v` is optional). Compared part by part as
 * numbers, so `100.10.0` is newer than `100.9.0`. Anything else, such as `v100.1.0-rc1`, doesn't parse.
 */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
  override fun compareTo(other: ReleaseVersion): Int =
    compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

  override fun toString(): String = "$major.$minor.$patch"

  companion object {
    private val pattern = Regex("""v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})""")

    fun parse(text: String): ReleaseVersion? = pattern.matchEntire(text.trim())?.destructured?.let { (a, b, c) ->
      ReleaseVersion(a.toInt(), b.toInt(), c.toInt())
    }
  }
}

/** A newer release than this build: its [version] and its release page, [url]. */
data class AvailableUpdate(val version: ReleaseVersion, val url: String)

/**
 * The client for [LATEST_RELEASE_URL]. It shares only the engine with Sankaku's clients: none of their plugins, so no
 * bearer token, no Sankaku endpoint headers, and not the browser fingerprint (#204). OkHttp keeps no cookies unless a
 * client installs `HttpCookies`, which none does. GitHub asks for a User-Agent naming the app, and for its media type.
 */
fun createGitHubClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
  expectSuccess = false
  install(HttpTimeout) {
    connectTimeoutMillis = 10_000
    socketTimeoutMillis = 30_000
    requestTimeoutMillis = 30_000
  }
  defaultRequest {
    header(HttpHeaders.UserAgent, "Tessera/$APP_VERSION")
    header(HttpHeaders.Accept, "application/vnd.github+json")
    header("X-GitHub-Api-Version", "2022-11-28")
  }
}

@Serializable
private data class LatestRelease(
  @SerialName("tag_name") val tagName: String,
  @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
private data class SavedUpdates(
  val enabled: Boolean = true,
  val lastCheckEpochMs: Long? = null,
  val latestVersion: String? = null,
  val latestUrl: String? = null,
)

/**
 * The update check (#209, decision 16 in #196). Once [start]ed it asks GitHub for the mirror's latest release, without
 * a token, at launch at most once every [UPDATE_CHECK_INTERVAL]. A release newer than [current] becomes [available]:
 * a dot on ☰ and "Update available" in Settings → Updates, which opens the release page. It only tells; nothing is
 * downloaded.
 *
 * [enabled] is Settings → Updates' switch, on by default; off, nothing is asked and nothing shows. Every failure is
 * silent: no network, a 5xx, a rate limit or a body it can't read leave things as they were and the next launch tries
 * again. A 404 (the mirror has no release yet) counts as an answer: no update.
 *
 * Kept in [file] between runs: the switch, when GitHub last answered, and the newest release it named, so the dot
 * stays across launches inside the day. "Clear everything" leaves it alone: it's a preference, not history.
 */
class UpdateCheck(
  private val file: TextFile,
  private val client: HttpClient,
  private val clock: Clock,
  private val current: ReleaseVersion? = ReleaseVersion.parse(APP_VERSION),
) {
  /**
   * Everything kept, changed only through [change]: the switch (UI thread) and an answer (the app's scope) can land at
   * once, and each must keep the other's part.
   */
  private val saved = MutableStateFlow(
    file.read()?.let { runCatching { json.decodeFromString(SavedUpdates.serializer(), it) }.getOrNull() } ?: SavedUpdates(),
  )

  private val _enabled = MutableStateFlow(saved.value.enabled)
  val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

  private val _available = MutableStateFlow(availableIn(saved.value))

  /** The release to tell the user about, or null: none newer, or the switch is off. */
  val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

  fun setEnabled(on: Boolean) = change { it.copy(enabled = on) }

  /**
   * Checks each time [ready] and [enabled] both turn true: at launch once the first-launch screen is accepted (nothing
   * goes out before it), and again if the switch is turned on later. [checkIfDue] keeps it to once a day.
   */
  fun start(scope: CoroutineScope, ready: Flow<Boolean>) {
    scope.launch {
      combine(ready, _enabled) { isReady, on -> isReady && on }
        .distinctUntilChanged()
        .filter { it }
        .collect { checkIfDue() }
    }
  }

  /** Asks GitHub unless the switch is off or it last answered less than [UPDATE_CHECK_INTERVAL] ago. Never throws. */
  suspend fun checkIfDue() {
    if (!_enabled.value) return
    val now = clock.now()
    val last = saved.value.lastCheckEpochMs?.let(Instant::fromEpochMilliseconds)
    if (last != null && last <= now && now - last < UPDATE_CHECK_INTERVAL) return
    val release = try {
      fetchLatest() ?: return
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return
    }
    change {
      it.copy(
        lastCheckEpochMs = now.toEpochMilliseconds(),
        latestVersion = release.update?.version?.toString(),
        latestUrl = release.update?.url,
      )
    }
  }

  /** GitHub's answer: [update] is null when it has no release (404) or one this app can't read. Null on any failure. */
  private class Answer(val update: AvailableUpdate?)

  private suspend fun fetchLatest(): Answer? {
    val response = client.get(LATEST_RELEASE_URL)
    return when (response.status.value) {
      200 -> {
        val body = json.decodeFromString(LatestRelease.serializer(), response.bodyAsText())
        Answer(ReleaseVersion.parse(body.tagName)?.let { AvailableUpdate(it, releasePageUrl(body.htmlUrl)) })
      }
      404 -> Answer(null)
      else -> null
    }
  }

  /**
   * Applies [transform] atomically, then shows and writes the newest state. The switch (UI thread) and an answer (the
   * app's scope) can change it at once: each caller publishes again until the state it published is still the newest,
   * so whichever finishes last leaves [enabled], [available] and the file matching it.
   */
  private fun change(transform: (SavedUpdates) -> SavedUpdates) {
    saved.update(transform)
    do {
      val shown = saved.value
      _enabled.value = shown.enabled
      _available.value = availableIn(shown)
      // The state has already changed for this run; a full disk or locked file only costs it the next restart.
      runCatching { file.write(json.encodeToString(SavedUpdates.serializer(), shown)) }
    } while (saved.value !== shown)
  }

  /** The kept release, if the switch is on and it's newer than [current]. */
  private fun availableIn(state: SavedUpdates): AvailableUpdate? {
    if (!state.enabled || current == null) return null
    val version = state.latestVersion?.let(ReleaseVersion::parse)?.takeIf { it > current } ?: return null
    return AvailableUpdate(version, releasePageUrl(state.latestUrl))
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }

    /** GitHub's page for the release, only if it's on the mirror; otherwise the mirror's latest release page. */
    fun releasePageUrl(htmlUrl: String?): String =
      htmlUrl?.takeIf { it.startsWith("$PUBLIC_REPO_URL/releases/") } ?: "$PUBLIC_REPO_URL/releases/latest"
  }
}
