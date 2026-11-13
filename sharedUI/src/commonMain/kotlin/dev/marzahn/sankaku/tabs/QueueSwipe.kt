package dev.marzahn.sankaku.tabs

import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.PlatformPredictiveBackHandler
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.isLone
import dev.marzahn.sankaku.features.grid.BarInsets
import dev.marzahn.sankaku.features.grid.LocalBarInsets
import dev.marzahn.sankaku.features.grid.LonePostPeek
import dev.marzahn.sankaku.features.grid.rememberBarInsets
import dev.marzahn.sankaku.features.grid.PostOrigin
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.post.postOriginLabel
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.shell.LocalChromePadding
import dev.marzahn.sankaku.ui.components.spatialSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.sign

/**
 * Touch: S1 of the tab queue (#181). On a grid or a lone post a sideways swipe moves to the previous or next queue
 * item. The tab's content follows the finger with the neighbour peeking in beside it; let go, it settles as the
 * viewer's pager does, and with no item that way it stretches as the pager does at its ends. The viewer over a grid
 * keeps its own pager: a screen opts in with [queueSwipe] only where sideways is free. On the Up next and End of the
 * queue cards a swipe only goes back, to the item they stand past (#248, [swipeWays]).
 */

/** What a touch that may become a queue swipe has done so far ([swipeStart]). */
internal enum class SwipeStart {
  /** Not far enough either way yet. */
  Wait,

  /** A deliberate sideways swipe: the queue takes it from here. */
  Take,

  /** It went up or down first: a scroll, the sheet or a drag down has it. */
  LetGo,
}

/**
 * #122's rule, as for the sidebar's old edge swipe: a swipe is taken once it has gone twice the touch slop sideways
 * ([dx]) before it has gone one touch slop ([slop]) up or down ([dy]). Twice the slop is Android's paging touch slop
 * (`ViewConfiguration.getScaledPagingTouchSlop`), its distance for a deliberate sideways swipe; one slop vertically is
 * where a grid's scroll or the viewer's vertical drag takes the touch anyway. So only drags within about 27° of
 * horizontal move the queue, and diagonal scrolls never do.
 */
internal fun swipeStart(dx: Float, dy: Float, slop: Float): SwipeStart = when {
  abs(dy) >= slop -> SwipeStart.LetGo
  abs(dx) >= 2 * slop -> SwipeStart.Take
  else -> SwipeStart.Wait
}

/**
 * Where a let-go queue swipe lands ([swipeTarget]). [Close] is only Back's, on a grid opened from an item (#349): it
 * slides off as [Previous] does, and the grid closes.
 */
internal enum class QueueMove { Previous, Next, Stay, Close }

/**
 * Where a swipe let go at [offset] px towards the end (positive shows the previous item) with [velocity] px/s towards
 * the end lands, the way foundation's `PagerDefaults.flingBehavior` settles the viewer's pager: a fling of at least
 * [minFling] px/s goes the way it flings, a slower let-go goes the way it's dragged once past half the [width], and a
 * fling back against the drag stays. There's nowhere to go without an item that way.
 */
internal fun swipeTarget(
  offset: Float,
  velocity: Float,
  width: Float,
  minFling: Float,
  hasPrevious: Boolean,
  hasNext: Boolean,
): QueueMove {
  val towards = when {
    abs(velocity) >= minFling -> sign(velocity)
    abs(offset) >= width / 2 -> sign(offset)
    else -> 0f
  }
  return when {
    towards > 0f && offset > 0f && hasPrevious -> QueueMove.Previous
    towards < 0f && offset < 0f && hasNext -> QueueMove.Next
    else -> QueueMove.Stay
  }
}

/** Which ways a swipe can move the queue: [back] to the previous item (or off a card), [forward] on. */
internal data class SwipeWays(val back: Boolean, val forward: Boolean)

/**
 * Which ways a swipe moves from what's shown (#181, #248). On an item: back with a [previous] item, forward unless
 * [ahead] is [Ahead.Nothing]. On a card ([cardUp], Up next or End of the queue): back only, to the item it stands
 * past, as the pill's ‹ goes; forward stays off, so a swipe never skips an offer or leaves End of the queue.
 */
internal fun swipeWays(cardUp: Boolean, previous: Boolean, ahead: Ahead): SwipeWays =
  if (cardUp) SwipeWays(back = true, forward = false) else SwipeWays(back = previous, forward = ahead != Ahead.Nothing)

/** What Back does at the queue's level, once the tab has nothing of its own to pop ([queueBack]). */
internal enum class QueueBack { Previous, Close, QueuePage, Tab }

