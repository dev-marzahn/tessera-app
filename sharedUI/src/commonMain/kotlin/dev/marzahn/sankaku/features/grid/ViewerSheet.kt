package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The phone viewer's details sheet stops, as how far the sheet is lifted above peek, in px: peek is 0, then [half],
 * then [full] (the sheet's top at the window's top).
 */
internal data class SheetStops(val half: Float, val full: Float) {
  val all: List<Float> get() = listOf(0f, half, full)

  /** The lift of [stop]. */
  fun at(stop: SheetStop): Float = when (stop) {
    SheetStop.Peek -> 0f
    SheetStop.Half -> half
    SheetStop.Full -> full
  }
}

/** A stop by name, with what TalkBack says of a sheet at it (#494), in the order of [SheetStops.all]. */
internal enum class SheetStop(val spoken: String) {
  Peek("Collapsed"),
  Half("Half expanded"),
  Full("Expanded"),
}

/**
 * The stop a sheet let go at [lift] settles at. A fling faster than [flingThreshold] (px/s; negative is upwards, as
 * pointer deltas are) goes on to the next stop that way; otherwise the nearest stop wins.
 */
internal fun settleLift(lift: Float, velocity: Float, stops: SheetStops, flingThreshold: Float): Float {
  val all = stops.all
  return when {
    velocity < -flingThreshold -> all.firstOrNull { it > lift + STOP_SLOP } ?: stops.full
    velocity > flingThreshold -> all.lastOrNull { it < lift - STOP_SLOP } ?: 0f
    else -> all.minBy { abs(it - lift) }
  }
}

/** How far peek → half ([halfFraction]) and half → full ([fullFraction]) a sheet at [lift] is, each 0 to 1. */
internal fun sheetFractions(lift: Float, stops: SheetStops): Pair<Float, Float> {
  val half = if (stops.half > 0f) (lift / stops.half).coerceIn(0f, 1f) else 0f
  val span = stops.full - stops.half
  val full = if (span > 0f) ((lift - stops.half) / span).coerceIn(0f, 1f) else 0f
  return half to full
}

/** A lift within this many px of a stop is at it. */
private const val STOP_SLOP = 1f

/**
 * Frames without a scroll that end a scroll no finger makes (a screen reader's, a mouse wheel's): until then its
 * further deltas belong to the stop it already moved the sheet to.
 */
private const val QUIET_FRAMES = 2

/**
 * The sheet's position: [lift] px above peek. Drags move it directly; letting go settles at a stop ([settle]). Its
 * [connection] gives a scroll inside the sheet to the sheet first while it isn't full, and a scroll down past the
 * details' top back to it, so dragging anywhere on the sheet lifts or lowers it (nested scroll). A scroll that isn't a
 * finger's moves it a whole stop instead ([viewerSheet], #494): a screen reader's has no release to settle on.
 */
