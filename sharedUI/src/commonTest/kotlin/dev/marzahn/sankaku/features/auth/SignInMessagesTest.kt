package dev.marzahn.sankaku.features.auth

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.auth.SignInResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInMessagesTest {
  @Test
  fun eachOutcomeHasItsMessage() {
    assertNull(SignInViewModel.messageFor(SignInResult.Ok))
    assertEquals("Wrong username or password.", SignInViewModel.messageFor(SignInResult.WrongCredentials))
    assertTrue(SignInViewModel.messageFor(SignInResult.TwoFactorUnsupported)!!.contains("two-factor"))
    assertTrue(
      SignInViewModel.messageFor(SignInResult.Failed(ApiError.Network(RuntimeException())))!!.contains("connection"),
    )
    assertTrue(SignInViewModel.messageFor(SignInResult.Failed(ApiError.RateLimited(30)))!!.contains("30 s"))
  }
}
