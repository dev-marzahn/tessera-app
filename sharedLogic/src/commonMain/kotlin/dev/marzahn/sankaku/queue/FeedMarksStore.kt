package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.api.Feed
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The feeds' marks (#314), on this device only, in `feed-marks.json` (versioned) beside the seen store. Holds post ids,
 * so nothing here is logged. A For you mark older than [FeedReading.FOR_YOU_KEPT] reads as none.
 *
 * Saved [SAVE_DELAY] after the last change, in [scope] on a background thread (a grid's visit changes the marks as it
 * scrolls); with no [scope] (tests), at once. [clear] (sign-out, Clear everything) is written at once.
 */
class FeedMarksStore(
  private val file: TextFile,
  private val clock: Clock = Clock.System,
  private val scope: CoroutineScope? = null,
) {
  private val state = MutableStateFlow(load())

  // Asks the saver to write now (a clear), between its debounced writes, never during one.
  private val flushes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  private val _clears = MutableStateFlow(0)

  /** Goes up on each [clear], so a feed's visit started before it starts again from no marks, and can't write the old ones back. */
  val clears: StateFlow<Int> = _clears.asStateFlow()

  init {
    startSaving()
  }

  /** [feed]'s marks, or null: none kept, or For you's expired. */
  fun get(feed: Feed): FeedMarks? = state.value[feed.tag]?.takeUnless { FeedReading.expired(feed, it, clock.now()) }

  /** Whether [feed] has marks that expired: a For you place kept with them is dropped too. */
  fun expired(feed: Feed): Boolean = state.value[feed.tag]?.let { FeedReading.expired(feed, it, clock.now()) } == true

  fun set(feed: Feed, marks: FeedMarks) {
    if (state.value[feed.tag] == marks) return
    state.update { it + (feed.tag to marks) }
    if (scope == null) write(state.value)
  }

  /**
   * Forgets every feed's marks and saves that at once (with no scope, before returning): sign-out and Clear
   * everything. Through the one writer, so a debounced write can't land after it and bring the old marks back.
   */
  fun clear() {
    state.value = emptyMap()
    _clears.update { it + 1 }
    if (scope == null) write(state.value) else flushes.tryEmit(Unit)
  }

  /** Clears the marks whenever [session] signs out, as the seen store does: the next account never inherits them. */
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

  private fun load(): Map<String, FeedMarks> =
    file.read()?.let { text -> runCatching { json.decodeFromString(MarksFile.serializer(), text) }.getOrNull() }
      ?.takeIf { it.version == VERSION }?.feeds.orEmpty()

  @OptIn(FlowPreview::class)
  private fun startSaving() {
    val scope = scope ?: return
    scope.launch(Dispatchers.Default) {
      merge(state.drop(1).debounce(SAVE_DELAY), flushes.map { state.value }).collect(::write)
    }
  }

  private fun write(feeds: Map<String, FeedMarks>) {
    // A full disk or a locked file loses only the marks since the last save.
    runCatching {
      if (feeds.isEmpty()) file.delete() else file.write(json.encodeToString(MarksFile.serializer(), MarksFile(VERSION, feeds)))
    }
  }

  private companion object {
    const val VERSION = 1
    val SAVE_DELAY = 1.seconds
    val json = Json { ignoreUnknownKeys = true }
  }
}

/** `feed-marks.json`: each feed's marks by its tag (`feed:following`, `feed:for-you`). */
@Serializable
private data class MarksFile(val version: Int, val feeds: Map<String, FeedMarks> = emptyMap())
