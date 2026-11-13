package dev.marzahn.sankaku.features.post

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The players' disk cache of the videos played (128 MB, least recently used first), one for the process. Keyed by the
 * post's md5, not the signed URL, so a video signed again still reads from disk (#189).
 */
@OptIn(UnstableApi::class)
private object VideoDiskCache {
  private var cache: SimpleCache? = null

  @Synchronized
  fun get(context: Context): SimpleCache = cache ?: run {
    val app = context.applicationContext
    // ComposeMediaPlayer's cache from before #189, keyed by signed URLs: nothing reads it any more. Its files and its
    // index rows (which hold those URLs) go, off the main thread, as this runs while the viewer opens.
    // One provider serves both, so a single helper holds exoplayer_internal.db.
    val database = StandaloneDatabaseProvider(app)
    val old = File(app.cacheDir, "compose_media_player_cache")
    if (old.exists()) Thread { runCatching { SimpleCache.delete(old, database) } }.start()
    SimpleCache(File(app.cacheDir, "video"), LeastRecentlyUsedCacheEvictor(MAX_BYTES), database)
      .also { cache = it }
  }

  private const val MAX_BYTES = 128L * 1024 * 1024
}

/** A film's audio: unplugged headphones pause it, and while it's heard other apps' audio pauses. */
private val MovieAudio =AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

@OptIn(UnstableApi::class)
@Stable
private class ExoVideoPlayback(context: Context) : VideoPlayback {
  val player: ExoPlayer = ExoPlayer.Builder(context)
    .setMediaSourceFactory(
      DefaultMediaSourceFactory(
        CacheDataSource.Factory()
          .setCache(VideoDiskCache.get(context))
          .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
          .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
      ),
    )
    // Audio focus only while it's heard (volume): other apps' audio pauses then, never for a muted video (#524).
    .setAudioAttributes(MovieAudio,true)
    .setHandleAudioBecomingNoisy(true)
    .build()
    .apply { repeatMode = Player.REPEAT_MODE_ALL }

  override var hasMedia by mutableStateOf(false)
    private set
  override var isPlaying by mutableStateOf(false)
    private set
  override var playWhenReady by mutableStateOf(false)
    private set
  override var buffering by mutableStateOf(false)
    private set
  override var failed by mutableStateOf(false)
    private set
  override var firstFrameShown by mutableStateOf(false)
    private set
  override var duration by mutableDoubleStateOf(0.0)
    private set
  override var position by mutableFloatStateOf(0f)
    private set

  /** Width over height; 0 until the first frame's size is known. */
  var aspectRatio by mutableFloatStateOf(0f)
    private set

  private var volumeState by mutableFloatStateOf(1f)
  override var volume: Float
    get() = volumeState
    set(value) {
      val heard = volumeState > 0f
      volumeState = value.coerceIn(0f, 1f)
      player.volume = volumeState
      // Muted, it gives the focus back, so the music it paused plays on; unmuted, it takes it again.
      if (heard != volumeState > 0f) player.setAudioAttributes(MovieAudio,volumeState > 0f)
    }

  private var draggingState by mutableStateOf(false)
  override var dragging: Boolean
    get() = draggingState
    set(value) {
      if (draggingState == value) return
      draggingState = value
      player.isScrubbingModeEnabled = value
      player.setSeekParameters(if (value) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
    }


  init {
    player.addListener(
      object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
          isPlaying = playing
        }

        override fun onPlayWhenReadyChanged(play: Boolean, reason: Int) {
          playWhenReady = play
          updateBuffering()
        }

        override fun onPlaybackStateChanged(state: Int) {
          updateBuffering()
          if (state == Player.STATE_READY) {
            failed = false
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 } ?: 0.0
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          failed = true
        }

        override fun onRenderedFirstFrame() {
          firstFrameShown = true
        }

        override fun onVideoSizeChanged(size: VideoSize) {
          if (size.width > 0 && size.height > 0) aspectRatio = size.width * size.pixelWidthHeightRatio / size.height
        }
      },
    )
  }

  /** From the player's own state, whichever of its two events came first. */
  private fun updateBuffering() {
    buffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
  }

  override fun open(url: String, cacheKey: String, play: Boolean) {
    failed = false
    firstFrameShown = false
    duration = 0.0
    position = 0f
    player.setMediaItem(MediaItem.Builder().setUri(url).setCustomCacheKey(cacheKey).build())
    player.playWhenReady = play
    playWhenReady = play
    player.prepare()
    hasMedia = true
  }

  override fun play() {
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
    player.play()
  }

  override fun pause() = player.pause()

  override fun seekTo(fraction: Float) {
    if (duration <= 0.0) return
    position = fraction.coerceIn(0f, 1f)
    player.seekTo((position * duration * 1000).toLong())
  }

  /** Follows the player's time once a frame while it plays; a drag sets [position] itself. */
  fun follow() {
    if (player.isPlaying && !dragging && duration > 0.0) {
      position = (player.currentPosition / 1000.0 / duration).toFloat().coerceIn(0f, 1f)
    }
  }
}

@Composable
internal actual fun rememberVideoPlayback(): VideoPlayback {
  val context = LocalContext.current
  val playback = remember { ExoVideoPlayback(context) }
  DisposableEffect(playback) { onDispose { playback.player.release() } }
  // The track follows the video once a frame, only while it plays: a paused one wakes nothing.
  LaunchedEffect(playback) {
    snapshotFlow { playback.isPlaying }.collectLatest { playing ->
      while (playing) withFrameMillis { playback.follow() }
    }
  }
  // In the background it pauses, and plays on coming back if it was playing.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle, playback) {
    var resume = false
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> {
          resume = playback.player.playWhenReady
          playback.pause()
        }
        Lifecycle.Event.ON_START -> if (resume) playback.play()
        else -> {}
      }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  return playback
}

@OptIn(UnstableApi::class)
@Composable
internal actual fun VideoPlaybackSurface(playback: VideoPlayback, modifier: Modifier) {
  val exo = playback as ExoVideoPlayback
  Box(modifier, contentAlignment = Alignment.Center) {
    val ratio = exo.aspectRatio
    // A TextureView, not a SurfaceView: it fades and moves with the viewer's layers. Transparent until the first frame,
    // so the preview under it shows until then.
    AndroidView(
      factory = { TextureView(it) },
      modifier = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier,
      update = { exo.player.setVideoTextureView(it) },
      onRelease = { exo.player.clearVideoTextureView(it) },
    )
  }
}

@OptIn(UnstableApi::class)
actual suspend fun clearVideoCache() {
  // Needs only a context for the cache's folder; the app's is reachable through Koin.
  val context = org.koin.mp.KoinPlatform.getKoin().get<Context>()
  withContext(Dispatchers.IO) {
    val cache = VideoDiskCache.get(context)
    cache.keys.toList().forEach(cache::removeResource)
  }
}

@OptIn(UnstableApi::class)
actual suspend fun videoCacheBytes(): Long {
  val context = org.koin.mp.KoinPlatform.getKoin().get<Context>()
  return withContext(Dispatchers.IO) { VideoDiskCache.get(context).cacheSpace }
}

