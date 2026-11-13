package dev.marzahn.sankaku.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #520: a failed load says what happened in plain words and offers the next step. */
@OptIn(ExperimentalTestApi::class)
class ApiErrorStateTest {
  @Test
  fun aServerErrorSaysSankakuIsHavingTroubleWithRetry() = runComposeUiTest {
    var retried = 0
    setContent { SankakuTheme { ApiErrorState(ApiError.Http(502, "code=\"bad-gateway\""), onRetry = { retried++ }) } }

    onNodeWithText("Sankaku is having trouble. Try again in a moment.").assertExists()
    assertTrue(onAllNodesWithText("HTTP", substring = true).fetchSemanticsNodes().isEmpty())
    onNodeWithText("Retry").performClick()
    assertEquals(1, retried)
  }

  @Test
  fun aRateLimitCountsDownOnADisabledRetry() = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent { SankakuTheme { ApiErrorState(ApiError.RateLimited(30), onRetry = {}) } }

    onNodeWithText("Retry in", substring = true).assertIsNotEnabled()
  }

  @Test
  fun aSearchTimeoutOffersEditSearchFirst() = runComposeUiTest {
    var edited = 0
    setContent { SankakuTheme { ApiErrorState(ApiError.SearchTimeout(), onRetry = {}, onEditSearch = { edited++ }) } }

    onNodeWithText("Retry").assertIsEnabled()
    onNodeWithText("Edit search").performClick()
    assertEquals(1, edited)
  }

  @Test
  fun theTagLimitOffersOnlyEditSearch() = runComposeUiTest {
    setContent { SankakuTheme { ApiErrorState(ApiError.TagLimit(4), onRetry = {}, onEditSearch = {}) } }

    onNodeWithText("Your search limit is 4 tags. Remove a tag first.").assertExists()
    onNodeWithText("Edit search").assertExists()
    assertTrue(onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty())
  }

  /** A screen that can't edit a search still offers a way on: never a message without a button. */
  @Test
  fun withNoneOfItsStepsHereRetryStands() = runComposeUiTest {
    setContent { SankakuTheme { ApiErrorState(ApiError.TagLimit(4), onRetry = {}) } }

    onNodeWithText("Retry").assertIsEnabled()
  }

  /** The owner's journey re-check (#520): a kept post, signed out after a restart, reads like the grids' cells. */
  @Test
  fun aNotFoundSignedOutOffersSignIn() = runComposeUiTest {
    var signIns = 0
    setContent {
      SankakuTheme {
        ApiErrorState(ApiError.Http(404, "code=\"x\""), onRetry = {}, onSignIn = { signIns++ }, signedIn = false)
      }
    }

    onNodeWithText("Sankaku shows this only to signed-in accounts. Sign in to view it.").assertExists()
    onNodeWithText("Sign in").performClick()
    assertEquals(1, signIns)
  }

  @Test
  fun anEndedSessionOffersSignInWhereTheScreenHasIt() = runComposeUiTest {
    var signIns = 0
    setContent { SankakuTheme { ApiErrorState(ApiError.Unauthorized(), onRetry = {}, onSignIn = { signIns++ }) } }

    onNodeWithText("Sign in").performClick()
    assertEquals(1, signIns)
  }
}
