package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #424 review: a key released while the window was in the background (Cmd-Tab with Space down) isn't held any more
 * when the window comes back, so its next press is a new press, not a repeat.
 */
@OptIn(ExperimentalTestApi::class)
class HeldKeysTest {
  @OptIn(InternalComposeUiApi::class)
  private fun ComposeUiTest.down(key: Key) =
    runOnIdle { (this as SkikoComposeUiTest).scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)) }

  @Test
  fun theWindowLosingFocusForgetsAHeldKey() = runComposeUiTest {
    var windowFocused by mutableStateOf(true)
    lateinit var keys: HeldKeys
    val focus = FocusRequester()
    setContent {
      val real = LocalWindowInfo.current
      val window = object : WindowInfo by real {
        override val isWindowFocused: Boolean get() = windowFocused
      }
      CompositionLocalProvider(LocalWindowInfo provides window) {
        keys = rememberHeldKeys()
        Box(keys.modifier) { Box(Modifier.size(10.dp).focusRequester(focus).focusable()) }
      }
    }
    runOnIdle { focus.requestFocus() }
    down(Key.Spacebar)
    down(Key.Spacebar)
    assertTrue(keys.repeating(Key.Spacebar), "a second press with no release is a repeat")

    // Space is let go in another app: the release never reaches the window.
    runOnIdle { windowFocused = false }
    runOnIdle { windowFocused = true }
    down(Key.Spacebar)
    assertFalse(keys.repeating(Key.Spacebar), "the first press after the window came back counted as a repeat")
  }
}
