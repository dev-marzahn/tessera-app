package dev.marzahn.sankaku.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.awaitCancellation

/** M3's Long snackbar time, before the accessibility settings lengthen it. */
const val LONG_WINDOW_MS = 10_000L

/** M3's Short snackbar time, before the accessibility settings lengthen it. */
const val SHORT_WINDOW_MS = 4_000L

/**
 * How long an in-place window stays (↶ Undo beside ✕, "↗ Open" or "↻ Retry" on ⋮): M3's Long snackbar time through
 * [androidx.compose.ui.platform.AccessibilityManager.calculateRecommendedTimeoutMillis], as M3's `SnackbarHost` does,
 * so Android's "Time to take action" setting lengthens it. Every window holds an action, an icon and text.
 */
@Composable
fun rememberActionWindowMillis(): Long = rememberRecommendedMillis(LONG_WINDOW_MS)

/**
 * How long a second tap may confirm the first (Sign out's "Sign out? Tap again"): M3's Short snackbar time, lengthened
 * by the accessibility settings as [rememberActionWindowMillis] is.
 */
@Composable
fun rememberConfirmWindowMillis(): Long = rememberRecommendedMillis(SHORT_WINDOW_MS)

@Composable
private fun rememberRecommendedMillis(base: Long): Long {
  val manager = LocalAccessibilityManager.current
  return manager?.calculateRecommendedTimeoutMillis(base, containsIcons = true, containsText = true, containsControls = true)
    ?: base
}

/**
 * Runs a window of [totalMillis] on the frame clock, reporting the share left (1 → 0) to [onLeft] each frame, and
 * returns when it's used up. Time doesn't pass while [isPaused] (the control is pressed or focused). A window of
 * [Long.MAX_VALUE] (the accessibility settings ask for no timeout) never ends: it stays full until cancelled.
 */
suspend fun runWindow(totalMillis: Long, isPaused: () -> Boolean = { false }, onLeft: (Float) -> Unit) {
  onLeft(1f)
  if (totalMillis == Long.MAX_VALUE) awaitCancellation()
  var used = 0L
  var last = withFrameMillis { it }
  while (used < totalMillis) {
    val now = withFrameMillis { it }
    if (!isPaused()) used += now - last
    last = now
    onLeft((1f - used.toFloat() / totalMillis).coerceIn(0f, 1f))
  }
}
