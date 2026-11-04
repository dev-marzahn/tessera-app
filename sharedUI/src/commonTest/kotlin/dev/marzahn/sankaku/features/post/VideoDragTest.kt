package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A drag on the track scrubs live: keyframe seeks while it moves, one exact seek where it lets go (#189). */
class VideoDragTest {
  private class FakePlayback : VideoPlayback {
    override val hasMedia = true
    override var isPlaying = true
    override val failed = false
    override val firstFrameShown = true
    override val duration = 120.0
    override var position = 0.25f
    override var volume = 1f
    override var dragging = false
    val seeks = mutableListOf<Pair<Float, Boolean>>()

    override fun open(url: String, cacheKey: String, play: Boolean) {}

    override fun play() {
      isPlaying = true
    }

    override fun pause() {
      isPlaying = false
    }

    override fun seekTo(fraction: Float) {
      seeks += fraction to dragging
      position = fraction
    }
  }

  private val player = FakePlayback()
  private val keys = VideoKeys(PlayerPreferences(InMemoryTextFile())).also { it.register(player) }

  @Test
  fun aDragSeeksToKeyframesThenOnceExactlyAndPlaysOn() {
    keys.drag(0.5f)
    assertFalse(player.isPlaying, "the video holds while dragged")
    keys.drag(0.6f)
    assertEquals(0.6f, keys.draggedTo)

    keys.release()

    assertEquals(listOf(0.5f to true, 0.6f to true, 0.6f to false), player.seeks)
    assertFalse(player.dragging)
    assertNull(keys.draggedTo)
    assertTrue(player.isPlaying, "it plays on, as it was playing")
  }

  @Test
  fun aPausedVideoStaysPausedAfterADrag() {
    player.pause()

    keys.drag(0.9f)
    keys.release()

    assertFalse(player.isPlaying)
  }

  @Test
  fun aReleaseWithoutADragDoesNothing() {
    keys.release()

    assertTrue(player.seeks.isEmpty())
  }

  @Test
  fun theCacheKeyIgnoresTheSignature() {
    val post = dev.marzahn.sankaku.api.dto.PostDto(id = "1", md5 = "abc", rating = "s", status = "active")
    val a = videoCacheKey(post, videoSource("https://v.example.test/s/ab/abc.mp4?token=1"))
    val b = videoCacheKey(post, videoSource("https://v.example.test/s/ab/abc.mp4?token=2"))

    assertEquals("abc/abc.mp4", a)
    assertEquals(a, b)
  }
}
