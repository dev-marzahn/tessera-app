package dev.marzahn.sankaku.features.post

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.PostTitle
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.KEPT_AGAIN
import dev.marzahn.sankaku.ui.components.alreadySpoken
import dev.marzahn.sankaku.ui.components.inTabLabel
import kotlin.math.roundToInt

/*
 * What TalkBack and the desktop's accessibility tree say for a post (#143). Never an id, md5 or user field: a post is
 * named by the tab-label rule ([PostTitle]) and its kind.
 */

/** [post]'s name by the tab-label rule, for a description. */
internal fun spokenTitle(post: PostDto): String = PostTitle.of(post) ?: PostTitle.UNTITLED

/** What the file is, as a word: "video", "animated GIF", "Flash animation" or "image". */
internal fun postKind(post: PostDto): String = when {
  isVideo(post) -> "video"
  post.fileType == "image/gif" -> "animated GIF"
  isFlash(post) -> "Flash animation"
  else -> "image"
}

/** The viewer's and the post tab's picture: "Rosehip, Peach, image". */
internal fun pictureDescription(post: PostDto): String = "${spokenTitle(post)}, ${postKind(post)}"

/**
 * A grid cell: its title, then its state, as the cell shows it: "Rosehip, Peach. Favourited. In tab. Video, 02:07".
 * [unfavourited] is a favourites grid's post unfavourited in place, which the cell dims. While its chip says
 * "Already tab N" ([alreadyTab], #130), "In tab" is "Already in tab N"; with [keptAgain] it's "Kept again" (#182). A
 * [seen] post says so, after its favourite state: "Rosehip, Peach. Favourited. Seen. In tab". On a phone ([touch]),
 * which has no tabs (#249), "In tab" is "Kept" and "Already in tab N" is "Already item N".
 */
internal fun cellDescription(
  post: PostDto,
  openInTab: Boolean,
  unfavourited: Boolean = false,
  alreadyTab: Int? = null,
  seen: Boolean = false,
  keptAgain: Boolean = false,
  touch: Boolean = platformIsTouch,
): String =
  listOfNotNull(
    spokenTitle(post),
    when {
      unfavourited -> "Unfavourited"
      post.isFavorited -> "Favourited"
      else -> null
    },
    if (seen) "Seen" else null,
    when {
      !openInTab -> null
      keptAgain -> KEPT_AGAIN
      alreadyTab != null -> alreadySpoken(alreadyTab, touch)
      else -> inTabLabel(touch)
    },
    if (isVideo(post)) listOfNotNull("Video", videoBadgeDuration(post)).joinToString(", ") else null,
  ).joinToString(". ")

/** The rating node's state: "Your vote, 3" or "Your vote, none". */
internal fun voteState(userVote: Int?): String = "Your vote, ${userVote ?: "none"}"

/** The rating node's name: "Average 4.2 from 12 votes", "Average 5.0 from 1 vote", or "No votes". */
internal fun ratingDescription(post: PostDto): String {
  val average = averageVote(post) ?: return "No votes"
  return "Average $average from ${post.voteCount} ${if (post.voteCount == 1) "vote" else "votes"}"
}

/**
 * The stars as one adjustable node, as Android's `RatingBar`: 0 to 5 in whole steps, 0 for no vote, so TalkBack's
 * swipe up or down votes one more or one less, and 0 takes the vote back. The same as custom actions: "Vote 1" to
 * "Vote 5" (but the current vote) and "Take vote back". Signed out ([onSignIn] set), activating opens sign-in instead,
 * as a tap on a star does.
 */
internal fun SemanticsPropertyReceiver.voteSemantics(post: PostDto, onVote: (Int?) -> Unit, onSignIn: (() -> Unit)?) {
  contentDescription = ratingDescription(post)
  stateDescription = voteState(post.userVote)
  if (onSignIn != null) {
    onClick("Sign in to vote") {
      onSignIn()
      true
    }
    return
  }
  progressBarRangeInfo = ProgressBarRangeInfo((post.userVote ?: 0).toFloat(), 0f..5f, steps = 4)
  setProgress("Vote") { target ->
    val vote = target.roundToInt().coerceIn(0, 5).takeIf { it > 0 }
    if (vote != post.userVote) onVote(vote)
    true
  }
  customActions = (1..5).filter { it != post.userVote }.map { n ->
    CustomAccessibilityAction("Vote $n") {
      onVote(n)
      true
    }
  } + listOfNotNull(
    post.userVote?.let {
      CustomAccessibilityAction("Take vote back") {
        onVote(null)
        true
      }
    },
  )
}

/** The favourite toggle's name: "Favourite, 6"; desktop adds its key, "Favourite, 6 (F)". */
internal fun favouriteDescription(count: String, key: String?): String =
  "Favourite, $count" + (key?.let { " ($it)" } ?: "")
