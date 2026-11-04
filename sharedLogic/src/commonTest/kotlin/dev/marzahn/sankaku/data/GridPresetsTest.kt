package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertEquals

class GridPresetsTest {
  @Test
  fun favouritesIsTheUsersFavTagAlone() {
    assertEquals(listOf("fav:someone"), GridPresets.favourites("someone"))
  }

  @Test
  fun popularAndTopAreTheirOrderTagAlone() {
    // No rating or other filter: presets open a clean slate.
    assertEquals(listOf("order:popularity"), GridPresets.popular)
    assertEquals(listOf("order:quality"), GridPresets.top)
  }

  @Test
  fun forYouAndFollowingAreTheirFeedTagAlone() {
    assertEquals(listOf("feed:for-you"), GridPresets.forYou)
    assertEquals(listOf("feed:following"), GridPresets.following)
  }
}