/**
 * What Back does at the queue's level ([QueueSwipeHost]). On a card ([cardUp]): back to the item it stands past
 * (#248). On a grid a search opened from an item, with nothing under it ([opened], [TabsController.openerOf]): it
 * closes, with Undo, and that item is shown again (#349). On a [lonePost] (a post alone in its tab, with nothing under
 * it): the [previous] item (#181), and with none, on the first item, the queue page, so Back never closes the app from
 * a post (#340). Anywhere else the tab's own Back acts.
 */
internal fun queueBack(cardUp: Boolean, lonePost: Boolean, previous: Boolean, opened: Boolean = false): QueueBack = when {
  cardUp -> QueueBack.Previous
  opened -> QueueBack.Close
  !lonePost -> QueueBack.Tab
  previous -> QueueBack.Previous
  else -> QueueBack.QueuePage
}

/**
 * Whether Back on [tab] goes on at the queue's level as on a lone post ([queueBack]'s `lonePost`): a lone post or a
 * kept book alone in its tab (#181, #385), and a series' books alone in its tab (#479). A series opened in the
 * background, or one whose item is gone, has no list under it and no item to close back to; the tab's own Back would
 * close the app on a phone. A grid or a Books search alone keeps the tab's own Back.
 */
internal val TabState.backsLikeALonePost: Boolean
  get() = backStack.size == 1 && top.let { it?.isLone == true || it is Route.BookSeries }

/**
 * The fling speed past which a let-go swipe goes the way it flings: foundation's `MinFlingVelocityDp` (internal), the
 * threshold `PagerDefaults.flingBehavior` uses, so the queue settles as the viewer's pager does. A lone post's drag
 * down closes on a fling past it too (`loneDragCloses`, #308).
 */
internal val MIN_FLING = 400.dp

/**
 * The queue swipe's state, one for the shell ([QueueSwipeHost]): how far the active tab's content is dragged towards
 * the end ([offset], px; positive shows the previous item), and the settling after a let-go. A settled move steps the
 * queue ([TabsController.previous] or [TabsController.next], which touch the item landed on) and the content snaps
 * back in place once the new item is the active one, so neither item shows out of place for a frame.
 */
