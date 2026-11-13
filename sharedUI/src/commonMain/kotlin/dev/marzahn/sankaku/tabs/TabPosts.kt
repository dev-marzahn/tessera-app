package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the queue page knows of a post a tab shows: whether it's [favourited], its [tags]' search names and its
 * [rating] code (`s`, `q` or `e`).
 */
class TabPost(val favourited: Boolean, val tags: List<String>, val rating: String? = null)

/** The posts the queue page's find, rating and "Close favourited" look at, from memory only: a tab never asks the API. */
fun interface TabPosts {
  /** What's known of each of [ids]' posts, following favourite toggles. A post the app doesn't hold is absent. */
  fun of(ids: Set<String>): Flow<Map<String, TabPost>>

  companion object {
    /** Knows no post (tests, and screens without the app's caches). */
    val None = TabPosts { flowOf(emptyMap()) }
  }
}

/**
 * [TabPosts] from the posts the app has loaded (a grid's pages, a post's full copy), with each favourite toggle
 * since. A post tab that was never shown, opened from a page that has since left the cache, is absent.
 */
class CachedTabPosts(private val posts: PostsRepository, private val state: PostStateStore) : TabPosts {
  override fun of(ids: Set<String>): Flow<Map<String, TabPost>> = state.overrides.map { overrides ->
    val cached = posts.cachedPosts(ids)
    ids.mapNotNull { id ->
      val post = cached[id]
      val favourited = overrides[id]?.isFavourited ?: post?.isFavorited ?: return@mapNotNull null
      id to TabPost(favourited, post?.tagNames.orEmpty(), post?.rating)
    }.toMap()
  }
}

/**
 * Whether [tab] matches the tab list's find [query]: every word of it is part of the tab's title or of one of its
 * tags (a grid's search; a post's own tags from [post], and the search it was opened from). Case and `_` don't count,
 * so "blue hair" finds blue_hair.
 */
internal fun matchesFind(query: String, tab: TabState, post: TabPost?): Boolean {
  val words = findText(query).split(' ').filter { it.isNotEmpty() }
  if (words.isEmpty()) return true
  val tags = when (val top = tab.top) {
    is Route.Grid -> top.tags
    is Route.Post -> top.originTags + post?.tags.orEmpty()
    is Route.Books -> top.tags
    is Route.BookSeries -> top.tags
    else -> emptyList()
  }
  val text = (listOf(tab.title) + tags).joinToString(" ", transform = ::findText)
  return words.all { it in text }
}

internal fun findText(text: String) = text.lowercase().replace('_', ' ')
