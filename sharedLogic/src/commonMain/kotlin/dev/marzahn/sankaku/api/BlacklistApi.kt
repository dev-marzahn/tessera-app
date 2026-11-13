package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.api.dto.BlacklistAddResponse
import dev.marzahn.sankaku.api.dto.BlacklistResponse
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A tag rule just added: its [ruleId] (to remove it again) and how many rules the account uses with it. */
data class BlacklistedTag(val ruleId: String, val usedRules: Int?)

/**
 * The account's blacklist (#6), on the main client with V2 (`:cli` steps 15 and d22, `docs/api.md` → Tag blacklist).
 * The server applies a `hide` rule to every search of the account at once, on this app and the site alike.
 */
class BlacklistApi(private val client: HttpClient) {
  /** How many blacklist rules the account uses (`count.used_rules`), tag and post rules together. */
  suspend fun usedRules(): Int? = client.apiRequest<BlacklistResponse>(
    HttpMethod.Get, API_BASE, "/users/blacklist", HeaderSet.V2,
    query = mapOf("limit" to "40", "page" to "1", "entity" to "tags"),
  ).count?.usedRules

  /** Adds a `hide` rule on [tagName] (a search key), as the web app does. */
  suspend fun hideTag(tagName: String): BlacklistedTag {
    val body = buildJsonObject {
      put("type", "tag")
      putJsonArray("tags") { add(tagName) }
      put("visibility", "hide")
    }
    val response = client.apiRequest<BlacklistAddResponse>(HttpMethod.Post, API_BASE, "/users/blacklist", HeaderSet.V2, body = body)
    val id = response.rule?.id ?: throw ApiError.Http(200, "no new_blacklist")
    return BlacklistedTag(id, response.count?.usedRules)
  }

  /** Removes the rule [ruleId]; returns how many rules the account uses after. An unknown id is a 404. */
  suspend fun remove(ruleId: String): Int? =
    client.apiRequest<BlacklistResponse>(HttpMethod.Delete, API_BASE, "/users/blacklist/$ruleId", HeaderSet.V2).count?.usedRules
}
