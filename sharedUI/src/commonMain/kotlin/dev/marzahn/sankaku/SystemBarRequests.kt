package dev.marzahn.sankaku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * One window's requests for how its system bars look ([PlatformSystemBars]). The newest open request is applied; the
 * others wait under it. Releasing the last one restores the app's own look, as the released request last asked.
 *
 * Several viewers can ask at once: swiping between lone posts composes the next post's viewer before the last one
 * leaves. Each one setting the window itself let the leaving viewer's reset land after the new viewer's light icons,
 * and the icons went dark on the black viewer (#297).
 */
internal class SystemBarRequests<S : Any>(
  private val apply: (S) -> Unit,
  private val restore: (S) -> Unit,
) {
  // Insertion order is the order the requests opened in; updating one keeps its place.
  private val open = LinkedHashMap<Any, S>()
  private var applied: S? = null

  /** Whether no request is open, so the app's own look shows. */
  val isEmpty: Boolean get() = open.isEmpty()

  /** Opens [owner]'s request, or updates it, with [state]. It's applied if it's the newest. */
  fun request(owner: Any, state: S) {
    open[owner] = state
    applyNewest()
  }

  /** Closes [owner]'s request. The newest one left is applied; with none left, the app's own look is restored. */
  fun release(owner: Any) {
    val state = open.remove(owner) ?: return
    if (open.isEmpty()) {
      applied = null
      restore(state)
    } else {
      applyNewest()
    }
  }

  private fun applyNewest() {
    val newest = open.values.last()
    if (newest == applied) return
    applied = newest
    apply(newest)
  }
}

/**
 * Asks [requests] for [state] while composed. It's applied as this composition's changes are, before the frame draws,
 * and released when this leaves composition.
 */
@Composable
internal fun <S : Any> SystemBarRequest(requests: SystemBarRequests<S>, state: S) {
  val owner = remember { Any() }
  DisposableEffect(requests) { onDispose { requests.release(owner) } }
  SideEffect { requests.request(owner, state) }
}
