package dev.marzahn.sankaku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.PlatformBackHandler
import dev.marzahn.sankaku.ui.components.Shortcut
import org.koin.compose.koinInject

/**
 * Back is always the top bar's (desktop) or the system's (Android), so a screen never places its own. A screen with
 * something to close before navigating (the viewer over a grid) registers it with [BackOverride]; Back then closes
 * the newest one first. On desktop the top bar's Back shows even when the tab has nothing to go back to.
 */
class BackOverrides {
  internal class Entry(val close: () -> Unit) {
    var alsoKey by mutableStateOf<Shortcut?>(null)
  }

  private val stack = mutableStateListOf<Entry>()

  val active: Boolean get() = stack.isNotEmpty()

  /** The newest override's second key besides Esc, for the top bar's Back to show (Space over the viewer, #424). */
  val alsoKey: Shortcut? get() = stack.lastOrNull()?.alsoKey

  /** Closes the newest override; false when there is none, so the caller navigates instead. */
  fun back(): Boolean {
    val entry = stack.lastOrNull() ?: return false
    entry.close()
    return true
  }

  internal fun add(entry: Entry) {
    stack.add(entry)
  }

  internal fun remove(entry: Entry) {
    stack.remove(entry)
  }
}

/**
 * While [enabled], Back calls [onBack] instead of navigating: the top bar's Back and Esc on desktop, system and
 * predictive back on Android. The override is composed inside the tab's content, after the tab's own back, so it
 * takes system back first. [alsoKey] labels another key that does the same, which the top bar's Back shows by Esc.
 */
@Composable
fun BackOverride(enabled: Boolean, onBack: () -> Unit, alsoKey: Shortcut? = null, overrides: BackOverrides = koinInject()) {
  val latest by rememberUpdatedState(onBack)
  val entry = remember { BackOverrides.Entry { latest() } }
  SideEffect { entry.alsoKey = alsoKey }
  DisposableEffect(enabled, overrides) {
    if (enabled) overrides.add(entry)
    onDispose { overrides.remove(entry) }
  }
  PlatformBackHandler(enabled = enabled) { latest() }
}
