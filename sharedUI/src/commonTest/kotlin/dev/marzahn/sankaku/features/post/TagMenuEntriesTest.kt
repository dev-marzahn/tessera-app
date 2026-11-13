package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.dto.TagType
import dev.marzahn.sankaku.features.post.TagMenuEntry.SearchKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TagMenuEntriesTest {
  private val search = listOf("blue_hair", "-text")

  private fun labels(entries: List<TagMenuEntry>) = entries.map { it.label }

  @Test
  fun aTagNotInTheSearchCanBeAddedOrExcluded() {
    val entries = tagMenuEntries("smile", search, canFollow = false, following = false)
    assertEquals(listOf("Add to search", "Exclude from search", "Search only this tag", "Copy name"), labels(entries))
    assertEquals(TagMenuEntry.Search(SearchKind.Add, search + "smile"), entries[0])
    assertEquals(TagMenuEntry.Search(SearchKind.Exclude, search + "-smile"), entries[1])
    assertEquals(TagMenuEntry.Search(SearchKind.Only, listOf("order:popularity", "smile")), entries[2])
  }

  @Test
  fun anIncludedTagCanBeExcludedOrRemoved() {
    val entries = tagMenuEntries("blue_hair", search, canFollow = false, following = false)
    assertEquals(listOf("Exclude from search", "Remove from search", "Search only this tag", "Copy name"), labels(entries))
    assertEquals(listOf("-blue_hair", "-text"), (entries[0] as TagMenuEntry.Search).search)
    assertEquals(listOf("-text"), (entries[1] as TagMenuEntry.Search).search)
  }

  @Test
  fun anExcludedTagCanBeAddedOrRemoved() {
    val entries = tagMenuEntries("text", search, canFollow = false, following = false)
    assertEquals(listOf("Add to search", "Remove from search", "Search only this tag", "Copy name"), labels(entries))
  }

  @Test
  fun followingIsOfferedOnlyWhenItCanBeDone() {
    assertEquals("Follow", tagMenuEntries("smile", search, canFollow = true, following = false).last().label)
    assertEquals("Unfollow", tagMenuEntries("smile", search, canFollow = true, following = true).last().label)
    assertEquals("Copy name", tagMenuEntries("smile", search, canFollow = false, following = true).last().label)
  }

  @Test
  fun blacklistingIsOfferedLastOnlyWhenSignedIn() {
    val entries = tagMenuEntries("smile", search, canFollow = true, following = false, canBlacklist = true)
    assertEquals(listOf("Follow", "Blacklist tag…"), labels(entries).takeLast(2))
    assertEquals(TagMenuEntry.Blacklist, entries.last())
    assertEquals("Blacklist tag…", tagMenuEntries("smile", search, canFollow = false, following = false, canBlacklist = true).last().label)
    assertEquals("Follow", tagMenuEntries("smile", search, canFollow = true, following = false, canBlacklist = false).last().label)
  }

  @Test
  fun aboutThisTagFollowsSearchOnlyWhenOffered() {
    // #486: the tag's page, where its books are.
    val entries = tagMenuEntries("smile", search, canFollow = false, following = false, canAbout = true)
    assertEquals(listOf("Search only this tag", "About this tag", "Copy name"), labels(entries).takeLast(3))
    assertEquals(TagMenuEntry.About, entries[entries.size - 2])
  }

  @Test
  fun theConfirmationSaysHowManyRulesAreUsed() {
    assertEquals("Counting your blacklist rules…", blacklistRulesLine(null, loaded = false))
    assertEquals("You use 3 blacklist rules; this adds one.", blacklistRulesLine(3, loaded = true))
    assertEquals("You use 1 blacklist rule; this adds one.", blacklistRulesLine(1, loaded = true))
    assertEquals("You use 0 blacklist rules; this adds one.", blacklistRulesLine(0, loaded = true))
    assertEquals("How many blacklist rules you use couldn't be loaded.", blacklistRulesLine(null, loaded = true))
  }

  @Test
  fun theBylineLeavesOutWhatIsNotKnown() {
    assertEquals("Character · 9.8k posts · following", tagSheetByline(TagType.Character, 9_812, following = true))
    assertEquals("General · 1 post", tagSheetByline(TagType.General, 1, following = false))
    assertEquals("", tagSheetByline(null, null, following = false))
  }
}
