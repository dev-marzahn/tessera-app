package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.dto.TagDto

/**
 * The tags the user follows (#314), for Settings → Account → Followed tags: every page of
 * `GET /users/followings?content_type=tags` (discovery step 25), read until a page comes back short or brings no tag
 * not seen yet, at most [MAX_PAGES] pages. Following or unfollowing one goes through [TagFollows].
 */
class FollowedTagList(private val api: TagsApi, private val pageSize: Int = TagsApi.FOLLOWED_PAGE_SIZE) {
  suspend fun all(): List<TagDto> {
    val tags = LinkedHashMap<String, TagDto>()
    for (page in 1..MAX_PAGES) {
      val got = api.followedTags(page, pageSize)
      val before = tags.size
      got.forEach { tags.putIfAbsent(it.id ?: it.searchName, it) }
      if (got.size < pageSize || tags.size == before) break
    }
    return tags.values.toList()
  }

  /** The followed tags' ids, for Following's context (#539), or null when the list can't be read. */
  suspend fun idsOrNull(): List<String>? = try {
    all().mapNotNull { it.id }
  } catch (e: ApiError) {
    null
  }

  private companion object {
    const val MAX_PAGES = 50
  }
}
