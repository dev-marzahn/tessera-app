package dev.marzahn.sankaku.features.post

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The player reopens only for another file, not for the same file signed again (#180). */
class VideoSourceTest {
  private val signed = "https://v.example.test/data/sample/aa/bb/sample-aabb.mp4?e=1&expires=100&token=t1"

  @Test
  fun theSameFileSignedAgainIsTheSameSource() {
    val resigned = "https://v.example.test/data/sample/aa/bb/sample-aabb.mp4?e=1&expires=200&token=t2"

    assertEquals(videoSource(signed), videoSource(resigned))
  }

  @Test
  fun anotherFileIsAnotherSource() {
    assertNotEquals(videoSource(signed), videoSource("https://v.example.test/data/sample/cc/dd/sample-ccdd.mp4?token=t1"))
    assertNotEquals(videoSource(signed), videoSource("https://v.example.test/data/aa/bb/aabb.mp4?token=t1"))
  }

  @Test
  fun aUrlWithoutAQueryIsItsOwnSource() {
    assertEquals("https://v.example.test/a.mp4", videoSource("https://v.example.test/a.mp4"))
  }
}
