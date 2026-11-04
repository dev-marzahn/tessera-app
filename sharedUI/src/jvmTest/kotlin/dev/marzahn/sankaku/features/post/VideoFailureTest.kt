package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop videos that sat at "00:00 / 00:00" (#192): AVPlayer can't open WebM, and a failed open left the controls
 * frozen. WebM now says so up front, and any other failure shows an error with Retry.
 */
@OptIn(ExperimentalTestApi::class)
class VideoFailureTest {
  private val webm = PostDto(
    id = "1", md5 = "abc", rating = "g", status = "active", fileType = "video/webm",
    fileUrl = "https://s.example.test/data/ab/abc.webm?token=t",
  )
  private val mp4 = webm.copy(fileType = "video/mp4", sampleUrl = "https://v.example.test/data/sample/ab/sample-abc.mp4?token=t")

  /**
   * Fails every open until [failNext] is cleared, as the macOS player does with a file it can't read. An open resets
   * the position, as both players do, and one that works gives the video a length.
   */
  private class FailingPlayback : VideoPlayback {
    var failNext = true
    val opens = mutableListOf<String>()
    val seeks = mutableListOf<Float>()
    override var failed by mutableStateOf(false)
    override val hasMedia = false
    override val isPlaying = false
    override val firstFrameShown = false
    override var duration by mutableStateOf(0.0)
    override var position by mutableStateOf(0f)
    override var volume = 1f
    override var dragging = false

    override fun open(url: String, cacheKey: String, play: Boolean) {
      opens += url
      position = 0f
      failed = failNext
      duration = if (failNext) 0.0 else 10.0
    }

    override fun play() {}

    override fun pause() {}

    override fun seekTo(fraction: Float) {
      seeks += fraction
      position = fraction
    }
  }

  @Test
  fun aRetryThatFailsAgainStillResumesWhereItWasOnceANewerUrlWorks() = runComposeUiTest {
    val player = FailingPlayback()
    var post by mutableStateOf(mp4)
    setContent { PostVideo(post, player = player, preferences = PlayerPreferences(InMemoryTextFile())) }
    // It had played half way when it failed.
    player.position = 0.5f

    onNodeWithContentDescription("Retry playing the video").performClick()
    waitForIdle()
    // The Retry failed too; then the details' fetch signs the URL again, and that one works.
    player.failNext = false
    post = mp4.copy(sampleUrl = mp4.sampleUrl!!.replace("token=t", "token=t2"))
    waitForIdle()

    assertEquals(3, player.opens.size)
    assertEquals(listOf(0.5f), player.seeks)
  }

  @Test
  fun macOsNeverOpensWebmAndOtherSystemsTryIt() {
    val webmFile = videoSource(videoUrl(webm)!!)
    val mp4File = videoSource(videoUrl(mp4)!!)

    assertFalse(desktopPlaysVideoFile(webmFile, isMac = true))
    assertTrue(desktopPlaysVideoFile(mp4File, isMac = true))
    assertTrue(desktopPlaysVideoFile(webmFile, isMac = false))
  }

  @Test
  fun aFailureWithANewerUrlWaitingReopensWithoutShowingTheError() = runComposeUiTest {
    val player = FailingPlayback()
    var post by mutableStateOf(mp4)
    setContent { PostVideo(post, player = player, preferences = PlayerPreferences(InMemoryTextFile())) }
    onNodeWithText("Couldn't play the video").assertExists()

    // The details' fetch signs the URL again; this time the open works.
    player.failNext = false
    post = mp4.copy(sampleUrl = mp4.sampleUrl!!.replace("token=t", "token=t2"))
    waitForIdle()

    assertEquals(2, player.opens.size)
    onNodeWithText("Couldn't play the video").assertDoesNotExist()
  }

  @Test
  fun aVideoThatCantPlayShowsAnErrorAndRetryOpensItAgain() = runComposeUiTest {
    val player = FailingPlayback()
    setContent { PostVideo(mp4, player = player, preferences = PlayerPreferences(InMemoryTextFile())) }

    onNodeWithText("Couldn't play the video").assertExists()
    player.failNext = false
    onNodeWithContentDescription("Retry playing the video").performClick()
    waitForIdle()

    assertEquals(2, player.opens.size)
    onNodeWithText("Couldn't play the video").assertDoesNotExist()
  }
}