@Stable
internal class ViewerSheetState(
  private val scope: CoroutineScope,
  private val spec: AnimationSpec<Float>,
  private val flingThreshold: Float,
  /** The stop the sheet takes once its stops are first measured ([update]): peek, or where a restored one was left. */
  private val initial: SheetStop = SheetStop.Peek,
) {
  /** Whether [update] has had the window's stops: before that every stop is 0. */
  var measured by mutableStateOf(false)
    private set

  var lift by mutableFloatStateOf(0f)
    private set

  var stops by mutableStateOf(SheetStops(0f, 0f))
    private set

  private var job: Job? = null

  /** The stop the sheet is springing to; null once there, or once a drag takes it. */
  private var springTarget by mutableStateOf<Float?>(null)

  /** A finger is on the sheet ([viewerSheet]): its scrolls drag the sheet, as opposed to a screen reader's. */
  internal var touched = false

  /** A scroll no finger makes has moved the sheet a stop, and goes on ([QUIET_FRAMES]). */
  private var stepped = false
  private var quiet: Job? = null

  /** At peek (or on its way down to it). */
  val atPeek: Boolean get() = lift <= STOP_SLOP

  /**
   * At peek, or springing down to it from anywhere: a drag down then closes the viewer. The spring's tail and bounce
   * hold the sheet a pixel or two above peek for a few hundred ms, and a drag down starting then went to a sheet with
   * nowhere to go, so the whole touch did nothing until lifted.
   */
  val headedForPeek: Boolean get() = atPeek || springTarget == 0f

  private val atStop: Boolean get() = stops.all.any { abs(it - lift) <= STOP_SLOP }

  /** Where the sheet is headed: the stop it's springing to, or where a drag left it. */
  private val heading: Float get() = springTarget ?: lift

  /** The stop the sheet is at or springing to; between stops, the nearest. */
  val stop: SheetStop by derivedStateOf {
    val all = stops.all
    SheetStop.entries[all.indices.minBy { abs(all[it] - heading) }]
  }

  private fun stopAbove(): Float? = stops.all.firstOrNull { it > heading + STOP_SLOP }

  private fun stopBelow(): Float? = stops.all.lastOrNull { it < heading - STOP_SLOP }

  /**
   * New stops (the window was measured): a sheet resting at a stop stays at the same stop. The first goes to [initial].
   */
  fun update(new: SheetStops) {
    if (new == stops) return
    val first = !measured
    val index = stops.all.indexOfFirst { abs(it - lift) <= STOP_SLOP }
    measured = true
    stops = new
    if (job?.isActive != true) {
      lift = when {
        first -> new.at(initial)
        index >= 0 -> new.all[index]
        else -> lift.coerceIn(0f, new.full)
      }
    }
  }

  /** Moves the sheet by a pointer delta [dy] (positive is down); returns how much of it was used. */
  fun dragBy(dy: Float): Float {
    job?.cancel()
    springTarget = null
    val before = lift
    lift = (lift - dy).coerceIn(0f, stops.full)
    return before - lift
  }

  /** Settles at the stop a release with [velocity] (px/s, positive is down) leads to. */
  fun settle(velocity: Float) = animateTo(settleLift(lift, velocity, stops, flingThreshold), velocity)

  /**
   * Springs to [target], starting with the release's [velocity]. The spring may pass its target, but the sheet never
   * goes past peek or full: it's exactly the window's height, so a lift past full uncovered a band at its bottom (#281).
   */
  fun animateTo(target: Float, velocity: Float = 0f) {
    job?.cancel()
    springTarget = target
    job = scope.launch {
      animate(lift, target, -velocity, spec) { value, _ -> lift = value.coerceIn(0f, stops.full) }
      springTarget = null
    }
  }

  /** There's a stop above where the sheet is headed: Expand can move it. */
  val canExpand: Boolean get() = stopAbove() != null

  /** There's a stop below where the sheet is headed: Collapse can move it. */
  val canCollapse: Boolean get() = stopBelow() != null

  /** Lifts the sheet to the next stop up; false at full. */
  fun expand(): Boolean = stopAbove()?.also { animateTo(it) } != null

  /** Lowers the sheet to the next stop down; false at peek. */
  fun collapse(): Boolean = stopBelow()?.also { animateTo(it) } != null

  /** The handle's tap: the next stop up, and from full back to peek. */
  fun step() = animateTo(stopAbove() ?: 0f)

  /** A scroll no finger makes moves the sheet one stop ([move]) and takes the rest of its deltas until it's quiet. */
  private fun stepBy(move: () -> Boolean) {
    if (!stepped) stepped = move()
    quiet?.cancel()
    quiet = scope.launch {
      repeat(QUIET_FRAMES) { withFrameNanos {} }
      stepped = false
    }
  }

  val connection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      // Lifting comes before scrolling the details, until the sheet is full.
      if (available.y < 0f && source == NestedScrollSource.UserInput && lift < stops.full) {
        if (touched) return Offset(0f, dragBy(available.y))
        stepBy(::expand)
        return Offset(0f, available.y)
      }
      return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
      // The details are at their top: the rest of a downward drag lowers the sheet.
      if (available.y > 0f && source == NestedScrollSource.UserInput && lift > 0f) {
        if (touched) return Offset(0f, dragBy(available.y))
        stepBy(::collapse)
        return Offset(0f, available.y)
      }
      return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
      if (atStop) return Velocity.Zero
      settle(available.y)
      return available
    }
  }
}

