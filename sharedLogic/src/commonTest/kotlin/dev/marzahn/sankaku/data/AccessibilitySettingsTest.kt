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
  fun leftHandedIsOffByDefault() {
    assertFalse(AccessibilitySettings(InMemoryTextFile()).leftHanded.value)
  }

  @Test
  fun leftHandedSurvivesARestartAlongsideTheQueueButtons() {
    val file = InMemoryTextFile()
    AccessibilitySettings(file).apply {
      setLeftHanded(true)
      setShowQueueButtons(true)
    }

    val restarted = AccessibilitySettings(file)
    assertTrue(restarted.leftHanded.value)
    assertTrue(restarted.showQueueButtons.value)
  }

  @Test
  fun aFileFromBeforeLeftHandedKeepsItsQueueButtons() {
    val file = InMemoryTextFile().apply { write("""{"showQueueButtons":true}""") }

    val settings = AccessibilitySettings(file)
    assertTrue(settings.showQueueButtons.value)
    assertFalse(settings.leftHanded.value)
  }

  @Test
  fun anUnreadableFileIsTheDefaults() {
    val file = InMemoryTextFile().apply { write("not json") }

    assertFalse(AccessibilitySettings(file).showQueueButtons.value)
    assertFalse(AccessibilitySettings(file).leftHanded.value)
  }
}
