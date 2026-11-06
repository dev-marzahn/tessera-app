package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

/** An image host answered with something that isn't a signed image, for example a redirect to an unsigned URL. */
class ImageRefusedException(val host: String) : Exception("Image refused by $host")

internal val SankakuJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

private const val CONNECT_TIMEOUT_MS = 10_000L
private const val SOCKET_TIMEOUT_MS = 30_000L

/** Settings both clients share. The 30 s request timeout is set per API call, so image loads have none. */
private fun HttpClientConfig<*>.baseConfig(
  log: RequestLog,
  limiter: RateLimiter,
  connectivity: ConnectivityState,
  fingerprint: BrowserFingerprint,
) {
  expectSuccess = false
  install(HttpTimeout) {
    connectTimeoutMillis = CONNECT_TIMEOUT_MS
    socketTimeoutMillis = SOCKET_TIMEOUT_MS
  }
  install(ContentNegotiation) { json(SankakuJson) }
  // The platform's Chrome on every request, API and image hosts alike (#204). Endpoint headers stay in `apiRequest`.
  defaultRequest {
    header(HttpHeaders.UserAgent, fingerprint.userAgent)
    fingerprint.clientHints.forEach { (name, value) -> header(name, value) }
  }
  install(rateLimiterPlugin(limiter))
  install(responseHook(log, connectivity))
}

/**
 * Records every call in [RequestLog] and updates [ConnectivityState]. Wraps `proceed()`, so connection errors, which
 * produce no response, are seen too. Decides by headers only and never consumes the body.
 */
private fun responseHook(log: RequestLog, connectivity: ConnectivityState): ClientPlugin<Unit> =
  createClientPlugin("ResponseHook") {
    on(Send) { request ->
      val host = request.url.host
      val method = request.method.value
      val path = request.url.encodedPath
      val start = TimeSource.Monotonic.markNow()
      try {
        val call = proceed(request)
        val status = call.response.status.value
        log.record(method, host, path, status, start.elapsedNow().inWholeMilliseconds, null)
        connectivity.setOffline(status >= 500, byConnection = false)
        if (host == API_HOST) {
          val isJson = call.response.contentType()?.match(ContentType.Application.Json) == true
          when {
            status == 403 || (!isJson && status != 204) -> connectivity.setRejected(true)
            isJson && status in 200..299 -> connectivity.setRejected(false)
          }
        }
        call
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        log.record(method, host, path, null, start.elapsedNow().inWholeMilliseconds, e::class.simpleName)
        if (e is IOException) connectivity.setOffline(true)
        throw e
      }
    }
  }

/**
 * Throws [ImageRefusedException] when an image request is sent to a URL without the `e` signature, so an unsigned
 * placeholder is never decoded or cached. A redirect is checked through its `Location` before it's followed.
 */
private val ImageRefusal: ClientPlugin<Unit> = createClientPlugin("ImageRefusal") {
  on(Send) { request ->
    val host = request.url.host
    val call = proceed(request)
    if (host in IMAGE_HOSTS) {
      val location = call.response.headers[HttpHeaders.Location]
      val target = if (call.response.status.value in 300..399 && location != null) {
        runCatching { Url(location) }.getOrNull()
      } else {
        call.request.url
      }
      if (target == null || target.parameters["e"] == null) throw ImageRefusedException(host)
    }
    call
  }
}

/**
 * Keeps the `Auth` plugin away from every host but `sankakuapi.com`. Without it, a 401 from an image host makes the
 * plugin resend the request with the bearer token attached.
 */
private val ApiHostOnlyAuth: ClientPlugin<Unit> = createClientPlugin("ApiHostOnlyAuth") {
  onRequest { request, _ ->
    if (request.url.host != API_HOST) request.attributes.put(AuthCircuitBreaker, Unit)
  }
}

/** For login, token exchange and refresh. It has no `Auth` plugin, so it can't trigger a refresh loop. */
fun createAuthClient(
  engine: HttpClientEngine,
  log: RequestLog,
  limiter: RateLimiter,
  connectivity: ConnectivityState,
  fingerprint: BrowserFingerprint = BrowserFingerprint.Current,
): HttpClient = HttpClient(engine) { baseConfig(log, limiter, connectivity, fingerprint) }

/** For API calls and image loads. Sets [AuthSession.onTokensChanged] to drop the plugin's cached token. */
fun createMainClient(
  engine: HttpClientEngine,
  log: RequestLog,
  limiter: RateLimiter,
  connectivity: ConnectivityState,
  authSession: AuthSession,
  fingerprint: BrowserFingerprint = BrowserFingerprint.Current,
): HttpClient {
  val client = HttpClient(engine) {
    baseConfig(log, limiter, connectivity, fingerprint)
    install(ImageRefusal)
    install(HttpRequestRetry) {
      // Drops Ktor's default retry on 5xx, so API calls (including favourite POST/DELETE) are never retried.
      noRetry()
      retryOnExceptionIf(maxRetries = 2) { request, cause ->
        request.url.host in IMAGE_HOSTS && cause is IOException
      }
      exponentialDelay(baseDelayMs = 500)
    }
    install(ApiHostOnlyAuth)
    install(Auth) {
      bearer {
        loadTokens { authSession.currentBearerTokens() }
        refreshTokens {
          if (response.call.request.url.host != API_HOST) null else authSession.refresh(oldTokens)
        }
        sendWithoutRequest { it.url.host == API_HOST }
      }
    }
  }
  authSession.onTokensChanged = { client.authProvider<BearerAuthProvider>()?.clearToken() }
  return client
}

/** A raw response for `:cli`: never throws on a status. `headerNames` are lowercase. */
data class RawResponse(
  val status: Int,
  val json: JsonElement?,
  val hint: String,
  val finalUrl: String,
  val headerNames: Set<String>,
)

/** For `:cli` only. Sends exactly what it's given; [apiRequest] is the app's path. */
suspend fun HttpClient.probe(
  method: HttpMethod,
  base: String,
  path: String,
  headerSet: HeaderSet = HeaderSet.NONE,
  extraHeaders: Map<String, String> = emptyMap(),
  bearer: String? = null,
  query: Map<String, String> = emptyMap(),
  body: JsonObject? = null,
): RawResponse {
  val response = request(if (path.startsWith("http")) path else base + path) {
    this.method = method
    query.forEach { (key, value) -> parameter(key, value) }
    headerSet.headers.forEach { (key, value) -> header(key, value) }
    extraHeaders.forEach { (key, value) -> header(key, value) }
    bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    body?.let {
      contentType(ContentType.Application.Json)
      setBody(it)
    }
  }
  val json = response.jsonOrNull()
  return RawResponse(
    status = response.status.value,
    json = json,
    hint = if (response.status.value >= 400) errorHint(json) else "",
    finalUrl = response.call.request.url.toString(),
    headerNames = response.headers.names().map { it.lowercase() }.toSet(),
  )
}