@Stable
class QueueSwipeState internal constructor(
  private val scope: CoroutineScope,
  private val spec: AnimationSpec<Float>,
  private val move: (QueueMove) -> Boolean,
) {
  var offset by mutableFloatStateOf(0f)
    private set

  internal var width by mutableIntStateOf(0)
  internal var hasPrevious = false
  internal var hasNext = false

  // The platform's overscroll (Android's stretch), as the pager draws at its ends; null where there's none.
  internal var overscroll: OverscrollEffect? = null

  // -1 when the layout is right to left: [offset] counts towards the end, the layer moves in px from the left.
  internal var direction = 1f

  // The tab a settled move left, until the next one is shown: the offset belongs to it alone.
  internal var leaving by mutableStateOf<TabId?>(null)
    private set

  private var job: Job? = null

  /**
   * Whether the offset is Back's closing a grid opened from an item (#349): the item it returns to peeks in instead of
   * the previous one, and the grid slides off even when it's the first item.
   */
  internal var closing by mutableStateOf(false)
    private set

  /** Whether a swipe holds the content or is settling it. */
  val active: Boolean get() = offset != 0f || job?.isActive == true

  /** The offset for the tab [id]'s content: none once a move has left it for another. */
  internal fun offsetFor(id: TabId): Float = if (leaving != null && leaving != id) 0f else offset

  /** A swipe takes the content (catching a settle, if one runs). */
  internal fun start() {
    job?.cancel()
    closing = false
  }

  /** Follows the finger by [towardsEnd] px. Past an end, the rest stretches the content ([overscroll]). */
  internal fun dragBy(towardsEnd: Float) {
    val effect = overscroll
    if (effect == null) {
      offset = clamp(offset + towardsEnd)
      return
    }
    effect.applyToScroll(Offset(towardsEnd * direction, 0f), NestedScrollSource.UserInput) { available ->
      val before = offset
      offset = clamp(before + available.x * direction)
      Offset((offset - before) * direction, 0f)
    }
  }

  private fun clamp(value: Float): Float {
    val w = width.toFloat()
    return value.coerceIn(if (hasNext) -w else 0f, if (hasPrevious || closing) w else 0f)
  }

  /**
   * Let go with [velocity] px/s towards the end; [minFling] is [MIN_FLING] in px. The settle takes the whole fling,
   * so the stretch gets none of it (#280): what [OverscrollEffect.applyToFling]'s callback doesn't consume, Android's
   * stretch absorbs once it returns. Only a let-go at an end, with the content in place and the fling towards no item,
   * leaves the fling to the stretch.
   */
  internal fun release(velocity: Float, minFling: Float, from: TabId) {
    val target = swipeTarget(offset, velocity, width.toFloat(), minFling, hasPrevious, hasNext)
    val atEnd = offset == 0f && ((velocity > 0f && !hasPrevious) || (velocity < 0f && !hasNext))
    job = scope.launch {
      val effect = overscroll
      if (effect == null) {
        settle(target, from, velocity)
        return@launch
      }
      effect.applyToFling(Velocity(velocity * direction, 0f)) { available ->
        // What's left after relaxing a stretch, back in px towards the end.
        settle(target, from, available.x * direction)
        if (atEnd) Velocity.Zero else available
      }
    }
  }

  /**
   * Predictive back on a lone post or a card (#248), or on a grid it [closes] (#349): the content follows the
   * gesture's [progress] (0–1) a share of the width.
   */
  internal fun pullBack(progress: Float, closes: Boolean = false) {
    job?.cancel()
    closing = closes
    offset = clamp(progress * width * BACK_SHARE)
  }

  /** Predictive back let go: the content goes back in place. */
  internal fun springBack() {
    job = scope.launch {
      slide(0f, velocity = 0f)
      closing = false
    }
  }

  /**
   * Back committed on a lone post or a card (or a plain back press): the previous item, or the card's, comes in. On a
   * grid it [closes] (#349), the item it was opened from comes in and the grid closes.
   */
  internal fun back(from: TabId, closes: Boolean = false) {
    job?.cancel()
    closing = closes
    job = scope.launch { settle(if (closes) QueueMove.Close else QueueMove.Previous, from) }
  }

  private suspend fun settle(target: QueueMove, from: TabId, velocity: Float = 0f) {
    val to = when (target) {
      QueueMove.Previous, QueueMove.Close -> width.toFloat()
      QueueMove.Next -> -width.toFloat()
      QueueMove.Stay -> 0f
    }
    slide(to, velocity)
    if (target == QueueMove.Stay) return
    leaving = from
    if (!move(target)) landed()
  }

  /**
   * Springs [offset] to [to] on the swipe's spring, from [velocity] px/s towards the end, without the bounce (#280):
   * - The first frame plays one frame on from the let-go, so the content keeps the finger's speed instead of standing
   *   still while the spring starts.
   * - The spring's overshoot is cut where it reaches [to], so the content never passes it (nor ±[width]), and the
   *   neighbour never slides past its place.
   * - Within [LAND_PX] of [to] it's in place: a Float spring's own 0.01 px threshold kept a move waiting about 500 ms
   *   after the picture looked settled.
   */
  private suspend fun slide(to: Float, velocity: Float) {
    val from = offset
    if (from == to) return
    val animation = TargetBasedAnimation(spec, Float.VectorConverter, from, to, Float.VectorConverter.convertToVector(velocity))
    var start = AnimationConstants.UnspecifiedTime
    var done = false
    while (!done) {
      withFrameNanos { now ->
        if (start == AnimationConstants.UnspecifiedTime) start = now - FIRST_FRAME_NANOS
        val played = now - start
        val value = animation.getValueFromNanos(played)
        done = animation.isFinishedFromNanos(played) || (value - to) * (from - to) <= 0f || abs(value - to) < LAND_PX
        offset = if (done) to else value
      }
    }
  }

  /** The item a move went to is shown: its content sits in place. */
  internal fun landed() {
    leaving = null
    offset = 0f
    closing = false
  }
}

/** Predictive back moves a lone post a quarter of the width at most, as it moves the viewer's card down. */
private const val BACK_SHARE = 0.25f

/**
 * How far a settle has played by its first frame: about one 60 Hz frame since the let-go, the last place the finger
 * put the content. Fixed rather than measured, as the let-go's time isn't on the frame clock: on a faster display the
 * first frame leads by up to one more short frame, about the time a drawn frame already trails the finger by. Back and
 * a cancelled predictive back start from rest, so the lead is a frame's worth of a slow start there.
 */
private const val FIRST_FRAME_NANOS = 16_000_000L

/** A settle within this many px of where it's going is in place ([QueueSwipeState.slide]). */
private const val LAND_PX = 1f

/** The shell's [QueueSwipeState], for the screens that opt in with [queueSwipe]; null on desktop. */
val LocalQueueSwipe = staticCompositionLocalOf<QueueSwipeState?> { null }

/**
 * The swipe's TalkBack equivalents, "Previous queue item" and "Next queue item", for the screens that take queue swipes
 * to add to their own nodes' custom actions (a grid's cells, a lone post's image). Empty where there's no item that
 * way, and on desktop.
 */
val LocalQueueActions = compositionLocalOf<List<CustomAccessibilityAction>> { emptyList() }

/**
 * A lone post's neighbour, as the post's own viewer draws it beside its picture during a swipe ([QueuePictureSwipe]):
 * its decoded [picture] when the preload has one (and G only allows it), else its [kind] and [title].
 */
data class PicturePeek(val title: String, val kind: String, val picture: LoadedNeighbour?, val leaving: String? = null)

