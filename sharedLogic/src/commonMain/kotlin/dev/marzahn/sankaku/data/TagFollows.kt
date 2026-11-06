package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.dto.TagDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which tags the user follows, as changed in this run. A tag's state as loaded comes with a full post's tags
 * (`is_following`); a follow or unfollow here overrides it, by tag id, for every post that shows the tag. The change
 * shows at once and is undone if the API refuses it.
 */
class TagFollows(private val api: TagsApi) {
  private val _overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  /** Followed or not, by tag id, for tags changed in this run. */
  val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

  // One call at a time, so a quick follow and unfollow reach the server in the order they were made.
  private val lock = Mutex()

  /** [tag] as the user sees it now: its loaded state unless it was changed since. */
  fun isFollowing(tag: TagDto, overrides: Map<String, Boolean> = _overrides.value): Boolean =
    tag.id?.let { overrides[it] } ?: tag.isFollowing

  /** Follows [tag] if it isn't followed, else unfollows it. A tag without an id can't be followed. */
  suspend fun toggle(tag: TagDto): Result<Unit> {
    val id = tag.id ?: return Result.failure(ApiError.Http(0, "tag has no id"))
    val was = isFollowing(tag)
    _overrides.update { it + (id to !was) }
    return try {
      lock.withLock { if (was) api.unfollow(id, tag.searchName) else api.follow(id, tag.searchName) }
      Result.success(Unit)
    } catch (e: ApiError) {
      _overrides.update { it + (id to was) }
      Result.failure(e)
    }
  }

  /** Signing in or out changes whose follows they are. */
  fun clear() {
    _overrides.value = emptyMap()
  }
}
