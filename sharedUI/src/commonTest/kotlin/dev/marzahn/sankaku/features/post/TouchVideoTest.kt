package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.dto.PostDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchVideoTest {
  @Test
  fun theTimeReadsAsThePlayerShowsIt() {
    assertEquals("00:00", videoTime(0.0))
    assertEquals("00:07", videoTime(7.9))
    assertEquals("04:29", videoTime(269.0))
    assertEquals("1:02:05", videoTime(3725.0))
    assertEquals("00:00", videoTime(-3.0))
  }

  @Test
  fun theVideoBadgeShowsTheLengthOnlyWhenTheListHasOne() {
    val video = PostDto(id = "1", md5 = "m", rating = "s", status = "active", fileType = "video/mp4")

    assertEquals("02:07", videoBadgeDuration(video.copy(videoDuration = 127.658667)))
    assertNull(videoBadgeDuration(video))
    assertNull(videoBadgeDuration(video.copy(videoDuration = 0.0)))
    assertTrue(isVideo(video))
    assertFalse(isVideo(video.copy(fileType = "image/gif")))
  }

  @Test
  fun theRightHalfSeeksForwardAndTheLeftHalfBack() {
    assertEquals(SeekDirection.Back, SeekDirection.at(0f, 1280f))
    assertEquals(SeekDirection.Back, SeekDirection.at(639f, 1280f))
    assertEquals(SeekDirection.Forward, SeekDirection.at(640f, 1280f))
    assertEquals(SeekDirection.Forward, SeekDirection.at(1279f, 1280f))
  }

  @Test
  fun eachDirectionLabelsItsFiveSeconds() {
    assertEquals("+5 s", SeekDirection.Forward.label)
    assertEquals("−5 s", SeekDirection.Back.label)
  }
}
