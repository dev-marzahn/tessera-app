package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.api.dto.FavouriteResponse
import dev.marzahn.sankaku.api.dto.Page
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.VoteResponse
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The response has no favourite count: its `score` is the post's vote score, unchanged by (un)favouriting. Callers
 * adjust `fav_count` locally.
 */
data class FavouriteResult(val postId: String)

/** The post's vote count after a vote, when the response has it. Its `score` isn't used: its meaning is unconfirmed. */
data class VoteResult(val voteCount: Int?)

/** Uses the main client. */
class PostsApi(private val client: HttpClient) {
  /**
   * The keyset endpoint with the POSTS set, never V2: V2 strips `file_url` and `sample_url`. A [Feed]'s tag among
   * [tags] is sent as its flag instead. [page] (from 1) goes with the [next] cursor, as the web app sends them: For
   * you ignores the cursor and pages by the number, and the other lists ignore the number (`docs/api.md`).
   */
  suspend fun keyset(tags: List<String>, next: String? = null, page: Int = 1, limit: Int = PAGE_SIZE): Page<PostDto> {
    val (feeds, searched) = tags.partition { Feed.of(it) != null }
    val query = buildMap {
      put("limit", limit.toString())
      put("page", page.toString())
      put("hide_posts_in_books", "in-larger-tags")
      if (searched.isNotEmpty()) put("tags", searched.joinToString(" "))
      feeds.forEach { put(Feed.of(it)!!.flag, "true") }
      next?.let { put("next", it) }
    }
    return client.apiRequest(HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, query)
  }

  /** Fresh URLs for one post, through the keyset `id:` query, which is known to return them. */
  suspend fun byId(id: String): PostDto = byIdOrNull(id) ?: throw ApiError.Http(404, "post not found")

  /**
   * One post with every tag object (the keyset list carries only 5) and fresh URLs. POSTS, never V2: V2 strips
   * `file_url` and `sample_url` here too (discovery step 10).
   */
  suspend fun full(id: String): PostDto = client.apiRequest(HttpMethod.Get, API_BASE, "/posts/$id", HeaderSet.POSTS)

  /**
   * The posts Sankaku recommends for [postId]: `GET /v2/posts?tags=recommended_for_post:<id>`, page-based, a plain
   * array. [tags] narrow it (the locked `rating:s`). POSTS, not the web app's V2, which leaves only `preview_url`
   * (`:cli` step 12). The web app's view tracking (`POST /recommendations/interactions`) is never sent.
   */
  suspend fun recommended(postId: String, tags: List<String> = emptyList(), limit: Int = RECOMMENDED_LIMIT): List<PostDto> {
    val query = mapOf(
      "tags" to (listOf("recommended_for_post:$postId") + tags).joinToString(" "),
      "limit" to limit.toString(),
      "page" to "1",
    )
    return client.apiRequest(HttpMethod.Get, API_BASE, "/v2/posts", HeaderSet.POSTS, query)
  }

  /**
   * The first [limit] children of [parentId], newest first: the keyset `parent:<id>` search, narrowed by [tags] (the
   * locked `rating:s`). It returns the children only, not the parent itself (`:cli` step 13).
   */
  suspend fun children(parentId: String, tags: List<String> = emptyList(), limit: Int = CHILDREN_LIMIT): List<PostDto> =
    keyset(listOf("parent:$parentId") + tags, limit = limit).data

  /** [byId] narrowed by [tags] (the locked `rating:s`): null when they leave it out, or it's gone. */
  suspend fun byIdOrNull(id: String, tags: List<String> = emptyList()): PostDto? =
    keyset(listOf("id:$id") + tags, limit = 1).data.firstOrNull()

  /** No header set: discovery step 5 showed V2 isn't needed. */
  suspend fun favourite(id: String): FavouriteResult = favouriteCall(HttpMethod.Post, id)

  suspend fun unfavourite(id: String): FavouriteResult = favouriteCall(HttpMethod.Delete, id)

  private suspend fun favouriteCall(method: HttpMethod, id: String): FavouriteResult {
    val response = client.apiRequest<FavouriteResponse>(method, API_BASE, "/posts/$id/favorite", HeaderSet.NONE)
    return FavouriteResult(postId = response.postId ?: id)
  }

  /**
   * Votes [score] (1–5) on [id], replacing the account's earlier vote: `PUT /posts/{id}/vote` with V2 (discovery
   * step 12). The post's `user_vote` then holds it.
   */
  suspend fun vote(id: String, score: Int): VoteResult =
    voteCall(HttpMethod.Put, id, buildJsonObject { put("score", score) })

  /** Takes the account's vote on [id] back: the same path with DELETE. */
  suspend fun unvote(id: String): VoteResult = voteCall(HttpMethod.Delete, id, null)

  private suspend fun voteCall(method: HttpMethod, id: String, body: JsonObject?): VoteResult {
    val response = client.apiRequest<VoteResponse>(method, API_BASE, "/posts/$id/vote", HeaderSet.V2, body = body)
    if (response.success == false) throw ApiError.Http(200, "success=false")
    return VoteResult(voteCount = response.voteCount)
  }

  companion object {
    const val PAGE_SIZE = 30

    /** One strip's worth: the details show a few at a time and nothing pages further. The web app asks for 40. */
    const val RECOMMENDED_LIMIT = 20

    /** The details' Children strip, as many as Recommended; with more, its last tile opens them all as a grid. */
    const val CHILDREN_LIMIT = 20
  }
}
