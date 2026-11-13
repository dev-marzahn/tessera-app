package dev.marzahn.sankaku.data

/**
 * The one order of a search's tags: filter tokens (favourites, rating, feed, order, minimum score) first, then the tags that pick
 * the content, each group in the order it was given. The search line ends with the content tags, so a filter added
 * last doesn't push them out of view (decision 2026-10-31). The draft, the route's search line and the request all
 * go through [filtersFirst].
 */
object SearchTags {
  /** What a filter token starts with, after any `-`. A new filter registers its prefix here. */
  val FILTER_PREFIXES: List<String> = listOf("fav:", "rating:", "feed:", "order:", "threshold:")

  /** The order a tag opened from a post searches by (decision 2026-10-31); a typed search keeps the API's default. */
  const val OPENED_TAG_ORDER: String = "order:popularity"

  fun isFilter(tag: String): Boolean {
    val body = tag.removePrefix("-")
    return FILTER_PREFIXES.any { body.startsWith(it, ignoreCase = true) }
  }

  /** [tags] with the filter tokens moved to the front; otherwise in the order given. */
  fun filtersFirst(tags: List<String>): List<String> {
    val (filters, content) = tags.partition(::isFilter)
    return filters + content
  }

  /** The search for [tag] opened from a post: the tag by popularity. */
  fun searchForOpenedTag(tag: String): List<String> = filtersFirst(listOf(tag, OPENED_TAG_ORDER))
}
