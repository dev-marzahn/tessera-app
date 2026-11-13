package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SourceKey

/**
 * A source's search as one list of words (#385): a grid's tags as they are, a Books search's tags after [BOOKS_SEARCH].
 * It's what a closed source's kept items keep as `closedSource` in `tabs.json` (#184), so the file needs no new field,
 * and what makes a Books search's [SourceKey] differ from a grid's on the same tags. Null for anything not a source's
 * screen. A series' books are under the Books search they were opened from, so its kept books are "from Books · …
 * (closed)" when it closes.
 */
internal fun sourceSearch(route: Route?): List<String>? = when (route) {
  is Route.Grid -> route.tags
  is Route.Books -> listOf(BOOKS_SEARCH) + route.tags
  is Route.BookSeries -> listOf(BOOKS_SEARCH) + route.tags
  else -> null
}

/** The screen a [sourceSearch] is the search of: a Books list after [BOOKS_SEARCH], else a grid. */
internal fun searchSource(search: List<String>): Route =
  if (search.firstOrNull() == BOOKS_SEARCH) Route.Books(search.drop(1)) else Route.Grid(search)

/**
 * The key that makes [route] one source per search ([SourceKey], #174), or null when it's no source's or merges with
 * none: a grid on a source's search, and a Books search, whose key always has [BOOKS_SEARCH] in it, so plain Books is a
 * search of its own (one Books item, not one per open). A series' books merge with none.
 */
internal fun sourceKeyOf(route: Route?): SourceKey? = when (route) {
  is Route.Grid -> if (QueueKind.ofGrid(route.tags) == QueueKind.Source) SourceKey.of(route.tags) else null
  is Route.Books -> SourceKey.of(listOf(BOOKS_SEARCH) + route.tags)
  else -> null
}

/**
 * The word that marks a Books search in a [sourceSearch]. No tag can be it: a tag never starts with `:`, and a search
 * typed with it is a grid's, whose words are tags.
 */
internal const val BOOKS_SEARCH = ":books"

/**
 * The id a book is seen by in the seen store (#385), which holds post ids: a book's id is a pool's, which may be some
 * post's too, so it's kept apart. On-device data, never logged.
 */
internal fun bookSeenId(bookId: String): String = "book:$bookId"
