package dev.marzahn.sankaku

import androidx.navigation3.runtime.NavKey
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.backsLikeALonePost
import dev.marzahn.sankaku.tabs.countedPosition
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.alreadySpoken
import kotlinx.serialization.Serializable

/** Everything a tab can show. */
@Serializable
sealed interface Route : NavKey {
  /**
   * [at] marked the copy of a grid a post opened in the background was put over, before #181 made every post a lone
   * post. Only tabs saved before then have it, and `loneAtLaunch` drops those grids; nothing sets it now.
   */
  @Serializable
  data class Grid(val tags: List<String>, val at: String? = null) : Route

  /**
   * A post in a tab of its own. [md5] finds its cached preview for the placeholder; a parent link doesn't know it.
   * [originTags] is the search of the grid it was opened from. [label] names it in the tab strip (its characters or
   * series), so a tab is recognisable before it loads. [rating] is its rating code (`s`, `q` or `e`) when it was opened
   * from a loaded post, so the queue page can blur or hide it before it loads (#177); a parent link doesn't know it.
   */
  @Serializable
  data class Post(
    val id: String,
    val md5: String?,
    val originTags: List<String>,
    val label: String? = null,
    val rating: String? = null,
  ) : Route

  /**
   * The Books list (#380) for [tags], as [dev.marzahn.sankaku.data.BookSearch] keeps a Books search: no order is
   * Popular. A queue source like a grid's search (#385), one per search, saved in `tabs.json` like a grid.
   */
  @Serializable
  data class Books(val tags: List<String> = emptyList()) : Route

  /**
   * A series' books (#380), in the Books list's row style, opened from its row in the Books list searching [tags].
   * [title] names it before it loads. On top of a Books tab, the tab is still a source, of no one search (#385).
   */
  @Serializable
  data class BookSeries(val id: String, val title: String, val tags: List<String> = emptyList()) : Route

  /**
   * A kept book (#385): the book [id] in the reader, alone in its tab, as a lone post is. [title] is its name, the
   * tab's title; [cover] its cover post's md5, for the queue row's and the tab's thumbnail (cache-only); [rating] its
   * rating code, so the queue page can blur or hide it before it loads.
   */
  @Serializable
  data class Book(val id: String, val title: String? = null, val cover: String? = null, val rating: String? = null) : Route
}

/** Whether [this] is a Books screen: the list or a series' books (not a kept book, which is the reader). */
val Route.isBooks: Boolean get() = this is Route.Books || this is Route.BookSeries

/** Whether [this] is alone in its tab and kept from where it was opened: a lone post or a kept book (#181, #385). */
val Route.isLone: Boolean get() = this is Route.Post || this is Route.Book

/**
 * The search the top bar shows on this screen: a grid's own, or for a post, the search it was opened from. Filter
 * tokens first ([SearchTags.filtersFirst]), however the route was made (a tab saved before the rule included).
 */
val Route.searchTags: List<String>
  get() = SearchTags.filtersFirst(
    when (this) {
      is Route.Grid -> tags
      is Route.Post -> originTags
      is Route.Books -> tags
      is Route.BookSeries -> tags
      else -> emptyList()
    },
  )

interface Navigator {
  fun goTo(route: Route)

  fun goBack()

  fun replaceTop(route: Route)

  /**
   * Opens [route] in a background tab, unless it's a post some tab already shows (the grid's "In tab" mark) or a search
   * whose source is open: then it opens nothing and returns that tab's position in the strip, from 1. Returns null
   * when it opened a tab.
   */
  fun openInNewTab(route: Route): Int?
}

/**
 * Acts on the active tab of [tabs]. A search whose source is open with exactly its filters (the same order and rating
 * too, [TabsController.exactSourceFor]) says "Already item N" ("Already in tab N" on desktop) through [messages], both
 * when it's shown ([goTo]) and when it opens nothing in the background ([openInNewTab]). One with other filters goes to
 * the same source and says nothing.
 */
class TabsNavigator(private val tabs: TabsController, private val messages: Messages? = null) : Navigator {
  private val backStack get() = tabs.activeTab.backStack

