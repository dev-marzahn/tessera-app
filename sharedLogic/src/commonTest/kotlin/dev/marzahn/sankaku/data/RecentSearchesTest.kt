package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertEquals

class RecentSearchesTest {
  @Test
  fun aRepeatedSearchMovesToTheFrontOnce() {
    val recent = RecentSearches()
    recent.add(listOf("a"))
    recent.add(listOf("b", "rating:s"))
    recent.add(listOf("a"))

    assertEquals(listOf(listOf("a"), listOf("b", "rating:s")), recent.searches.value)
  }

  @Test
  fun keepsOnlyTheNewestAndSkipsEmptySearches() {
    val recent = RecentSearches()
    recent.add(emptyList())
    repeat(RecentSearches.MAX + 2) { recent.add(listOf("t$it")) }

    assertEquals(RecentSearches.MAX, recent.searches.value.size)
    assertEquals(listOf("t${RecentSearches.MAX + 1}"), recent.searches.value.first())
  }
}
