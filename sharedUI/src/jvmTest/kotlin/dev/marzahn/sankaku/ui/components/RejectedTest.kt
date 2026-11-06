package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RejectedTest {
  @Test
  fun aRiseAnswersOnceAndAControlComposedAgainDoesNotReplayIt() = runComposeUiTest {
    val felt = mutableListOf<HapticFeedbackType>()
    val haptics = object : HapticFeedback {
      override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        felt += hapticFeedbackType
      }
    }
    var count by mutableIntStateOf(0)
    var generation by mutableIntStateOf(0)
    setContent {
      SankakuTheme {
        CompositionLocalProvider(LocalHapticFeedback provides haptics) {
          // A new generation is the control leaving and coming back (a lazy row scrolled out and in).
          key(generation) { Box(Modifier.size(48.dp).rejected(count, CircleShape)) }
        }
      }
    }
    waitForIdle()
    assertEquals(emptyList(), felt)

    count = 1
    waitForIdle()
    assertEquals(listOf(HapticFeedbackType.Reject), felt)

    generation++
    waitForIdle()
    assertEquals(1, felt.size)
  }
}
