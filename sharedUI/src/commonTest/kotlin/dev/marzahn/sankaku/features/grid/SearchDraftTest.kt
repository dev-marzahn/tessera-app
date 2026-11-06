package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.tabs.filterNotes
import dev.marzahn.sankaku.tabs.tabTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchDraftTest {
  @Test
  fun filterAndRatingTagsLeaveTheChipsAndGoFirst() {
    val draft = SearchDraft.of(listOf("threshold:4", "rating:q", "blue_hair", "order:popularity"))

    assertEquals(listOf("blue_hair"), draft.chips)
    assertEquals(setOf(Rating.R15), draft.ratings)
    assertEquals(listOf("rating:q", "order:popularity", "threshold:4", "blue_hair"), draft.tags)
  }

  @Test
  fun aFilterAddedLastStillGoesBeforeTheContentTags() {
    val draft = SearchDraft.of(listOf("blue_hair", "smile")).add("order:score").add("order:id")

    assertEquals(listOf("order:id", "blue_hair", "smile"), draft.chips)
    assertEquals(listOf("order:score", "order:id", "blue_hair", "smile"), draft.tags)
  }

  @Test
  fun changingOrRemovingAFilterKeepsTheOrder() {
    val draft = SearchDraft.of(listOf("order:popularity", "blue_hair", "smile")).add("threshold:2")
    val score = Filter.Order.optionFor("order:score")

    assertEquals(listOf("order:score", "threshold:2", "blue_hair", "smile"), draft.with(Filter.Order, score).tags)
    assertEquals(listOf("threshold:2", "blue_hair", "smile"), draft.with(Filter.Order, null).tags)
    assertEquals(listOf("rating:s", "order:popularity", "threshold:2", "blue_hair", "smile"), draft.withRatings(setOf(Rating.G)).tags)
  }

  @Test
  fun everyMenuFilterIsAFilterTokenForTheSearchOrder() {
    Filter.entries.flatMap { it.options }.forEach { assertTrue(SearchTags.isFilter(it.tag), it.tag) }
    Rating.entries.forEach { assertTrue(SearchTags.isFilter(it.tag), it.tag) }
  }

  @Test
  fun twoRatingsAreTheThirdExcludedAndThreeAreNone() {
    val draft = SearchDraft.of(listOf("blue_hair", "rating:g"))

    assertEquals(listOf("-rating:q", "blue_hair"), draft.withRatings(setOf(Rating.G, Rating.R18)).tags)
    assertEquals(listOf("blue_hair"), draft.withRatings(Rating.entries.toSet()).tags)
    assertEquals(setOf(Rating.G, Rating.R18), SearchDraft.of(listOf("-rating:q")).ratings)
  }

  @Test
  fun aSearchsRatingsAreWhatItsTagsLetThrough() {
    assertEquals(emptySet(), Rating.inTags(listOf("blue_hair")))
    assertEquals(setOf(Rating.G, Rating.R18), Rating.inTags(listOf("-rating:q")))
    assertEquals(setOf(Rating.G), Rating.inTags(listOf("rating:safe")))
  }

  /** A post's rating code reads as the search bar's badge and name, as in the post details (#139). */
  @Test
  fun aPostsRatingCodeIsTheSearchBarsBadgeAndName() {
    val shown = listOf("s", "q", "e").map { Rating.ofCode(it)?.let { rating -> rating.badge to rating.label } }
    assertEquals(listOf("G" to "General", "R15" to "Questionable", "R18" to "Explicit"), shown)
    assertEquals(null, Rating.ofCode("x"))
  }

  @Test
  fun theOwnFavouritesTagIsTheFavouritesFilterAndGoesFirst() {
    val draft = SearchDraft.of(listOf("blue_hair", "-FAV:me", "rating:s"), me = "me")

    assertEquals(Favourites.Hide, draft.favourites)
    assertEquals(listOf("blue_hair"), draft.chips)
    assertEquals(listOf("-fav:me", "rating:s", "blue_hair"), draft.tags)
    assertEquals(listOf("fav:me", "rating:s", "blue_hair"), draft.withFavourites(Favourites.Only).tags)
    assertEquals(listOf("rating:s", "blue_hair"), draft.withFavourites(null).tags)
    assertEquals(Favourites.Only, draft.add("fav:me").favourites)
  }

  @Test
  fun anotherUsersOrASignedOutFavouritesTagStaysAChip() {
    assertEquals(listOf("fav:someone"), SearchDraft.of(listOf("fav:someone"), me = "me").chips)
    assertEquals(listOf("fav:me"), SearchDraft.of(listOf("fav:me")).chips)
    assertEquals(null, SearchDraft.of(listOf("fav:me")).favourites)
  }

  @Test
  fun learningTheUsersNameKeepsTheDraftAndMakesTheirTagTheFilter() {
    val draft = SearchDraft.of(listOf("blue_hair", "fav:me", "order:score"))

    val signedIn = draft.withMe("me")
    assertEquals(Favourites.Only, signedIn.favourites)
    assertEquals(listOf("fav:me", "order:score", "blue_hair"), signedIn.tags)
    assertEquals(listOf("fav:me", "blue_hair"), signedIn.withMe(null).chips)
  }

  @Test
  fun aTabTitleNamesTheFavouritesFilterWithoutTheUsersName() {
    // With content tags the filter is left to the subtitle (#233), which doesn't name the user either.
    assertEquals("Blue Hair", tabTitle(Route.Grid(listOf("fav:me", "blue_hair"))))
    assertEquals(listOf("favourites"), filterNotes(listOf("fav:me", "blue_hair")))
    assertEquals("Favourites", tabTitle(Route.Grid(listOf("fav:me"))))
    assertEquals("Not favourites", tabTitle(Route.Grid(listOf("-fav:me"))))
  }

  @Test
  fun typedWordsBecomeChipsWithoutDuplicates() {
    val draft = SearchDraft().add(" blue_hair  -smile ").add("blue_hair rating:explicit")

    assertEquals(listOf("rating:e", "blue_hair", "-smile"), draft.tags)
    assertEquals(listOf("rating:e", "-smile"), draft.remove("blue_hair").tags)
  }
}