  // The open source [route] is "already" in the queue as, looked up before opening (which restores a faded one): exactly
  // its search, and not the item shown.
  private fun alreadyFor(route: Route): TabState? = tabs.exactSourceFor(route)?.takeIf { it !== tabs.activeTab }

  private fun sayAlready(source: TabState) {
    val index = tabs.tabs.value.indexOf(source).takeIf { it >= 0 } ?: return
    messages?.show(alreadySpoken(existingPosition(tabs.tabs.value, index)))
  }

  /**
   * A search opens in a new tab in front of this one's block unless this tab shows an untouched grid, which it
   * replaces ([TabsController.search]). Touched is the tab's flag ([TabState.topIsTouchedGrid]), saved with it, so a
   * grid the queue page covered, or a relaunch restored, is still touched (#245). A post opens alone, as a lone post right after this tab
   * ([TabsController.openLone], #181): only a tap on a grid cell opens one over its grid, and that's the grid's viewer,
   * not a route; a kept book (#385) opens alone the same way. A Books search is a source as a grid's search is (#385):
   * "Already item N" when its source is open with exactly its filters.
   */
  override fun goTo(route: Route) {
    when (route) {
      is Route.Grid -> {
        val already = alreadyFor(route)
        tabs.search(route)
        already?.let(::sayAlready)
      }
      is Route.Post -> tabs.openLone(route)
      is Route.Book -> tabs.openLone(route)
      is Route.Books, is Route.BookSeries -> {
        val already = alreadyFor(route)
        tabs.openBooks(route)
        already?.let(::sayAlready)
      }
    }
  }

  /**
   * Back within the tab; on a lone post, which has nothing under it, the previous queue item (#181); on a grid a search
   * opened from an item, which has nothing under it either, that grid closes and the item is shown again (#349,
   * [TabsController.closeBack]; Z reopens it). A series alone in its tab with no item to close back to goes to the
   * previous item as a lone post does (#479, [backsLikeALonePost]). Touch does all three in `QueueSwipeHost`, with Undo
   * on the pill.
   */
  override fun goBack() {
    val tab = tabs.activeTab
    when {
      backStack.size > 1 -> tab.pop()
      tabs.openerOf(tab) != null -> if (tabs.closeBack(tab.id) != null) return
      tab.backsLikeALonePost -> if (tabs.previous()) return
    }
    tabs.touchActive()
  }

  override fun replaceTop(route: Route) {
    tabs.activeTab.replaceTop(route)
    tabs.touchActive()
  }

  /**
   * A post opens alone (a lone post, #181) at the end of this tab's block ([TabsController.openInBlock]); its grid is
   * the block's head, the previous items. A post some tab already shows opens nothing and is kept again there
   * ([TabsController.keepAgain]). A book is kept the same way (#385): from its Books search, in that search's block.
   * Anything else opens "later", at the end of the session's tabs ([TabsController.open]); a search whose source is
   * already open opens nothing and returns that source's position (one source per search, #174).
   */
  override fun openInNewTab(route: Route): Int? {
    if (route.isLone) {
      tabs.indexOfLone(route)?.let { index ->
        tabs.keepAgain(tabs.tabs.value[index].id)
        return existingPosition(tabs.tabs.value, index)
      }
      tabs.openInBlock(listOf(route))
    } else {
      val already = alreadyFor(route)
      tabs.open(route, background = true)?.let { source ->
        if (already === source) sayAlready(source)
        return existingPosition(tabs.tabs.value, tabs.tabs.value.indexOf(source))
      }
    }
    return null
  }
}

/**
 * The position (from 1) [TabsNavigator.openInNewTab] gives the open item at [index] of [tabs]: as the pill counts it
 * ([countedPosition]), since a feed before it isn't counted (#265, and since #327 a feed can stand anywhere). A feed
 * itself isn't counted, so it gives the counted item before it, on the same scale, and 1 when it stands first, never 0.
 */
internal fun existingPosition(tabs: List<TabState>, index: Int): Int = countedPosition(tabs, index).coerceAtLeast(1)
