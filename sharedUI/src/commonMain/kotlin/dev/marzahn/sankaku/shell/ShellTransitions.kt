package dev.marzahn.sankaku.shell

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import dev.marzahn.sankaku.features.grid.imageKey
import dev.marzahn.sankaku.features.grid.sharingConfig
import dev.marzahn.sankaku.ui.components.spatialBoundsTransform

/**
 * Touch: the shell's one shared-transition scope, around the tab's content and the queue page (#320), so a picture on one
 * screen can move into its counterpart on another. Null elsewhere (desktop, a screen in a test), where each screen makes
 * its own ([SharedTransitions]).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalShellTransitions = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Touch: the tab's content's visibility under the queue page (#320). It goes out as the page opens and comes in as it
 * closes, so a lone post's picture has a transition to shrink into its row on and grow out of it on. Null where there's
 * no queue page (desktop, a test).
 */
val LocalUnderQueuePage = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Touch: the queue page is composed over the tab's content, and the content leaves in a couple of frames (#320). A lone
 * post attaches its picture's shared element now, so it's laid out matched with its row before it leaves for it.
 */
val LocalQueuePageOpening = compositionLocalOf { false }

/** Touch: the queue page's own visibility (#320), the other end of [LocalUnderQueuePage]'s. Null where it isn't a transition. */
val LocalQueuePageVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Whether the tab's content is on its way in or out under the queue page ([LocalUnderQueuePage]), or about to leave
 * ([LocalQueuePageOpening]): the only time its end of a shared picture is attached. Left attached once settled, a lone
 * post drew no picture for a frame as a later opening ended (#320).
 */
@Composable
fun underQueuePageMoving(): Boolean {
  val under = LocalUnderQueuePage.current ?: return false
  val transition = under.transition
  val settled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
  return !settled || LocalQueuePageOpening.current
}

/**
 * The post [id]'s picture as one element across the shell's screens (#320): a lone post's and its row's thumbnail on the
 * queue page share [imageKey], each on its own side of the queue page's transition ([visibility]). The bounds move on
 * the spatial spring, the slow one into a post ([slow]) as from a grid cell, and the overlay clips to [clip]. Only
 * switched by [enabled], as a grid cell's are, so the element keeps its bounds while it's off: one added as the page
 * starts to close would have none yet. Nothing without the shell's scope or a [visibility].
 */
@Composable
fun Modifier.sharedPicture(
  id: String,
  visibility: AnimatedVisibilityScope?,
  enabled: Boolean,
  slow: Boolean,
  clip: Shape,
): Modifier = sharedImage(imageKey(id), visibility, enabled, slow, clip, remeasure = false)

/**
 * The post with [md5] as one element between a grid's mosaic on the queue page and that grid's cell (#322): a mosaic
 * thumbnail holds its post's md5 and no id, so the key is the md5's ([mosaicKey]). Otherwise as [sharedPicture], on
 * the spatial spring a lone post's picture moves on between it and its row.
 *
 * The [cell] is laid out anew at each size in flight, as its card is into the viewer, so its picture is cropped as
 * the cell crops it and its marks keep their size. The thumbnail is drawn scaled, as a kept row's is: laid out anew,
 * it was held to the room its 2 × 2 square has.
 */
@Composable
fun Modifier.sharedMosaicThumb(md5: String, visibility: AnimatedVisibilityScope?, enabled: Boolean, clip: Shape, cell: Boolean): Modifier =
  sharedImage(mosaicKey(md5), visibility, enabled, slow = false, clip, remeasure = cell)

/** A grid's mosaic thumbnail and its cell, by the post's md5 (#322). */
fun mosaicKey(md5: String) = "mosaic-$md5"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedImage(
  key: Any,
  visibility: AnimatedVisibilityScope?,
  enabled: Boolean,
  slow: Boolean,
  clip: Shape,
  remeasure: Boolean,
): Modifier {
  val shell = LocalShellTransitions.current
  val bounds = spatialBoundsTransform(slow)
  if (shell == null || visibility == null) return this
  return with(shell) {
    this@sharedImage.sharedBounds(
      rememberSharedContentState(key, sharingConfig(enabled)),
      visibility,
      enter = EnterTransition.None,
      exit = ExitTransition.None,
      boundsTransform = bounds,
      resizeMode = if (remeasure) {
        SharedTransitionScope.ResizeMode.RemeasureToBounds
      } else {
        SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop)
      },
      clipInOverlayDuringTransition = OverlayClip(clip),
    )
  }
}

/**
 * Drawn above a running shared transition's pictures, as the shell's chrome ([TopBar], the count pill, the top line) is
 * drawn above the screens: while each screen had a scope of its own, its overlay stayed under them, and a card closing
 * into a cell went under the pill handed back to the shell (#103). Nothing without the shell's scope.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.aboveSharedTransitions(enabled: Boolean = true): Modifier {
  val shell = LocalShellTransitions.current ?: return this
  return with(shell) {
    this@aboveSharedTransitions.renderInSharedTransitionScopeOverlay(ABOVE_SCREENS_Z) { enabled && isTransitionActive }
  }
}

/** Above every screen's overlay: the grid's marks, the viewer's card, picture and chrome. */
private const val ABOVE_SCREENS_Z = 10f

/**
 * The shell's scope ([LocalShellTransitions]) for [content] in a box with [modifier], or, without one, a
 * [SharedTransitionLayout] of its own.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitions(modifier: Modifier = Modifier, content: @Composable SharedTransitionScope.() -> Unit) {
  val shell = LocalShellTransitions.current
  if (shell == null) {
    SharedTransitionLayout(modifier, content)
  } else {
    Box(modifier) { shell.content() }
  }
}

/** Whether animations are off here (animator duration scale 0), as the animations themselves read it. */
internal suspend fun animationsOff(): Boolean = (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f

/**
 * Frames a shared element is attached (or switched on) before the screen it moves to comes: enough for its match to be
 * laid out from its bounds (#320's queue page opening, #321's post from a sheet).
 */
internal const val MATCH_FRAMES = 2
