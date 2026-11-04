package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.Feed

/**
 * The tag lists behind the fixed grids (sidebar, account menu). Each opens a clean slate: nothing from the current
 * search carries over. Signed out, the grid still adds the locked `rating:s`, as it does for every search.
 * Popular and Top are keyset tags (discovery `d1-popular`, `d1-top`), so they need no date range. The web app's
 * Trending is Popular. For you and Following are feeds (see [Feed]).
 */
object GridPresets {
  fun favourites(name: String): List<String> = listOf("fav:$name")

  val forYou: List<String> = listOf(Feed.ForYou.tag)

  val following: List<String> = listOf(Feed.Following.tag)

  val popular: List<String> = listOf("order:popularity")

  val top: List<String> = listOf("order:quality")
}
