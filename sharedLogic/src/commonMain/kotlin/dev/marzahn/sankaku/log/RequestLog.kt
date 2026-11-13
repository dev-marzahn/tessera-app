package dev.marzahn.sankaku.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/** Never holds ids, md5s, query strings, headers or bodies, so the log isn't a viewing history. */
data class RequestLogEntry(
  val time: Instant,
  val method: String,
  val host: String,
  val pathTemplate: String,
  val status: Int?,
  val durationMs: Long,
  val errorClass: String?,
) {
  /** The status, or for a request that got no response, its error class. */
  val outcome: String get() = status?.toString() ?: errorClass ?: "Failed"

  /** No response, or an error status: Diagnostics shows these in the error colour. */
  val failed: Boolean get() = status.let { it == null || it >= 400 }
}

class RequestLog(private val clock: Clock = Clock.System, private val capacity: Int = CAPACITY) {
  private val _entries = MutableStateFlow<List<RequestLogEntry>>(emptyList())
  val entries: StateFlow<List<RequestLogEntry>> = _entries.asStateFlow()

  fun record(method: String, host: String, path: String, status: Int?, durationMs: Long, errorClass: String?) {
    val entry = RequestLogEntry(clock.now(), method, host, template(path), status, durationMs, errorClass)
    _entries.update { (it + entry).takeLast(capacity) }
  }

  companion object {
    const val CAPACITY = 200

    /**
     * The fixed words of every path the app requests: the `*Api.kt` endpoints and the image hosts' folders. Any other
     * segment is logged as `{id}`, so a new endpoint's id can't leak before its words are added here (#540).
     */
    private val PATH_WORDS = setOf(
      "v2", "posts", "post", "keyset", "favorite", "vote",
      "pools", "poolseriesv2", "reading", "readings", "destroy", "graphql",
      "tags", "autosuggestCreating", "tag-and-wiki", "name",
      "users", "me", "blacklist", "followings",
      "auth", "token", "sso", "token-exchange",
      "data", "sample", "preview", "o",
    )

    /**
     * Words that a value follows, with the only words that may stand in that slot: `/posts/keyset` but `/posts/{id}`.
     * A tag or id that happens to be a [PATH_WORDS] word, such as the tag `sample`, is still replaced.
     */
    private val VALUE_SLOTS = mapOf(
      "posts" to setOf("keyset"),
      "post" to emptySet(),
      "pools" to setOf("keyset", "readings"),
      "blacklist" to emptySet(),
      "name" to emptySet(),
    )
    private val MD5 = Regex("[0-9a-f]{32}")
    private val MD5_SHARD = Regex("^[0-9a-f]{2}$")

    /**
     * Drops the query, replaces md5s and keeps only [PATH_WORDS] outside [VALUE_SLOTS]:
     * `/posts/abc/favorite` → `/posts/{id}/favorite`, `/data/ab/cd/<md5>.jpg` → `/data/{md5}`,
     * `/tag-and-wiki/name/blue_hair` → `/tag-and-wiki/name/{id}`.
     */
    fun template(path: String): String {
      val segments = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
      val md5Index = segments.indexOfFirst { MD5.containsMatchIn(it) }
      val out = segments.mapIndexedNotNull { i, segment ->
        val slotWords = VALUE_SLOTS[segments.getOrNull(i - 1)]
        when {
          i == md5Index -> "{md5}"
          i < md5Index && MD5_SHARD.matches(segment) -> null
          slotWords != null -> if (segment in slotWords) segment else "{id}"
          segment in PATH_WORDS -> segment
          else -> "{id}"
        }
      }
      return "/" + out.joinToString("/")
    }
  }
}
