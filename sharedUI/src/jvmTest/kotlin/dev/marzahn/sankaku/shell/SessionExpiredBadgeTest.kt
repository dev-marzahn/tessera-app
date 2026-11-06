package dev.marzahn.sankaku.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.auth.AuthState.SignedOut.Reason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SessionExpiredBadgeTest {
  @Test
  fun theBadgeStaysFromExpiryUntilSignedInAgain() = runComposeUiTest {
    val state = MutableStateFlow<AuthState>(AuthState.SignedIn(UserSummary("1", "me")))
    var expired = false
    setContent { expired = rememberSessionExpired(state) }
    waitForIdle()
    assertEquals(false, expired)

    state.value = AuthState.SignedOut(Reason.Expired)
    waitForIdle()
    assertEquals(true, expired)

    // A failed sign-in attempt doesn't clear it; signing in does.
    state.value = AuthState.SignedOut(Reason.WrongCredentials)
    waitForIdle()
    assertEquals(true, expired)
    state.value = AuthState.SignedIn(UserSummary("1", "me"))
    waitForIdle()
    assertEquals(false, expired)
  }
}
