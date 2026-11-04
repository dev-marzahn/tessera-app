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
import androidx.compose.ui.test.onNodeWithTag
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
}
