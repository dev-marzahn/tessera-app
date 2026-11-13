package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.api.dto.TagSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Why a search shows only G posts, in words (#523). */
class GOnlyReasonTest {
  @Test
  fun signedOutComesFirstWhateverTheSwitchSays() {
    assertEquals(GOnlyReason.SignedOut, gOnlyReason(signedIn = false, gOnly = false, canTurnOff = true))
    assertEquals(GOnlyReason.SignedOut, gOnlyReason(signedIn = false, gOnly = true, canTurnOff = false))
    assertEquals("Signed out: G only", GOnlyReason.SignedOut.text)
    assertEquals("Sign in", GOnlyReason.SignedOut.action)
  }

  @Test
  fun signedInItIsTheSwitchWhichOffersTurnOffUnlessADebugRunHoldsIt() {
    assertEquals(GOnlyReason.Switch, gOnlyReason(signedIn = true, gOnly = true, canTurnOff = true))
    assertEquals(GOnlyReason.Held, gOnlyReason(signedIn = true, gOnly = true, canTurnOff = false))
    assertEquals("G only is on", GOnlyReason.Switch.text)
    assertEquals("Turn off", GOnlyReason.Switch.action)
    assertEquals("G only is on", GOnlyReason.Held.text)
    assertNull(GOnlyReason.Held.action)
  }

  @Test
  fun signedInWithTheSwitchOffNothingIsLocked() {
    assertNull(gOnlyReason(signedIn = true, gOnly = false, canTurnOff = true))
  }

  @Test
  fun aDroppedRatingSaysWhy() {
    assertEquals("Ratings stay G while signed out", GOnlyReason.SignedOut.droppedRating)
    assertEquals("Ratings stay G while G only is on", GOnlyReason.Switch.droppedRating)
    assertEquals("Ratings stay G while G only is on", GOnlyReason.Held.droppedRating)
  }

  @Test
  fun theOwnEmptyStatesAreOnlyForTheBareFeedAndFavourites() {
    assertEquals(EmptyFeed.Following, EmptyFeed.of(listOf("feed:following"), me = "me", ratingLocked = false))
    // The locked rating is added to every search; it doesn't make it a search of its own.
    assertEquals(EmptyFeed.Following, EmptyFeed.of(listOf("rating:s", "feed:following"), me = "me", ratingLocked = true))
    assertEquals(EmptyFeed.Favourites, EmptyFeed.of(listOf("fav:me"), me = "me", ratingLocked = false))
    assertEquals(EmptyFeed.Favourites, EmptyFeed.of(listOf("fav:me", "rating:s"), me = "me", ratingLocked = true))
    // Another user's favourites, a narrowed feed, or a rating the user chose: a search, with its chips.
    assertNull(EmptyFeed.of(listOf("fav:someone"), me = "me", ratingLocked = false))
    assertNull(EmptyFeed.of(listOf("fav:me"), me = null, ratingLocked = true))
    assertNull(EmptyFeed.of(listOf("feed:following", "scenery"), me = "me", ratingLocked = false))
    assertNull(EmptyFeed.of(listOf("rating:q", "feed:following"), me = "me", ratingLocked = false))
  }

  @Test
  fun gOnlyHidesRecentSearchesWithAKnownR15OrR18TagOrRating() {
    val known = mapOf(
      "scenery" to TagSuggestion("scenery", rating = "s"),
      "underwater_scenery" to TagSuggestion("underwater_scenery", rating = "e"),
      "censor" to TagSuggestion("censor", rating = "q"),
    )
    val recent = listOf(
      listOf("scenery"),
      listOf("-underwater_scenery", "scenery"),
      listOf("Censor"),
      listOf("rating:e", "scenery"),
      listOf("not_learnt_yet"),
      listOf("rating:s", "scenery"),
    )

    assertEquals(
      listOf(listOf("scenery"), listOf("not_learnt_yet"), listOf("rating:s", "scenery")),
      shownRecentSearches(recent, known, gOnly = true),
    )
    assertEquals(recent, shownRecentSearches(recent, known, gOnly = false))
  }
}
