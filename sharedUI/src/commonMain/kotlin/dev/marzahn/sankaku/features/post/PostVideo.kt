package dev.marzahn.sankaku.features.post

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.pause
import com.google.fonts.icons.play_arrow
import com.google.fonts.icons.volume_off
import com.google.fonts.icons.volume_up
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.MediaSlider
import dev.marzahn.sankaku.ui.components.VideoBadge
import dev.marzahn.sankaku.ui.components.VideoFailed
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import org.koin.compose.koinInject

/** Whether [post] is a video the app plays (`file_type` `video/…`, discovery step 14: mp4, with an mp4 sample). */
fun isVideo(post: PostDto): Boolean = post.fileType?.startsWith("video/") == true

/** [post]'s length as the player shows it ([videoTime]); null when the list didn't say. */
internal fun videoBadgeDuration(post: PostDto): String? = post.videoDuration?.takeIf { it > 0 }?.let(::videoTime)

/** The [VideoBadge] of [post] on a grid cell or a recommended post, with its length; nothing unless it's a video. */
@Composable
fun PostVideoBadge(post: PostDto, modifier: Modifier = Modifier) {
  if (isVideo(post)) VideoBadge(videoBadgeDuration(post), modifier)
}

/**
 * Which file [url] is, whatever its signature: the URL without its query. The details' fetch signs a post's URLs
 * again, and the player must not reopen the same file for that (#180).
 */
internal fun videoSource(url: String): String = url.substringBefore('?')

/** The URL [PostVideo] plays for [post]: the sample (an mp4, smaller than the file), else the file itself. */
internal fun videoUrl(post: PostDto): String? = post.sampleUrl ?: post.fileUrl

/**
 * Whether this platform's player can open [post]'s video. AVPlayer, behind the desktop player on macOS, can't open
 * WebM (#192); Android's plays every video the site has.
 */
internal fun platformPlaysVideo(post: PostDto): Boolean = videoUrl(post)?.let { playsVideoFile(videoSource(it)) } ?: true

/**
 * The name of [source] (see [videoSource]) of [post] in the players' disk cache: the post's md5 and the file's name,
 * whatever the URL's signature, so a video signed again still reads from disk (#189). Never logged.
 */
internal fun videoCacheKey(post: PostDto, source: String): String =
  if (post.md5.isNotEmpty()) "${post.md5}/${source.substringAfterLast('/')}" else source

/** Flash, which nothing in the app can play. */
fun isFlash(post: PostDto): Boolean = post.fileExt.equals("swf", ignoreCase = true) || post.fileType?.contains("flash") == true

/**
 * The keys of the video on screen, for the viewer's key handler: the [PostVideo] shown registers its player here.
 * Space plays or pauses and M mutes. V starts scrubbing, as T hands keys to the tag list: then A/D seek by
 * [SEEK_SECONDS] and W/S set the volume, instead of stepping posts, until V or Esc. On touch the phone viewer's seek
 * row ([VideoSeekRow]), its progress line and a double-tap's seek act on the same player. A drag on either track goes
 * through [drag] and [release].
 */
@Stable
class VideoKeys(private val preferences: PlayerPreferences) {
  internal var player by mutableStateOf<VideoPlayback?>(null)

  var scrubbing by mutableStateOf(false)
    private set

  /**
   * Where a drag on the track holds the video, 0 to 1 of its length; null while no drag is under way. The phone
   * viewer shows the time there in the letterbox (#189).
   */
  var draggedTo by mutableStateOf<Float?>(null)
    private set

  private var resumeAfterDrag = false

  /**
   * A drag on the track moved to [to] (0 to 1): the video holds and shows the keyframe nearest to it (live scrub,
   * `.scratch/video-scrub/` option B, #189). The first move pauses it; [release] plays it again if it was playing.
   */
  fun drag(to: Float) {
    val player = player ?: return
    if (draggedTo == null) {
      resumeAfterDrag = player.isPlaying
      player.pause()
      player.dragging = true
    }
    draggedTo = to.coerceIn(0f, 1f)
    player.seekTo(to)
  }

