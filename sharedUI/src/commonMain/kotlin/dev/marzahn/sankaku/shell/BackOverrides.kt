package dev.marzahn.sankaku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import dev.marzahn.sankaku.PlatformBackHandler
import org.koin.compose.koinInject

/**
 * Back is always the top bar's (desktop) or the system's (Android), so a screen never places its own. A screen with
 * something to close before navigating (the viewer over a grid) registers it with [BackOverride]; Back then closes
 * the newest one first. On desktop the top bar's Back shows even when the tab has nothing to go back to.
 */
class BackOverrides {
  private val stack = mutableStateListOf<() -> Unit>()

  val active: Boolean get() = stack.isNotEmpty()

  /** Closes the newest override; false when there is none, so the caller navigates instead. */
  fun back(): Boolean {
    val close = stack.lastOrNull() ?: return false
    close()
    return true
  }

  internal fun add(close: () -> Unit) {
    stack.add(close)
  }

  internal fun remove(close: () -> Unit) {
    stack.remove(close)
  }
}

/**
 * While [enabled], Back calls [onBack] instead of navigating: the top bar's Back and Esc on desktop, system and
 * predictive back on Android. The override is composed inside the tab's content, after the tab's own back, so it
 * takes system back first.
 */
@Composable
fun BackOverride(enabled: Boolean, onBack: () -> Unit, overrides: BackOverrides = koinInject()) {
  val latest by rememberUpdatedState(onBack)
  DisposableEffect(enabled, overrides) {
    val close = { latest() }
    if (enabled) overrides.add(close)
    onDispose { overrides.remove(close) }
  }
  PlatformBackHandler(enabled = enabled) { latest() }
}
