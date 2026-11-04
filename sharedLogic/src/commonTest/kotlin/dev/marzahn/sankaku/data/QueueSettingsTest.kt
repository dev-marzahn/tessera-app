package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueSettingsTest {
  @Test
  fun forYouIsOutOfTheQueueByDefault() {
    assertFalse(QueueSettings(InMemoryTextFile()).forYouInQueue.value)
  }

  @Test
  fun forYouInTheQueueSurvivesARestart() {
    val file = InMemoryTextFile()
    QueueSettings(file).setForYouInQueue(true)

    assertTrue(QueueSettings(file).forYouInQueue.value)
  }

  @Test
  fun anUnreadableFileIsTheDefaults() {
    val file = InMemoryTextFile().apply { write("not json") }

    assertFalse(QueueSettings(file).forYouInQueue.value)
  }
}
