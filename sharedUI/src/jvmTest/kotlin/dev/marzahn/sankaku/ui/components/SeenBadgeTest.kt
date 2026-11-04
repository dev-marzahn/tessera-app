package dev.marzahn.sankaku.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.ui.theme.MinTextContrast
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import dev.marzahn.sankaku.ui.theme.wcagContrast
import kotlin.test.Test
import kotlin.test.assertTrue

/** The seen mark (#182): it keeps the contrast #131 set, whatever the image under it. */
@OptIn(ExperimentalTestApi::class)
class SeenBadgeTest {
  @Test
  fun theBadgeIsWhiteOnAScrimThatReadsOnTheBrightestImage() {
    val scrim = Color.Black.copy(alpha = SEEN_BADGE_SCRIM_ALPHA)
    // The brightest image under the scrim is the worst case; the dimmed image is brighter than any other.
    val onWhite = scrim.compositeOver(Color.White)
    assertTrue(wcagContrast(Color.White, onWhite) >= MinTextContrast, "${wcagContrast(Color.White, onWhite)}")
  }

  @Test
  fun dimmingTakesAboutAFifthOfTheImageAndLeavesTheMarksAlone() {
    // 85% (the spec): lighter than the "disabled" look; the badge and chips are drawn over it at full strength.
    assertTrue(SEEN_IMAGE_ALPHA in 0.8f..0.9f)
  }

  @Test
  fun theBadgeSaysSeen() = runComposeUiTest {
    setContent { SankakuTheme { SeenBadge(Modifier.testTag("badge")) } }

    onNodeWithTag("badge").assertContentDescriptionEquals("Seen")
  }
}
