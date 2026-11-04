package dev.marzahn.sankaku.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class OpenOnClickTest {
  @Test
  fun aMiddleClickCallsTheHandlerOfTheLatestComposition() = runComposeUiTest {
    var tab by mutableStateOf("first")
    val closed = mutableListOf<String>()
    setContent {
      // The handler captures the value at composition, as TabStrip's `{ controller.close(tab.id) }` does.
      val current = tab
      Box(Modifier.size(100.dp).testTag("tab").openOnClick(onOpen = {}, onOpenInNewTab = { closed += current }))
    }

    onNodeWithTag("tab").performMouseInput {
      press(MouseButton.Tertiary)
      release(MouseButton.Tertiary)
    }
    tab = "second"
    waitForIdle()
    onNodeWithTag("tab").performMouseInput {
      press(MouseButton.Tertiary)
      release(MouseButton.Tertiary)
    }

    assertEquals(listOf("first", "second"), closed)
  }

  @Test
  fun onTouchALongPressOpensInANewTabAndATapOpensHere() = runComposeUiTest {
    val calls = mutableListOf<String>()
    setContent {
      Box(
        Modifier.size(100.dp).testTag("cell")
          .openOnClick(onOpen = { calls += "here" }, onOpenInNewTab = { calls += "new tab" }, touch = true),
      )
    }

    onNodeWithTag("cell").performTouchInput { longClick() }
    onNodeWithTag("cell").performClick()

    assertEquals(listOf("new tab", "here"), calls)
  }

  @Test
  fun onTouchALongPressOnAPostAlreadyInATabFeelsRejected() = runComposeUiTest {
    val felt = mutableListOf<HapticFeedbackType>()
    val haptics = object : HapticFeedback {
      override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        felt += hapticFeedbackType
      }
    }
    var inTab by mutableStateOf(false)
    setContent {
      CompositionLocalProvider(LocalHapticFeedback provides haptics) {
        Box(
          Modifier.size(100.dp).testTag("cell").openOnClick(onOpen = {}, onOpenInNewTab = {}, touch = true, inTab = inTab),
        )
      }
    }

    onNodeWithTag("cell").performTouchInput { longClick() }
    inTab = true
    waitForIdle()
    onNodeWithTag("cell").performTouchInput { longClick() }

    assertEquals(listOf(HapticFeedbackType.Confirm, HapticFeedbackType.Reject), felt)
  }

  @Test
  fun onTouchALongPressCanDoSomethingElse() = runComposeUiTest {
    val calls = mutableListOf<String>()
    setContent {
      Box(
        Modifier.size(100.dp).testTag("chip").openOnClick(
          onOpen = { calls += "here" },
          onOpenInNewTab = { calls += "new tab" },
          touch = true,
          onLongPress = { calls += "menu" },
        ),
      )
    }

    onNodeWithTag("chip").performTouchInput { longClick() }

    assertEquals(listOf("menu"), calls)
  }

  /**
   * #324: on touch a tap opens in the frame its finger lifts in, with no frame advanced after the release. The grid's
   * cells took a double-tap through the same detector until then, so every tap waited out the double-tap timeout
   * (about 300 ms) before opening.
   */
  @Test
  fun onTouchATapOpensAsTheFingerLifts() = runComposeUiTest {
    val calls = mutableListOf<String>()
    setContent {
      Box(
        Modifier.size(100.dp).testTag("cell")
          .openOnClick(onOpen = { calls += "here" }, onOpenInNewTab = { calls += "new tab" }, touch = true),
      )
    }
    waitForIdle()
    mainClock.autoAdvance = false

    onNodeWithTag("cell").performTouchInput {
      down(center)
      up()
    }

    assertEquals(listOf("here"), calls)
  }

  @Test
  fun withoutTouchALongPressIsAClick() = runComposeUiTest {
    val calls = mutableListOf<String>()
    setContent {
      Box(
        Modifier.size(100.dp).testTag("cell")
          .openOnClick(onOpen = { calls += "here" }, onOpenInNewTab = { calls += "new tab" }, touch = false),
      )
    }

    onNodeWithTag("cell").performTouchInput { longClick() }

    assertEquals(listOf("here"), calls)
  }
}
