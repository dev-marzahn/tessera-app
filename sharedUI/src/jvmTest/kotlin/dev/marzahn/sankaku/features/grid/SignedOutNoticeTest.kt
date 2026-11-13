package dev.marzahn.sankaku.features.grid

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.StoredTokens
import dev.marzahn.sankaku.features.auth.SignInController
import org.koin.core.context.GlobalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * #507: signed out, posts hidden from signed-out users are left out of the grid, and one line above it says so with
 * Sign in, instead of a "Sign in to view" cell for each.
 */
@OptIn(ExperimentalTestApi::class)
class SignedOutNoticeTest {
  private fun run(fixture: GridScreenFixture, test: ComposeUiTest.() -> Unit) {
    fixture.start()
    try {
      runDesktopComposeUiTest(width = 1280, height = 832) {
        with(fixture) { showGrid() }
        test()
      }
    } finally {
      fixture.stop()
    }
  }

  private fun ComposeUiTest.cells(n: Int) =
    onAllNodesWithContentDescription("Fixture $n", substring = true).fetchSemanticsNodes().size

  @Test
  fun signedOutTheLockedPostsAreLeftOutAndTheNoticeShowsOnce() = run(GridScreenFixture(locked = setOf(2, 4))) {
    onAllNodesWithText(NOTICE).assertCountEquals(1)
    onAllNodesWithText("Sign in to view").assertCountEquals(0)
    for (n in listOf(2, 4)) assertEquals(0, cells(n), "locked post $n has no cell")
    for (n in listOf(1, 3, 5, 6)) assertTrue(cells(n) > 0, "post $n has a cell")
  }

  @Test
  fun signInOnTheNoticeOpensSignIn() = run(GridScreenFixture(locked = setOf(3))) {
    val signIn = GlobalContext.get().get<SignInController>()
    assertFalse(signIn.isOpen.value)
    onNodeWithText("Sign in").performClick()
    waitForIdle()
    assertTrue(signIn.isOpen.value)
  }

  @Test
  fun signedOutWithNothingLockedThereIsNoNotice() = run(GridScreenFixture()) {
    onAllNodesWithText(NOTICE).assertCountEquals(0)
  }

  @Test
  fun signedInThereIsNoNotice() = run(GridScreenFixture(locked = setOf(2), tokens = signedIn())) {
    onAllNodesWithText(NOTICE).assertCountEquals(0)
  }

  private fun signedIn(): StoredTokens {
    val at = Clock.System.now()
    return StoredTokens("access", "refresh", at + 7.days, at + 90.days, UserSummary(id = "42", name = "tester", avatarUrl = null))
  }

  private companion object {
    const val NOTICE = "Some posts need an account"
  }
}
