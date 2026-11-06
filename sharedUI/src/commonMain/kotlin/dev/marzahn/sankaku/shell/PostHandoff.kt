package dev.marzahn.sankaku.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.features.post.SheetStrip
import dev.marzahn.sankaku.tabs.LocalQueuePictureSwipe
import dev.marzahn.sankaku.tabs.TabId
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.ui.components.spatialFadeSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Touch: a post opened from another post's sheet (a Recommended thumbnail, #321), as one picture: the thumbnail moves
 * into the opened lone post's picture while the post it was tapped on fades over it, its sheet going down. One at a
 * time; [current] is the one under way, from the tap until both posts have settled.
 */
@Stable
class PostHandoff {
  /** The opening under way, or null. */
  var current by mutableStateOf<PostOpening?>(null)
    private set

  /**
   * Opens [postId] from the item [from] with [navigate], which makes the post's item the active one, read back with
   * [active]. The thumbnail's element is switched on first, for [MATCH_FRAMES] laid-out frames, so the move starts from
   * its bounds, and stays on until the move is over. With animations off, or another opening under way, it only
   * navigates.
   */
  suspend fun open(from: TabId, postId: String, active: () -> TabId, strip: SheetStrip? = null, navigate: suspend () -> Unit) {
    if (current != null || animationsOff()) return navigate()
    val opening = PostOpening(from, postId, strip = strip)
    current = opening
    try {
      repeat(MATCH_FRAMES) { withFrameNanos {} }
      navigate()
    } catch (e: Throwable) {
      // The sheet went away first, or opening failed: nothing moves, and the next tap starts afresh.
      finish(opening)
      throw e
    }
    val to = active()
    if (current == opening) current = if (to == from) null else opening.copy(to = to)
  }

  /**
   * Whether the thumbnail of [postId] in [strip] on the item [tab] is the one moving, so its element is switched on.
   * By strip too: a post can be both a child and a recommendation, and only the thumbnail tapped may share the key.
   */
  fun sharing(tab: TabId, postId: String, strip: SheetStrip? = null): Boolean =
    current?.let { it.from == tab && it.postId == postId && it.strip == strip } == true

  /** Ends the opening under way, if any: the queue page opening over it takes the post's picture to its row. */
  fun end() {
    current = null
  }

  internal fun finish(opening: PostOpening) {
    if (current == opening) current = null
  }
}

/**
 * [PostHandoff]'s opening of [postId] from the item [from] onto the item [to], once that's known; [strip] is the sheet's
 * strip its thumbnail is in (Recommended, Parent, Children).
 */
data class PostOpening(val from: TabId, val postId: String, val to: TabId? = null, val strip: SheetStrip? = null)

/** Touch: the shell's [PostHandoff]. Null elsewhere (desktop, a screen in a test): a thumbnail then only opens its post. */
val LocalPostHandoff = staticCompositionLocalOf<PostHandoff?> { null }

/**
 * Touch: the shown item's visibility, which only changes while a [PostHandoff] fades it out. A sheet's thumbnail is a
 * shared element on it, switched on only while it's the one moving. Null without a [PostHandoffHost].
 */
val LocalItemVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Touch: the lone post a [PostHandoff] is opening, while it comes in: its picture grows out of the thumbnail on it. */
val LocalHandoffEntering = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Touch: the post a [PostHandoff] is leaving, while it fades: its sheet and chrome go down and out. */
val LocalHandoffLeaving = compositionLocalOf { false }

/**
 * Touch: the active item's [content], and while a [handoff] opens it from [items]' other one, that one too, above it,
 * fading out ([LocalHandoffLeaving]) as the new one comes in ([LocalHandoffEntering]). The two are keyed by item in one
 * loop, so the item tapped on keeps its composition, and its thumbnail its bounds, as the new one joins it. Any other
 * change of item cuts as before: the old one goes at once.
 */
@Composable
internal fun PostHandoffHost(
  active: TabState,
  items: List<TabState>,
  handoff: PostHandoff?,
  content: @Composable (TabState) -> Unit,
) {
  val running = handoff?.current?.takeIf { it.to == active.id }
  val leaving = running
    ?.takeIf { (active.top as? Route.Post)?.id == it.postId }
    ?.let { r -> items.firstOrNull { it.id == r.from } }
  // Each shown item's visibility, by item, for the end of the move. Kept by the items' effects, not while composing.
  val states = remember { HashMap<TabId, MutableTransitionState<Boolean>>() }
  val fade = spatialFadeSpec<Float>()
  Box(Modifier.fillMaxSize()) {
    // The item left drawn last, over the new one, whose black card is there from its first frame.
    for (item in listOfNotNull(active, leaving)) {
      key(item.id) {
        val isLeaving = item.id == leaving?.id
        val entering = leaving != null && item.id == active.id
        // The post opened comes in from its first frame; the one left starts fading on the next.
        val state = remember { MutableTransitionState(!entering).apply { targetState = true } }
        SideEffect { state.targetState = !isLeaving }
        DisposableEffect(state) {
          states[item.id] = state
          onDispose { if (states[item.id] === state) states.remove(item.id) }
        }
        AnimatedVisibility(
          state,
          enter = EnterTransition.None,
          exit = fadeOut(fade),
          // While it fades, the post left takes no touches: it's on its way out.
          modifier = if (isLeaving) Modifier.swallowTouches() else Modifier,
        ) {
          CompositionLocalProvider(
            LocalItemVisibility provides this,
            LocalHandoffEntering provides this.takeIf { entering },
            LocalHandoffLeaving provides isLeaving,
            // The queue swipe's picture swipe is the shown item's.
            LocalQueuePictureSwipe provides if (isLeaving) null else LocalQueuePictureSwipe.current,
          ) { content(item) }
        }
      }
    }
  }
  // Another item came forward before or during the move (a swipe, ‹ ›): the opening is over.
  val overtaken = handoff?.current?.takeIf { it.to != null && active.id != it.to && active.id != it.from }
  if (handoff != null && overtaken != null) LaunchedEffect(overtaken) { handoff.finish(overtaken) }
  if (handoff != null && running != null) {
    LaunchedEffect(running, leaving?.id) {
      if (leaving != null) {
        snapshotFlow {
          val shown = states[active.id]
          val left = states[leaving.id]
          shown?.isIdle != false && left?.isIdle != false && left?.currentState != true
        }.first { it }
      }
      handoff.finish(running)
    }
  }
}

private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
  awaitPointerEventScope {
    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
  }
}