  /** The drag let go: the video seeks exactly to where it was let go, and plays on if it was playing. */
  fun release() {
    val to = draggedTo ?: return
    draggedTo = null
    val player = player ?: return
    player.dragging = false
    player.seekTo(to)
    if (resumeAfterDrag) player.play()
  }

  /**
   * Touch: a hardware volume key. The system sets the media volume as usual (so this never consumes the key); volume
   * up while a video is shown also lifts the shared mute, as turning a volume up does on desktop.
   */
  fun onVolumeKey(key: Key) {
    if (player != null && key == Key.VolumeUp && preferences.muted) preferences.toggleMute()
  }

  /**
   * Seeks the shown video [SEEK_SECONDS] [forward] or back, as A/D do while scrubbing and a double-tap on either half
   * does on touch; false when no video with a length is shown.
   */
  fun seek(forward: Boolean): Boolean {
    val player = player ?: return false
    if (player.duration <= 0.0) return false
    val to = player.position * player.duration + if (forward) SEEK_SECONDS else -SEEK_SECONDS
    player.seekTo((to / player.duration).toFloat())
    return true
  }

  /** Handles [key] if a video is shown and it's one of the video's keys. */
  fun onKey(key: Key): Boolean {
    val player = player ?: return false
    return when {
      Shortcuts.PlayPause.matches(key) -> true.also { if (player.isPlaying) player.pause() else player.play() }
      Shortcuts.Mute.matches(key) -> true.also { preferences.toggleMute() }
      Shortcuts.Scrub.matches(key) -> true.also { scrubbing = !scrubbing }
      !scrubbing -> false
      Shortcuts.Previous.matches(key) -> true.also { seek(forward = false) }
      Shortcuts.Next.matches(key) -> true.also { seek(forward = true) }
      Shortcuts.Up.matches(key) -> true.also { preferences.changeVolume(preferences.playedVolume + VOLUME_STEP) }
      Shortcuts.Down.matches(key) -> true.also { preferences.changeVolume(preferences.playedVolume - VOLUME_STEP) }
      Shortcuts.Back.matches(key) -> true.also { scrubbing = false }
      else -> false
    }
  }

  internal fun register(player: VideoPlayback) {
    // A drag on the last video ends with it, even if that video's unregister comes after this.
    this.player?.dragging = false
    draggedTo = null
    this.player = player
  }

  internal fun unregister(player: VideoPlayback) {
    if (this.player !== player) return
    player.dragging = false
    this.player = null
    scrubbing = false
    draggedTo = null
  }

  private companion object {
    const val VOLUME_STEP = 0.1f
  }
}

/** How far A/D (while scrubbing) and a double-tap on touch seek, as YouTube does. */
internal const val SEEK_SECONDS = 5.0

/** The viewer's [VideoKeys], for the [PostVideo] it shows; null where nothing handles video keys. */
val LocalVideoKeys = staticCompositionLocalOf<VideoKeys?> { null }

/** Where the video is, in seconds, as its track shows it: where a drag holds it, else where it plays. */
private fun VideoPlayback.shownTime(keys: VideoKeys?): Double = (keys?.draggedTo ?: position) * duration

/** "01:05", or "1:02:05" from an hour. */
internal fun videoTime(seconds: Double): String {
  val total = seconds.coerceAtLeast(0.0).toInt()
  val (h, m, s) = Triple(total / 3600, total / 60 % 60, total % 60)
  fun two(n: Int) = n.toString().padStart(2, '0')
  return if (h > 0) "$h:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}"
}

/**
 * Plays [post]'s sample (an mp4, smaller than the file) through the platform's player (AVPlayer on macOS): at once,
 * looping, at the saved volume ([PlayerPreferences]). On desktop the controls show while the pointer is over the
 * video, while it's paused and while scrubbing; on touch they're the phone viewer's chrome ([VideoSeekRow]) instead.
 * Under it, the preview stays as the placeholder until the first frame. [onFirstFrame] runs once it plays.
 *
 * It loads as soon as it's composed but shows and plays only once [play] (the viewer has settled), so the open
 * transition and the download overlap (#180). Once shown, it stays drawn while [shown], paused unless [play]: a
 * closing viewer keeps its frame instead of dropping back to the preview (#283). It opens each file once: the
 * details' fetch signs the post's URLs again, and reopening for that restarted the video and showed the preview over
 * it. Only a failure reopens it, with the newest URL, where it was.
 */
