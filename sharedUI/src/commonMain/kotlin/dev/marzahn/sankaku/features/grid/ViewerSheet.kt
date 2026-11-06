package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
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
 * The sheet's position: [lift] px above peek. Drags move it directly; letting go settles at a stop ([settle]). Its
 * [connection] gives a scroll inside the sheet to the sheet first while it isn't full, and a scroll down past the
 * details' top back to it, so dragging anywhere on the sheet lifts or lowers it (nested scroll).
 */
@Stable
internal class ViewerSheetState(
  private val scope: CoroutineScope,
  private val spec: AnimationSpec<Float>,
  private val flingThreshold: Float,
) {
  var lift by mutableFloatStateOf(0f)
    private set

  var stops by mutableStateOf(SheetStops(0f, 0f))
    private set

  private var job: Job? = null

  /** The stop the sheet last sprang to; null once a drag takes it. */
  private var springTarget by mutableStateOf<Float?>(null)

  /** At peek (or on its way down to it). */
  val atPeek: Boolean get() = lift <= STOP_SLOP

  /**
   * At peek, or springing down to it from anywhere: a drag down then closes the viewer. The spring's tail and bounce
   * hold the sheet a pixel or two above peek for a few hundred ms, and a drag down starting then went to a sheet with
   * nowhere to go, so the whole touch did nothing until lifted.
   */
  val headedForPeek: Boolean get() = atPeek || springTarget == 0f

  private val atStop: Boolean get() = stops.all.any { abs(it - lift) <= STOP_SLOP }

  /** New stops (the window was measured): a sheet resting at a stop stays at the same stop. */
  fun update(new: SheetStops) {
    if (new == stops) return
    val index = stops.all.indexOfFirst { abs(it - lift) <= STOP_SLOP }
    stops = new
    if (job?.isActive != true) lift = if (index >= 0) new.all[index] else lift.coerceIn(0f, new.full)
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
    job = scope.launch { animate(lift, target, -velocity, spec) { value, _ -> lift = value.coerceIn(0f, stops.full) } }
  }

  val connection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      // Lifting comes before scrolling the details, until the sheet is full.
      if (available.y < 0f && source == NestedScrollSource.UserInput && lift < stops.full) {
        return Offset(0f, dragBy(available.y))
      }
      return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
      // The details are at their top: the rest of a downward drag lowers the sheet.
      if (available.y > 0f && source == NestedScrollSource.UserInput && lift > 0f) {
        return Offset(0f, dragBy(available.y))
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
