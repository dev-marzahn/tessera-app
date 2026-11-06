package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.api.dto.TagType
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.grid.ratingsDescription
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What TalkBack says for a post: its title and kind, its state, and the rating as an adjustable node (#143). */
@OptIn(ExperimentalTestApi::class)
class PostSemanticsTest {
  private val id = "98765432"
  private val md5 = "0123456789abcdef0123456789abcdef"
  private val tags = listOf(
    TagDto("Rosehip", type = TagType.Character.id),
    TagDto("Peach", type = TagType.Character.id),
    TagDto("Girls und Panzer", type = TagType.Copyright.id),
  )
  private val post = PostDto(id = id, md5 = md5, rating = "s", status = "active", tags = tags, favCount = 6)

  @Test
  fun aCellSaysItsTitleThenItsState() {
    assertEquals("Rosehip, Peach", cellDescription(post, openInTab = false))
    assertEquals(
      "Rosehip, Peach. Favourited. In tab. Video, 02:07",
      cellDescription(post.copy(isFavorited = true, fileType = "video/mp4", videoDuration = 127.0), openInTab = true),
    )
    assertEquals("Rosehip, Peach. Unfavourited", cellDescription(post, openInTab = false, unfavourited = true))
    assertEquals("Rosehip, Peach. Video", cellDescription(post.copy(fileType = "video/webm"), openInTab = false))
    assertEquals("Rosehip, Peach. Already in tab 37", cellDescription(post, openInTab = true, alreadyTab = 37))
  }

  @Test
  fun onAPhoneACellSaysKeptNotInTab() {
    assertEquals("Rosehip, Peach. Seen. Kept", cellDescription(post, openInTab = true, seen = true, touch = true))
    assertEquals("Rosehip, Peach. Already item 37", cellDescription(post, openInTab = true, alreadyTab = 37, touch = true))
    assertEquals(
      "Rosehip, Peach. Kept again",
      cellDescription(post, openInTab = true, alreadyTab = 37, keptAgain = true, touch = true),
    )
  }

  @Test
  fun aSeenCellSaysSeenAfterItsFavouriteState() {
    assertEquals("Rosehip, Peach. Seen", cellDescription(post, openInTab = false, seen = true))
    assertEquals(
      "Rosehip, Peach. Favourited. Seen. In tab",
      cellDescription(post.copy(isFavorited = true), openInTab = true, seen = true),
    )
    assertEquals("Rosehip, Peach", cellDescription(post, openInTab = false, seen = false))
  }

  @Test
  fun aKeptAgainCellSaysSoInPlaceOfAlreadyInTab() {
    assertEquals(
      "Rosehip, Peach. Seen. Kept again",
      cellDescription(post, openInTab = true, alreadyTab = 37, seen = true, keptAgain = true),
    )
  }

  @Test
  fun thePictureSaysItsTitleAndKind() {
    assertEquals("Rosehip, Peach, image", pictureDescription(post.copy(fileType = "image/jpeg")))
    assertEquals("Rosehip, Peach, video", pictureDescription(post.copy(fileType = "video/mp4")))
    assertEquals("Rosehip, Peach, animated GIF", pictureDescription(post.copy(fileType = "image/gif")))
  }

  @Test
  fun noDescriptionHoldsTheIdOrMd5() {
    val untitled = post.copy(tags = emptyList(), isFavorited = true, fileType = "video/mp4")
    for (said in listOf(cellDescription(untitled, openInTab = true), pictureDescription(untitled), cellDescription(post, true))) {
      assertFalse(id in said || md5 in said, said)
    }
    assertEquals("Untitled post, video", pictureDescription(untitled))
  }

  @Test
  fun theRatingSaysTheAverageAndTheVote() {
    assertEquals("No votes", ratingDescription(post))
    assertEquals("Average 5.0 from 1 vote", ratingDescription(post.copy(voteCount = 1, totalScore = 5)))
    assertEquals("Average 4.2 from 12 votes", ratingDescription(post.copy(voteCount = 12, totalScore = 50)))
    assertEquals("Your vote, none", voteState(null))
    assertEquals("Your vote, 3", voteState(3))
  }

  @Test
  fun theGBadgeSaysSafeOnlyAndWhy() {
    assertEquals("Safe only, signed out", ratingsDescription(setOf(Rating.G), signedOut = true))
    assertEquals("Safe only", ratingsDescription(setOf(Rating.G), signedOut = false))
    assertEquals("Ratings R15, R18", ratingsDescription(setOf(Rating.R18, Rating.R15), signedOut = false))
  }

  /** One node, adjustable 0–5 in whole steps, with a vote action per star and one to take it back. */
  @Test
  fun theStarsAreOneAdjustableNode() = runComposeUiTest {
    var shown by mutableStateOf(post.copy(voteCount = 12, totalScore = 50, userVote = 2))
    val votes = mutableListOf<Int?>()
    setContent {
      SankakuTheme {
        PostStats(shown, onToggleFavourite = {}, onVote = { votes += it }, modifier = Modifier.width(320.dp))
      }
    }
    val stars = onNodeWithContentDescription("Average 4.2 from 12 votes")
    stars.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Your vote, 2"))
    stars.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(2f, 0f..5f, 4)))
    val actions = stars.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
    assertEquals(listOf("Vote 1", "Vote 3", "Vote 4", "Vote 5", "Take vote back"), actions)

    stars.performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
    stars.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
    // The vote it already has is no new vote.
    stars.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
    assertEquals(listOf(4, null), votes)

    shown = shown.copy(userVote = null)
    waitForIdle()
    stars.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Your vote, none"))
    assertTrue("Take vote back" !in stars.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
  }

  /** Signed out the stars aren't adjustable: activating them opens sign-in, as a tap on a star does. */
  @Test
  fun signedOutActivatingTheStarsOpensSignIn() = runComposeUiTest {
    var signIns = 0
    setContent {
      SankakuTheme {
        PostStats(post, onToggleFavourite = {}, onVote = {}, onSignIn = { signIns++ }, modifier = Modifier.width(320.dp))
      }
    }
    val stars = onNodeWithContentDescription("No votes")
    assertNull(stars.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
    // What TalkBack's double-tap does: the action, not a touch, which would land on a star.
    stars.performSemanticsAction(SemanticsActions.OnClick)
    assertEquals(1, signIns)
  }

  /** ♡ is a toggle with its state, named by what it is and its count; the key only on desktop. */
  @Test
  fun theFavouriteIsAToggleNamedWithItsCount() = runComposeUiTest {
    setContent {
      SankakuTheme {
        PostStats(post.copy(isFavorited = true), onToggleFavourite = {}, onVote = {}, modifier = Modifier.width(320.dp))
      }
    }
    // The JVM tests run as desktop, which keeps its key.
    onNodeWithContentDescription("Favourite, 6 (F)")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    assertEquals("Favourite, 6", favouriteDescription("6", key = null))
  }
}
