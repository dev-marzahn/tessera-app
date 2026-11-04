package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.api.dto.SuccessResponse
import dev.marzahn.sankaku.api.dto.TagSuggestion
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Uses the main client, so a signed-in user gets suggestions for every rating. */
class TagsApi(private val client: HttpClient) {
  /**
   * Tags starting with [prefix]. A leading `-` (exclude) is sent as is; the API answers with `name` `-text` but
   * `tagName` `text`, so the `-` is put back on [TagSuggestion.tagName] here.
   */
  suspend fun autocomplete(prefix: String): List<TagSuggestion> {
    val query = mapOf("tag" to prefix, "show_meta" to "1", "target" to "post", "show_rating" to "true")
    val suggestions = client.apiRequest<List<TagSuggestion>>(
      HttpMethod.Get, API_BASE, "/tags/autosuggestCreating", HeaderSet.POSTS, query,
    )
    return if (prefix.startsWith("-")) suggestions.map { it.excluded() } else suggestions
  }

  /**
   * Follows the tag [id] (search key [tagName]): `POST /users/followings` with V2 and the web app's body (discovery
   * step 11). Whether a tag is followed comes with a full post's tags (`is_following`).
   */
  suspend fun follow(id: String, tagName: String) = following(HttpMethod.Post, id, tagName)

  /** The same call as [follow] with DELETE. */
  suspend fun unfollow(id: String, tagName: String) = following(HttpMethod.Delete, id, tagName)

  private suspend fun following(method: HttpMethod, id: String, tagName: String) {
    val body = buildJsonObject {
      put("following_id", id)
      put("following_name", tagName)
      put("type", "tag")
      put("content_type", "tag")
    }
    val response = client.apiRequest<SuccessResponse>(method, API_BASE, "/users/followings", HeaderSet.V2, body = body)
    if (!response.success) throw ApiError.Http(200, "success=false")
  }
}
