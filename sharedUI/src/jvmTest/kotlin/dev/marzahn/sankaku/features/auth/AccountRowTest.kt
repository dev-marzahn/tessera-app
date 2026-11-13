package dev.marzahn.sankaku.features.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.ui.SHORT_WINDOW_MS
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sign out at the sidebar's foot asks for a second tap (#134). */
@OptIn(ExperimentalTestApi::class)
class AccountRowTest {
  private var signOuts = 0
  private var open by mutableStateOf(true)

  /** The row, on a clock that moves only when told, so the window doesn't run out while the test waits. */
  private fun ComposeUiTest.showRow() {
    mainClock.autoAdvance = false
    setContent {
      SankakuTheme {
        AccountRow("someone", avatarUrl = null, open = open, onSignOut = { signOuts++ }, windowMillis = SHORT_WINDOW_MS)
      }
    }
    settle()
  }

  /** Long enough for the row's fade, well inside the window. */
  private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(SETTLE_MS)

  private fun ComposeUiTest.signOutButton(): SemanticsNodeInteraction = onNodeWithContentDescription("Sign out")

  private fun ComposeUiTest.confirm(): SemanticsNodeInteraction = onNodeWithText("Sign out? Tap again")

  @Test
  fun oneTapAsksAndTheRowComesBackWhenTheWindowRunsOut() = runComposeUiTest {
    showRow()
    signOutButton().performClick()
    settle()
    confirm().assertExists()
    signOutButton().assertDoesNotExist()
    assertEquals(0, signOuts)

    // Still asking just before the window ends, gone once it has.
    mainClock.advanceTimeBy(SHORT_WINDOW_MS - SETTLE_MS - 200)
    confirm().assertExists()
    mainClock.advanceTimeBy(200 + SETTLE_MS)
    confirm().assertDoesNotExist()
    signOutButton().assertExists()
    assertEquals(0, signOuts)
  }

  @Test
  fun aSecondTapWithinTheWindowSignsOut() = runComposeUiTest {
    showRow()
    signOutButton().performClick()
    settle()
    mainClock.advanceTimeBy(SHORT_WINDOW_MS / 2)
    confirm().performClick()
    settle()
    assertEquals(1, signOuts)
    confirm().assertDoesNotExist()
  }

  @Test
  fun closingTheSidebarCancelsTheQuestion() = runComposeUiTest {
    showRow()
    signOutButton().performClick()
    settle()
    confirm().assertExists()

    open = false
    settle()
    confirm().assertDoesNotExist()
    signOutButton().assertExists()
    assertEquals(0, signOuts)
  }

  @Test
  fun theCustomActionSignsOutAtOnce() = runComposeUiTest {
    showRow()
    val actions = signOutButton().fetchSemanticsNode().config[SemanticsActions.CustomActions]
    runOnIdle { actions.single { it.label == "Sign out now" }.action() }
    settle()
    assertEquals(1, signOuts)
  }

  private companion object {
    const val SETTLE_MS = 1_000L
  }
}
