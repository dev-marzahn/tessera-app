package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The player behind [PostVideo], the seek row and the video keys: our own Media3 ExoPlayer on Android, ComposeMediaPlayer
 * (AVPlayer on macOS) on desktop. Android's is ours so a drag can seek to keyframes and the disk cache can key by md5
 * (#189); ComposeMediaPlayer exposes neither.
 */
@Stable
interface VideoPlayback {
  /** A video has been opened. */
  val hasMedia: Boolean

  /** Frames are moving: false while it [buffering] as while paused. */
  val isPlaying: Boolean

  /**
   * It plays, or will once it has the data: what the play button and Space show and toggle, so a video still
   * buffering reads Pause and can be stopped before it starts (#524).
   */
  val playWhenReady: Boolean get() = isPlaying

  /** It wants to play ([playWhenReady]) but waits for data: [PostVideo] shows a spinner. */
  val buffering: Boolean get() = false

  /**
   * The last open failed (a signed URL that ran out, the network, a file the player can't read); [open] clears it.
   * [PostVideo] shows it with Retry (#192).
   */
  val failed: Boolean

  /** The first frame has been drawn (on desktop: it plays). */
  val firstFrameShown: Boolean

  /** The length in seconds; 0 until known. */
  val duration: Double

  /** Where the video is, 0 to 1 of [duration]: set at once by [seekTo], so a paused video's track follows a seek. */
  val position: Float

  /** 0 to 1. */
  var volume: Float

  /**
   * Opens [url], looping, playing at once if [play]. [cacheKey] names the file in the disk cache whatever the URL's
   * signature (Android; desktop keeps no cache).
   */
  fun open(url: String, cacheKey: String, play: Boolean)

  fun play()

  fun pause()

  /**
   * A drag on the track is under way. While it is, Android's player seeks to the nearest keyframe, much faster than an
   * exact seek, in Media3's scrubbing mode; desktop's seeks as always.
   */
  var dragging: Boolean

  /** Seeks to [fraction] of the length: exactly, or to the nearest keyframe while [dragging]. */
  fun seekTo(fraction: Float)
}

/** A [VideoPlayback], released when it leaves the composition. */
@Composable
internal expect fun rememberVideoPlayback(): VideoPlayback

/**
 * Makes the player of a post's [PostVideo]: the platform's ([rememberVideoPlayback]). UI tests of a whole screen put
 * a fake in its place, as the native players don't run headless.
 */
internal val LocalVideoPlayback = staticCompositionLocalOf<@Composable () -> VideoPlayback> { { rememberVideoPlayback() } }

/** Draws [playback]'s video, fitted and centred in [modifier]'s bounds; transparent until its first frame. */
@Composable
internal expect fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier = Modifier)

/** Empties the players' disk cache of the videos played. It does nothing on desktop, which keeps none. */
expect suspend fun clearVideoCache()

/** The bytes the players' disk cache of videos takes (Settings → Storage, #205); 0 on desktop, which keeps none. */
expect suspend fun videoCacheBytes(): Long
