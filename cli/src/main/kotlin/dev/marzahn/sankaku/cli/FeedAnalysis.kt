package dev.marzahn.sankaku.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** A keyset page reduced to what the feed steps read: its post objects and its `meta.next`. */
class RawPage(val posts: List<JsonObject>, val next: String?) {
  val ids: List<String> get() = posts.mapNotNull { (it["id"] as? JsonPrimitive)?.content }
  val created: List<Long> get() = posts.mapNotNull { it.createdAt() }
}

/** `created_at.s`, the upload time in Unix seconds (`created_at` is `{json_class, s, n}`). */
fun JsonObject.createdAt(): Long? =
  ((this["created_at"] as? JsonObject)?.get("s") as? JsonPrimitive)?.content?.toLongOrNull()

/** Adjacent pairs where the later one is bigger: 0 for a list in newest-first order (ties allowed). */
fun <T : Comparable<T>> List<T>.descendingBreaks(): Int = zipWithNext().count { (a, b) -> a < b }

/** Pairs anywhere in the list where a later element is bigger than an earlier one. */
fun <T : Comparable<T>> List<T>.inversions(): Int {
  var count = 0
  for (i in indices) for (j in i + 1 until size) if (this[j] > this[i]) count++
  return count
}

/** A short digest, so a saved file can tell whether it saw an id or a page again without holding the ids. */
fun digest(text: String): String =
  MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

fun digestOf(ids: List<String>): String = digest(ids.joinToString(","))

/** The start of the UTC hour [epochSeconds] falls in. */
fun hourStart(epochSeconds: Long): Long = epochSeconds - Math.floorMod(epochSeconds, 3600L)

private val BOUND = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** A `date:` bound as the search takes it, from Unix seconds (UTC). */
fun dateBound(epochSeconds: Long): String = LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC).format(BOUND)

/** "45 min", "3 h", "2 d 4 h": an age for the replay notes. */
fun formatAge(seconds: Long): String = when {
  seconds < 3600 -> "${seconds / 60} min"
  seconds < 86400 -> "${seconds / 3600} h"
  else -> "${seconds / 86400} d ${seconds % 86400 / 3600} h"
}

/**
 * What a cursor is made of, without its values: its encoding, size, whether it reads as text or JSON, and whether it
 * holds the text of one of [ids] (the posts on the page it came with).
 */
fun cursorShape(cursor: String, ids: Collection<String>): String {
  val encoding = when {
    cursor.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> "base64url"
    cursor.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' } -> "base64"
    else -> "other"
  }
  val bytes = runCatching { Base64.getUrlDecoder().decode(cursor) }.getOrNull()
  val readable = bytes != null && bytes.isNotEmpty() && bytes.count { it in 32..126 } * 10 >= bytes.size * 9
  val text = if (readable) String(bytes!!, Charsets.UTF_8) else null
  val json = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
  val holdsId = ids.any { it in cursor || (text != null && it in text) }
  return listOf(
    "encoding=$encoding",
    "bytes=${bytes?.size ?: "-"}",
    "readable text=${yesNo(readable)}",
    "JSON=${yesNo(json != null)}",
    "holds a page id=${yesNo(holdsId)}",
  ).joinToString(", ")
}

fun yesNo(value: Boolean) = if (value) "yes" else "no"

/** A post of Following as [lateArrivals] compares it: its id's [digest], upload time, and its (first 5) tags' digests. */
class SeenPost(val id: String, val created: Long, val tags: Set<String>)

/**
 * Following now ([posts], newest first) against an earlier [head] (#539): the posts new in the span it covered, and of
 * those below its newest post, which carry a tag followed since ([follows] now, digests; null if unread) and which
 * arrived late: how many of the snapshot's posts sit above them, and how long after upload they turned up at the
 * least (the snapshot was taken after they were uploaded but didn't have them). Counts and ages only.
 */
fun lateArrivals(head: HeadSnapshot, posts: List<SeenPost>, follows: Set<String>?): String {
  val seen = head.seen.toSet()
  val fresh = posts.filter { it.created >= head.oldest && it.id !in seen }
  val below = fresh.filter { it.created < head.newest }
  val parts = mutableListOf("${fresh.size} new posts in the span it covered, ${below.size} of them below its newest post")
  val before = head.follows?.toSet()
  val added = if (before != null && follows != null) follows - before else null
  parts += when {
    before == null || follows == null -> "follows then or now unknown"
    else -> "follows since: ${added!!.size} added, ${(before - follows).size} removed"
  }
  val fromNewFollows = below.filter { post -> added != null && post.tags.any { it in added } }
  val late = below - fromNewFollows.toSet()
  if (added != null) parts += "${fromNewFollows.size} of those below carry a tag followed since (of their first 5 tags)"
  if (late.isNotEmpty()) {
    val depth = late.maxOf { post -> posts.count { it.id in seen && it.created > post.created } }
    val lags = late.map { head.savedAt - it.created }
    parts += "${late.size} late (no newly followed tag seen): up to $depth of the snapshot's posts above one, " +
      "turned up at least ${formatAge(lags.min())} to ${formatAge(lags.max())} after upload"
  }
  return parts.joinToString(", ")
}
