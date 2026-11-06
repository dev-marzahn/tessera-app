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
