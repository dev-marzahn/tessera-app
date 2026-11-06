package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.TagFollows
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.ui.Messages
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** A toggle that failed and was rolled back, so its control can answer (touch: a shake, an outline, a haptic). */
sealed interface Rejection {
  /**
   * [viaDoubleTap]: the toggle came from a grid cell's own toggle (TalkBack's Favourite action; a double-tap until
   * #324), which the cell answers, not the viewer's ♡.
   */
  data class Favourite(val postId: String, val viaDoubleTap: Boolean = false) : Rejection

  data class Vote(val postId: String) : Rejection

  data class Follow(val tagName: String) : Rejection
}

/**
 * What any screen can do to a post. Runs on the app scope, so a toggle finishes even if its screen closes; a failure
 * is reported as an error message (the reason) and in [rejections] (for the control), and the toggle has already
 * been undone by [PostsRepository] or [TagFollows].
 */
class PostActions(
  private val repository: PostsRepository,
  private val session: AuthSession,
  private val signIn: SignInController,
  private val messages: Messages,
  private val scope: CoroutineScope,
  /** The follows changed in this run. */
  val follows: TagFollows,
  /**
   * Called when the user acts on a post in the viewer or on its own (♡, a vote, a tag action): it's seen ([actedOn]).
   * Favouriting from the grid doesn't call it.
   */
  private val onActed: (PostDto) -> Unit = {},
) {

  /** The user acted on [post] in the viewer or on its own, other than by ♡ or a vote: a tag action. */
  fun actedOn(post: PostDto) = onActed(post)

  private val _rejections = MutableSharedFlow<Rejection>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** Each failed toggle, after its rollback. */
  val rejections: SharedFlow<Rejection> = _rejections.asSharedFlow()

  private fun reject(rejection: Rejection, reason: String) {
    _rejections.tryEmit(rejection)
    messages.showError(reason)
  }

  init {
    // Another account's follows, or none: what a full post said before is the truth again.
    scope.launch { session.isSignedIn.drop(1).collect { follows.clear() } }
  }

  /** Whether an action would run, rather than open sign-in. */
  val isSignedIn: StateFlow<Boolean> get() = session.isSignedIn

  /** Opens sign-in, as any action does while signed out. */
  fun openSignIn() = signIn.open()

  /**
   * Opens sign-in saying [reason]; once signed in, [action] runs on the app scope after the caches have been cleared
   * for the new account, so that clear can't wipe what [action] changes. Signed out again by then, it's dropped.
   */
  private fun signInThen(reason: String, action: suspend () -> Unit) {
    signIn.open(reason) {
      scope.launch {
        repository.awaitCurrentAccount()
        if (session.isSignedIn.value) action()
      }
    }
  }

  /**
   * [post] should already be merged with its override, so the toggle starts from what the user sees. Returns whether
   * it toggled: false signed out, where it opens sign-in instead and favourites [post] once signed in.
   * [viaDoubleTap] marks its [Rejection] for the grid. [fromGrid]: a grid cell's ♡ or Favourite action, which doesn't make
   * the post seen; anywhere else (the viewer, a post tab) it does.
   */
  fun toggleFavourite(post: PostDto, viaDoubleTap: Boolean = false, fromGrid: Boolean = false): Boolean {
    if (!fromGrid) onActed(post)
    if (!session.isSignedIn.value) {
      signInThen("to favourite") {
        // Signed out, the post can't say whether the account favourited it already: ask, and favourite it only if
        // not, so the count isn't raised twice. If asking fails, favourite it from what the user saw.
        val current = try {
          repository.fullPost(post.id)
        } catch (_: ApiError) {
          post
        }
        if (!current.isFavorited) favourite(current, viaDoubleTap)
      }
      return false
    }
    scope.launch { favourite(post, viaDoubleTap) }
    return true
  }

  private suspend fun favourite(post: PostDto, viaDoubleTap: Boolean) {
    repository.toggleFavourite(post).onFailure {
      reject(
        Rejection.Favourite(post.id, viaDoubleTap),
        if (post.isFavorited) "Couldn't unfavourite: ${it.message}" else "Couldn't favourite: ${it.message}",
      )
    }
  }

  /**
   * Votes [score] (1–5) on [post], or takes the vote back with null; signed out, it opens sign-in instead and votes
   * once signed in. Votes are only cast in the viewer or a post tab, so the post is seen.
   */
  fun vote(post: PostDto, score: Int?) {
    onActed(post)
    if (!session.isSignedIn.value) {
      signInThen("to vote") { castVote(post, score) }
      return
    }
    scope.launch { castVote(post, score) }
  }

  private suspend fun castVote(post: PostDto, score: Int?) {
    repository.vote(post, score).onFailure {
      reject(Rejection.Vote(post.id), if (score == null) "Couldn't take the vote back: ${it.message}" else "Couldn't vote: ${it.message}")
    }
  }

  /**
   * Follows [tag], or unfollows it if it's followed; signed out, it opens sign-in instead. Screens offer following
   * only when signed in, so this doesn't carry the follow through sign-in.
   */
  fun toggleFollow(tag: TagDto) {
    if (!session.isSignedIn.value) {
      signIn.open()
      return
    }
    val following = follows.isFollowing(tag)
    scope.launch {
      follows.toggle(tag).onFailure {
        reject(Rejection.Follow(tag.name), "Couldn't ${if (following) "unfollow" else "follow"} ${tag.displayName}: ${it.message}")
      }
    }
  }
}

/**
 * How many times [rejection] has come from [actions] since this composed: the count a control's `rejected` modifier
 * answers.
 */
@Composable
fun rememberRejections(actions: PostActions, rejection: Rejection): Int {
  var count by remember(rejection) { mutableIntStateOf(0) }
  LaunchedEffect(actions, rejection) { actions.rejections.collect { if (it == rejection) count++ } }
  return count
}
