package dev.marzahn.sankaku

import androidx.navigation3.runtime.NavKey
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.countedPosition
import kotlinx.serialization.Serializable

/** Everything a tab can show. Books arrive with M3. */
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
   * Settings, in a desktop tab of its own (#205): always alone in its tab, and one tab at most
   * ([TabsController.openSettings]). It's never saved in `tabs.json`, so the file and an older app reading it are
   * unchanged. A phone shows Settings as a page over the app instead, never as this route.
   */
  @Serializable
  data object Settings : Route
}

/**
 * The search the top bar shows on this screen: a grid's own, or for a post, the search it was opened from. Filter
 * tokens first ([SearchTags.filtersFirst]), however the route was made (a tab saved before the rule included).
 */
val Route.searchTags: List<String>
  get() = SearchTags.filtersFirst(
    when (this) {
      is Route.Grid -> tags
      is Route.Post -> originTags
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

/** Acts on the active tab of [tabs]. */
class TabsNavigator(private val tabs: TabsController) : Navigator {
  private val backStack get() = tabs.activeTab.backStack

  /**
   * A search opens in a new tab in front of this one's block unless this tab shows an untouched grid, which it
   * replaces ([TabsController.search]). Touched is the tab's flag ([TabState.topIsTouchedGrid]), saved with it, so a
   * grid the queue page covered, or a relaunch restored, is still touched (#245). A post opens alone, as a lone post right after this tab
   * ([TabsController.openLone], #181): only a tap on a grid cell opens one over its grid, and that's the grid's viewer,
   * not a route. Settings (desktop) opens in its own tab right after this one's block, or shows the one open
   * ([TabsController.openSettings], #205).
   */
  override fun goTo(route: Route) {
    when (route) {
      is Route.Grid -> tabs.search(route)
      is Route.Post -> tabs.openLone(route)
      Route.Settings -> tabs.openSettings(background = false)
    }
  }

  /**
   * Back within the tab; on a lone post, which has nothing under it, the previous queue item (#181); on a grid a search
   * opened from an item, which has nothing under it either, that grid closes and the item is shown again (#349,
   * [TabsController.closeBack]; Z reopens it). Touch does both in `QueueSwipeHost`, with Undo on the pill. On a
   * Settings tab (desktop), it closes and the tab it was opened from is shown again ([TabsController.closeSettings],
   * #205).
   */
  override fun goBack() {
    when {
      backStack.size > 1 -> tabs.activeTab.pop()
      backStack.lastOrNull() is Route.Settings -> if (tabs.closeSettings(tabs.activeTab.id) != null) return
      backStack.lastOrNull() is Route.Post -> if (tabs.previous()) return
      else -> if (tabs.closeBack(tabs.activeTab.id) != null) return
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
   * ([TabsController.keepAgain]).
   * Anything else opens "later", at the end of the session's tabs ([TabsController.open]); a search whose source is
   * already open opens nothing and returns that source's position (one source per search, #174), as does Settings while
   * its tab is open (#205).
   */
  override fun openInNewTab(route: Route): Int? {
    if (route is Route.Settings) {
      tabs.openSettings(background = true)?.let { open -> return existingPosition(tabs.tabs.value, tabs.tabs.value.indexOf(open)) }
    } else if (route is Route.Post) {
      tabs.indexOfPost(route.id)?.let { index ->
        tabs.keepAgain(tabs.tabs.value[index].id)
        return existingPosition(tabs.tabs.value, index)
      }
      tabs.openInBlock(listOf(route))
    } else {
      tabs.open(route, background = true)?.let { source -> return existingPosition(tabs.tabs.value, tabs.tabs.value.indexOf(source)) }
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
