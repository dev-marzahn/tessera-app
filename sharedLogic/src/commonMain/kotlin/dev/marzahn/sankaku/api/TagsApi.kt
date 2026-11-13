package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.api.dto.FollowedTags
import dev.marzahn.sankaku.api.dto.SuccessResponse
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.api.dto.TagAndWiki
import dev.marzahn.sankaku.api.dto.TagSuggestion
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
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
   * The tag [tagName] (a search key) and its wiki, for the tag's page (#486): `GET /tag-and-wiki/name/{tag}` with V2,
   * as the web app sends it (`:cli` step 16 found every header set gives the same answer). Signed in, the tag's
   * `is_following` is the account's.
   */
  suspend fun tagAndWiki(tagName: String): TagAndWiki =
    client.apiRequest(HttpMethod.Get, API_BASE, "/tag-and-wiki/name/${tagName.encodeURLPathPart()}", HeaderSet.V2)

  /**
   * One page of the tags the user follows (#314): `GET /users/followings?content_type=tags` (plural; discovery step
   * 25), paged by [limit] and [page] from 1, with V2 as the follow calls. Each is a full tag, `is_following` true.
   */
  suspend fun followedTags(page: Int, limit: Int = FOLLOWED_PAGE_SIZE): List<TagDto> {
    val query = mapOf("content_type" to "tags", "limit" to "$limit", "page" to "$page")
    return client.apiRequest<FollowedTags>(HttpMethod.Get, API_BASE, "/users/followings", HeaderSet.V2, query).list
  }

  /**
   * Follows the tag [id] (search key [tagName]): `POST /users/followings` with V2 and the web app's body (discovery
   * step 11). Whether a tag is followed comes with a full post's tags (`is_following`).
   */
  suspend fun follow(id: String, tagName: String) = following(HttpMethod.Post, id, tagName)

  /** The same call as [follow] with DELETE. */
  suspend fun unfollow(id: String, tagName: String) = following(HttpMethod.Delete, id, tagName)

  /**
   * A tag already in the state asked for counts as done (step d24): a follow sent again answers 400
   * `code: user__content-already-followed`, an unfollow sent again 404 `error: user__not-follow-content`. A change
   * that waited offline is sent again when a call that timed out may have reached Sankaku (#505).
   */
  private suspend fun following(method: HttpMethod, id: String, tagName: String) {
    val body = buildJsonObject {
      put("following_id", id)
      put("following_name", tagName)
      put("type", "tag")
      put("content_type", "tag")
    }
    val response = try {
      client.apiRequest<SuccessResponse>(method, API_BASE, "/users/followings", HeaderSet.V2, body = body)
    } catch (e: ApiError.Http) {
      val already = if (method == HttpMethod.Post) e.status == 400 && ALREADY_FOLLOWED in e.hint else e.status == 404 && NOT_FOLLOWED in e.hint
      if (already) return
      throw e
    }
    if (!response.success) throw ApiError.Http(200, "success=false")
  }

  companion object {
    /** The followed tags' page size. */
    const val FOLLOWED_PAGE_SIZE = 40
    private const val ALREADY_FOLLOWED = "user__content-already-followed"
    private const val NOT_FOLLOWED = "user__not-follow-content"
  }
}
