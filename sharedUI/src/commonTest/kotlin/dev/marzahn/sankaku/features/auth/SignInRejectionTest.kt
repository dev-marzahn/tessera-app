package dev.marzahn.sankaku.features.auth

import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A refused password puts the fields in error and counts each refusal, so the form can refocus Password. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInRejectionTest {
  @BeforeTest
  fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  /** Refuses every login, except when [failAttempt] says the connection drops on that (1-based) attempt. */
  private suspend fun viewModelRefusingEveryLogin(failAttempt: Int = -1): SignInViewModel {
    var attempts = 0
    val engine = MockEngine {
      if (++attempts == failAttempt) throw IOException("connection reset")
      respond("", HttpStatusCode.Unauthorized)
    }
    val client = createAuthClient(engine, RequestLog(), RateLimiter(limits = emptyMap()), ConnectivityState())
    val session = AuthSession(AuthApi(client), InMemoryTokenStore())
    session.restore()
    return SignInViewModel(session, SignInController().apply { open() })
  }

  /** Submits, then lets the engine (on its own threads) and the test dispatcher finish the sign-in. */
  private suspend fun TestScope.submitAndSettle(viewModel: SignInViewModel) {
    viewModel.submit("user", "wrong")
    withContext(Dispatchers.Default) {
      withTimeout(5.seconds) {
        advanceUntilIdle()
        while (viewModel.state.value.submitting) {
          delay(10)
          advanceUntilIdle()
        }
      }
    }
  }

  @Test
  fun wrongCredentialsPutTheFieldsInErrorAndCountTheRefusal() = runTest {
    val viewModel = viewModelRefusingEveryLogin()

    submitAndSettle(viewModel)

    val first = viewModel.state.value
    assertTrue(first.fieldsInError)
    assertEquals("Wrong username or password.", first.error)
    assertEquals(1, first.rejections)
    assertFalse(first.submitting)

    submitAndSettle(viewModel)
    assertEquals(2, viewModel.state.value.rejections)
  }

  @Test
  fun anotherOutcomeLeavesTheFieldsAloneAndResetsTheCount() = runTest {
    val viewModel = viewModelRefusingEveryLogin(failAttempt = 2)

    submitAndSettle(viewModel)
    assertEquals(1, viewModel.state.value.rejections)
    submitAndSettle(viewModel)

    val state = viewModel.state.value
    assertFalse(state.fieldsInError, "a network error isn't shown on the fields")
    assertEquals(0, state.rejections)
    assertTrue(state.error!!.contains("connection"))
  }
}
