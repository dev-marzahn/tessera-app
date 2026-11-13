package dev.marzahn.sankaku.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parsing.ParseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.ceil

/** The endpoint-specific header sets. Which endpoint takes which set is the table in `docs/api.md`. */
enum class HeaderSet(val headers: Map<String, String>) {
  NONE(emptyMap()),

  /** Never combine with [V2] on the keyset endpoint: V2 strips `file_url` and `sample_url`. */
  POSTS(
    mapOf(
      "enable-new-tag-type" to "true",
      "expiration-policy" to "reduced",
      "obfuscate-type" to "tag,wiki",
    ),
  ),
  V2(
    mapOf(
      "accept" to "application/vnd.sankaku.api+json;v=2",
      "api-version" to "2",
      "platform" to "web-app",
    ),
  ),
}

/**
 * Apart from [probe], the only code that sets endpoint headers. Maps every failure to an [ApiError];
 * connectivity and auth state are left to the client plugins.
 */
internal suspend inline fun <reified T> HttpClient.apiRequest(
  method: HttpMethod,
  base: String,
  path: String,
  headerSet: HeaderSet,
  query: Map<String, String> = emptyMap(),
  body: JsonObject? = null,
  aboutId: String? = null,
): T {
  val response = sendApiRequest(method, base, path, headerSet, query, body, aboutId)
  checkResponse(response)
  return try {
    response.body<T>()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    throw ApiError.Decoding(e)
  }
}

internal suspend fun HttpClient.sendApiRequest(
  method: HttpMethod,
  base: String,
  path: String,
  headerSet: HeaderSet,
  query: Map<String, String>,
  body: JsonObject?,
  aboutId: String? = null,
): HttpResponse = try {
  request(base + path) {
    this.method = method
    aboutId?.let { attributes.put(AboutId, it) }
    timeout { requestTimeoutMillis = API_REQUEST_TIMEOUT_MS }
    if (Url(base).host == API_HOST) parameter("lang", "en")
    query.forEach { (key, value) -> parameter(key, value) }
    headerSet.headers.forEach { (key, value) -> header(key, value) }
    body?.let {
      contentType(ContentType.Application.Json)
      setBody(it)
    }
  }
} catch (e: CancellationException) {
  throw e
} catch (e: ParseException) {
  // A plugin couldn't parse a header of a response that did arrive (#470, #473): Sankaku answered, so not offline.
  throw ApiError.Decoding(e)
} catch (e: Exception) {
  throw ApiError.Network(e)
}

/** Throws the [ApiError] for any response that isn't a 2xx JSON body. */
internal suspend fun checkResponse(response: HttpResponse) {
  val status = response.status
  val fromApi = response.call.request.url.host == API_HOST
  when {
    status == HttpStatusCode.Unauthorized -> throw ApiError.Unauthorized()
    status == HttpStatusCode.TooManyRequests -> throw tagLimit(response) ?: rateLimited(response)
    fromApi && (status == HttpStatusCode.Forbidden || !response.isJson()) -> throw ApiError.Rejected(status.value)
    !status.isSuccess() -> {
      val json = response.jsonOrNull()
      if (status == HttpStatusCode.RequestTimeout && json.code() == SEARCH_TIMEOUT_CODE) throw ApiError.SearchTimeout()
      throw ApiError.Http(status.value, errorHint(json))
    }
  }
}

/** A 429's error: its `Retry-After` seconds, or the limiter's pause without one. */
internal fun rateLimited(response: HttpResponse): ApiError.RateLimited {
  // Rounded up to whole seconds for the message and the Retry button; the pause itself is the plugin's.
  val retryAfter = retryAfterPause(response.headers[HttpHeaders.RetryAfter])
    ?.let { ceil(it.inWholeMilliseconds / 1000.0).toLong() }
  return ApiError.RateLimited(retryAfter ?: RateLimiter.DEFAULT_PAUSE.inWholeSeconds)
}

/**
 * A 429 that refuses a search past the tag limit (step d27): `snackbar__anonymous_tags-limit` with `param: "4"` signed
 * out, `snackbar__account_regular_tags-explicit-limit` with `param: "member_tag_limit_9_and_…"` signed in.
 */
internal suspend fun tagLimit(response: HttpResponse): ApiError.TagLimit? {
  val json = response.jsonOrNull()
  val code = json.code() ?: return null
  if (!code.endsWith("tags-limit") && !code.endsWith("tags-explicit-limit")) return null
  val param = ((json as? JsonObject)?.get("param") as? JsonPrimitive)?.contentOrNull.orEmpty()
  return ApiError.TagLimit(param.toIntOrNull() ?: TAG_LIMIT_PARAM.find(param)?.groupValues?.get(1)?.toIntOrNull())
}

private val TAG_LIMIT_PARAM = Regex("""tag_limit_(\d+)""")

private const val SEARCH_TIMEOUT_CODE = "snackbar__account_search-query-timeout"

private fun JsonElement?.code(): String? = ((this as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull

internal fun HttpResponse.isJson(): Boolean = contentType()?.match(ContentType.Application.Json) == true

internal suspend fun HttpResponse.jsonOrNull(): JsonElement? = try {
  val text = bodyAsText().trimStart()
  if (text.startsWith("{") || text.startsWith("[")) Json.parseToJsonElement(text) else null
} catch (e: CancellationException) {
  throw e
} catch (_: Exception) {
  null
}
