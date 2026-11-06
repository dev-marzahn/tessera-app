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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
): T {
  val response = sendApiRequest(method, base, path, headerSet, query, body)
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
): HttpResponse = try {
  request(base + path) {
    this.method = method
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
} catch (e: Exception) {
  throw ApiError.Network(e)
}

/** Throws the [ApiError] for any response that isn't a 2xx JSON body. */
internal suspend fun checkResponse(response: HttpResponse) {
  val status = response.status
  val fromApi = response.call.request.url.host == API_HOST
  when {
    status == HttpStatusCode.Unauthorized -> throw ApiError.Unauthorized()
    status == HttpStatusCode.TooManyRequests -> {
      val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
      throw ApiError.RateLimited(retryAfter ?: RateLimiter.DEFAULT_PAUSE.inWholeSeconds)
    }
    fromApi && (status == HttpStatusCode.Forbidden || !response.isJson()) -> throw ApiError.Rejected(status.value)
    !status.isSuccess() -> {
      val json = response.jsonOrNull()
      if (status == HttpStatusCode.RequestTimeout && json.code() == SEARCH_TIMEOUT_CODE) throw ApiError.SearchTimeout()
      throw ApiError.Http(status.value, errorHint(json))
    }
  }
}

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
