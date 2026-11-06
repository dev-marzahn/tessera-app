package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.api.Feed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** One source per search (#174): which searches are the same source, and which tops are which kind. */
class SourceKeyTest {
  @Test
  fun orderDoesNotCount() {
    assertEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("order:popularity", "artist_x")))
    assertEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("artist_x", "-order:score")))
  }

  @Test
  fun theRatingFilterDoesNotCount() {
    val plain = SourceKey.of(listOf("artist_x"))
    assertEquals(plain, SourceKey.of(listOf("-rating:e", "artist_x")))
    assertEquals(plain, SourceKey.of(listOf("rating:s", "order:quality", "artist_x")))
  }

  @Test
  fun contentTagsAreNormalisedAndSorted() {
    assertEquals(SourceKey.of(listOf("b_tag", "a_tag")), SourceKey.of(listOf(" A_Tag ", "b_tag", "a_tag", "")))
  }

  @Test
  fun differentContentTagsAreDifferentSources() {
    assertNotEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("artist_y")))
    assertNotEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("artist_x", "blue_hair")))
    assertNotEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("artist_x", "-blue_hair")))
  }

  @Test
  fun feedAndFavouritesTokensCount() {
    assertNotEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("feed:following", "artist_x")))
    assertNotEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("fav:someone", "artist_x")))
    assertNotEquals(SourceKey.of(listOf("artist_x")), SourceKey.of(listOf("threshold:3", "artist_x")))
  }

  @Test
  fun aSearchOfEverythingHasNoKey() {
    assertNull(SourceKey.of(emptyList()))
    assertNull(SourceKey.of(listOf("order:popularity")))
    assertNull(SourceKey.of(listOf("order:quality", "rating:s")))
  }

  @Test
  fun aFeedIsExactlyAFeedTokenWithAtMostARating() {
    assertEquals(Feed.Following, QueueKind.feedOf(listOf("feed:following")))
    assertEquals(Feed.ForYou, QueueKind.feedOf(listOf("rating:s", "feed:for-you")))
    assertEquals(QueueKind.Feed, QueueKind.ofGrid(listOf("feed:following")))
  }

  @Test
  fun aFeedWithATagOrAnOrderIsASource() {
    assertNull(QueueKind.feedOf(listOf("feed:following", "artist_x")))
    assertNull(QueueKind.feedOf(listOf("feed:following", "order:popularity")))
    assertNull(QueueKind.feedOf(listOf("feed:following", "feed:for-you")))
    assertEquals(QueueKind.Source, QueueKind.ofGrid(listOf("feed:following", "artist_x")))
    assertEquals(QueueKind.Source, QueueKind.ofGrid(listOf("artist_x")))
    assertEquals(QueueKind.Source, QueueKind.ofGrid(emptyList()))
  }
}
