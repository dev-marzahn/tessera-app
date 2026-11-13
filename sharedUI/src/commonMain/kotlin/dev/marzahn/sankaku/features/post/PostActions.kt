package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.couldNot
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.OutboxSender
import dev.marzahn.sankaku.data.PendingChange
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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

/** A favourite toggle's heart: [postId] now shows [favourited] (see [PostActions.hearts]). */
data class FavouriteHeart(val postId: String, val favourited: Boolean)

/**
 * The hearts of the post [shownId] names when each comes, as favourited or not: a viewer's burst plays only its shown
 * post's, so a toggle still running when stepping on doesn't pop on the next picture (#419).
 */
fun Flow<FavouriteHeart>.forShown(shownId: () -> String?): Flow<Boolean> = filter { it.postId == shownId() }.map { it.favourited }

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
  /**
   * Changes made while Sankaku couldn't be reached that it refused once they were sent ([OutboxSender]), already
   * undone: each is answered as a failed toggle (#505).
   */
  refusals: Flow<OutboxSender.Refusal> = emptyFlow(),
) {

  /** The user acted on [post] in the viewer or on its own, other than by ♡ or a vote: a tag action. */
  fun actedOn(post: PostDto) = onActed(post)

  private val _rejections = MutableSharedFlow<Rejection>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** Each failed toggle, after its rollback. */
  val rejections: SharedFlow<Rejection> = _rejections.asSharedFlow()

  private val _hearts = MutableSharedFlow<FavouriteHeart>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /**
   * Each favourite toggled outside the grid (F, the viewer's ♡) that ran, and each rollback of one, as the state the
   * post shows then: the desktop viewer plays its burst from these (#419). One that opened sign-in has none.
   */
  val hearts: SharedFlow<FavouriteHeart> = _hearts.asSharedFlow()

  private fun reject(rejection: Rejection, reason: String) {
    _rejections.tryEmit(rejection)
    messages.showError(reason)
  }

  init {
    // Another account's follows, or none: what a full post said before is the truth again.
    scope.launch { session.isSignedIn.drop(1).collect { follows.clear() } }
    scope.launch { refusals.collect(::refused) }
  }

  private fun refused(refusal: OutboxSender.Refusal) {
    val error = refusal.error
    when (val change = refusal.change) {
      is PendingChange.Favourite -> {
        _hearts.tryEmit(FavouriteHeart(change.postId, change.wasFavourited))
        reject(Rejection.Favourite(change.postId), error.couldNot(if (change.favourited) "favourite" else "unfavourite"))
      }
      is PendingChange.Vote -> reject(
        Rejection.Vote(change.postId),
        error.couldNot(if (change.vote.userVote == null) "take the vote back" else "vote"),
      )
      is PendingChange.Follow -> reject(
        Rejection.Follow(change.name),
        error.couldNot("${if (change.following) "follow" else "unfollow"} ${change.tag.displayName}"),
      )
    }
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
        if (!current.isFavorited) favourite(current, viaDoubleTap, playsHeart = false)
      }
      return false
    }
    // A grid cell plays its own heart.
    val playsHeart = !fromGrid
    if (playsHeart) _hearts.tryEmit(FavouriteHeart(post.id, !post.isFavorited))
    scope.launch { favourite(post, viaDoubleTap, playsHeart) }
    return true
  }

  /**
   * Favourites [post] and never unfavourites it, as the phone viewer's long-press on the picture does (#527, as Keep
   * and favourite in #163): already a favourite, it plays the filled heart again and changes nothing. Otherwise it's
   * [toggleFavourite] (optimistic, rolled back on failure, sign-in while signed out). Returns whether it ran rather
   * than opening sign-in.
   */
  fun addFavourite(post: PostDto): Boolean {
    if (!post.isFavorited || !session.isSignedIn.value) return toggleFavourite(post)
    onActed(post)
    _hearts.tryEmit(FavouriteHeart(post.id, favourited = true))
    return true
  }

  /** [playsHeart]: a rollback plays the heart of the state it went back to, as the toggle played its own. */
  private suspend fun favourite(post: PostDto, viaDoubleTap: Boolean, playsHeart: Boolean) {
    repository.toggleFavourite(post).onFailure {
      if (playsHeart) _hearts.tryEmit(FavouriteHeart(post.id, post.isFavorited))
      reject(
        Rejection.Favourite(post.id, viaDoubleTap),
        it.couldNot(if (post.isFavorited) "unfavourite" else "favourite"),
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
      reject(Rejection.Vote(post.id), it.couldNot(if (score == null) "take the vote back" else "vote"))
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
        reject(Rejection.Follow(tag.name), it.couldNot("${if (following) "unfollow" else "follow"} ${tag.displayName}"))
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
