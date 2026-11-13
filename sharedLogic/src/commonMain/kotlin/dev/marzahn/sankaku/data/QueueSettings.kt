package dev.marzahn.sankaku.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedQueue(val forYouInQueue: Boolean = false, val switcherList: Boolean = false)

/**
 * The queue's settings, kept in [file] between runs and written at once.
 *
 * - [forYouInQueue] ("Show For you in the queue"; off by default): signed in, For you is a feed item at the end of the
 *   queue after Following, as Following always is (#229, `FeedItems`).
 * - [switcherList] (desktop): the tab switcher shows its queue list rather than its cards (#502). C opens it as it was
 *   last left, after a restart too (#542).
 *
 * "Clear everything" leaves this file alone: the switches are preferences, not history.
 */
class QueueSettings(private val file: TextFile) {
  private val saved = file.read()?.let { runCatching { json.decodeFromString(SavedQueue.serializer(), it) }.getOrNull() }
    ?: SavedQueue()

  private val _forYouInQueue = MutableStateFlow(saved.forYouInQueue)
  val forYouInQueue: StateFlow<Boolean> = _forYouInQueue.asStateFlow()

  private val _switcherList = MutableStateFlow(saved.switcherList)
  val switcherList: StateFlow<Boolean> = _switcherList.asStateFlow()

  fun setForYouInQueue(on: Boolean) {
    _forYouInQueue.value = on
    save()
  }

  fun setSwitcherList(list: Boolean) {
    _switcherList.value = list
    save()
  }

  private fun save() {
    val saved = SavedQueue(_forYouInQueue.value, _switcherList.value)
    // The switch has already changed for this run; a full disk or locked file only costs it the next restart.
    runCatching { file.write(json.encodeToString(SavedQueue.serializer(), saved)) }
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