/**
 * Between two lone posts a queue swipe moves only the picture, as the viewer's pager does over a grid (#263): the top
 * row and the sheet stay, and show the post landed on once it's shown. [QueueSwipeHost] gives the shown lone post
 * this, with its neighbours that are lone posts too ([previous], [next]); the post's viewer slides its picture by
 * [offset] and draws the neighbour's beside it. Towards anything else (a grid, a card) the whole screen moves.
 */
@Stable
class QueuePictureSwipe internal constructor(
  private val state: QueueSwipeState,
  private val from: TabId,
  val previous: PicturePeek?,
  val next: PicturePeek?,
  // The host's for the shown item, so it outlives a rebuild with a sharper picture mid-drag.
  private val drawnBy: MutableState<Int>,
) {
  /**
   * Whether the shown post's viewer draws the picture swipe ([drawing]): while the post loads or has failed there's no
   * viewer, and the whole screen moves.
   */
  val drawn: Boolean get() = drawnBy.value > 0

  /** Called by the viewer that draws the swipe, for as long as it's composed: undo with the returned function. */
  fun drawing(): () -> Unit {
    drawnBy.value++
    return { drawnBy.value-- }
  }

  /**
   * The picture's offset in px towards the end (positive shows [previous]): the swipe's, towards a lone post only, and
   * only while a viewer draws it ([drawn]).
   */
  fun offset(): Float {
    val offset = state.offsetFor(from)
    return if (drawn && ((offset > 0f && previous != null) || (offset < 0f && next != null))) offset else 0f
  }

  /** -1 when the layout is right to left: [offset] counts towards the end, a layer moves in px from the left. */
  val direction: Float get() = state.direction
}

/** The shown lone post's [QueuePictureSwipe]; null elsewhere, and on desktop. */
val LocalQueuePictureSwipe = compositionLocalOf<QueuePictureSwipe?> { null }

/**
 * Lets a sideways swipe here move between queue items while [enabled] ([QueueSwipeHost]). Only touch counts: a mouse
 * drag on desktop scrolls as before. A swipe starts inside the system's gesture insets at both sides, so with gesture
 * navigation the edges stay system back; with three buttons the insets are 0. A second finger (a pinch) lets go.
 *
 * Until it takes the swipe ([swipeStart]) nothing is consumed, so a grid's scroll and its cells' taps and holds see the
 * touch as if this weren't here, and anything below that consumes it first (a scroll, a pan, a cell's hold) wins. With
 * [claimSideways], a lone post's image, it consumes a sideways move from one touch slop on, as the no-op drag it
 * replaces did: the image's tap would otherwise take a short sideways swipe for a tap and hide the chrome, and the
 * image's vertical drag (the sheet, a drag down) gives way to it, as it gave way to the pager over a grid. It claims
 * them even where it can't move the queue (disabled, from an edge inset, or outside a shell), as that drag did.
 */
fun Modifier.queueSwipe(enabled: Boolean, from: TabId?, claimSideways: Boolean = false): Modifier = composed {
  val state = LocalQueueSwipe.current
  if ((state == null || from == null) && !claimSideways) return@composed this
  val isEnabled by rememberUpdatedState(enabled && state != null && from != null)
  val tab by rememberUpdatedState(from)
  val insets = WindowInsets.systemGestures
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
  onGloballyPositioned { placed[0] = it }.pointerInput(state, insets, density, direction, claimSideways) {
    val minFling = MIN_FLING.toPx()
    val towardsEnd = if (direction == LayoutDirection.Ltr) 1f else -1f
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      if (down.type != PointerType.Touch) return@awaitEachGesture
      val coordinates = placed[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
      val downX = coordinates.localToWindow(down.position).x
      val windowWidth = coordinates.findRootCoordinates().size.width
      val inEdge = downX < insets.getLeft(density, direction) || downX >= windowWidth - insets.getRight(density, direction)
      val canMove = isEnabled && !inEdge
      if (!canMove && !claimSideways) return@awaitEachGesture
      val slop = viewConfiguration.touchSlop
      var claimed = false
      // Up or down first rules the swipe out for this touch, even once it's claimed and comes back level.
      var movable = canMove
      // The finger's place in the window, which the content's own move doesn't change (#236). The content follows the
      // finger, so in the content's coordinates the finger barely moves: a fling read there is slow or even backwards,
      // and a move's delta there is short by however far the content moved since the last event.
      // Up and down stay in the content's coordinates: the content only moves sideways.
      fun windowX(change: PointerInputChange) = coordinates.localToWindow(change.position).x
      var fingerX = downX
      val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, Offset(fingerX, 0f)) }
      while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
        if (!change.pressed || (change.isConsumed && !claimed)) return@awaitEachGesture
        if (!coordinates.isAttached) return@awaitEachGesture
        fingerX = windowX(change)
        velocity.addPosition(change.uptimeMillis, Offset(fingerX, 0f))
        val dx = fingerX - downX
        val start = swipeStart(dx, change.position.y - down.position.y, slop)
        if (start == SwipeStart.LetGo) {
          // Once claimed, the touch is this one's to the end, as a drag's is once past its slop.
          if (!claimed) return@awaitEachGesture
          movable = false
        }
        if (start == SwipeStart.Take && movable) {
          change.consume()
          break
        }
        if (claimed || (claimSideways && abs(dx) >= slop)) {
          claimed = true
          change.consume()
        }
      }
      val swipe = state ?: return@awaitEachGesture
      val from = tab ?: return@awaitEachGesture
      swipe.start()
      // The content takes up the way the finger went before the swipe was taken, so it sits under the finger from here
      // and a let-go past half the width from the down moves (#236).
      swipe.dragBy((fingerX - downX) * towardsEnd)
      var fling = 0f
      // Settled however the drag ends: let go, cancelled, or this screen leaving mid-drag (a lone post that finishes
      // loading swaps its loading box for the viewer), so the content never stays off centre.
      try {
        val ended = horizontalDrag(down.id) { change ->
          if (coordinates.isAttached) {
            val now = windowX(change)
            velocity.addPosition(change.uptimeMillis, Offset(now, 0f))
            swipe.dragBy((now - fingerX) * towardsEnd)
            fingerX = now
          }
          change.consume()
        }
        if (ended) fling = velocity.calculateVelocity().x * towardsEnd
      } finally {
        swipe.release(fling, minFling, from)
      }
    }
  }
}

