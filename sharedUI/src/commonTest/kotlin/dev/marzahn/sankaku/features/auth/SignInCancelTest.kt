package dev.marzahn.sankaku.features.auth

import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cancel while a sign-in is in flight: the login request itself is cancelled, so nothing after it runs (no token
 * exchange, nothing stored) and the closed form stays reset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInCancelTest {
  @BeforeTest
  fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun cancelDuringSubmitStopsTheSignIn() = runTest {
    // The login request waits; it would answer as if the password were wrong, which the form would show.
    val requested = CompletableDeferred<Unit>()
    val requestCancelled = CompletableDeferred<Unit>()
    val engine = MockEngine {
      requested.complete(Unit)
      try {
        awaitCancellation()
      } catch (e: CancellationException) {
        requestCancelled.complete(Unit)
        throw e
      }
      @Suppress("UNREACHABLE_CODE")
      respond("", HttpStatusCode.Unauthorized)
    }
    val client = createAuthClient(engine, RequestLog(), RateLimiter(limits = emptyMap()), ConnectivityState())
    val store = InMemoryTokenStore()
    val session = AuthSession(AuthApi(client), store)
    session.restore()
    val controller = SignInController().apply { open() }
    val viewModel = SignInViewModel(session, controller)

    viewModel.submit("user", "password")
    advanceUntilIdle()
    // The engine runs on its own threads.
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { requested.await() } }
    assertTrue(viewModel.state.value.submitting)

    viewModel.cancel()
    // Without the fix the request is never cancelled, and this times out.
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { requestCancelled.await() } }
    advanceUntilIdle()

    assertEquals(SignInUiState(), viewModel.state.value)
    assertFalse(controller.isOpen.value)
    assertEquals(AuthState.SignedOut(AuthState.SignedOut.Reason.None), session.state.value)
    assertNull(store.load(), "nothing was stored")  }
}
