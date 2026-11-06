package dev.marzahn.sankaku.features.post

import kotlin.test.Test
import kotlin.test.assertEquals

class TagSearchStateTest {
  private val search = listOf("rating:s", "blue_hair", "-text")

  @Test
  fun aTagIsIncludedExcludedOrNotInTheSearch() {
    assertEquals(InSearch.Included, inSearch("Blue_Hair", search))
    assertEquals(InSearch.Excluded, inSearch("text", search))
    assertEquals(InSearch.No, inSearch("smile", search))
  }

  @Test
  fun addingAndExcludingAppendANewTag() {
    assertEquals(search + "smile", withTag(search, "smile", InSearch.Included))
    assertEquals(search + "-smile", withTag(search, "smile", InSearch.Excluded))
  }

  @Test
  fun filterTokensStayFirstWhateverTheSearchWas() {
    assertEquals(listOf("order:popularity", "blue_hair", "smile"), withTag(listOf("blue_hair", "order:popularity"), "smile", InSearch.Included))
    assertEquals(listOf("order:popularity"), withTag(listOf("blue_hair", "order:popularity"), "blue_hair", InSearch.No))
  }

  @Test
  fun switchingKeepsTheTagsPlace() {
    assertEquals(listOf("rating:s", "-blue_hair", "-text"), withTag(search, "blue_hair", InSearch.Excluded))
    assertEquals(listOf("rating:s", "blue_hair", "text"), withTag(search, "text", InSearch.Included))
  }

  @Test
  fun eIncludesQExcludesAndEachSwitchesTheOther() {
    assertEquals(search + "smile", searchAfterKey(search, "smile", TagKey.Include))
    assertEquals(search + "-smile", searchAfterKey(search, "smile", TagKey.Exclude))
    assertEquals(listOf("rating:s", "blue_hair", "text"), searchAfterKey(search, "text", TagKey.Include))
    assertEquals(listOf("rating:s", "-blue_hair", "-text"), searchAfterKey(search, "blue_hair", TagKey.Exclude))
  }

  @Test
  fun theSameKeyAgainOrBackspaceRemoves() {
    assertEquals(listOf("rating:s", "-text"), searchAfterKey(search, "blue_hair", TagKey.Include))
    assertEquals(listOf("rating:s", "blue_hair"), searchAfterKey(search, "text", TagKey.Exclude))
    assertEquals(listOf("rating:s", "blue_hair"), searchAfterKey(search, "text", TagKey.Remove))
  }

  @Test
  fun backspaceOnATagNotInTheSearchChangesNothing() {
    assertEquals(null, searchAfterKey(search, "smile", TagKey.Remove))
  }

  @Test
  fun removingDropsEitherForm() {
    assertEquals(listOf("rating:s", "-text"), withTag(search, "blue_hair", InSearch.No))
    assertEquals(listOf("rating:s", "blue_hair"), withTag(search, "text", InSearch.No))
    assertEquals(search, withTag(search, "smile", InSearch.No))
  }
}
