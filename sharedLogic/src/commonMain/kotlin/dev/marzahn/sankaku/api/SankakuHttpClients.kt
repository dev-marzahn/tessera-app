package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.replaceResponse
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
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
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
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

internal const val CONNECT_TIMEOUT_MS = 10_000L
private const val SOCKET_TIMEOUT_MS = 30_000L

/**
 * The image hosts' connect timeout. `s.sankakucomplex.com`, the only host of a book page without a separate sample,
 * drops a new connection's first packets now and then (2026-11-11: 3 of 20 connects took 1 to 2 s, 3 more none within
 * 3 s, against 0.1 s otherwise), and the system then waits seconds before each resend. A fresh connect after 2.5 s
 * usually gets through at once, where the 10 s wait and two retries left a page blank for 20 s or more.
 */
internal const val IMAGE_CONNECT_TIMEOUT_MS = 2_500L

/** How often an image load is tried again after a connect timeout. */
internal const val IMAGE_CONNECT_RETRIES = 4

/** How often an image load is tried again after any other connection error, a read timeout included. */
internal const val IMAGE_RETRIES = 2

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
  install(fingerprintPlugin(fingerprint))
  install(rateLimiterPlugin(limiter))
  install(responseHook(log, connectivity))
}

/**
 * The platform's Chrome on every request, API and image hosts alike (#204); a request about a numeric id claims
 * [BrowserFingerprint.forNumericIds] instead, if there is one (#475). Endpoint headers stay in `apiRequest`.
 */
private fun fingerprintPlugin(fingerprint: BrowserFingerprint): ClientPlugin<Unit> = createClientPlugin("Fingerprint") {
  onRequest { request, _ ->
    val about = request.attributes.getOrNull(AboutId)
    val claimed = fingerprint.forNumericIds?.takeIf { aboutNumericId(request.url.build(), about) } ?: fingerprint
    request.headers[HttpHeaders.UserAgent] = claimed.userAgent
    claimed.clientHints.forEach { (name, value) -> request.headers[name] = value }
  }
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
        // Any answer means Sankaku is reachable: a 5xx is its trouble, shown where the load failed, not "Offline" (#520).
        connectivity.setOffline(false)
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

/** Gives image requests [IMAGE_CONNECT_TIMEOUT_MS]; the rest of [HttpTimeout]'s settings stay the client's. */
private val ImageConnectTimeout: ClientPlugin<Unit> = createClientPlugin("ImageConnectTimeout") {
  onRequest { request, _ ->
    if (request.url.host in IMAGE_HOSTS) request.timeout { connectTimeoutMillis = IMAGE_CONNECT_TIMEOUT_MS }
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

/**
 * Drops `WWW-Authenticate` from `sankakuapi.com`'s 401s before the `Auth` plugin, installed before this, sees them.
 * Sankaku sends `Bearer, error="…"` (a comma straight after the scheme), which the plugin's parser rejects with a
 * `ParseException`, so a rejected access token never got its refresh and the grid said offline (#470, #473). The
 * plugin has one provider, which it picks for a 401 without the header, as for any 401.
 */
private val ApiUnauthorizedHeader: ClientPlugin<Unit> = createClientPlugin("ApiUnauthorizedHeader") {
  on(Send) { request ->
    val call = proceed(request)
    val headers = call.response.headers
    if (request.url.host != API_HOST || call.response.status != HttpStatusCode.Unauthorized ||
      HttpHeaders.WWWAuthenticate !in headers
    ) {
      return@on call
    }
    // A 401's body is a few bytes of JSON; reading it is how the new response gets it without Ktor's internal API.
    val body = call.response.readRawBytes()
    call.replaceResponse(
      headers = Headers.build {
        headers.forEach { name, values -> if (!name.equals(HttpHeaders.WWWAuthenticate, ignoreCase = true)) appendAll(name, values) }
      },
      content = { ByteReadChannel(body) },
    )
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
    install(ImageConnectTimeout)
    install(HttpRequestRetry) {
      // Drops Ktor's default retry on 5xx, so API calls (including favourite POST/DELETE) are never retried.
      noRetry()
      // retryCount is the retry this would be: 1 after the first failure.
      retryOnExceptionIf(maxRetries = IMAGE_CONNECT_RETRIES) { request, cause ->
        request.url.host in IMAGE_HOSTS && cause is IOException && (cause is ConnectTimeoutException || retryCount <= IMAGE_RETRIES)
      }
      exponentialDelay(baseDelayMs = 250, maxDelayMs = 2_000, randomizationMs = 250)
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
    // After `Auth`, so it sees the response first: the plugin installed first wraps those installed later.
    install(ApiUnauthorizedHeader)
  }
  authSession.onTokensChanged = { client.authProvider<BearerAuthProvider>()?.clearToken() }
  return client
}

/**
 * A raw response for `:cli`: never throws on a status. `headerNames` and the keys of `headers` (each header's first
 * value) are lowercase.
 */
data class RawResponse(
  val status: Int,
  val json: JsonElement?,
  val hint: String,
  val finalUrl: String,
  val headerNames: Set<String>,
  val headers: Map<String, String> = emptyMap(),
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
    headers = response.headers.names().associate { it.lowercase() to response.headers[it].orEmpty() },
  )
}
