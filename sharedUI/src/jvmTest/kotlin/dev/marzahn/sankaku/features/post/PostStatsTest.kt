package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.width
import kotlin.math.abs
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PostStatsTest {
  private val post = PostDto(id = "1", md5 = "m", rating = "s", status = "active", favCount = 1284, voteCount = 1234, totalScore = 5000)

  /** The vote pane is one line high (the group's 48 dp) however narrow the row, as on a phone with large text (#70). */
  @Test
  fun theVoteCountsStayOnOneLineWhenTheRowIsNarrow() = runComposeUiTest {
    setContent {
      SankakuTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
          PostStats(post, onToggleFavourite = {}, onVote = {}, modifier = Modifier.width(220.dp))
        }
      }
    }
    assertEquals(48.dp, onNodeWithContentDescription("Average 4.1 from 1234 votes").getUnclippedBoundsInRoot().height)
  }

  /**
   * The counts end as far from the pane's end as the first star starts from its cell's start (a fifth of the stars'
   * width), at full cell width and when the stars give way (#104).
   */
  @Test
  fun theCountsKeepTheFirstStarsInsetFromTheEnd() = runComposeUiTest {
    var width by mutableStateOf(240.dp)
    setContent {
      StarsAndCounts(
        stars = { Box(Modifier.fillMaxWidth().height(24.dp).testTag("stars")) },
        counts = { Box(Modifier.size(40.dp).testTag("counts")) },
        modifier = Modifier.width(width),
      )
    }
    for (pane in listOf(240.dp, 150.dp, 100.dp)) {
      width = pane
      waitForIdle()
      val stars = onNodeWithTag("stars").getUnclippedBoundsInRoot()
      val counts = onNodeWithTag("counts").getUnclippedBoundsInRoot()
      val cell = minOf(24.dp, stars.width / 5)
      val starInset = maxOf(0.dp, (cell - 20.dp) / 2)
      assertTrue(abs((pane - counts.right - starInset).value) <= 1f, "at $pane: end ${pane - counts.right}, start $starInset")
    }
  }

  /**
   * #525, option C: the phone sheet's row on a 360 dp phone (340 dp inside its padding, ⋮ after it) keeps five full
   * 40 dp stars and the row's 48 dp at 100% and 200% text; ♡ shows no count, and the counts line has them all.
   */
  @Test
  fun thePhoneSheetKeepsFiveFullStarsAtAnyTextSize() {
    for (scale in listOf(1f, 2f)) runComposeUiTest {
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = scale)) {
          SankakuTheme {
            androidx.compose.foundation.layout.Column(Modifier.width(340.dp)) {
              PostStats(
                post, onToggleFavourite = {}, onVote = {}, countsApart = true,
                trailing = { Box(Modifier.size(52.dp)) },
              )
              PostCounts(post)
            }
          }
        }
      }
      val pane = onNodeWithContentDescription("Average 4.1 from 1234 votes").getUnclippedBoundsInRoot()
      assertTrue(pane.width >= 40.dp * 5, "at $scale: $pane")
      assertEquals(48.dp, pane.height, "at $scale")
      val toggle = onNodeWithContentDescription("Favourite, 1.2k (F)").getUnclippedBoundsInRoot()
      assertTrue(toggle.right <= 340.dp - 52.dp, "at $scale: $toggle")
      // ♡'s count is on the counts line, not in the toggle; the line has the average and votes too.
      for (text in listOf("4.1", "1.2k votes", "1.2k")) onNodeWithText(text, useUnmergedTree = true).assertExists()
      assertTrue(onNodeWithText("1.2k", useUnmergedTree = true).getUnclippedBoundsInRoot().top >= pane.bottom, "at $scale")
    }
  }

  /** The stars come first and the favourite toggle ends the group, each with its key under it (#81). */
  @Test
  fun theFavouriteToggleEndsTheGroupWithItsKeyUnderIt() = runComposeUiTest {
    setContent {
      SankakuTheme {
        PostStats(post, onToggleFavourite = {}, onVote = {}, modifier = Modifier.width(320.dp))
      }
    }
    val stars = onNodeWithContentDescription("Average 4.1 from 1234 votes").getUnclippedBoundsInRoot()
    val toggle = onNodeWithContentDescription("Favourite, 1.2k (F)").getUnclippedBoundsInRoot()
    assertEquals(0.dp, stars.left)
    assertTrue(toggle.left > stars.right, "the toggle follows the stars")
    assertEquals(320.dp, toggle.right)

    val voteKey = onNodeWithText("1–5").getUnclippedBoundsInRoot()
    val favouriteKey = onNodeWithText("F").getUnclippedBoundsInRoot()
    assertTrue(voteKey.right < stars.right, "1–5 sits under the stars")
    assertTrue(favouriteKey.left >= toggle.left && favouriteKey.right <= toggle.right, "F sits under the toggle")
  }

  /**
   * Left-handed (#528), the row mirrors: ⋮ at the start edge, then the favourite, then the stars at the end. TalkBack's
   * order follows: ⋮, the favourite, the stars.
   */
  @Test
  fun mirroredTheMenuAndTheFavouriteComeBeforeTheStars() = runComposeUiTest {
    var toggled = 0
    var voted: Int? = null
    setContent {
      SankakuTheme {
        PostStats(
          post,
          onToggleFavourite = { toggled++ },
          onVote = { voted = it },
          mirrored = true,
          trailing = { Box(Modifier.size(48.dp).testTag("menu")) },
          modifier = Modifier.width(320.dp),
        )
      }
    }
    val menu = onNodeWithTag("menu").getUnclippedBoundsInRoot()
    val toggle = onNodeWithContentDescription("Favourite, 1.2k (F)").getUnclippedBoundsInRoot()
    val stars = onNodeWithContentDescription("Average 4.1 from 1234 votes").getUnclippedBoundsInRoot()
    assertEquals(0.dp, menu.left)
    assertTrue(toggle.left > menu.right, "the favourite follows ⋮")
    assertTrue(stars.left > toggle.right, "the stars follow the favourite")
    assertEquals(320.dp, stars.right)

    // Both still work where they've moved to.
    onNodeWithContentDescription("Favourite, 1.2k (F)").performClick()
    onNodeWithContentDescription("Average 4.1 from 1234 votes").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
    assertEquals(1, toggled)
    assertEquals(4, voted)
  }

  /**
   * Mirrored (#528), a failed favourite or vote still shakes its control back with the reject haptic (feedback C), the
   * favourite in its leading shapes now.
   */
  @Test
  fun mirroredARejectedFavouriteOrVoteStillShakesBack() = runComposeUiTest {
    val felt = mutableListOf<HapticFeedbackType>()
    val haptics = object : HapticFeedback {
      override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        felt += hapticFeedbackType
      }
    }
    var favouriteRejected by mutableIntStateOf(0)
    var voteRejected by mutableIntStateOf(0)
    setContent {
      SankakuTheme {
        CompositionLocalProvider(LocalHapticFeedback provides haptics) {
          PostStats(
            post,
            onToggleFavourite = {},
            onVote = {},
            mirrored = true,
            favouriteRejected = favouriteRejected,
            voteRejected = voteRejected,
            trailing = { Box(Modifier.size(48.dp)) },
            modifier = Modifier.width(320.dp),
          )
        }
      }
    }
    val favourite = onNodeWithContentDescription("Favourite, 1.2k (F)")
    val favouriteAtRest = favourite.getBoundsInRoot().left
    mainClock.autoAdvance = false

    favouriteRejected = 1
    mainClock.advanceTimeBy(50)
    assertEquals(listOf(HapticFeedbackType.Reject), felt)
    assertTrue(favourite.getBoundsInRoot().left != favouriteAtRest, "the favourite shakes")

    mainClock.advanceTimeBy(2_000)
    assertEquals(favouriteAtRest, favourite.getBoundsInRoot().left, "and comes back")

    // The pane's semantics sit outside its shake's layer (mirrored or not), so its bounds stay; the haptic is the
    // rejection's answer reaching it.
    voteRejected = 1
    mainClock.advanceTimeBy(50)
    assertEquals(listOf(HapticFeedbackType.Reject, HapticFeedbackType.Reject), felt)
  }

  /** Not mirrored, ⋮ ends the row after the favourite, as it always has (#178). */
  @Test
  fun unmirroredTheMenuEndsTheRow() = runComposeUiTest {
    setContent {
      SankakuTheme {
        PostStats(
          post,
          onToggleFavourite = {},
          onVote = {},
          trailing = { Box(Modifier.size(48.dp).testTag("menu")) },
          modifier = Modifier.width(320.dp),
        )
      }
    }
    val menu = onNodeWithTag("menu").getUnclippedBoundsInRoot()
    val toggle = onNodeWithContentDescription("Favourite, 1.2k (F)").getUnclippedBoundsInRoot()
    val stars = onNodeWithContentDescription("Average 4.1 from 1234 votes").getUnclippedBoundsInRoot()
    assertEquals(0.dp, stars.left)
    assertTrue(toggle.left > stars.right, "the favourite follows the stars")
    assertTrue(menu.left > toggle.right, "⋮ follows the favourite")
    assertEquals(320.dp, menu.right)
  }
}
