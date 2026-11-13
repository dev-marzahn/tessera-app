package dev.marzahn.sankaku.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import dev.marzahn.sankaku.ui.components.ScrollToTopButton
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScrollToTopButtonTest {
  @Test
  fun onAPhoneTopIsA48DpIconLabelledTopForScreenReaders() = runComposeUiTest {
    var clicked = false
    setContent { SankakuTheme { ScrollToTopButton(visible = true, onClick = { clicked = true }, compact = true) } }

    onNodeWithText("Top", useUnmergedTree = true).assertDoesNotExist()
    val top = onNodeWithContentDescription("Top")
    top.assertHasClickAction()
    // The count pill's and Undo's 48 dp target (#261).
    assertEquals(DpSize(48.dp, 48.dp), top.getUnclippedBoundsInRoot().size)
    top.performClick()
    assertTrue(clicked)
  }

  @Test
  fun onDesktopTopKeepsItsLabel() = runComposeUiTest {
    setContent { SankakuTheme { ScrollToTopButton(visible = true, onClick = {}) } }

    onNodeWithText("Top", useUnmergedTree = true).assertExists()
  }
}
