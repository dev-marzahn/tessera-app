package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FollowedIndexTest {
  private fun followed(ids: List<String>, index: Int, id: String?) = followedIndex(index, id, ids.size) { ids[it] }

  @Test
  fun anUnchangedListKeepsTheIndex() {
    assertEquals(1, followed(listOf("a", "b", "c"), 1, "b"))
  }

  @Test
  fun aReloadedListFollowsThePostToItsNewPlace() {
    // Signing in reloaded the list: "b" was at 1 and is now at 2, and "x" took its place.
    assertEquals(2, followed(listOf("a", "x", "b"), 1, "b"))
  }

  @Test
  fun aPostTheReloadedListLacksIsGone() {
    assertNull(followed(listOf("x", "y"), 1, "b"))
    assertNull(followed(listOf("x"), 5, "b"))
  }

  @Test
  fun withoutAnIdTheIndexStands() {
    assertEquals(1, followed(listOf("a", "b"), 1, null))
  }
}