/**
 * Touch: the active tab's [content], which a sideways swipe drags ([queueSwipe]), with the neighbour it reveals
 * peeking in beside it ([QueuePeek]). Settling on an item steps the queue, which touches it. With a card up
 * ([UpNextState.card]) it shows the card in place of [content], which a swipe back or Back slides off (#248). Each
 * queue item's content is a pane titled by its place in the queue ([queueItemDescription]), so TalkBack says where each move landed.
 * The items a move lands on load ahead ([preload], [PreloadNeighbours]).
 */
@Composable
fun QueueSwipeHost(
  active: TabState,
  content: @Composable () -> Unit,
  tabs: TabsController = koinInject(),
  upNext: UpNextState = koinInject(),
  preload: @Composable (shown: TabState, neighbours: List<TabState>) -> Map<String, LoadedNeighbour> = { shown, near -> PreloadNeighbours(shown, near) },
  /** The shell's, so Back's close of a grid opened from an item offers Undo on the pill (#349). */
  undo: ClosedTabUndo = koinInject(),
  /** A swipe forward settled, on the next item or a card: the swipe tip's "used" (#501). */
  onMoved: () -> Unit = {},
) {
  val closeUndo by rememberUpdatedState(undo)
  val moved by rememberUpdatedState(onMoved)
  val tabList by tabs.tabs.collectAsStateWithLifecycle()
  val faded by tabs.faded.collectAsStateWithLifecycle()
  val gOnly by upNext.gOnly.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val spec = spatialSpec<Float>()
  val state = remember(tabs) {
    QueueSwipeState(scope, spec) { move ->
      when (move) {
        // Back off a card is the pill's ‹: the item it stands past, which is still the active one (#248).
        QueueMove.Previous -> if (upNext.card != null) {
          upNext.dismiss()
          false
        } else {
          tabs.previous()
        }
        // Past the last source the Up next card shows instead of the neighbour, and past the last item End of the
        // queue; the item stays the active one (#216, #230).
        // A swipe forward settled, onto the next item or a card ([UpNextState.forward] is false for a card): the swipe
        // tip's lesson is learnt either way.
        QueueMove.Next -> upNext.forward().also { moved() }
        QueueMove.Stay -> false
        // Back on a grid opened from an item: it closes, with Undo, and that item is shown (#349).
        QueueMove.Close -> closeUndo.closeBack(tabs.activeTab.id)
      }
    }
  }
  val index = tabList.indexOf(active)
  // Where a move lands, as `step` lands: a block entered may return to the tab it remembers. Read again whenever the
  // list or the active item changes.
  val previous = remember(tabList, active) { tabs.neighbour(forward = false) }
  val next = remember(tabList, active) { tabs.neighbour(forward = true) }
  // On a grid a search opened from an item, the item Back closes it and returns to (#349). The back stack is snapshot
  // state, so a search run in the grid in place (nothing to close then) is followed.
  val opener = tabs.openerOf(active)
  // At the last source, a swipe towards the end brings the Up next card when it has anything to show, and at the last
  // item End of the queue (#216, #230). On a card a swipe only goes back, to the item it stands past (#248).
  val card = upNext.card
  val ahead = upNext.ahead(gOnly, faded)
  val ways = swipeWays(cardUp = card != null, previous = previous != null, ahead = ahead)
  val overscroll = rememberOverscrollEffect()
  val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
  SideEffect {
    state.hasPrevious = ways.back
    state.hasNext = ways.forward
    state.overscroll = overscroll
    state.direction = direction
  }
  LaunchedEffect(active.id) { if (state.leaving != null && state.leaving != active.id) state.landed() }
  // The items a move lands on load before the move (#263): back, and on unless a card comes first; on a card, the item
  // it stands past, which a swipe back brings. What has loaded peeks in as itself ([QueuePeek]).
  val loaded = preload(
    active,
    if (card != null) listOf(active) else listOfNotNull(previous, next.takeIf { ahead == Ahead.Item }),
  )
  // Back on a lone post goes to the previous item (#181), and on a card to the item it stands past (#248),
  // predictively: the content slides towards the end as the gesture goes, with what Back brings peeking in. On the
  // first item's lone post it opens the queue page, as the pill's ▤ does, instead of closing the app (#340); the post
  // stays put as the gesture goes, and the page's own transition takes its picture to its row. Composed before the
  // tab's content, so the post's own handlers (a raised sheet lowers, a zoom zooms out) come first, and the tab's own
  // back has nothing to pop.
  // On a grid a search opened from an item, Back closes it and slides back to that item, which peeks in as the previous
  // one does (#349); its viewer, search panel and sheet are composed after this, so they close first.
  val back = queueBack(
    cardUp = card != null,
    lonePost = active.backsLikeALonePost,
    previous = previous != null,
    opened = opener != null,
  )
  val openQueuePage = LocalOpenTabList.current
  // Only a move to an item slides with the gesture; the queue page comes on the commit.
  val slides = back == QueueBack.Previous || back == QueueBack.Close
  val closes = back == QueueBack.Close
  PlatformPredictiveBackHandler(
    enabled = back != QueueBack.Tab,
    onProgress = { if (slides) state.pullBack(it, closes) },
    onCancel = { if (slides) state.springBack() },
    onBack = { if (slides) state.back(active.id, closes) else openQueuePage() },
  )

  val actions = if (card != null) {
    // The card's swipe back, as the pill's ‹ and its action name it.
    listOf(CustomAccessibilityAction(backToLabel(active.title)) { upNext.dismiss(); true })
  } else {
    listOfNotNull(
      previous?.let { CustomAccessibilityAction(PREVIOUS_ITEM) { tabs.previous() } },
      aheadAction(ahead, upNext),
    )
  }
  // Between two lone posts only the picture moves, as the viewer's pager does over a grid (#263, [QueuePictureSwipe]).
  // Where the shown item stands in its block (#501): a neighbour in another block peeks in with "Leaving Landscape".
  val place = blockPlace(tabList, index)
  fun leavingFor(tab: TabState?): String? = if (tab != null && tab.head != active.head) leavingLabel(place) else null
  fun picturePeek(tab: TabState?): PicturePeek? {
    val route = tab?.top as? Route.Post ?: return null
    val picture = loaded[route.id]?.takeIf { !(gOnly && Rating.ofCode(it.post.rating) != Rating.G) }
    return PicturePeek(tab.title, kindLabel(tab, tabs.sourceOf(tab)), picture, leavingFor(tab))
  }
  val previousPeek = picturePeek(previous.takeIf { card == null && active.top is Route.Post })
  val nextPeek = picturePeek(next.takeIf { card == null && active.top is Route.Post && ahead == Ahead.Item })
  // How many viewers draw the shown post's picture swipe, kept across rebuilds of it ([QueuePictureSwipe.drawn]).
  val drawnBy = remember(state, active.id) { mutableStateOf(0) }
  val pictureSwipe = remember(state, active.id, previousPeek, nextPeek) {
    if (previousPeek == null && nextPeek == null) null else QueuePictureSwipe(state, active.id, previousPeek, nextPeek, drawnBy)
  }
  val showing by remember(state, active.id) { derivedStateOf { state.offsetFor(active.id).sign } }
  // The bars' insets this window's viewers last saw, for the next one and the peek ([BarInsets]).
  val barInsets = rememberBarInsets()
  Box(Modifier.fillMaxSize().onSizeChanged { state.width = it.width }.clipToBounds()) {
    // A neighbour the shown post's viewer draws beside its picture has no peek of its own.
    val pictureSide = pictureSwipe?.drawn == true && ((showing > 0f && previousPeek != null) || (showing < 0f && nextPeek != null))
    val neighbour = when {
      pictureSide -> null
      // Behind a card is the item it stands past; behind a grid Back closes, the item it returns to (#349).
      showing > 0f -> if (card != null) active else if (state.closing && opener != null) opener else previous
      showing < 0f -> next.takeIf { ahead == Ahead.Item && card == null }
      else -> null
    }
    val peekLayer = Modifier.graphicsLayer {
      val offset = state.offsetFor(active.id)
      translationX = (offset - showing * state.width) * state.direction
    }
    if (neighbour != null) {
      val route = neighbour.top as? Route.Post
      QueuePeek(
        neighbour,
        source = tabs.sourceOf(neighbour),
        loaded = route?.let { loaded[it.id] },
        origin = route?.let { postOriginLabel(neighbour, it, tabs) },
        gOnly = gOnly,
        barInsets = barInsets,
        // Not behind a card: that's the item the card stands past, in no other block.
        leaving = if (card == null) leavingFor(neighbour) else null,
        forward = showing < 0f,
        modifier = peekLayer,
      )
    } else if (pictureSide) {
      // The viewer draws it.
    } else if (showing < 0f && card == null && ahead == Ahead.UpNext) {
      CardPeek(UP_NEXT_PANE, leavingLabel(place), peekLayer)
    } else if (showing < 0f && card == null && ahead == Ahead.End) {
      CardPeek(END_PANE, leavingLabel(place), peekLayer)
    }
    val description = queueItemDescription(
      countedPosition(tabList, index),
      countedItems(tabList),
      active,
      tabs.sourceOf(active),
      place = place,
      next = aheadTitle(ahead, next),
    )
    Box(
      Modifier
        .fillMaxSize()
        // What the viewer's picture swipe takes ([QueuePictureSwipe.offset]) the screen doesn't: its chrome stays.
        .graphicsLayer { translationX = (state.offsetFor(active.id) - (pictureSwipe?.offset() ?: 0f)) * state.direction }
        .overscroll(overscroll)
        // The swipe's actions on the pane too, for the screens that take it without cells or an image to carry them
        // (a grid loading or empty, a lone post loading or failed). A card is a pane of its own, and its title carries
        // the actions.
        .semantics {
          if (card == null) {
            paneTitle = description
            customActions = actions
          }
        },
    ) {
      CompositionLocalProvider(
        LocalQueueSwipe provides state,
        LocalQueueActions provides actions,
        LocalQueuePictureSwipe provides pictureSwipe,
        LocalBarInsets provides barInsets,
      ) {
        // A card stands in for the tab's content, as the queue page covers it (#216, #230), and takes the swipe back.
        if (card != null) {
          CardPage(active.id, upNext)
        } else {
          content()
        }
      }
    }
  }
}

