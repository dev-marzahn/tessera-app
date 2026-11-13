package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.runtime.CompositionLocalProvider
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A video waiting for data (#524): it shows a spinner, and the play button follows play-when-ready, so while it buffers
 * the button reads Pause and cancels the autoplay; Space does the same. The tracks have names for screen readers.
 */
@OptIn(ExperimentalTestApi::class)
class VideoBufferingTest {
  private val mp4 = PostDto(
    id = "1", md5 = "abc", rating = "g", status = "active", fileType = "video/mp4",
    sampleUrl = "https://v.example.test/data/sample/ab/sample-abc.mp4?token=t",
  )

  /** Wants to play from the start but never gets the data: buffering, not playing. */
  private class StalledPlayback : VideoPlayback {
    override var playWhenReady by mutableStateOf(false)
    var waiting by mutableStateOf(false)
    override val buffering get() = playWhenReady && waiting
    override val isPlaying = false
    override val hasMedia = true
    override val failed = false
    override val firstFrameShown = false
    override var duration by mutableStateOf(0.0)
    override var position by mutableStateOf(0f)
    override var volume = 1f
    override var dragging = false

    override fun open(url: String, cacheKey: String, play: Boolean) {
      playWhenReady = play
      waiting = true
    }

    override fun play() {
      playWhenReady = true
    }

    override fun pause() {
      playWhenReady = false
    }

    override fun seekTo(fraction: Float) {
      position = fraction
    }
  }

  @Test
  fun aBufferingVideoShowsASpinnerAndItsSeekRowsPauseCancelsTheAutoplay() = runComposeUiTest {
    val player = StalledPlayback()
    val preferences = PlayerPreferences(InMemoryTextFile())
    val keys = VideoKeys(preferences)
    setContent {
      CompositionLocalProvider(LocalVideoKeys provides keys) {
        // Not shown: the surface is the platform player's, which a fake isn't.
        PostVideo(mp4, player = player, preferences = preferences, shown = false)
        VideoSeekRow(keys, preferences = preferences)
      }
    }

    onNodeWithContentDescription(VideoBufferingDescription).assertExists()
    onNodeWithContentDescription("Pause").performClick()
    waitForIdle()

    assertFalse(player.playWhenReady, "Pause cancels the autoplay while it buffers")
    onNodeWithContentDescription("Play").assertExists()
    onNodeWithContentDescription(VideoBufferingDescription).assertDoesNotExist()
  }

  @Test
  fun spaceFollowsPlayWhenReadyNotWhetherFramesAreMoving() {
    val player = StalledPlayback().apply { open("u", "k", play = true) }
    val keys = VideoKeys(PlayerPreferences(InMemoryTextFile())).also { it.register(player) }

    keys.onKey(Key.Spacebar)
    assertFalse(player.playWhenReady)
    keys.onKey(Key.Spacebar)
    assertTrue(player.playWhenReady)
  }

  @Test
  fun theSeekTrackIsNamedAndSaysWhereTheVideoIs() = runComposeUiTest {
    val player = StalledPlayback().apply {
      duration = 130.0
      position = 0.5f
    }
    val preferences = PlayerPreferences(InMemoryTextFile())
    val keys = VideoKeys(preferences).also { it.register(player) }
    setContent { VideoSeekRow(keys, preferences = preferences) }

    onNodeWithContentDescription(SeekTrackDescription)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "01:05 of 02:10"))
  }

  @Test
  fun theDesktopVolumeSliderIsNamed() = runComposeUiTest {
    val player = StalledPlayback()
    val preferences = PlayerPreferences(InMemoryTextFile())
    // Paused, so the desktop controls show.
    setContent { PostVideo(mp4, player = player, preferences = preferences, shown = false) }
    player.pause()
    waitForIdle()

    onNodeWithContentDescription(VolumeSliderDescription)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
    onNodeWithContentDescription(SeekTrackDescription).assertExists()
  }
}
