package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTagsTest {
  @Test
  fun filterTokensMoveToTheFrontInTheirOwnOrder() {
    assertEquals(
      listOf("rating:s", "order:popularity", "threshold:3", "feed:following", "blue_hair", "-smile"),
      SearchTags.filtersFirst(listOf("blue_hair", "rating:s", "-smile", "order:popularity", "threshold:3", "feed:following")),
    )
  }

  @Test
  fun excludedAndUpperCaseFiltersCount() {
    assertTrue(SearchTags.isFilter("-rating:q"))
    assertTrue(SearchTags.isFilter("ORDER:score"))
    // The favourites filter (#90), either way, and any user's favourites.
    assertTrue(SearchTags.isFilter("fav:me"))
    assertTrue(SearchTags.isFilter("-fav:me"))
    assertEquals(listOf("-fav:me", "blue_hair"), SearchTags.filtersFirst(listOf("blue_hair", "-fav:me")))
    assertFalse(SearchTags.isFilter("blue_hair"))
    assertFalse(SearchTags.isFilter("parent:123"))
  }

  @Test
  fun onlyFiltersOrOnlyContentKeepTheirOrder() {
    assertEquals(listOf("order:quality", "rating:s"), SearchTags.filtersFirst(listOf("order:quality", "rating:s")))
    assertEquals(listOf("a", "b"), SearchTags.filtersFirst(listOf("a", "b")))
    assertEquals(emptyList(), SearchTags.filtersFirst(emptyList()))
  }

  @Test
  fun anOpenedTagSearchesByPopularity() {
    assertEquals(listOf("order:popularity", "blue_hair"), SearchTags.searchForOpenedTag("blue_hair"))
  }
}
