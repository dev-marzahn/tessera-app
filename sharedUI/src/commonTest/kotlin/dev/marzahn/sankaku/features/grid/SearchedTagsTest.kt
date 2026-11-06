package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.data.GridPresets
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which tags a search sends; their order is `SearchOrderTest`'s. */
class SearchedTagsTest {
  @Test
  fun withSafeOnlyEverySearchIsSentAsGOnlyWhenSignedIn() {
    val g = Rating.G.tag
    assertSent(listOf("blue_hair", g), searchedTags(listOf("blue_hair", "rating:e"), signedIn = true, safeOnly = true))
    assertSent(listOf("blue_hair", g), searchedTags(listOf("blue_hair", "-rating:s"), signedIn = true, safeOnly = true))
    assertSent(listOf(g), searchedTags(emptyList(), signedIn = true, safeOnly = true))
    assertSent(GridPresets.forYou + g, searchedTags(GridPresets.forYou, signedIn = true, safeOnly = true))
  }

  @Test
  fun withoutSafeOnlyASignedInSearchKeepsItsRating() {
    assertSent(listOf("blue_hair", "rating:e"), searchedTags(listOf("blue_hair", "rating:e"), signedIn = true, safeOnly = false))
  }

  private fun assertSent(expected: List<String>, actual: List<String>) = assertEquals(expected.sorted(), actual.sorted())
}
