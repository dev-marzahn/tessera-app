package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.SearchTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A post tab is titled by the name it was opened with, or "Untitled post"; never by its id (#138). */
class TabTitleTest {
  @Test
  fun aPostTabShowsItsName() {
    val route = Route.Post("12345", "m", emptyList(), "Monochrome · 1boy · Short hair")
    assertEquals("Monochrome · 1boy · Short hair", tabTitle(route))
    assertFalse(tabIsUntitled(route))
  }

  @Test
  fun aPostTabWithoutANameIsUntitledNotItsId() {
    val route = Route.Post("12345", null, emptyList())
    assertEquals("Untitled post", tabTitle(route))
    assertTrue(tabIsUntitled(route))
    assertFalse("12345" in tabTitle(route))
  }

  @Test
  fun childPostsAreNotTitledByTheParentsId() {
    assertEquals("Child posts", tabTitle(Route.Grid(listOf("parent:12345"))))
    assertEquals("parent:none", tabTitle(Route.Grid(listOf("parent:none"))))
  }

  @Test
  fun aSourceIsNamedByItsContentTagsNotItsFilterTokens() {
    // #233's acceptance criterion: the order is in the subtitle, not the title.
    assertEquals("Artist X", tabTitle(Route.Grid(listOf("order:popularity", "artist_x"))))
    assertEquals(listOf("popular"), filterNotes(listOf("order:popularity", "artist_x")))
    assertEquals("Pipoyami", tabTitle(Route.Grid(listOf("pipoyami", "order:popularity"))))
    assertEquals(
      "Blue Hair -Smile",
      tabTitle(Route.Grid(listOf("rating:g", "threshold:3", "-rating:e", "blue_hair", "-smile", "feed:following"))),
    )
  }

  @Test
  fun aSourceUsesItsTagChipsNames() {
    // #246's acceptance criteria: the chip's "K-Suwabe", and a key with no name.
    val names = mapOf("k-suwabe" to "K-Suwabe", "smile" to "Smiling")
    assertEquals("K-Suwabe", tabTitle(Route.Grid(listOf("k-suwabe", "order:popularity")), names = names::get))
    assertEquals("Jien (Nikke)", tabTitle(Route.Grid(listOf("jien_(nikke)")), names = names::get))
    // Looked up by the lowercase key without the `-`, which stays in the title.
    assertEquals("K-Suwabe -Smiling", tabTitle(Route.Grid(listOf("K-suwabe", "-smile")), names = names::get))
    assertEquals("from K-Suwabe (closed)", closedBlockTitle(listOf("k-suwabe"), names::get))
  }

  @Test
  fun everyFilterPrefixIsLeftOutOfATitle() {
    // Each prefix [SearchTags.FILTER_PREFIXES] registers, so a new filter is left out without a second list.
    for (prefix in SearchTags.FILTER_PREFIXES) {
      assertEquals("Cat", tabTitle(Route.Grid(listOf("${prefix}x", "cat"))), prefix)
      assertEquals("Cat", tabTitle(Route.Grid(listOf("-${prefix}x", "cat"))), prefix)
    }
  }

  @Test
  fun aSearchOfFiltersOnlyHasAShortName() {
    assertEquals("Popular", tabTitle(Route.Grid(listOf("order:popularity"))))
    assertEquals("Top", tabTitle(Route.Grid(listOf("order:quality", "rating:g"))))
    assertEquals("Favourites", tabTitle(Route.Grid(listOf("fav:someone", "order:popularity"))))
    assertEquals("Following", tabTitle(Route.Grid(listOf("feed:following", "order:popularity"))))
    assertEquals("For you", tabTitle(Route.Grid(listOf("feed:for-you", "rating:g"))))
    assertEquals("Score 3+", tabTitle(Route.Grid(listOf("threshold:3"))))
    assertEquals("Browse", tabTitle(Route.Grid(listOf("rating:g"))))
    assertEquals("Browse", tabTitle(Route.Grid(listOf("order:unknown"))))
    assertEquals("Browse", tabTitle(Route.Grid(emptyList())))
    // A title of filters only already names them, so the subtitle doesn't repeat them.
    assertEquals(emptyList(), filterNotes(listOf("order:popularity")))
  }

  @Test
  fun theSubtitleNotesTheFeedFavouritesAndOrderTheTitleLeavesOut() {
    assertEquals(
      listOf("following", "favourites", "top quality"),
      filterNotes(listOf("order:quality", "fav:someone", "feed:following", "rating:g", "threshold:2", "cat")),
    )
    assertEquals(emptyList(), filterNotes(listOf("cat")))
  }

  @Test
  fun aSourceTitleShowsNoUsersName() {
    assertFalse("someone" in tabTitle(Route.Grid(listOf("fav:someone", "cat"))))
    assertFalse(filterNotes(listOf("fav:someone", "cat")).any { "someone" in it })
  }

  @Test
  fun aGridIsNeverUntitled() {
    assertFalse(tabIsUntitled(Route.Grid(emptyList())))
  }
}
