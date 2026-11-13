package dev.marzahn.sankaku.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the app opens on at each start (a launch, or a return after more than 5 minutes away). */
@Serializable
enum class StartOn {
  /** The queue page (the default). */
  Queue,

  /** A Browse grid, opened "now", in front of the current block. */
  Browse,
}

@Serializable
private data class SavedPrivacy(
  val hideInRecents: Boolean = true,
  val gOnly: Boolean = false,
  val startOn: StartOn = StartOn.Queue,
  val alwaysStartGOnly: Boolean = false,
)

/**
 * The privacy and start settings, kept in [file] between runs and written at once (a few bytes, changed by a tap).
 *
 * - [hideInRecents] (Android; on by default): the app switcher shows a blank preview instead of the screen.
 * - [gOnly] (off by default): the signed-out rating lock for a signed-in user. [gOnlyForced] is the debug `safeOnly`
 *   launch flag, which holds the same lock for a run, so screenshots and the switch share one path.
 * - [startOn] (touch; the queue page by default): what each start opens on (#177).
 * - [alwaysStartGOnly] (off by default): G only is switched on at every launch; it can still be switched off for the
 *   run.
 *
 * "Clear everything" leaves this file alone: the switches are preferences, not history.
 */
class PrivacySettings(private val file: TextFile, val gOnlyForced: Boolean = false) {
  private val saved = file.read()?.let { runCatching { json.decodeFromString(SavedPrivacy.serializer(), it) }.getOrNull() }
    ?: SavedPrivacy()

  private val _hideInRecents = MutableStateFlow(saved.hideInRecents)
  val hideInRecents: StateFlow<Boolean> = _hideInRecents.asStateFlow()

  private val _alwaysStartGOnly = MutableStateFlow(saved.alwaysStartGOnly)
  val alwaysStartGOnly: StateFlow<Boolean> = _alwaysStartGOnly.asStateFlow()

  private val _startOn = MutableStateFlow(saved.startOn)
  val startOn: StateFlow<StartOn> = _startOn.asStateFlow()

  // "Always start in G only" switches it on as the app launches.
  private val _gOnlySwitch = MutableStateFlow(saved.gOnly || saved.alwaysStartGOnly)

  /** The user's own choice, as the sidebar's switch shows it. */
  val gOnlySwitch: StateFlow<Boolean> = _gOnlySwitch.asStateFlow()

  private val _gOnly = MutableStateFlow(_gOnlySwitch.value || gOnlyForced)

  /** Whether the rating is locked to G for a signed-in user: the switch, or the debug launch flag. */
  val gOnly: StateFlow<Boolean> = _gOnly.asStateFlow()

  init {
    if (_gOnlySwitch.value != saved.gOnly) save()
  }

  fun setHideInRecents(on: Boolean) {
    _hideInRecents.value = on
    save()
  }

  fun setGOnly(on: Boolean) {
    _gOnlySwitch.value = on
    _gOnly.value = on || gOnlyForced
    save()
  }

  fun setStartOn(start: StartOn) {
    _startOn.value = start
    save()
  }

  fun setAlwaysStartGOnly(on: Boolean) {
    _alwaysStartGOnly.value = on
    save()
  }

  // The switch has already changed for this run; a full disk or locked file only costs it the next restart.
  private fun save() {
    runCatching {
      val state = SavedPrivacy(_hideInRecents.value, _gOnlySwitch.value, _startOn.value, _alwaysStartGOnly.value)
      file.write(json.encodeToString(SavedPrivacy.serializer(), state))
    }
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
