package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The empty grid lists every token, the unknown tags first (#135). */
class EmptySearchTokensTest {
  private val searched = listOf("rating:s", "feed:following", "animated_gif", "ZZQQ", "-nope")

  @Test
  fun anUnknownTagLeadsAndTheRestKeepTheSearchOrder() {
    val tokens = EmptySearchTokens.of(searched, unknownKeys = setOf("zzqq", "nope"), ratingLocked = false)

    assertEquals(listOf("ZZQQ"), tokens.unknown)
    assertEquals(listOf("rating:s", "feed:following", "animated_gif", "-nope"), tokens.others)
  }

  @Test
  fun aLockedRatingIsNotListed() {
    val tokens = EmptySearchTokens.of(searched, unknownKeys = emptySet(), ratingLocked = true)

    assertEquals(listOf("feed:following", "animated_gif", "ZZQQ", "-nope"), tokens.all)
  }

  @Test
  fun aFilterIsNeverUnknown() {
    val tokens = EmptySearchTokens.of(listOf("order:popularity"), unknownKeys = setOf("order:popularity"), ratingLocked = false)

    assertEquals(emptyList(), tokens.unknown)
  }

  @Test
  fun theLineNamesEachUnknownTag() {
    assertNull(unknownTagsLine(emptyList()))
    assertEquals("“zzqq” isn't a known tag", unknownTagsLine(listOf("zzqq")))
    assertEquals("“a”, “b” and “c” aren't known tags", unknownTagsLine(listOf("a", "b", "c")))
  }
}