@Composable
fun PostVideo(
  post: PostDto,
  modifier: Modifier = Modifier,
  play: Boolean = true,
  shown: Boolean = play,
  preferences: PlayerPreferences = koinInject(),
  onFirstFrame: () -> Unit = {},
  player: VideoPlayback = rememberVideoPlayback(),
) {
  val url = videoUrl(post) ?: return
  val firstFrame by rememberUpdatedState(onFirstFrame)
  val source = videoSource(url)
  LaunchedEffect(player, source) {
    snapshotFlow { player.firstFrameShown }.first { it }
    firstFrame()
  }
  val keys = LocalVideoKeys.current
  val latestUrl by rememberUpdatedState(url)
  val playing by rememberUpdatedState(play)
  // Retry (#192) opens the file again with the newest URL, from where it was.
  var attempt by remember(player, source) { mutableIntStateOf(0) }
  var resumeAt by remember(player, source) { mutableFloatStateOf(0f) }
  // The URL last opened: a failure with a newer URL waiting reopens by itself, so only one without shows (#192).
  var opened by remember(player, source) { mutableStateOf("") }
  LaunchedEffect(player, source, attempt) {
    player.volume = preferences.playedVolume
    val cacheKey = videoCacheKey(post, source)
    opened = latestUrl
    player.open(opened, cacheKey, playing)
    // Where the video should be once an open succeeds: a Retry's position, kept until a seek has landed, so a reopen
    // after another failure still goes there rather than to the failed media's 0.
    var pendingSeek: Float? = resumeAt.takeIf { it > 0f }
    // In its own coroutine, so a newer URL that comes while this open stalls still reopens below.
    val resume = pendingSeek?.let { to ->
      launch {
        snapshotFlow { player.duration > 0.0 || player.failed }.first { it }
        if (!player.failed) {
          player.seekTo(to)
          pendingSeek = null
        }
      }
    }
    // A signed URL runs out: once the player fails and a newer URL has come, it reopens with that one, where it was.
    snapshotFlow { player.failed && latestUrl != opened }.collect { failed ->
      if (!failed) return@collect
      resume?.cancel()
      val at = pendingSeek ?: player.position
      pendingSeek = at
      opened = latestUrl
      // open clears the length and the failure at once, so neither is the old media's.
      player.open(opened, cacheKey, playing)
      snapshotFlow { player.duration > 0.0 || player.failed }.first { it }
      // Failed again: the next newer URL gets its turn through the flow above, still bound for the same place.
      if (!player.failed) {
        player.seekTo(at)
        pendingSeek = null
      }
    }
  }
  LaunchedEffect(player, play) { if (play) player.play() else player.pause() }
  LaunchedEffect(preferences.playedVolume) { player.volume = preferences.playedVolume }
  // Only a shown video takes keys and the seek row: while the viewer opens, nothing on screen is playing yet.
  DisposableEffect(keys, player, play) {
    if (play) keys?.register(player)
    onDispose { keys?.unregister(player) }
  }
  val hover = remember { MutableInteractionSource() }
  val hovered by hover.collectIsHoveredAsState()
  val scrubbing = keys?.scrubbing == true
  Box(modifier.hoverable(hover)) {
    if (shown && player.hasMedia) VideoPlaybackSurface(player, Modifier.fillMaxSize())
    // A failed open says so over the preview, with Retry, instead of controls frozen at "00:00 / 00:00" (#192).
    val failed = player.failed && latestUrl == opened
    if (play && failed) {
      VideoFailed(
        onRetry = {
          // A failed open can reset the position: then the last place it was kept stays.
          if (player.position > 0f) resumeAt = player.position
          attempt++
        },
        modifier = Modifier.align(Alignment.Center),
      )
    }
    AnimatedVisibility(
      visible = play && !platformIsTouch && !failed && (hovered || scrubbing || (player.hasMedia && !player.isPlaying)),
      enter = fadeIn(effectsSpec()),
      exit = fadeOut(effectsSpec()),
      // The panel's whole bottom edge: the post counter sits top left.
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
    ) {
      VideoControls(player, keys, preferences, scrubbing)
    }
  }
}

