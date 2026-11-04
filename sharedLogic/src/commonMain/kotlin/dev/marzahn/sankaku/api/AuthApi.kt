package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.api.dto.TokenResponse
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.StoredTokens
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class TokenPair(
  val accessToken: String,
  val refreshToken: String,
  val accessExpiresAt: Instant,
  val refreshExpiresAt: Instant,
  val user: UserSummary,
) {
  fun toStored() = StoredTokens(accessToken, refreshToken, accessExpiresAt, refreshExpiresAt, user)
}

sealed interface LoginResult {
  /** The login host's token, which is only good for [AuthApi.exchange]. */
  data class Token(val loginToken: String) : LoginResult

  data object WrongCredentials : LoginResult

  data object TwoFactorRequired : LoginResult
}

sealed interface RefreshResult {
  data class Ok(val pair: TokenPair) : RefreshResult

  /** The server rejected the refresh token: the session is over. */
  data object Rejected : RefreshResult

  /** Anything else. The session stays; the failed request shows its error. */
  data class Transient(val error: Throwable) : RefreshResult
}

/** Uses the auth client, which has no `Auth` plugin, so these calls never carry a bearer token. */
class AuthApi(private val client: HttpClient, private val clock: Clock = Clock.System) {
  /** Throws [ApiError.Network] when the login host can't be reached. */
  suspend fun passwordLogin(login: String, password: String): LoginResult {
    val body = buildJsonObject {
      put("login", login)
      putJsonObject("mfaParams") { put("login", login) }
      put("password", password)
    }
    val response = client.sendApiRequest(HttpMethod.Post, LOGIN_BASE, "/auth/token", HeaderSet.NONE, emptyMap(), body)
    if (!response.status.isSuccess()) return LoginResult.WrongCredentials
    val json = response.jsonOrNull() as? JsonObject
      ?: throw ApiError.Decoding(IllegalStateException("login response isn't a JSON object"))
    if ((json["success"] as? JsonPrimitive)?.booleanOrNull == false) return LoginResult.WrongCredentials
    json.string("access_token")?.let { return LoginResult.Token(it) }
    if (json.keys.any { it.startsWith("mfa") }) return LoginResult.TwoFactorRequired
    throw ApiError.Decoding(IllegalStateException("login response has no access_token"))
  }

  suspend fun exchange(loginToken: String): TokenPair {
    val body = buildJsonObject {
      put("access_token", loginToken)
      put("client_id", CLIENT_ID)
      put("url", CLIENT_URL)
    }
    val response = client.apiRequest<TokenResponse>(HttpMethod.Post, API_BASE, "/sso/token-exchange", HeaderSet.NONE, body = body)
    return response.toPair() ?: throw ApiError.Decoding(IllegalStateException("exchange returned no token pair"))
  }

  /** Rotates the refresh token. Never throws; see [RefreshResult]. */
  suspend fun refresh(refreshToken: String): RefreshResult {
    val body = buildJsonObject { put("refresh_token", refreshToken) }
    val response = try {
      client.sendApiRequest(HttpMethod.Post, API_BASE, "/auth/token", HeaderSet.NONE, emptyMap(), body)
    } catch (e: ApiError) {
      return RefreshResult.Transient(e)
    }
    if (isRefreshRejection(response.status)) return RefreshResult.Rejected
    if (!response.status.isSuccess() || !response.isJson()) {
      return RefreshResult.Transient(ApiError.Http(response.status.value, errorHint(response.jsonOrNull())))
    }
    val pair = try {
      response.body<TokenResponse>().toPair()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return RefreshResult.Transient(ApiError.Decoding(e))
    }
    return pair?.let { RefreshResult.Ok(it) }
      ?: RefreshResult.Transient(ApiError.Decoding(IllegalStateException("refresh returned no token pair")))
  }

  private fun TokenResponse.toPair(): TokenPair? {
    val now = clock.now()
    return TokenPair(
      accessToken = accessToken ?: return null,
      refreshToken = refreshToken ?: return null,
      accessExpiresAt = now + (accessTokenTtl ?: return null).seconds,
      refreshExpiresAt = now + (refreshTokenTtl ?: return null).seconds,
      user = currentUser ?: return null,
    )
  }

  companion object {
    /**
     * A rejected refresh token: a 401, or the response discovery step 6 recorded for a made-up and a rotated token
     * (see `docs/api.md`). Everything else keeps the session.
     */
    internal fun isRefreshRejection(status: HttpStatusCode): Boolean =
      status == HttpStatusCode.Unauthorized
  }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
