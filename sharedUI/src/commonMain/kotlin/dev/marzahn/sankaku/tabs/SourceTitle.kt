package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.api.dto.TagNames
import dev.marzahn.sankaku.features.grid.Favourites
import dev.marzahn.sankaku.features.grid.Filter

/**
 * A grid's name (#233): the tags the user searched for, never the filter tokens ([SearchTags.isFilter]) that shape the
 * results. `order:popularity pipoyami` is "Pipoyami"; the order is in [filterNotes], for the subtitle. A search of filters
 * only has a short name of its own ([filterOnlyName]), "Browse" when none of them has one. [names] gives a tag's chip
 * name by its search key ([TagNameLookup]).
 */
internal fun sourceTitle(tags: List<String>, names: TagNameLookup = NoTagNames): String {
  val (filters, content) = tags.partition(SearchTags::isFilter)
  if (content.isEmpty()) return filterOnlyName(filters) ?: "Browse"
  return content.take(3).joinToString(" ") { contentName(it, names) }
}

/**
 * A tag's name as its search chip shows it ([dev.marzahn.sankaku.api.dto.TagSuggestion.displayName], from
 * [dev.marzahn.sankaku.data.TagsRepository.known]), by its search key: lowercase, without a leading `-`. Null for a tag
 * the app knows nothing about.
 */
internal typealias TagNameLookup = (String) -> String?

internal val NoTagNames: TagNameLookup = { null }

/**
 * A content tag by the tab-label rule: a link's `parent:` and a `date:` range in words, else the tag chip's name ("K-Suwabe"
 * for `k-suwabe`, #246), else the search key by [TagNames.ofKey] ("jien_(nikke)" is "Jien (Nikke)"). An excluded tag keeps
 * its `-` ("-Smile"). Any other `key:value` tag (`parent:none`) is search syntax and keeps its own text.
 */
private fun contentName(tag: String, names: TagNameLookup): String {
  childPostsLabel(tag)?.let { return it }
  dateRangeLabel(tag)?.let { return it }
  if (':' in tag) return tag
  val sign = if (tag.startsWith("-")) "-" else ""
  val key = tag.removePrefix(sign)
  return sign + (names(key.lowercase())?.takeIf { it.isNotBlank() } ?: TagNames.ofKey(key))
}

/**
 * "Child posts" for the `parent:<id>` tag of the Children strip's "All" tile (#342), so no title shows the post's id (#138). A typed
 * `parent:none` (posts without a parent) keeps its own text.
 */
private fun childPostsLabel(tag: String): String? =
  if (tag.startsWith("parent:") && tag != "parent:none") "Child posts" else null

/**
 * The name of a search with nothing but [filters]: its feed ("Following"), else favourites ("Favourites"), else its
 * order ("Popular", "Top"), else its minimum score; null when it has none of them (a rating alone is the plain grid).
 */
private fun filterOnlyName(filters: List<String>): String? {
  filters.firstNotNullOfOrNull { Feed.of(it)?.label }?.let { return it }
  filters.firstNotNullOfOrNull(Favourites::shortLabel)?.let { return it }
  order(filters)?.let { return it.title }
  return filters.firstNotNullOfOrNull { Filter.Threshold.optionFor(it)?.label }
}

/**
 * What the title of a source with content tags leaves out, for its subtitle: its feed, favourites and order, in words
 * ("following", "favourites", "popular"). Empty for a search of filters only, whose title already names them
 * ([filterOnlyName]). A rating or minimum score isn't noted: the row shows the rating, and neither changes what it is.
 */
internal fun filterNotes(tags: List<String>): List<String> {
  val (filters, content) = tags.partition(SearchTags::isFilter)
  if (content.isEmpty()) return emptyList()
  return listOfNotNull(
    filters.firstNotNullOfOrNull { Feed.of(it)?.label?.lowercase() },
    filters.firstNotNullOfOrNull(Favourites::shortLabel)?.lowercase(),
    order(filters)?.note,
  )
}

/** How an order reads as a title (alone) and as a note (after the content). */
private class OrderName(val title: String, val note: String)

private val ORDER_NAMES = mapOf(
  "order:popularity" to OrderName("Popular", "popular"),
  "order:quality" to OrderName("Top", "top quality"),
  "order:score" to OrderName("Top scored", "top scored"),
  "order:favcount" to OrderName("Most favourited", "most favourited"),
  "order:random" to OrderName("Random", "random"),
)

private fun order(filters: List<String>): OrderName? =
  filters.firstNotNullOfOrNull { ORDER_NAMES[it.lowercase()] }
