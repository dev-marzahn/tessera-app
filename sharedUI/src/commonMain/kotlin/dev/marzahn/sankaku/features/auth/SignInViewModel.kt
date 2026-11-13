package dev.marzahn.sankaku.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.advice
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.SignInResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Whether the sign-in dialog is open. It isn't a route, so any screen can open it. An action that needs an account
 * opens it with a [reason] (the subtitle, such as "to see Following") and the action itself as [then]; it runs once
 * when the sign-in succeeds and is dropped when the dialog closes any other way. Main thread only: clicks and the
 * view model call it.
 */
class SignInController {
  private val _isOpen = MutableStateFlow(false)
  val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

  private val _reason = MutableStateFlow<String?>(null)

  /** Why sign-in was asked for, shown under the title; null when the user asked for it (the sidebar's Sign in). */
  val reason: StateFlow<String?> = _reason.asStateFlow()

  private var pending: (() -> Unit)? = null

  /** Opens the dialog. A later [open] replaces an earlier one's [reason] and [then]. */
  fun open(reason: String? = null, then: (() -> Unit)? = null) {
    _reason.value = reason
    pending = then
    _isOpen.value = true
  }

  /** Closes the dialog and drops the pending action. */
  fun close() {
    pending = null
    // Closed first, so the closing dialog never draws a frame without its subtitle.
    _isOpen.value = false
    _reason.value = null
  }

  /** The sign-in succeeded: closes the dialog, then runs the pending action, once. */
  fun succeeded() {
    val action = pending
    close()
    action?.invoke()
  }
}

/**
 * [fieldsInError] is true when the server refused the username or password: the form puts both fields in their error
 * state and shows [error] under Password. [rejections] counts consecutive refusals (0 after any other outcome), so the
 * form can send focus back to Password once per refusal. Any other [error] (network, rate limit, Sankaku unavailable,
 * two-factor) is a message under the fields, and [errorDetail] its small print, such as "HTTP 503".
 */
data class SignInUiState(
  val submitting: Boolean = false,
  val error: String? = null,
  val errorDetail: String? = null,
  val fieldsInError: Boolean = false,
  val rejections: Int = 0,
)

/** Never keeps the password: it's passed to [AuthSession.signIn] and dropped. */
class SignInViewModel(private val session: AuthSession, private val controller: SignInController) : ViewModel() {
  private val _state = MutableStateFlow(SignInUiState())
  val state: StateFlow<SignInUiState> = _state.asStateFlow()
  private var signInJob: Job? = null

  fun submit(login: String, password: String) {
    if (_state.value.submitting || login.isBlank() || password.isEmpty()) return
    _state.update { it.copy(submitting = true, error = null, fieldsInError = false) }
    signInJob = viewModelScope.launch {
      val result = session.signIn(login.trim(), password)
      val rejected = result == SignInResult.WrongCredentials
      _state.update {
        SignInUiState(
          error = messageFor(result),
          errorDetail = detailFor(result),
          fieldsInError = rejected,
          // Back to 0 on any other outcome, so a form opened later (the view model outlives it) doesn't refocus.
          rejections = if (rejected) it.rejections + 1 else 0,
        )
      }
      if (result == SignInResult.Ok) controller.succeeded()
    }
  }

  /** Stops a sign-in still in flight, so its result can't land after the form has closed. */
  fun cancel() {
    signInJob?.cancel()
    signInJob = null
    _state.value = SignInUiState()
    controller.close()
  }

  companion object {
    private const val UNAVAILABLE = "Sankaku couldn't sign you in right now. Try again later."

    fun messageFor(result: SignInResult): String? = when (result) {
      SignInResult.Ok -> null
      SignInResult.WrongCredentials -> "Wrong username or password."
      // A way out (#520): the app works signed out, and the site can turn two-factor sign-in off.
      SignInResult.TwoFactorUnsupported ->
        "This account uses two-factor sign-in, which the app doesn't support yet. Browse signed out, or turn " +
          "two-factor sign-in off in your account settings on sankakucomplex.com."
      is SignInResult.Failed -> when (val e = result.error) {
        is ApiError.Network ->
          if (e.timedOut) UNAVAILABLE else "Can't reach Sankaku. Check your connection and try again."
        is ApiError.RateLimited -> "Too many attempts. Try again in ${e.retryAfterSeconds} s."
        // A 401 from the exchange: the login took the password, so it isn't wrong.
        is ApiError.Http, is ApiError.Unauthorized -> UNAVAILABLE
        else -> e.advice().sentence
      }
    }

    /** The small print under [messageFor]'s "try again later": the status, or that Sankaku didn't answer in time. */
    fun detailFor(result: SignInResult): String? = when (val e = (result as? SignInResult.Failed)?.error) {
      is ApiError.Http -> "HTTP ${e.status}"
      is ApiError.Unauthorized -> "HTTP 401"
      is ApiError.Network -> if (e.timedOut) "Timed out" else null
      else -> null
    }
  }
}
