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
 * shows at once and is undone if the API refuses it. Made while Sankaku can't be reached, it stays and waits in the
 * [outbox] (#505).
 */
class TagFollows(private val api: TagsApi, private val outbox: Outbox = Outbox(InMemoryTextFile()) { null }) {
  private val _overrides = MutableStateFlow(pending())

  /** Followed or not, by tag id, for tags changed in this run. */
  val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

  // The tags changed in this run, by id, the last changed last: Followed tags lists the follows among them (#539).
  private val _changed = MutableStateFlow<Map<String, TagDto>>(emptyMap())

  /** The tags followed or unfollowed in this run, by id, in the order they were last changed. */
  val changed: StateFlow<Map<String, TagDto>> = _changed.asStateFlow()

  // One call at a time, so a quick follow and unfollow reach the server in the order they were made.
  private val lock = Mutex()

  /**
   * Sankaku's list of followed tags ([listed], newest follow first) with this run's changes (#539): a tag followed here
   * that the list doesn't have yet goes first, the last followed first, so the list matches right after a follow even
   * while the call waits (or waits offline). A tag unfollowed here stays where the list has it, shown unfollowed, and
   * one followed here then unfollowed only while its id is in [keep] (the page still shows its row).
   */
  fun withChanges(
    listed: List<TagDto>,
    changed: Map<String, TagDto> = _changed.value,
    overrides: Map<String, Boolean> = _overrides.value,
    keep: Set<String> = emptySet(),
  ): List<TagDto> {
    val ids = listed.mapNotNullTo(mutableSetOf()) { it.id }
    val added = changed.values.reversed().filter { it.id !in ids && (overrides[it.id] == true || it.id in keep) }
    return added + listed
  }

  /** [tag] as the user sees it now: its loaded state unless it was changed since. */
  fun isFollowing(tag: TagDto, overrides: Map<String, Boolean> = _overrides.value): Boolean =
    tag.id?.let { overrides[it] } ?: tag.isFollowing

  /** Follows [tag] if it isn't followed, else unfollows it. A tag without an id can't be followed. */
  suspend fun toggle(tag: TagDto): Result<Unit> {
    val id = tag.id ?: return Result.failure(ApiError.Http(0, "tag has no id"))
    val was = isFollowing(tag)
    _overrides.update { it + (id to !was) }
    _changed.update { it - id + (id to tag) }
    // The outbox changes only under the lock, in the order the calls ran, so a round sending a follow that waited can't
    // send it after this, and this one waiting can't land after a later toggle that got through.
    val error = lock.withLock {
      try {
        if (was) api.unfollow(id, tag.searchName) else api.follow(id, tag.searchName)
        outbox.settled(Outbox.Kind.Follow, id)
        null
      } catch (e: ApiError) {
        e.takeUnless { it is ApiError.Network && outbox.add(PendingChange.Follow(id, tag.name, tag.tagName, following = !was)) }
      }
    }
    if (error == null) return Result.success(Unit)
    _overrides.update { it + (id to was) }
    return Result.failure(error)
  }

  /**
   * Sends [change], which waited in the [outbox]: sent, it leaves the outbox; refused by Sankaku, it leaves it and the
   * tag goes back, unless the user changed it again meanwhile. One that no longer waits once the lock is taken (a
   * toggle that got through since settled it) isn't sent. Returns null once sent or no longer waiting, or the error:
   * [waits] says whether the change stays for the next try.
   */
  suspend fun send(change: PendingChange.Follow): ApiError? = lock.withLock {
    if (change !in outbox.changes.value) return@withLock null
    val error = try {
      if (change.following) api.follow(change.tagId, change.tag.searchName) else api.unfollow(change.tagId, change.tag.searchName)
      null
    } catch (e: ApiError) {
      e
    }
    if (error?.waits() == true) return@withLock error
    outbox.remove(change)
    if (error != null) _overrides.update { if (it[change.tagId] == change.following) it + (change.tagId to !change.following) else it }
    error
  }

  /**
   * Signing in or out changes whose follows they are: back to what full posts say, but for the follows still waiting
   * in the [outbox] (none once it's cleared for another account).
   */
  fun clear() {
    _overrides.value = pending()
    _changed.value = emptyMap()
  }

  private fun pending(): Map<String, Boolean> =
    outbox.changes.value.filterIsInstance<PendingChange.Follow>().associate { it.tagId to it.following }
}
