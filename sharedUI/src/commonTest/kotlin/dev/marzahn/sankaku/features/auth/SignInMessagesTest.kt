package dev.marzahn.sankaku.features.auth

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.auth.SignInResult
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
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

  /** Each message says what to do next, in plain words (#520). */
  @Test
  fun messagesHaveAWayOutAndNoInternalText() {
    val twoFactor = SignInViewModel.messageFor(SignInResult.TwoFactorUnsupported)!!
    assertTrue("Browse signed out" in twoFactor, twoFactor)
    val unreadable = SignInViewModel.messageFor(SignInResult.Failed(ApiError.Decoding(IllegalStateException("x"))))!!
    assertTrue("Unexpected response" !in unreadable && "Try again" in unreadable, unreadable)
    val rejected = SignInViewModel.messageFor(SignInResult.Failed(ApiError.Rejected(403)))!!
    assertTrue("client" !in rejected && "Try again later" in rejected, rejected)
  }

  /** Sankaku answered, but not with a refusal, or didn't answer in time: no hint that the password is wrong (#493). */
  @Test
  fun anAnswerOtherThanTheRefusalSaysTryAgainLater() {
    val unavailable = "Sankaku couldn't sign you in right now. Try again later."
    val http = SignInResult.Failed(ApiError.Http(503, "code=down"))
    assertEquals(unavailable, SignInViewModel.messageFor(http))
    assertEquals("HTTP 503", SignInViewModel.detailFor(http))

    val timedOut = SignInResult.Failed(ApiError.Network(HttpRequestTimeoutException("https://login.example/", 30_000)))
    assertEquals(unavailable, SignInViewModel.messageFor(timedOut))
    assertEquals("Timed out", SignInViewModel.detailFor(timedOut))

    val quiet = SignInResult.Failed(ApiError.Network(SocketTimeoutException("read timed out")))
    assertEquals(unavailable, SignInViewModel.messageFor(quiet))
    assertEquals("Timed out", SignInViewModel.detailFor(quiet))

    val exchange401 = SignInResult.Failed(ApiError.Unauthorized())
    assertEquals(unavailable, SignInViewModel.messageFor(exchange401))
    assertEquals("HTTP 401", SignInViewModel.detailFor(exchange401))

    val noConnect = SignInResult.Failed(ApiError.Network(ConnectTimeoutException("connect timed out")))
    assertTrue(SignInViewModel.messageFor(noConnect)!!.startsWith("Can't reach Sankaku"))
    assertNull(SignInViewModel.detailFor(noConnect))
    assertNull(SignInViewModel.detailFor(SignInResult.Failed(ApiError.Network(RuntimeException()))))
    assertNull(SignInViewModel.detailFor(SignInResult.WrongCredentials))
  }
}
