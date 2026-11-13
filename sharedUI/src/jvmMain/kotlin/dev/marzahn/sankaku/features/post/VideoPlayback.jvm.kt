package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Desktop: ComposeMediaPlayer (AVPlayer on macOS), which keeps no disk cache and seeks to keyframes only.
 *
 * Its seek is asynchronous, and the library reads the position back before AVPlayer has moved: `sliderPos` falls back
 * to where the video was, and stays there while paused. So the track jumped back after A/D, and the next A/D counted
 * from the old place, never getting past one step. [position] is the seek's target instead until the player reports
 * a position near it ([settleSeeks]).
 */
@Stable
internal class LibraryVideoPlayback(val state: VideoPlayerState) : VideoPlayback {
  /** The last seek's target, 0 to 1, until the player has caught up with it. */
  private var seekTarget by mutableStateOf<Float?>(null)

  private val reported get() = state.sliderPos / SLIDER_RANGE

  override val hasMedia get() = state.hasMedia
  override val isPlaying get() = state.isPlaying

  /** The library has no play-when-ready: what the last open, play or pause asked for. */
  override var playWhenReady by mutableStateOf(false)
    private set
  override val buffering get() = playWhenReady && state.isLoading && state.error == null
  override val failed get() = state.error != null
  override val firstFrameShown get() = state.hasMedia && state.isPlaying
  override val duration get() = state.duration
  override val position get() = seekTarget ?: reported
  override var volume: Float
    get() = state.volume
    set(value) {
      state.volume = value
    }
  override var dragging = false

  override fun open(url: String, cacheKey: String, play: Boolean) {
    seekTarget = null
    state.loop = true
    // The library keeps the last open's error through a new open: cleared here, a reopen or a Retry isn't taken
    // for failed before it has even started (#192).
    state.clearError()
    playWhenReady = play
    state.openUri(url, if (play) InitialPlayerState.PLAY else InitialPlayerState.PAUSE)
  }

  override fun play() {
    playWhenReady = true
    state.play()
  }

  override fun pause() {
    playWhenReady = false
    state.pause()
  }

  override fun seekTo(fraction: Float) {
    val to = fraction.coerceIn(0f, 1f)
    state.seekTo(to * SLIDER_RANGE)
    seekTarget = to
  }

  /**
   * Lets go of each seek's target once the player has reported a position within [SEEK_LANDED_SECONDS] of it for
   * [SEEK_HOLD_MS]. AVPlayer lands on the keyframe nearest the target (every 3 s in a typical post), not on the target:
   * let go at once, the track stepped back to the keyframe and the next A/D counted from there, so a held A/D jumped
   * back on most presses. Held for a moment, a held key's next press (well within it) seeks on from the target; once the
   * presses stop, a playing video short of the target catches up with it before the track follows the player again, so
   * the track never steps back. Playing, it also lets go [SEEK_SETTLE_MS] after the
   * last seek if it never lands (a dropped seek, keyframes further apart), as the reported position moves on by
   * itself; paused, it holds on, as the library doesn't read the position again until it plays.
   */
  suspend fun settleSeeks() {
    snapshotFlow { seekTarget }.collectLatest { to ->
      if (to == null) return@collectLatest
      val settled = withTimeoutOrNull(SEEK_SETTLE_MS) { awaitLanded(to) } != null
      if (!settled) snapshotFlow { state.isPlaying }.first { it }
      // Landed on a keyframe before the target, a playing video catches up with it within a keyframe interval: the
      // track waits for it there rather than stepping back.
      if (settled) withTimeoutOrNull(CATCH_UP_MS) { snapshotFlow { reported >= to || !state.isPlaying }.first { it } }
      seekTarget = null
    }
  }

  /**
   * Waits until the player reports a position near [to] and still does [SEEK_HOLD_MS] later: the library writes the
   * target into `sliderPos` for a moment before it puts back the stale position.
   */
  private suspend fun awaitLanded(to: Float) {
    while (true) {
      snapshotFlow { landed(to) }.first { it }
      delay(SEEK_HOLD_MS)
      if (landed(to)) return
    }
  }

  /**
   * Only while playing: paused, the library reads the position back once, before AVPlayer has moved, and not again,
   * so a seek within [SEEK_LANDED_SECONDS] of where the video was (the track, A at the start) would count the stale
   * position as landed.
   */
  private fun landed(to: Float) = state.isPlaying && abs(reported - to) * state.duration <= SEEK_LANDED_SECONDS

  internal companion object {
    /** The library's seek range: `sliderPos` and `seekTo` run from 0 to 1000. */
    const val SLIDER_RANGE = 1000f

    /**
     * How near the target the reported position must be for a playing video's seek to have landed: half a typical 3 s
     * keyframe interval and some margin. A playing video's stale position may fall within it on a short seek (the
     * track, A/D at the ends): ahead of the target it shows until the seek lands, a step in the seek's own direction;
     * behind it, the track waits at the target as for a keyframe.
     */
    const val SEEK_LANDED_SECONDS = 2.0

    /** How long the track keeps to a landed seek's target: longer than a held key's repeat. */
    const val SEEK_HOLD_MS = 400L

    /** How long a playing video's track keeps to a seek's target before following the player again. */
    const val SEEK_SETTLE_MS = 1_500L

    /** The longest a playing video takes to catch up with a target its seek landed short of, by [SEEK_LANDED_SECONDS]. */
    const val CATCH_UP_MS = 2_500L
  }
}

@Composable
internal actual fun rememberVideoPlayback(): VideoPlayback {
  val state = rememberVideoPlayerState()
  val playback = remember(state) { LibraryVideoPlayback(state) }
  LaunchedEffect(playback) { playback.settleSeeks() }
  return playback
}

@Composable
internal actual fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier) {
  VideoPlayerSurface((playback as LibraryVideoPlayback).state, modifier)
}

actual suspend fun clearVideoCache() {}

actual suspend fun videoCacheBytes(): Long = 0