/**
 * A [ViewerSheetState] whose stop outlives its composition (#560), as the details' scroll does: a viewer that leaves
 * with its item (a tag in the sheet opened a grid in front of it, #349) comes back, by Back or the way back, with the
 * sheet at the stop it was left at. One new to the composition, a post's new viewer, starts at peek.
 *
 * Saved under a key of its own, [viewerSheetKey] of the post shown ([postId]), not where it sits in the composition: a
 * grid's viewer is keyed on its `ViewerOnScreen` state, a new object when the tab comes back, so a positional key never
 * matched. A restored value is used up only by a viewer that reads it, and one whose viewer doesn't come back (its post
 * gone after a reload) is saved again; keyed on the post, it can only come back to that post.
 */
@Composable
internal fun rememberViewerSheetState(
  scope: CoroutineScope,
  spec: AnimationSpec<Float>,
  flingThreshold: Float,
  postId: String?,
): ViewerSheetState {
  var saved by rememberSaveable(key = postId?.let(::viewerSheetKey)) { mutableStateOf(SheetStop.Peek.name) }
  val sheet = remember {
    ViewerSheetState(scope, spec, flingThreshold, initial = SheetStop.entries.firstOrNull { it.name == saved } ?: SheetStop.Peek)
  }
  // Not before the stops are measured: until then every stop is 0, which reads as peek.
  LaunchedEffect(sheet) { snapshotFlow { sheet.stop.takeIf { sheet.measured } }.collect { if (it != null) saved = it.name } }
  return sheet
}

/** The saved-state key of the viewer's sheet stop while it shows the post [id] ([rememberViewerSheetState]). */
internal fun viewerSheetKey(id: String): String = "viewer-sheet-$id"

/**
 * The sheet's own modifiers: the details' scroll nested into it ([ViewerSheetState.connection]), whether a finger is
 * on it, and a screen reader's scroll (TalkBack's from the handle or ♡, Switch Access's) moving it a stop (#494).
 */
internal fun Modifier.viewerSheet(sheet: ViewerSheetState): Modifier = this
  .pointerInput(sheet) {
    awaitPointerEventScope {
      try {
        while (true) sheet.touched = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
      } finally {
        sheet.touched = false
      }
    }
  }
  .nestedScroll(sheet.connection)
  .semantics {
    verticalScrollAxisRange = ScrollAxisRange(value = { sheet.lift }, maxValue = { sheet.stops.full })
    scrollBy { _, y ->
      when {
        y > 0f -> sheet.expand()
        y < 0f -> sheet.collapse()
        else -> false
      }
    }
  }

/**
 * Expand and Collapse for TalkBack, each while there's a stop that way from where the sheet is headed (#494). Read in
 * composition, so they follow the sheet. [beforeExpand] runs first on Expand (the picture's brings tap-hidden chrome
 * back).
 */
internal fun sheetActions(sheet: ViewerSheetState, beforeExpand: () -> Unit = {}): List<CustomAccessibilityAction> =
  listOfNotNull(
    CustomAccessibilityAction(EXPAND_ACTION) {
      beforeExpand()
      sheet.expand()
    }.takeIf { sheet.canExpand },
    CustomAccessibilityAction(COLLAPSE_ACTION) { sheet.collapse() }.takeIf { sheet.canCollapse },
  )

private const val EXPAND_ACTION = "Expand details"
private const val COLLAPSE_ACTION = "Collapse details"

/**
 * The sheet's handle: its 16 dp row across the sheet is a button (#494) that steps the sheet peek → half → full →
 * peek, read as "Details, collapsed" with [sheetActions]. The row stays 16 dp, so peek stays 80 dp (the owner's
 * choice); the drawn bar fades out as the sheet goes full ([fullFraction]). No ripple: the sheet moving is the answer.
 */
@Composable
internal fun SheetHandle(sheet: ViewerSheetState, fullFraction: () -> Float, modifier: Modifier = Modifier) {
  val stop = sheet.stop
  val actions = sheetActions(sheet)
  Box(
    modifier
      .fillMaxWidth()
      .height(16.dp)
      .clickable(
        interactionSource = null,
        indication = null,
        onClickLabel = if (stop == SheetStop.Full) "Collapse" else "Expand",
        role = Role.Button,
        onClick = sheet::step,
      )
      .semantics {
        contentDescription = "Details"
        stateDescription = stop.spoken
        customActions = actions
      },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .graphicsLayer { alpha = 1f - fullFraction() }
        .size(width = 32.dp, height = 4.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
    )
  }
}
