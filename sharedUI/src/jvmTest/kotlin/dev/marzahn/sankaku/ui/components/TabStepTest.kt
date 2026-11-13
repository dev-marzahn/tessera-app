package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TabStepTest {
  @Test
  fun shiftWithAOrDStepsTabsAndNothingElseDoes() = runComposeUiTest {
    val steps = mutableListOf<Boolean?>()
    setContent {
      val focus = remember { FocusRequester() }
      LaunchedEffect(Unit) { focus.requestFocus() }
      Box(
        Modifier.size(10.dp).testTag("keys").focusRequester(focus).focusable()
          .onKeyEvent { event -> false.also { if (event.type == KeyEventType.KeyDown && event.key != Key.ShiftLeft && event.key != Key.CtrlLeft) steps += event.tabStep } },
      )
    }
    val keys = onNodeWithTag("keys")

    keys.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.D) } }
    keys.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.A) } }
    // Plain A/D move the grid cursor or step posts; Shift with another key, or Ctrl-Shift-D, is something else.
    keys.performKeyInput { pressKey(Key.D) }
    keys.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.W) } }
    keys.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.D) } } }

    assertEquals(listOf(true, false, null, null, null), steps)
  }
}
