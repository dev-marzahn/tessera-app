package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

/** Desktop: ComposeMediaPlayer (AVPlayer on macOS), which keeps no disk cache and seeks only exactly. */
@Stable
private class LibraryVideoPlayback(val state: VideoPlayerState) : VideoPlayback {
  override val hasMedia get() = state.hasMedia
  override val isPlaying get() = state.isPlaying
  override val failed get() = state.error != null
  override val firstFrameShown get() = state.hasMedia && state.isPlaying
  override val duration get() = state.duration
  override val position get() = state.sliderPos / SLIDER_RANGE
  override var volume: Float
    get() = state.volume
    set(value) {
      state.volume = value
    }
  override var dragging = false

  override fun open(url: String, cacheKey: String, play: Boolean) {
    state.loop = true
    // The library keeps the last open's error through a new open: cleared here, a reopen or a Retry isn't taken
    // for failed before it has even started (#192).
    state.clearError()
    state.openUri(url, if (play) InitialPlayerState.PLAY else InitialPlayerState.PAUSE)
  }

  override fun play() = state.play()

  override fun pause() = state.pause()

  override fun seekTo(fraction: Float) {
    val to = fraction.coerceIn(0f, 1f) * SLIDER_RANGE
    state.seekTo(to)
    // The player moves `sliderPos` only while playing; set here, a paused video's track follows the seek too.
    state.sliderPos = to
  }

  private companion object {
    /** The library's seek range: `sliderPos` and `seekTo` run from 0 to 1000. */
    const val SLIDER_RANGE = 1000f
  }
}

@Composable
internal actual fun rememberVideoPlayback(): VideoPlayback {
  val state = rememberVideoPlayerState()
  return remember(state) { LibraryVideoPlayback(state) }
}

@Composable
internal actual fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier) {
  VideoPlayerSurface((playback as LibraryVideoPlayback).state, modifier)
}

actual suspend fun clearVideoCache() {}

actual suspend fun videoCacheBytes(): Long = 0
