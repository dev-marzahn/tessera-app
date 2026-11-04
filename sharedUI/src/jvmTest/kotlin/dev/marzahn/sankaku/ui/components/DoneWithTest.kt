package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #184: "Done with …?" at the end of a source's grid is a button with the heading role, and it wraps. */
@OptIn(ExperimentalTestApi::class)
class DoneWithTest {
  @Test
  fun theButtonClosesTheSource() = runComposeUiTest {
    var closed = 0
    setContent { SankakuTheme { DoneWith("artist_x", keptStay = 0, onClose = { closed++ }) } }

    onNodeWithText("Done with artist_x?").assertExists()
    onNodeWithText("Close artist_x").performClick()

    assertEquals(1, closed)
  }

  @Test
  fun theButtonIsAHeading() = runComposeUiTest {
    setContent { SankakuTheme { DoneWith("artist_x", keptStay = 0, onClose = {}) } }

    val config = onNodeWithText("Close artist_x").fetchSemanticsNode().config
    assertTrue(config.contains(SemanticsProperties.Heading))
    assertTrue(config.contains(SemanticsActions.OnClick))
  }

  @Test
  fun theKeptPostsThatStayAreSaid() = runComposeUiTest {
    setContent { SankakuTheme { DoneWith("artist_x", keptStay = 30, onClose = {}) } }

    onNodeWithText("30 kept stay").assertExists()
  }

  @Test
  fun aLongNameWrapsAtALargeFontInsteadOfBeingCutOff() = runComposeUiTest {
    val name = "an_artist_with_a_very_long_name another_tag_that_is_long"
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 2f)) {
        SankakuTheme { DoneWith(name, keptStay = 0, onClose = {}, modifier = Modifier.width(240.dp)) }
      }
    }

    // Two lines or more (one is about 56 px at 2×), inside 240 dp (480 px), and the button still clicks.
    val question = onNodeWithText("Done with $name?").fetchSemanticsNode().boundsInRoot
    assertTrue(question.height > 100f, "${question.height}")
    assertTrue(question.width <= 481f, "${question.width}")
    val button = onNodeWithText("Close $name")
    assertTrue(hasClickAction().matches(button.fetchSemanticsNode()))
    assertTrue(button.fetchSemanticsNode().boundsInRoot.width <= 481f)
  }
}
