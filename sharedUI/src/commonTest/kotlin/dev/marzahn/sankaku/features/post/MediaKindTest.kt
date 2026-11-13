package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.dto.PostDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mark a grid cell and a recommended post carry (#524): videos the platform plays (▶ and the length), GIFs, Flash,
 * and a video it can't play here (a WebM on macOS, #226); a still image carries none.
 */
class MediaKindTest {
  private val post = PostDto(id = "1", md5 = "abc", rating = "g", status = "active", fileType = "image/jpeg")

  @Test
  fun eachKindOfPostGetsItsMark() {
    assertNull(mediaKind(post))
    assertEquals(MediaKind.Video, mediaKind(post.copy(fileType = "video/mp4"), playsVideo = true))
    assertEquals(MediaKind.CantPlayVideo, mediaKind(post.copy(fileType = "video/webm"), playsVideo = false))
    assertEquals(MediaKind.Gif, mediaKind(post.copy(fileType = "image/gif")))
    assertEquals(MediaKind.Flash, mediaKind(post.copy(fileType = "application/x-shockwave-flash", fileExt = "swf")))
  }

  @Test
  fun eachMarkSaysWhatItIs() {
    val video = post.copy(fileType = "video/mp4", videoDuration = 127.0)
    assertEquals("Video, 02:07", mediaDescription(video, MediaKind.Video))
    assertEquals("Video, 02:07, can't play here", mediaDescription(video, MediaKind.CantPlayVideo))
    assertEquals("Animated GIF", mediaDescription(post, MediaKind.Gif))
    assertEquals("Flash, can't play", mediaDescription(post, MediaKind.Flash))
    assertEquals("Video", mediaDescription(post.copy(fileType = "video/mp4"), MediaKind.Video))
  }
}
