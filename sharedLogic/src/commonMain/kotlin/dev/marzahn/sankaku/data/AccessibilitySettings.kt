package dev.marzahn.sankaku.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedAccessibility(val showQueueButtons: Boolean = false)

/**
 * The accessibility settings, kept in [file] between runs and written at once.
 *
 * - [showQueueButtons] (touch; off by default): ✕ and ‹ › around the queue's count pill, for anyone who can't swipe
 *   or drag (#178, WCAG 2.5.1). TalkBack has them as the pill's custom actions either way.
 *
 * "Clear everything" leaves this file alone: the switches are preferences, not history.
 */
class AccessibilitySettings(private val file: TextFile) {
  private val saved = file.read()?.let { runCatching { json.decodeFromString(SavedAccessibility.serializer(), it) }.getOrNull() }
    ?: SavedAccessibility()

  private val _showQueueButtons = MutableStateFlow(saved.showQueueButtons)
  val showQueueButtons: StateFlow<Boolean> = _showQueueButtons.asStateFlow()

  fun setShowQueueButtons(on: Boolean) {
    _showQueueButtons.value = on
    // The switch has already changed for this run; a full disk or locked file only costs it the next restart.
    runCatching { file.write(json.encodeToString(SavedAccessibility.serializer(), SavedAccessibility(on))) }
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
