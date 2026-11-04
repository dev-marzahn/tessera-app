package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.Feed
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Where a grid was (#162): [post], its first fully visible post; [cursor] and [page], the [PageCursor] of the page that
 * post came in on (null and 1 for the first page); and [time], when the post was uploaded (UTC seconds), which finds
 * the place again with `date:` when the cursor no longer works. Saved with the grid's tab, so it's on-device data like
 * the tab's routes: never logged. The defaults are left out of the JSON.
 */
@Serializable
data class GridPlace(val post: String, val cursor: String? = null, val page: Int = 1, val time: Long? = null) {
  internal val pageCursor: PageCursor get() = PageCursor(cursor, page)
}

/**
 * Where a grid opens to show a [GridPlace]: its first page is [cursor] of the search [tags], the grid's own or the same
 * narrowed by `date:` ([GridPlace.datedSearch]). [midway] when it doesn't start at the top of the grid's own search,
 * so newer posts aren't above it.
 */
data class GridStart(val tags: List<String>, val cursor: PageCursor) {
  val midway: Boolean get() = cursor != PageCursor() || tags.any(::isDate)
}

internal const val DATE_PREFIX = "date:"

private fun isDate(tag: String) = tag.removePrefix("-").startsWith(DATE_PREFIX, ignoreCase = true)

/** Whether [tags] are the For you feed, which is ordered for the account and paged by number. */
internal fun isForYou(tags: List<String>): Boolean = tags.any { Feed.of(it) == Feed.ForYou }

/**
 * Whether [tags] list posts newest first by upload time, so `date:` can find a place in them (#160's findings on
 * Following's order, PR #168): no `order:` other than the default, not For you, and no `date:` of their own.
 */
internal fun timeOrdered(tags: List<String>): Boolean =
  !isForYou(tags) && tags.none { isDate(it) || it.removePrefix("-").startsWith("order:", ignoreCase = true) }

/**
 * [tags] narrowed to posts uploaded before the end of [time]'s UTC hour: `date:` works to the hour, its end is
 * exclusive, and a post of [time]'s own hour is in (#160, PR #168). The start is before Sankaku's first post.
 */
internal fun datedSearch(tags: List<String>, time: Long): List<String> {
  val end = Instant.fromEpochSeconds((time.floorDiv(SECONDS_PER_HOUR) + 1) * SECONDS_PER_HOUR)
  // Whole seconds, so ISO's form is `2026-11-01T14:00:00Z`; the trailing `Z` is dropped, as in the checked form.
  return tags + "${DATE_PREFIX}$DATE_FLOOR..${end.toString().removeSuffix("Z")}"
}

private const val SECONDS_PER_HOUR = 3_600L

private const val DATE_FLOOR = "2000-01-01"