/**
 * [player]'s track: it follows a drag through [keys] (a keyframe while dragging, exact on release; #189), or seeks
 * straight away where no viewer handles video keys.
 */
@Composable
private fun VideoTrack(player: VideoPlayback, keys: VideoKeys?, modifier: Modifier, colors: SliderColors = SliderDefaults.colors()) {
  MediaSlider(
    value = keys?.draggedTo ?: player.position,
    onValueChange = { if (keys != null) keys.drag(it) else player.seekTo(it) },
    onValueChangeFinished = { keys?.release() },
    colors = colors,
    modifier = modifier,
  )
}

@Composable
private fun VideoControls(player: VideoPlayback, keys: VideoKeys?, preferences: PlayerPreferences, scrubbing: Boolean) {
  Surface(
    shape = RoundedCornerShape(50),
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
    shadowElevation = 2.dp,
  ) {
    Row(
      Modifier.padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
        Icon(
          if (player.isPlaying) pause else play_arrow,
          contentDescription = "${if (player.isPlaying) "Pause" else "Play"} (${Shortcuts.PlayPause.label})",
        )
      }
      Text("${videoTime(player.shownTime(keys))} / ${videoTime(player.duration)}", style = MaterialTheme.typography.labelMedium)
      VideoTrack(player, keys, Modifier.weight(1f))
      if (scrubbing) {
        KeyHint("A D", "seek")
        KeyHint("W S", "volume")
        KeyHint(Shortcuts.Back.label, "done")
      } else {
        KeyHint(Shortcuts.Scrub.label, "keys")
      }
      IconButton(onClick = preferences::toggleMute) {
        Icon(
          if (preferences.playedVolume == 0f) volume_off else volume_up,
          contentDescription = "${if (preferences.muted) "Unmute" else "Mute"} (${Shortcuts.Mute.label})",
        )
      }
      MediaSlider(
        value = preferences.playedVolume,
        onValueChange = preferences::changeVolume,
        modifier = Modifier.width(96.dp),
      )
    }
  }
}

/**
 * Touch: the phone viewer's seek row (`.scratch/android-shell/` option 3, row 9), part of its chrome: play or pause,
 * the time, the track, the length and the shared mute, on a dark pill over the letterbox. Volume is the hardware
 * keys, so there's no volume slider. Drawn dark whatever the theme, as it always lies over the black around the video.
 */
@Composable
fun VideoSeekRow(keys: VideoKeys, modifier: Modifier = Modifier, preferences: PlayerPreferences = koinInject()) {
  val player = keys.player ?: return
  SankakuTheme(isDark = true) {
    Surface(
      shape = CircleShape,
      color = Color.Black.copy(alpha = 0.55f),
      contentColor = Color.White,
      modifier = modifier.height(VIDEO_SEEK_ROW_HEIGHT),
    ) {
      // No padding: the icons' own 12 dp insets line them up with the sheet's content edges.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
          Icon(if (player.isPlaying) pause else play_arrow, contentDescription = if (player.isPlaying) "Pause" else "Play")
        }
        Text(videoTime(player.shownTime(keys)), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
        VideoTrack(
          player,
          keys,
          // The mockup's light track over the black, as the progress line draws it.
          colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = UNPLAYED_ALPHA)),
          modifier = Modifier.weight(1f),
        )
        Text(videoTime(player.duration), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
        IconButton(onClick = preferences::toggleMute) {
          Icon(
            if (preferences.playedVolume == 0f) volume_off else volume_up,
            contentDescription = if (preferences.muted) "Unmute" else "Mute",
          )
        }
      }
    }
  }
}

/**
 * Touch: while the seek row's track is dragged, where the video will land, large, over the letterbox ("01:23", "of
 * 02:07"), as the video itself shows the frame there (`.scratch/video-scrub/` option B, #189). Nothing otherwise.
 */
