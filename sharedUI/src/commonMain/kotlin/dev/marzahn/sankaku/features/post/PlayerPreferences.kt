package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.data.TextFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedPlayer(val volume: Float = 1f, val muted: Boolean = false)

/**
 * The video player's volume and mute, the same for every video and kept in [file] between runs. Every change is
 * written at once: it's a few bytes, and a change only comes from a click, a drag or a key.
 */
class PlayerPreferences(private val file: TextFile) {
  private val saved = file.read()?.let { runCatching { json.decodeFromString(SavedPlayer.serializer(), it) }.getOrNull() }
    ?: SavedPlayer()

  var volume by mutableFloatStateOf(saved.volume.coerceIn(0f, 1f))
    private set

  var muted by mutableStateOf(saved.muted)
    private set

  /** What the player plays at: silent while [muted], else [volume]. */
  val playedVolume: Float get() = if (muted) 0f else volume

  /** Sets the volume (0 to 1); a change while muted unmutes, as turning a volume up does. */
  fun changeVolume(to: Float) {
    volume = to.coerceIn(0f, 1f)
    muted = false
    save()
  }

  fun toggleMute() {
    muted = !muted
    save()
  }

  private fun save() = file.write(json.encodeToString(SavedPlayer.serializer(), SavedPlayer(volume, muted)))

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
