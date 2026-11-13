package dev.marzahn.sankaku.features.auth

import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
    return viewModelAnswering {
      if (++attempts == failAttempt) throw IOException("connection reset")
      respond(WRONG_PASSWORD, HttpStatusCode.Forbidden, jsonHeaders)
    }
  }

  /** The login host answers every login with [login]. */
  private suspend fun viewModelAnswering(login: MockRequestHandler): SignInViewModel {
    val client = createAuthClient(MockEngine(login), RequestLog(), RateLimiter(limits = emptyMap()), ConnectivityState())
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

  /** Only the recorded refusal says the password is wrong; a 429, a 5xx or a block page is Sankaku's doing (#493). */
  @Test
  fun eachLoginAnswerHasItsMessage() = runTest {
    data class Case(val name: String, val error: String, val detail: String?, val login: MockRequestHandler)
    val cases = listOf(
      Case("403 success false", "Wrong username or password.", null) {
        respond(WRONG_PASSWORD, HttpStatusCode.Forbidden, jsonHeaders)
      },
      Case("429", "Too many attempts. Try again in 42 s.", null) {
        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "42"))
      },
      Case("500", UNAVAILABLE, "HTTP 500") {
        respond("""{"success":false}""", HttpStatusCode.InternalServerError, jsonHeaders)
      },
      Case("403 block page", UNAVAILABLE, "HTTP 403") {
        respond("<html><body>Access denied</body></html>", HttpStatusCode.Forbidden, htmlHeaders)
      },
    )
    for (case in cases) {
      val viewModel = viewModelAnswering(case.login)

      submitAndSettle(viewModel)

      val state = viewModel.state.value
      assertEquals(case.error, state.error, case.name)
      assertEquals(case.detail, state.errorDetail, case.name)
      assertEquals(case.name == "403 success false", state.fieldsInError, case.name)
    }
  }
}

/** The login host's answer to a wrong password, as discovery step 8 recorded it (`docs/api.md`). */
private const val WRONG_PASSWORD = """{"success":false,"error":"invalid login or password"}"""
private const val UNAVAILABLE = "Sankaku couldn't sign you in right now. Try again later."
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html")
