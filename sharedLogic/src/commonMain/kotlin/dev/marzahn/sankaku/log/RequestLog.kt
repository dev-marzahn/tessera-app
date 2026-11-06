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

    private val ID_PARENTS = setOf("posts", "books", "pools")

    /** Fixed path words that follow an id parent, such as `/v2/posts/keyset`. */
    private val LITERALS = setOf("keyset")
    private val MD5 = Regex("[0-9a-f]{32}")
    private val MD5_SHARD = Regex("^[0-9a-f]{2}$")

    /**
     * Drops the query and replaces ids and md5s: `/posts/abc/favorite` → `/posts/{id}/favorite`,
     * `/data/ab/cd/<md5>.jpg` → `/data/{md5}`.
     */
    fun template(path: String): String {
      val segments = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
      val md5Index = segments.indexOfFirst { MD5.containsMatchIn(it) }
      val out = segments.mapIndexedNotNull { i, segment ->
        when {
          i == md5Index -> "{md5}"
          i < md5Index && MD5_SHARD.matches(segment) -> null
          i > 0 && segments[i - 1] in ID_PARENTS && segment !in LITERALS -> "{id}"
          else -> segment
        }
      }
      return "/" + out.joinToString("/")
    }
  }
}
