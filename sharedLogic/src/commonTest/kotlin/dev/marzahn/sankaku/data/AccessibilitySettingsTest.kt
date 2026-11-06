package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessibilitySettingsTest {
  @Test
  fun queueButtonsAreOffByDefault() {
    assertFalse(AccessibilitySettings(InMemoryTextFile()).showQueueButtons.value)
  }

  @Test
  fun queueButtonsSurviveARestart() {
    val file = InMemoryTextFile()
    AccessibilitySettings(file).setShowQueueButtons(true)

    assertTrue(AccessibilitySettings(file).showQueueButtons.value)
  }

  @Test
  fun anUnreadableFileIsTheDefaults() {
    val file = InMemoryTextFile().apply { write("not json") }

    assertFalse(AccessibilitySettings(file).showQueueButtons.value)
  }
}
