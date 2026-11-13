package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.data.TextFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One seen post: its id, its md5 when known, and when it was last seen. */
data class SeenPost(val id: String, val md5: String?, val at: Instant)

/**
 * The posts the user has seen (Glossary → Seen), on this device only: nothing here is synced, and nothing is logged,
 * since it holds post ids and md5s (AGENTS.md).
 *
 * - **Bounded:** at most [limit] posts (50,000), the newest kept; and posts not seen for [KEPT_FOR] (90 days) are pruned
 *   at launch, at most once a day.
 * - **Saved** to [file] [SAVE_DELAY] after the last change, in [scope] on a background thread; with no [scope] (tests),
 *   at once. [clear] is saved at once. One coroutine does every write, so two never overlap.
 * - **Copies:** a post counts as seen when its id or its md5 was, so an exact copy under another id counts too.
 *
 * Safe from any thread: each change swaps in a new immutable snapshot atomically, and saves read a snapshot.
 */
class SeenStore(
  private val file: TextFile,
  private val clock: Clock = Clock.System,
  private val scope: CoroutineScope? = null,
  private val limit: Int = LIMIT,
) {
  private val state: MutableStateFlow<Snapshot>

  // Asks the saver to write now (a clear), between its debounced writes, never during one.
  private val flushes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  init {
    val saved = file.read()?.let { text -> runCatching { json.decodeFromString(SeenFile.serializer(), text) }.getOrNull() }
    val posts = LinkedHashMap<String, SeenPost>()
    saved?.posts.orEmpty().sortedBy { it.at }.forEach { entry ->
      posts.remove(entry.id)
      posts[entry.id] = SeenPost(entry.id, entry.md5, Instant.fromEpochSeconds(entry.at))
    }
    state = MutableStateFlow(Snapshot(posts.bounded(), saved?.pruned?.let(Instant::fromEpochSeconds)))
    // The launch prune is written here, before the saver starts, so no other write can overlap it.
    if (prune()) write(state.value)
    startSaving(state.value)
  }

  // Counts the changes made since launch, for [revision].
  private val _revision = MutableStateFlow(0)

  /**
   * Goes up whenever a post is recorded or the store is cleared, so a screen showing seen posts can recompose then.
   * Only a counter: it says nothing about which posts changed.
   */
  val revision: StateFlow<Int> get() = _revision

  /** How many posts are held. */
  val size: Int get() = state.value.posts.size

  /** Every post held, the least recently seen first. */
  val posts: List<SeenPost> get() = state.value.posts.values.toList()

  /** Records the post [postId] (with its [md5] when known) as seen now. */
  fun record(postId: String, md5: String?) {
    val now = clock.now()
    change { current ->
      val posts = LinkedHashMap(current.posts)
      val known = posts.remove(postId)
      posts[postId] = SeenPost(postId, md5 ?: known?.md5, now)
      Snapshot(posts.bounded(), current.pruned)
    }
  }

  /** Whether the post [postId], or an exact copy of it by [md5], was seen. */
  fun isSeen(postId: String, md5: String? = null): Boolean = lastSeen(postId, md5) != null

  /** When the post [postId], or an exact copy of it by [md5], was last seen; null if never (or pruned). */
  fun lastSeen(postId: String, md5: String? = null): Instant? {
    val snapshot = state.value
    val byId = snapshot.posts[postId]?.at
    val byMd5 = md5?.let { snapshot.byMd5[it.lowercase()] }
    return listOfNotNull(byId, byMd5).maxOrNull()
  }

  /**
   * "Seen since kept": whether the kept post [postId] (or a copy, by [md5]) was seen after [keptAt]. Keeping it again
   * moves its `kept at` to now, so it's unseen again until it's seen again ("Kept again").
   */
  fun seenSince(postId: String, md5: String?, keptAt: Instant): Boolean =
    lastSeen(postId, md5)?.let { it > keptAt } == true

  // At launch: drops the posts not seen for [KEPT_FOR], unless that was done less than [PRUNE_EVERY] ago. Returns
  // whether it ran.
  private fun prune(): Boolean {
    val now = clock.now()
    val current = state.value
    if (current.pruned != null && now - current.pruned < PRUNE_EVERY) return false
    val cutoff = now - KEPT_FOR
    state.value = Snapshot(LinkedHashMap(current.posts.filterValues { it.at >= cutoff }), pruned = now)
    return true
  }

  /**
   * Forgets every seen post and saves that at once (with no scope, before returning): sign-out and Clear everything.
   * Safe from any thread: a record racing it can't bring the old posts back, and its write can't interleave with a
   * debounced one.
   */
  fun clear() {
    state.update { Snapshot(LinkedHashMap(), it.pruned) }
    _revision.update { it + 1 }
    if (scope == null) write(state.value) else flushes.tryEmit(Unit)
  }

  /**
   * Clears the store whenever [session] signs out: going from signed in to signed out (Sign out, or a refresh the
   * site rejected), or a session that ran out, found at launch, so the next account to sign in never inherits it.
   * A launch with no session at all clears nothing (the posts were seen signed out, by whoever uses the device).
   */
  fun clearOnSignOut(session: AuthSession, scope: CoroutineScope) {
    scope.launch {
      var signedIn: Boolean? = null
      session.state.collect { state ->
        if (state is AuthState.Restoring) return@collect
        val now = state is AuthState.SignedIn
        val expired = state is AuthState.SignedOut && state.reason == AuthState.SignedOut.Reason.Expired
        if ((signedIn == true && !now) || (signedIn == null && expired)) clear()
        signedIn = now
      }
    }
  }

  // Atomic, so changes from two threads (a record, a sign-out's clear) never undo each other.
  private fun change(next: (Snapshot) -> Snapshot) {
    state.update(next)
    _revision.update { it + 1 }
    if (scope == null) write(state.value)
  }

  // One collector does every write, one after another: each change [SAVE_DELAY] after the last, and a clear at once.
  // Every state but the one it started from counts, so a record made before it subscribed is saved too.
  @OptIn(FlowPreview::class)
  private fun startSaving(initial: Snapshot) {
    val scope = scope ?: return
    scope.launch(Dispatchers.Default) {
      merge(state.filter { it !== initial }.debounce(SAVE_DELAY), flushes.map { state.value }).collect(::write)
    }
  }

  private fun write(snapshot: Snapshot) {
    val entries = snapshot.posts.values.map { SeenEntry(it.id, it.md5, it.at.epochSeconds) }
    // A full disk or a locked file loses only what was seen since the last save.
    runCatching { file.write(json.encodeToString(SeenFile.serializer(), SeenFile(VERSION, snapshot.pruned?.epochSeconds, entries))) }
  }

  // The newest [limit] posts; the map is ordered from least to most recently seen.
  private fun LinkedHashMap<String, SeenPost>.bounded(): LinkedHashMap<String, SeenPost> {
    val excess = size - limit
    if (excess <= 0) return this
    val keys = keys.take(excess)
    keys.forEach(::remove)
    return this
  }

  /**
   * What's held at one moment. Never changed once made, so a save can read it on another thread. Equal only to itself,
   * so the state flow doesn't compare 50,000 posts on each change.
   */
  private class Snapshot(val posts: LinkedHashMap<String, SeenPost>, val pruned: Instant?) {
    val byMd5: Map<String, Instant> by lazy {
      buildMap { posts.values.forEach { post -> post.md5?.lowercase()?.let { put(it, maxOf(get(it) ?: post.at, post.at)) } } }
    }
  }

  companion object {
    /** How many posts are kept at most, the newest first: about 3 MB of JSON. */
    const val LIMIT: Int = 50_000

    /** A post not seen again for this long is forgotten. */
    val KEPT_FOR: Duration = 90.days

    /** Pruning runs at launch, but no more often than this. */
    val PRUNE_EVERY: Duration = 1.days

    val SAVE_DELAY: Duration = 2.seconds

    private const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }
  }
}

/** `seen.json`: [version], when it was last [pruned] (UTC seconds), and the [posts], least recently seen first. */
@Serializable
private data class SeenFile(val version: Int, val pruned: Long? = null, val posts: List<SeenEntry> = emptyList())

/** One post, with short names: the file holds up to [SeenStore.LIMIT] of them. [at] is in UTC seconds. */
@Serializable
private data class SeenEntry(@SerialName("i") val id: String, @SerialName("m") val md5: String? = null, @SerialName("t") val at: Long)
