package dev.marzahn.sankaku.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
  /** A connection error or timeout: no response arrived. */
  class Network(cause: Throwable) : ApiError("Can't reach Sankaku", cause)

  class Unauthorized : ApiError("Unauthorized")

  class RateLimited(val retryAfterSeconds: Long) : ApiError("Rate-limited, retry in $retryAfterSeconds s")

  /** A 403, or a body that isn't JSON, from `sankakuapi.com`: Sankaku rejected this client. */
  class Rejected(val status: Int) : ApiError("Sankaku rejected this client ($status)")

  /** A 408 with `code: snackbar__account_search-query-timeout`: the search ran past the account's search-time limit. */
  class SearchTimeout : ApiError("Sankaku took too long to search")

  class Http(val status: Int, val hint: String) : ApiError("HTTP $status${if (hint.isEmpty()) "" else ": $hint"}")

  class Decoding(cause: Throwable) : ApiError("Unexpected response", cause)
}

/** The error fields of a response body, as `key=value` pairs, each value cut to 120 characters. */
fun errorHint(json: JsonElement?): String {
  val obj = json as? JsonObject ?: return ""
  return listOf("code", "error", "message", "errors")
    .mapNotNull { key -> obj[key]?.let { "$key=${it.toString().take(120)}" } }
    .joinToString(", ")
}