/**
 * A card with the swipe back on it (#248). A composable of its own, so it skips as the host recomposes during a drag:
 * a new [queueSwipe] there would restart its pointer input and drop the drag.
 */
@Composable
private fun CardPage(from: TabId, upNext: UpNextState) {
  Box(Modifier.fillMaxSize().queueSwipe(enabled = true, from = from)) { UpNextCardView(state = upNext) }
}

/** TalkBack's names for the swipe's custom actions ([LocalQueueActions]). */
internal const val PREVIOUS_ITEM = "Previous queue item"
internal const val NEXT_ITEM = "Next queue item"

/** "Back to artist_x": leaving a card for the item [title] it stands past, on the pill's ‹ and the swipe's action. */
internal fun backToLabel(title: String) = "Back to $title"

/**
 * The queue item beside the one shown, peeking in as a swipe reveals it. A lone post whose picture has loaded
 * ([loaded], [PreloadNeighbours]) comes in as itself (#263, option A): its picture where it will be, with its top row
 * saying why it's there ([origin], "Kept · from artist_x"), so landing on it changes nothing but the sheet's controls.
 * With G only on, an R15 or R18 post never shows its picture here ([gOnly]). Otherwise, and for a grid, its kind and
 * title on its screen's colour, text only (the queue page's blur, #164, is for its thumbnails; a picture here is the
 * item a moment before it's shown). A grid's peek keeps below the top bar, as its grid does; a post's covers it.
 */
