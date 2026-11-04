package dev.marzahn.sankaku.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedFirstLaunch(val accepted: Boolean = false)

/**
 * Whether the first-launch screen has been accepted (#210): what Tessera is, the terms risk, and "Sankaku is an adult
 * site. I'm 18 or older". Until [accepted], the app shows only that screen and sends nothing to Sankaku. Kept in
 * [file], so it's shown once; a file that can't be read or parsed shows it again.
 *
 * "Clear everything" leaves this file alone: it's the user's answer, not history, and the session it leaves signed in
 * is the same adult's.
 */
class FirstLaunch(private val file: TextFile) {
  private val _accepted = MutableStateFlow(
    file.read()?.let { runCatching { json.decodeFromString(SavedFirstLaunch.serializer(), it) }.getOrNull() }?.accepted
      ?: false,
  )
  val accepted: StateFlow<Boolean> = _accepted.asStateFlow()

  /** Continue. The app goes on for this run even if the file can't be written; only the next launch asks again. */
  fun accept() {
    _accepted.value = true
    runCatching { file.write(json.encodeToString(SavedFirstLaunch.serializer(), SavedFirstLaunch(accepted = true))) }
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
