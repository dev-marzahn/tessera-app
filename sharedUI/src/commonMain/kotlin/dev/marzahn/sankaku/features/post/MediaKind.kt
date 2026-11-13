package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.fonts.icons.play_arrow_filled
import com.google.fonts.icons.play_disabled_filled
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.ui.components.MediaBadge

/** What a post's mark on a grid cell or a recommended post says it is (#524); a still image has none. */
enum class MediaKind {
  /** A video this platform plays: ▶ and its length. */
  Video,

  /** A video this platform can't play (WebM on macOS, #226): ⊘ and its length, as the viewer can only open its page. */
  CantPlayVideo,

  /** An animated GIF: "GIF". */
  Gif,

  /** Flash, which nothing plays: ⊘ "Flash". */
  Flash,
}

/** [post]'s [MediaKind]; [playsVideo] is whether this platform's player opens its video. */
fun mediaKind(post: PostDto, playsVideo: Boolean = platformPlaysVideo(post)): MediaKind? = when {
  isVideo(post) -> if (playsVideo) MediaKind.Video else MediaKind.CantPlayVideo
  isFlash(post) -> MediaKind.Flash
  post.fileType == "image/gif" -> MediaKind.Gif
  else -> null
}

/** What the mark of [post] as [kind] says to a screen reader, and what its cell says of it: "Video, 02:07". */
internal fun mediaDescription(post: PostDto, kind: MediaKind): String = when (kind) {
  MediaKind.Video -> listOfNotNull("Video", videoBadgeDuration(post)).joinToString(", ")
  MediaKind.CantPlayVideo -> listOfNotNull("Video", videoBadgeDuration(post), "can't play here").joinToString(", ")
  MediaKind.Gif -> "Animated GIF"
  MediaKind.Flash -> "Flash, can't play"
}

/** [post]'s mark on a grid cell or a recommended post; nothing for a still image. */
@Composable
fun PostMediaBadge(post: PostDto, modifier: Modifier = Modifier) {
  val kind = mediaKind(post) ?: return
  val description = mediaDescription(post, kind)
  when (kind) {
    MediaKind.Video -> MediaBadge(play_arrow_filled, videoBadgeDuration(post), description, modifier)
    MediaKind.CantPlayVideo -> MediaBadge(play_disabled_filled, videoBadgeDuration(post), description, modifier)
    MediaKind.Gif -> MediaBadge(null, "GIF", description, modifier)
    MediaKind.Flash -> MediaBadge(play_disabled_filled, "Flash", description, modifier)
  }
}
