package dev.marzahn.sankaku.cli

import dev.marzahn.sankaku.api.API_BASE
import dev.marzahn.sankaku.api.HeaderSet
import dev.marzahn.sankaku.api.RawResponse
import dev.marzahn.sankaku.api.probe
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The followed tags (#314): discovery step 25, the list of the account's follows that step 11 didn't find. It reads
 * `GET /users/followings?content_type=tags`, checks `content_type=tag` is refused and that `limit` and `page` page it,
 * then follows [FOLLOW_TAG] (if it isn't followed), finds it in the list, and unfollows it in the same step. Notes and
 * markers hold counts, keys and yes/no only: no tag but [FOLLOW_TAG], no id or user field.
 */
internal class FollowingsSteps(private val smoke: SmokeSteps) {
  suspend fun run() {
    val bearer = smoke.session.currentBearerTokens()?.accessToken
    val token = bearer?.takeIf { smoke.env["SANKAKU_TRY_FOLLOW"] != "false" }
    val reason = "not signed in or SANKAKU_TRY_FOLLOW=false"
    // How many tags the account follows isn't printed, only whether it follows any.
    var count = 0
    smoke.discover("d25 GET /users/followings (tags)", "d25-followings-list", requires = bearer, "not signed in", detail = { "follows any=${yesNo(count > 0)}" }) {
      val raw = list(it)
      val tags = raw.tags()
      count = tags.size
      val singular = list(it, contentType = "tag")
      val second = list(it, limit = 1, page = 2)
      val first = list(it, limit = 1, page = 1)
      val paged = count < 2 || (first.tags().size == 1 && second.tags().size == 1 && first.ids() != second.ids())
      raw to "list[]=${yesNo(raw.obj()?.get("list") is JsonArray)}, all is_following=${yesNo(tags.all { t -> t.str("is_following") == "true" })}, " +
        "tag keys=${tags.firstOrNull()?.keys?.containsAll(TAG_KEYS)?.let(::yesNo) ?: "none followed"}, " +
        "content_type=tag: ${singular.status} ${singular.json.str("code")}, limit and page page it=${yesNo(paged)}"
    }
    var tag: JsonObject? = null
    smoke.discover("d25 the tag to follow", "d25-follow-target", requires = token, reason) {
      val raw = smoke.authClient.probe(HttpMethod.Get, API_BASE, "/tag-and-wiki/name/$FOLLOW_TAG", HeaderSet.V2, bearer = it, query = LANG)
      tag = ((raw.obj()?.get("tag")) as? JsonObject)?.takeIf { t -> t.str("is_following") == "false" && t.str("id") != null }
      raw to "id=${yesNo((raw.obj()?.get("tag") as? JsonObject).str("id") != null)}"
    }
    // Where a new follow lands in the list (#539): first, last or in between (the list is read right after the follow).
    var place = "not listed"
    smoke.discover("d25 follow, listed, unfollow, gone", "d25-follow-listed", requires = tag, "needs $FOLLOW_TAG unfollowed", detail = { "listed right after the follow, placed $place" }) { t ->
      val id = t.str("id")!!
      val body = buildJsonObject {
        put("following_id", id)
        put("following_name", FOLLOW_TAG)
        put("type", "tag")
        put("content_type", "tag")
      }
      val follow = smoke.authClient.probe(HttpMethod.Post, API_BASE, "/users/followings", HeaderSet.V2, bearer = token, query = LANG, body = body)
      var unfollow: RawResponse? = null
      val listed = undoing({
        unfollow = smoke.authClient.probe(HttpMethod.Delete, API_BASE, "/users/followings", HeaderSet.V2, bearer = token, query = LANG, body = body)
        if (unfollow!!.status !in 200..299) fail("unfollow answered ${unfollow!!.status}: unfollow $FOLLOW_TAG by hand")
      }) {
        val ids = allIds(token!!).toList()
        place = when (ids.indexOf(id)) {
          -1 -> "not listed"
          0 -> "first"
          ids.lastIndex -> "last"
          else -> "in between"
        }
        id in ids
      }
      val gone = id !in allIds(token!!)
      unfollow!! to "follow=${follow.status}, listed=${yesNo(listed)}, unfollow=${unfollow!!.status}, gone=${yesNo(gone)}"
    }
    // Whether the list and a full post's `is_following` agree (#539): the app reads the list for Settings and the flag
    // for a post's tags, so a lag in either shows as a tag followed in one and not the other.
    smoke.discover("d25 the list agrees with is_following", "d25-list-agrees", requires = bearer?.let { smoke.firstPostId }, "not signed in or no post") { postId ->
      val listedIds = allIds(bearer!!)
      val raw = smoke.authClient.probe(HttpMethod.Get, API_BASE, "/posts/$postId", HeaderSet.POSTS, bearer = bearer, query = LANG)
      val tags = (raw.obj()?.get("tags") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.str("id") != null }
      val disagree = tags.count { (it.str("is_following") == "true") != (it.str("id") in listedIds) }
      raw to "tags compared>0: ${yesNo(tags.isNotEmpty())}, all agree: ${yesNo(disagree == 0)}"
    }
  }

  // Every followed tag's id, a page of 40 at a time.
  suspend fun allIds(token: String): Set<String> {
    val ids = mutableSetOf<String>()
    for (page in 1..MAX_PAGES) {
      val got = list(token, page = page).ids()
      if (!ids.addAll(got) || got.size < PAGE) break
    }
    return ids
  }

  private suspend fun list(token: String, contentType: String = "tags", limit: Int = PAGE, page: Int = 1) = smoke.authClient.probe(
    HttpMethod.Get, API_BASE, "/users/followings", HeaderSet.V2, bearer = token,
    query = LANG + mapOf("content_type" to contentType, "limit" to "$limit", "page" to "$page"),
  )

  private fun RawResponse.tags() = (obj()?.get("list") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

  private fun RawResponse.ids() = tags().mapNotNull { it.str("id") }

  private fun RawResponse.obj() = json as? JsonObject

  private companion object {
    val LANG = mapOf("lang" to "en")
    const val PAGE = 40
    const val MAX_PAGES = 10

    /** What the app reads of a followed tag (`TagDto`). */
    val TAG_KEYS = setOf("id", "name", "tagName", "type", "post_count", "is_following")

    /** A common general tag the step follows and unfollows again, as step 11 does with another. */
    const val FOLLOW_TAG = "blue_sky"
  }
}
