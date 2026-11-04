package dev.marzahn.sankaku.data

import io.ktor.http.Url
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * When a signed image URL stops working, from its `e` parameter (Unix seconds). Null for an unsigned or unparsable
 * URL. An expired URL on `s.` hangs instead of failing (discovery step 7), so callers check this before requesting.
 */
fun signedUrlExpiresAt(url: String): Instant? =
  runCatching { Url(url) }.getOrNull()?.parameters?.get("e")?.toLongOrNull()?.let(Instant::fromEpochSeconds)

/**
 * Expired, unsigned and unparsable URLs all count as unusable. A URL expiring within a minute counts as expired, so
 * a download doesn't start just before the deadline.
 */
fun isSignedUrlUsable(url: String, now: Instant): Boolean =
  signedUrlExpiresAt(url)?.let { it > now + 1.minutes } ?: false
