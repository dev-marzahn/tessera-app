package dev.marzahn.sankaku.features.grid

import androidx.paging.compose.LazyPagingItems
import dev.marzahn.sankaku.api.dto.PostDto

/*
 * The grid's list read by an index that may be past its end. A reload that shortens the list (signing in, #472; a
 * refresh, #488) lands before everything holding an index from the old list has caught up: a pager's key map is
 * rebuilt with its old page count, a close that waited on a scroll reads the index it started from. Paging's own
 * `peek` and `get` throw there; these give null.
 */

/** The item at [index] without loading anything, or null past the list's end. */
internal fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? = if (index in 0 until itemCount) peek(index) else null

/** The item at [index], loading the next page near the end as `items[index]` does, or null past the list's end. */
internal fun <T : Any> LazyPagingItems<T>.getOrNull(index: Int): T? = if (index in 0 until itemCount) get(index) else null

/**
 * The phone viewer's key for its [page], a position in [order]: the post's id, so a page that loads mid-way and slots
 * its posts in before the current one leaves the pager on it; the page itself where there's no post (yet, or any more).
 */
internal fun LazyPagingItems<PostDto>.viewerPageKey(page: Int, order: ReadingOrder): Any = peekOrNull(order.indexAt(page))?.id ?: page
