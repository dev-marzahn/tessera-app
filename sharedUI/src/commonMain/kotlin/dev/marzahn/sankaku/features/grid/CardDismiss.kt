package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * How far the phone viewer's card has been pulled down to close it, in px: a drag down on the image, or predictive
 * back. The card shrinks and the scrim fades with it ([fraction]). Let go past the threshold, the viewer closes from
 * wherever the card was left, so [offset] stays put through the close; let go short of it, it springs back.
 *
 * It belongs to one opening ([rememberCardDismiss]). A cell tapped while the card is still closing reopens the viewer
 * in the same composition, and an offset carried into that opening left the new card shrunk over a dimmed grid with
 * nothing to finish it (#105).
 */
@Stable
internal class CardDismiss(private val scope: CoroutineScope, private val spec: AnimationSpec<Float>) {
  var offset by mutableFloatStateOf(0f)
    private set

  private var job: Job? = null

  /** The card is where it rests, so sideways swipes (the pager, the queue swipe) work. */
  val atRest: Boolean get() = offset == 0f

  /** Follows a drag by [dy] px (positive is down), never above where the card rests. */
  fun dragBy(dy: Float) {
    job?.cancel()
    offset = (offset + dy).coerceAtLeast(0f)
  }

  /** Follows predictive back, which reports where the card is rather than by how much it moved. */
  fun pullTo(px: Float) {
    job?.cancel()
    offset = px.coerceAtLeast(0f)
  }

  /**
   * Let go short of closing, or predictive back cancelled: the card grows back. It never goes above rest, and lands
   * once it's within [LAND_SLOP]: a spring runs until it's within 0.01 px, and its tail kept swipes off for about
   * 300 ms after the card looked settled (#281).
   */
  fun springBack() {
    job?.cancel()
    job = scope.launch {
      animate(offset, 0f, animationSpec = spec) { value, _ ->
        offset = if (value < LAND_SLOP) 0f else value
        if (offset == 0f) this@launch.cancel()
      }
    }
  }

  /** How far through [range] px the card has been pulled, 0 to 1. */
  fun fraction(range: Float): Float = if (range > 0f) (offset / range).coerceIn(0f, 1f) else 0f

  /** Stops a spring back; the offset stays where it was. */
  fun stop() {
    job?.cancel()
  }
}

/** A spring back within this many px of rest lands there, which is too little to see. */
private const val LAND_SLOP = 1f

/**
 * A [CardDismiss] for this [opening], at rest. A new opening gets a new one, and the last one's spring is stopped so
 * nothing writes to it after.
 */
@Composable
internal fun rememberCardDismiss(opening: Int, spec: AnimationSpec<Float>): CardDismiss {
  val scope = rememberCoroutineScope()
  val dismiss = remember(opening) { CardDismiss(scope, spec) }
  DisposableEffect(dismiss) { onDispose { dismiss.stop() } }
  return dismiss
}