@Composable
fun VideoDragTime(keys: VideoKeys, modifier: Modifier = Modifier) {
  val player = keys.player ?: return
  val at = keys.draggedTo ?: return
  val shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)
  Column(modifier.height(VIDEO_DRAG_TIME_HEIGHT), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
    Text(
      videoTime(at * player.duration),
      color = Color.White,
      style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, shadow = shadow),
    )
    Text(
      "of ${videoTime(player.duration)}",
      color = Color.White.copy(alpha = 0.75f),
      style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, shadow = shadow),
    )
  }
}

/** [VideoDragTime]'s height, for the viewer to place it by. */
val VIDEO_DRAG_TIME_HEIGHT = 64.dp

/** [VideoSeekRow]'s height, a 48 dp touch target, for the viewer to place it by. */
val VIDEO_SEEK_ROW_HEIGHT = 48.dp

/** Touch: the 2 dp progress line that stays while the phone viewer's chrome, and with it the seek row, is hidden. */
@Composable
fun VideoProgressLine(keys: VideoKeys, modifier: Modifier = Modifier) {
  val player = keys.player ?: return
  SankakuTheme(isDark = true) {
    val played = MaterialTheme.colorScheme.primary
    Box(
      modifier
        .fillMaxWidth()
        .height(2.dp)
        .drawBehind {
          drawRect(Color.White.copy(alpha = UNPLAYED_ALPHA))
          drawRect(played, size = size.copy(width = size.width * player.position.coerceIn(0f, 1f)))
        },
    )
  }
}

/**
 * Which way a double-tap seeks, with how its ripple looks: on the video's right edge with » and "+5 s" forward, on
 * its left with « and "−5 s" back. [ForwardRipple]'s shape is the right half of an ellipse twice the ripple's width.
 */
internal enum class SeekDirection(val glyph: String, val sign: String, val alignment: Alignment, val shape: Shape) {
  Back("«", "−", Alignment.CenterStart, GenericShape { size, _ -> addOval(Rect(-size.width, 0f, size.width, size.height)) }),
  Forward("»", "+", Alignment.CenterEnd, GenericShape { size, _ -> addOval(Rect(0f, 0f, size.width * 2, size.height)) }),
  ;

  val label: String get() = "$sign${SEEK_SECONDS.toInt()} s"

  companion object {
    /** The right half of a box [width] wide seeks forward, the left half back. */
    fun at(x: Float, width: Float): SeekDirection = if (x >= width / 2f) Forward else Back
  }
}

/** A double-tap's seek while it shows; [id] tells one double-tap from the next, so each restarts the timer. */
@Immutable
internal data class SeekShown(val direction: SeekDirection, val id: Int)

/**
 * The feedback for a double-tap seek (`.scratch/android-viewer/` direction A, row 4): a light half-ellipse on the
 * tapped side of the video with the direction's arrow and "+5 s" or "−5 s", fading out after a moment.
 */
@Composable
internal fun SeekRipple(seek: SeekShown?, modifier: Modifier = Modifier) {
  // The last seek stays drawn while it fades out; it's kept after composition, never written during it.
  val last = remember { mutableStateOf<SeekShown?>(null) }
  SideEffect { if (seek != null) last.value = seek }
  val shown = (seek ?: last.value)?.direction ?: return
  Box(modifier) {
    AnimatedVisibility(
      visible = seek != null,
      enter = fadeIn(effectsSpec()),
      exit = fadeOut(effectsSpec()),
      modifier = Modifier.align(shown.alignment).fillMaxHeight().fillMaxWidth(RIPPLE_SHARE),
    ) {
      Column(
        Modifier.fillMaxSize().clip(shown.shape).background(Color.White.copy(alpha = 0.18f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(shown.glyph, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(shown.label, color = Color.White, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

/** The unplayed part of a track or the progress line over the black around a video (the mockups' white at 35%). */
private const val UNPLAYED_ALPHA = 0.35f

/** How long a double-tap's seek stays shown after the last tap. */
internal const val SEEK_SHOWN_MS = 700L

/** The ripple's width as a share of the video's (112 of 412 dp in the mockup). */
private const val RIPPLE_SHARE = 0.27f