@Composable
private fun QueuePeek(
  tab: TabState,
  source: TabState?,
  loaded: LoadedNeighbour?,
  origin: String?,
  gOnly: Boolean,
  barInsets: BarInsets,
  leaving: String?,
  forward: Boolean,
  modifier: Modifier,
) {
  Box(modifier.fillMaxSize()) {
    if (loaded != null && !(gOnly && Rating.ofCode(loaded.post.rating) != Rating.G)) {
      // The peek goes nowhere: its origin pill is drawn, not tapped.
      LonePostPeek(loaded.post, loaded.picture, origin?.let { PostOrigin(it) {} }, barInsets)
    } else {
      TextPeek(tab, source, Modifier)
    }
    if (leaving != null) LeavingEdge(leaving, forward)
  }
}

/**
 * Over a peek of an item in another block (#501): a line down the peek's edge that faces the item shown ([forward]:
 * the peek comes in from the end, so its start edge), and "Leaving Landscape" ([label]) beside it, a third of the way
 * down, clear of the top row and of a peek's centred title.
 */
@Composable
internal fun BoxScope.LeavingEdge(label: String, forward: Boolean) {
  val colors = MaterialTheme.colorScheme
  val edge = if (forward) Alignment.TopStart else Alignment.TopEnd
  Box(Modifier.matchParentSize().semantics { hideFromAccessibility() }) {
    Box(Modifier.align(if (forward) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().width(3.dp).background(colors.primary))
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = colors.primaryContainer,
      contentColor = colors.onPrimaryContainer,
      modifier = Modifier
        .align(edge)
        .fillMaxHeight(LEAVING_LABEL_TOP)
        .wrapContentHeight(Alignment.Bottom)
        .padding(horizontal = 12.dp),
    ) {
      Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
  }
}

/** How far down the peek "Leaving …" sits: a third of the way. */
private const val LEAVING_LABEL_TOP = 0.33f

/** [QueuePeek] without a picture: the item's kind and title. */
@Composable
private fun TextPeek(tab: TabState, source: TabState?, modifier: Modifier) {
  val post = tab.top is Route.Post
  Box(
    modifier
      .fillMaxSize()
      .background(if (post) Color.Black else MaterialTheme.colorScheme.surface)
      .then(if (post) Modifier else Modifier.padding(LocalChromePadding.current))
      .semantics { hideFromAccessibility() },
    contentAlignment = Alignment.Center,
  ) {
    val color = if (post) Color.White else MaterialTheme.colorScheme.onSurface
    Column(
      Modifier.padding(horizontal = 32.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(kindLabel(tab, source), style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = 0.7f))
      Text(
        tab.title,
        style = MaterialTheme.typography.titleLarge,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * What peeks in past the last source or item (#216, #230): just "Up next" or "End of the queue" ([title]), for the
 * card is not built until the swipe lands.
 */
@Composable
private fun CardPeek(title: String, leaving: String?, modifier: Modifier) {
  Box(modifier.fillMaxSize()) {
    Box(
      Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .padding(LocalChromePadding.current)
        .semantics { hideFromAccessibility() },
      contentAlignment = Alignment.Center,
    ) {
      Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    // A card past a block's last item leaves it too (#501).
    if (leaving != null) LeavingEdge(leaving, forward = true)
  }
}

/** "Kept · from artist_x", "Kept post", "Source", "Feed": what kind of queue item [tab] is, never an id. */
internal fun kindLabel(tab: TabState, source: TabState?): String = when (tab.kind) {
  QueueKind.KeptPost -> source?.let { "Kept · from ${it.title}" } ?: "Kept post"
  QueueKind.KeptBook -> source?.let { "Kept book · from ${it.title}" } ?: "Kept book"
  QueueKind.Source -> "Source"
  QueueKind.Feed -> "Feed"
  QueueKind.Other -> "Queue item"
}

/**
 * The pane title of the queue item [tab] at [position] (from 1) of [count], which TalkBack says on each move: "Queue
 * item 5 of 29, kept post from artist_x". Titles only, never ids or tags beyond the title's.
 *
 * In a block (#501, [place]) it says the place in it too: "Queue item 5 of 11, kept post 3 of 8 from Landscape", and
 * on its last kept item where moving on goes ([next]): "…, kept post 8 of 8 from Landscape, last, next Cat"; a source
 * or feed with kept items says how many follow ("…, 8 kept items follow").
 */
internal fun queueItemDescription(
  position: Int,
  count: Int,
  tab: TabState,
  source: TabState?,
  place: BlockPlace? = null,
  next: String? = null,
): String {
  if (place is BlockPlace.Kept) {
    return "Queue item $position of $count, ${place.spoken} from ${place.source}${place.closedSuffix}${blockEndSpoken(place, next)}"
  }
  val follow = (place as? BlockPlace.Head)?.let { ", ${it.follow}" }.orEmpty()
  // A feed isn't counted (#229), and since #327 it can stand anywhere: it's named by the counted items before it,
  // [position] ([countedPosition]), "Queue, feed Following, after 5 items", or "first" with none.
  if (tab.kind == QueueKind.Feed) {
    val where = if (position <= 0) "first" else "after $position ${if (position == 1) "item" else "items"}"
    return "Queue, feed ${tab.title}, $where$follow"
  }
  if (place is BlockPlace.Head) return "Queue item $position of $count, source ${tab.title}$follow"
  val what = when (tab.kind) {
    QueueKind.KeptPost -> source?.let { "kept post from ${it.title}" } ?: "kept post, ${tab.title}"
    QueueKind.KeptBook -> source?.let { "kept book ${tab.title} from ${it.title}" } ?: "kept book, ${tab.title}"
    QueueKind.Source -> "source ${tab.title}"
    QueueKind.Feed -> "feed ${tab.title}"
    QueueKind.Other -> tab.title
  }
  return "Queue item $position of $count, $what"
}
