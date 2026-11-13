package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.marzahn.sankaku.data.InMemoryTextFile
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop's A/D seeks, against a fake of what the macOS player did in the running app: a seek lands quickly but on the
 * keyframe nearest the target (every 3 s there), and until then the library reports the old position; paused, it
 * reports nothing new until it plays. The track keeps to the target while A/D are pressed or held, each press seeks on
 * from the last target, and the track never steps back to the keyframe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSeekTest {
  /**
   * A 120 s video at 30 s that takes its seeks but goes on reporting the old position until it lands ([landAt]). A
   * write to `sliderPos` doesn't stick: the library's seek puts the old position back straight after.
   */
  private class LaggingState(playing: Boolean) : VideoPlayerState by PreviewableVideoPlayerState(duration = 120.0) {
    var reported by mutableStateOf(250f)
    override var sliderPos: Float
      get() = reported
      set(_) {}
    override var isPlaying by mutableStateOf(playing)
    var asked: Float? = null

    override fun seekTo(value: Float) {
      asked = value
    }

    /** Reports [seconds] (the keyframe the seek landed on, or where the video has played to). */
    fun landAt(seconds: Double) {
      reported = (seconds / 120.0 * 1000).toFloat()
    }

    val askedSeconds get() = asked!! / 1000.0 * 120.0
  }

  /** [state]'s playback, settling its seeks in the background as the app's composition does. */
  private fun TestScope.playbackOf(state: VideoPlayerState) =
    LibraryVideoPlayback(state).also { backgroundScope.launch { it.settleSeeks() } }.also { settle() }

  /** Hands the state written so far to the snapshot flows, as a frame does, and runs what they start. */
  private fun TestScope.settle(ms: Long = 0) {
    Snapshot.sendApplyNotifications()
    runCurrent()
    if (ms > 0) {
      advanceTimeBy(ms)
      Snapshot.sendApplyNotifications()
      runCurrent()
    }
  }

  private val VideoPlayback.seconds get() = position * 120.0

  @Test
  fun heldDSeeksOnFromEachTargetThoughEachSeekLandsOnAKeyframe() = runTest {
    val state = LaggingState(playing = true)
    val playback = playbackOf(state)
    val keys = VideoKeys(PlayerPreferences(InMemoryTextFile())).also { it.register(playback) }

    // A held D: a press about every 50 ms, each seek landing on the keyframe before its target.
    keys.seek(forward = true)
    settle()
    state.landAt(33.0)
    settle(ms = 50)
    assertEquals(35.0, playback.seconds, 0.01, "the track stepped back to the keyframe")
    keys.seek(forward = true)
    settle()
    state.landAt(39.0)
    settle(ms = 50)
    keys.seek(forward = true)
    settle()

    assertEquals(45.0, state.askedSeconds, 0.01, "a press seeked on from the keyframe, not the target")
    assertEquals(45.0, playback.seconds, 0.01)
  }

  @Test
  fun aSeekLandedShortHoldsTheTrackUntilThePlayerCatchesUp() = runTest {
    val state = LaggingState(playing = true)
    val playback = playbackOf(state)

    playback.seekTo((40.0 / 120).toFloat())
    settle()
    state.landAt(39.0)
    settle(ms = LibraryVideoPlayback.SEEK_HOLD_MS * 2)
    assertEquals(40.0, playback.seconds, 0.01, "the track stepped back to where the seek landed")

    // Played on past the target, the track follows the player again.
    state.landAt(40.2)
    settle()
    assertEquals(40.2, playback.seconds, 0.01)
  }

  @Test
  fun aPausedVideoKeepsToItsSeekAsThePlayerReportsNothingNew() = runTest {
    val state = LaggingState(playing = false)
    val playback = playbackOf(state)

    playback.seekTo(0.75f)
    settle(ms = LibraryVideoPlayback.SEEK_SETTLE_MS * 3)
    assertEquals(0.75f, playback.position)

    // Played again, the player reads the position, which is where the seek went, and plays on.
    state.isPlaying = true
    state.landAt(90.0)
    settle(ms = LibraryVideoPlayback.SEEK_HOLD_MS)
    state.landAt(96.0)
    settle()
    assertEquals(96.0, playback.seconds, 0.01)
  }

  @Test
  fun aPausedSeekNearWhereTheVideoWasKeepsToItsTarget() = runTest {
    // Paused at 1.5 s, A clamps the target to the start; the stale 1.5 s is near it but no landing.
    val state = LaggingState(playing = false).apply { landAt(1.5) }
    val playback = playbackOf(state)

    playback.seekTo(0f)
    settle(ms = LibraryVideoPlayback.SEEK_HOLD_MS * 2)
    assertEquals(0f, playback.position)
    settle(ms = LibraryVideoPlayback.SEEK_SETTLE_MS * 3)
    assertEquals(0f, playback.position)
  }

  @Test
  fun aPlayingVideoFollowsThePlayerAgainWhenASeekNeverLands() = runTest {
    val state = LaggingState(playing = true)
    val playback = playbackOf(state)

    playback.seekTo(0.75f)
    settle(ms = LibraryVideoPlayback.SEEK_SETTLE_MS / 2)
    assertEquals(0.75f, playback.position)
    settle(ms = LibraryVideoPlayback.SEEK_SETTLE_MS)

    assertEquals(0.25f, playback.position)
  }
}
