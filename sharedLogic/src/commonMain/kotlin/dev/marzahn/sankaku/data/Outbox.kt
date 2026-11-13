package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.TagDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A favourite, vote or follow made while Sankaku couldn't be reached, waiting in the [Outbox] to be sent (#505). */
@Serializable
sealed interface PendingChange {
  /** [favourited] with [favCount] shown; [wasFavourited] and [wasFavCount] before the first change waiting. */
  @Serializable
  @SerialName("favourite")
  data class Favourite(
    val postId: String,
    val favourited: Boolean,
    val favCount: Int,
    val wasFavourited: Boolean,
    val wasFavCount: Int,
  ) : PendingChange

  /** The account's [vote] (its `userVote` null: taken back); [was] before the first change waiting. */
  @Serializable
  @SerialName("vote")
  data class Vote(val postId: String, val vote: VoteState, val was: VoteState) : PendingChange

  /** The tag [tagId] ([name], searched as [tagName]) to be [following]; before, it was the other way. */
  @Serializable
  @SerialName("follow")
  data class Follow(val tagId: String, val name: String, val tagName: String?, val following: Boolean) : PendingChange {
    val tag: TagDto get() = TagDto(name = name, tagName = tagName, id = tagId, isFollowing = !following)
  }
}

/**
 * The changes made while Sankaku couldn't be reached, in the order made, kept in [file] (`outbox.json`) so they're
 * sent after a restart too; [OutboxSender] sends them. They belong to the account that made them ([owner]):
 * [currentUser] says whose account is signed in, and nothing is kept signed out.
 *
 * One change per post and kind, or per tag: a newer one replaces it, keeping the state from before the first, and one
 * that goes back to that state cancels it, so a favourite and an unfavourite made offline send nothing.
 *
 * Holds post and tag ids, so nothing here is logged (AGENTS.md). Safe from any thread: changes take turns, and each
 * saves at once, as the file stays small.
 */
class Outbox(private val file: TextFile, private val currentUser: () -> String?) {
  private val lock = Mutex()
  private val _changes: MutableStateFlow<List<PendingChange>>

  /** Whose changes these are; null when there are none. */
  @Volatile
  var owner: String?
    private set

  init {
    val saved = file.read()?.let { text -> runCatching { json.decodeFromString(OutboxFile.serializer(), text) }.getOrNull() }
      ?.takeIf { it.version == VERSION && it.owner != null }
    owner = saved?.owner
    _changes = MutableStateFlow(saved?.changes.orEmpty())
  }

  /** What waits, the oldest first. */
  val changes: StateFlow<List<PendingChange>> = _changes.asStateFlow()

  /**
   * Keeps [change] for the signed-in account, merged with what waits for the same post or tag. Returns false signed
   * out, where nothing is kept and the change should be undone.
   */
  suspend fun add(change: PendingChange): Boolean = lock.withLock {
    val user = currentUser() ?: return@withLock false
    val held = if (owner == user) _changes.value else emptyList()
    val earlier = held.firstOrNull { it.sameTarget(change) }
    val merged = if (earlier == null) change else change.after(earlier)
    set(user, held.filterNot { it === earlier } + listOfNotNull(merged))
    true
  }

  /** Whether a [kind] of change waits for [id], for the signed-in account. */
  fun holds(kind: Kind, id: String): Boolean =
    owner != null && owner == currentUser() && _changes.value.any { it.kind == kind && it.id == id }

  /** Drops [change] once sent or refused; a newer change for the same post or tag made since stays. */
  suspend fun remove(change: PendingChange) = lock.withLock {
    val held = _changes.value
    if (change in held) set(owner, held - change)
  }

  /** Drops what waits for [id]'s [kind] of change, as a call for it that reached Sankaku since settled it. */
  suspend fun settled(kind: Kind, id: String) = lock.withLock {
    val held = _changes.value
    val left = held.filterNot { it.kind == kind && it.id == id }
    if (left.size != held.size) set(owner, left)
  }

  /** Forgets everything: signing out, or another account. */
  suspend fun clear() = lock.withLock {
    if (owner != null || _changes.value.isNotEmpty()) set(null, emptyList())
  }

  // Under [lock].
  private fun set(user: String?, changes: List<PendingChange>) {
    _changes.value = changes
    owner = user.takeIf { changes.isNotEmpty() }
    if (changes.isEmpty()) {
      runCatching { file.delete() }
    } else {
      // A full disk loses only this change's survival across a restart; it's still sent in this run.
      runCatching { file.write(json.encodeToString(OutboxFile.serializer(), OutboxFile(VERSION, user, changes))) }
    }
  }

  /** Which call a change makes, so a call that got through for the same target settles it. */
  enum class Kind { Favourite, Vote, Follow }

  @Serializable
  private data class OutboxFile(val version: Int, val owner: String?, val changes: List<PendingChange>)

  private companion object {
    const val VERSION = 1
    val json = Json { ignoreUnknownKeys = true }
  }
}

internal val PendingChange.kind: Outbox.Kind
  get() = when (this) {
    is PendingChange.Favourite -> Outbox.Kind.Favourite
    is PendingChange.Vote -> Outbox.Kind.Vote
    is PendingChange.Follow -> Outbox.Kind.Follow
  }

internal val PendingChange.id: String
  get() = when (this) {
    is PendingChange.Favourite -> postId
    is PendingChange.Vote -> postId
    is PendingChange.Follow -> tagId
  }

private fun PendingChange.sameTarget(other: PendingChange) = kind == other.kind && id == other.id

// This change, made after [earlier] for the same target, counted from the state before [earlier]; null when that
// takes the target back to that state, so nothing needs sending.
private fun PendingChange.after(earlier: PendingChange): PendingChange? = when (this) {
  is PendingChange.Favourite -> (earlier as PendingChange.Favourite)
    .let { copy(wasFavourited = it.wasFavourited, wasFavCount = it.wasFavCount) }
    .takeUnless { it.favourited == it.wasFavourited }
  is PendingChange.Vote -> copy(was = (earlier as PendingChange.Vote).was).takeUnless { it.vote.userVote == it.was.userVote }
  // Following is a toggle: the state before [earlier] is the other way, so a change back takes it back.
  is PendingChange.Follow -> takeIf { following == (earlier as PendingChange.Follow).following }
}

/**
 * Whether [this], from sending a waiting change, means it should wait for the next try rather than be undone:
 * Sankaku wasn't reached, or answered that it can't take it now (a 5xx, 408 or 429, an expired session, a rejected
 * client). Anything else is Sankaku refusing the change itself.
 */
internal fun ApiError.waits(): Boolean = when (this) {
  is ApiError.Network, is ApiError.RateLimited, is ApiError.Rejected, is ApiError.Unauthorized -> true
  is ApiError.Http -> status >= 500 || status == 408
  is ApiError.Decoding, is ApiError.SearchTimeout, is ApiError.TagLimit -> false
}
