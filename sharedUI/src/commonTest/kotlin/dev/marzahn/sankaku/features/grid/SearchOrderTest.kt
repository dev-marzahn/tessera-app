package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.searchTags
import dev.marzahn.sankaku.tabs.tabTitle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Filter tokens first in the search line and the request, whatever order the route has (a tab saved before it). */
class SearchOrderTest {
  private val saved = Route.Grid(listOf("blue_hair", "order:popularity", "smile"))

  @Test
  fun theSearchLineShowsFiltersFirst() {
    assertEquals(listOf("order:popularity", "blue_hair", "smile"), saved.searchTags)
    assertEquals(listOf("order:popularity", "blue_hair"), Route.Post("1", null, listOf("blue_hair", "order:popularity")).searchTags)
  }

  @Test
  fun theRequestSendsFiltersFirstWithTheLockedRating() {
    assertEquals(listOf("order:popularity", "rating:s", "blue_hair", "smile"), searchedTags(saved.tags, signedIn = false, safeOnly = false))
    assertEquals(listOf("order:popularity", "blue_hair", "smile"), searchedTags(saved.tags, signedIn = true, safeOnly = false))
  }

  @Test
  fun aTabIsNamedByItsContentTags() {
    assertEquals("Blue Hair Smile", tabTitle(saved))
    assertEquals("Top", tabTitle(Route.Grid(listOf("order:quality"))))
  }
}
