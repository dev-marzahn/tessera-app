package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.net.ConnectivityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends what waits in the [outbox] (#505), in the order it was made: at launch, and whenever the connection may be back
 * (a response clears [ConnectivityState.offline], or [ConnectivityState.retryRequests] goes up). A change Sankaku
 * refuses is undone and reported in [refusals]; one it can't take now (no connection, a 5xx, a 429) stops the round
 * and waits for the next, so later changes keep their order behind it.
 *
 * The outbox is the signed-in account's: it's cleared when that account signs out or another signs in, and the
 * waiting follows with it.
 */
class OutboxSender(
  private val outbox: Outbox,
  private val posts: PostsRepository,
  private val follows: TagFollows,
  private val session: AuthSession,
  private val connectivity: ConnectivityState,
  private val scope: CoroutineScope,
) {
  /** A waiting [change] that Sankaku refused when it was sent, after it was undone. */
  data class Refusal(val change: PendingChange, val error: ApiError)

  private val _refusals = MutableSharedFlow<Refusal>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** Each waiting change Sankaku refused, after it was undone. */
  val refusals: SharedFlow<Refusal> = _refusals.asSharedFlow()

  // One round at a time, so a change is never sent twice.
  private val sending = Mutex()

  /** Starts watching the account and the connection. Call once, after the session is restored. */
  fun start() {
    scope.launch {
      session.state.collect { state ->
        if (state is AuthState.Restoring) return@collect
        val user = (state as? AuthState.SignedIn)?.user?.id
        val owner = outbox.owner
        if (owner != null && owner != user) {
          outbox.clear()
          follows.clear()
        }
      }
    }
    scope.launch {
      // Launch counts as a chance: offline starts false.
      merge(connectivity.offline.filter { !it }.map { }, connectivity.retryRequests.drop(1).map { }).collect { send() }
    }
  }

  /** Returns once no round is running: for tests, whose requests finish on the engine's threads. */
  internal suspend fun idle() = sending.withLock {}

  /** Sends what waits, oldest first, until something must wait. Signed out, or another account's, nothing is sent. */
  suspend fun send() = sending.withLock {
    if (outbox.changes.value.isEmpty()) return@withLock
    // Not before the caches hold this account's state, so a launch's clear can't undo what a send settles.
    posts.awaitCurrentAccount()
    val user = (session.state.value as? AuthState.SignedIn)?.user?.id ?: return@withLock
    for (change in outbox.changes.value) {
      if (outbox.owner != user) return@withLock
      val error = when (change) {
        is PendingChange.Favourite -> posts.send(change)
        is PendingChange.Vote -> posts.send(change)
        is PendingChange.Follow -> follows.send(change)
      } ?: continue
      if (error.waits()) return@withLock
      _refusals.tryEmit(Refusal(change, error))
    }
  }
}
