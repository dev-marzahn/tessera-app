package dev.marzahn.sankaku.auth

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.LoginResult
import dev.marzahn.sankaku.api.RefreshResult
import dev.marzahn.sankaku.auth.AuthState.SignedOut.Reason
import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

sealed interface SignInResult {
  data object Ok : SignInResult

  data object WrongCredentials : SignInResult

  data object TwoFactorUnsupported : SignInResult

  /** The login or exchange failed for another reason, such as no connection. The state is unchanged. */
  data class Failed(val error: ApiError) : SignInResult
}

/**
 * Owns the tokens. A network failure, timeout, 403, 429 or 5xx never signs the user out; only a refresh the server
 * rejects does.
 */
class AuthSession(
  private val authApi: AuthApi,
  private val tokenStore: TokenStore,
  private val clock: Clock = Clock.System,
  private val expireAccessTokenOnRestore: Boolean = false,
  /** Debug only: `restore()` treats the stored session as run out, as a rejected refresh would ("Session expired"). */
  private val expireSessionOnRestore: Boolean = false,
) {
  private val _state = MutableStateFlow<AuthState>(AuthState.Restoring)
  private val _isSignedIn = MutableStateFlow(false)
  private val mutex = Mutex()

  val state: StateFlow<AuthState> = _state.asStateFlow()
  val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

  /** Called synchronously before every change of the signed-in user. Set once by `createMainClient`. */
  var onTokensChanged: () -> Unit = {}

  /** Loads the stored session without a network call. */
  suspend fun restore() {
    val stored = tokenStore.load()
    when {
      stored == null -> setState(AuthState.SignedOut(Reason.None))
      expireSessionOnRestore || stored.refreshExpiresAt <= clock.now() -> {
        tokenStore.clear()
        setState(AuthState.SignedOut(Reason.Expired))
      }
      else -> {
        if (expireAccessTokenOnRestore) tokenStore.save(stored.copy(accessToken = DEBUG_EXPIRED_ACCESS_TOKEN))
        setState(AuthState.SignedIn(stored.user))
      }
    }
  }

  /** The password is passed on to the login host and kept nowhere. */
  suspend fun signIn(login: String, password: String): SignInResult {
    val pair = try {
      when (val result = authApi.passwordLogin(login, password)) {
        LoginResult.WrongCredentials -> {
          setState(AuthState.SignedOut(Reason.WrongCredentials))
          return SignInResult.WrongCredentials
        }
        LoginResult.TwoFactorRequired -> {
          setState(AuthState.SignedOut(Reason.TwoFactorUnsupported))
          return SignInResult.TwoFactorUnsupported
        }
        is LoginResult.Token -> authApi.exchange(result.loginToken)
      }
    } catch (e: ApiError) {
      return SignInResult.Failed(e)
    }
    mutex.withLock {
      tokenStore.save(pair.toStored())
      onTokensChanged()
      setState(AuthState.SignedIn(pair.user))
    }
    return SignInResult.Ok
  }

  suspend fun signOut() {
    mutex.withLock { signOutLocked(Reason.None) }
  }

  suspend fun currentBearerTokens(): BearerTokens? = tokenStore.load()?.bearer()

  /**
   * Refreshes once, however many callers saw the same expired pair. Under the lock it re-reads the store: if the
   * stored pair differs from [seen], another caller already refreshed and that pair is returned without a request.
   */
  suspend fun refresh(seen: BearerTokens?): BearerTokens? = mutex.withLock {
    val stored = tokenStore.load() ?: return null
    if (seen == null || stored.accessToken != seen.accessToken || stored.refreshToken != seen.refreshToken) {
      return stored.bearer()
    }
    when (val result = authApi.refresh(stored.refreshToken)) {
      is RefreshResult.Ok -> {
        tokenStore.save(result.pair.toStored())
        if (_state.value != AuthState.SignedIn(result.pair.user)) setState(AuthState.SignedIn(result.pair.user))
        result.pair.toStored().bearer()
      }
      RefreshResult.Rejected -> {
        signOutLocked(Reason.Expired)
        null
      }
      is RefreshResult.Transient -> null
    }
  }

  private suspend fun signOutLocked(reason: Reason) {
    tokenStore.clear()
    onTokensChanged()
    setState(AuthState.SignedOut(reason))
  }

  private fun setState(state: AuthState) {
    _state.value = state
    _isSignedIn.value = state is AuthState.SignedIn
  }

  private fun StoredTokens.bearer() = BearerTokens(accessToken, refreshToken)

  companion object {
    internal const val DEBUG_EXPIRED_ACCESS_TOKEN = "expired-by-debug-flag"
  }
}
