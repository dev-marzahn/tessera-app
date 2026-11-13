package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The player's volume and mute (#524): a fresh install plays at full volume, unmuted, as the system volume is what sets
 * how loud (the owner, 2026-11-12); Start videos muted mutes every run's first video.
 */
class PlayerPreferencesTest {
  @Test
  fun aFreshInstallPlaysUnmutedAtFullVolumeWithStartMutedOff() {
    val preferences = PlayerPreferences(InMemoryTextFile())

    assertFalse(preferences.muted)
    assertEquals(1f, preferences.playedVolume)
    assertFalse(preferences.startMuted)
  }

  @Test
  fun startMutedMutesEachRunWhateverTheLastRunLeft() {
    val file = InMemoryTextFile()
    PlayerPreferences(file).apply {
      changeStartMuted(true)
      // Unmuted during the run…
      toggleMute()
      toggleMute()
      changeVolume(0.6f)
      assertFalse(muted)
    }

    // …the next run starts muted again, at the volume it was left at.
    val next = PlayerPreferences(file)
    assertTrue(next.startMuted)
    assertTrue(next.muted)
    assertEquals(0f, next.playedVolume)
    next.toggleMute()
    assertEquals(0.6f, next.playedVolume)
  }

  @Test
  fun withStartMutedOffARunKeepsTheLastRunsMute() {
    val file = InMemoryTextFile()
    PlayerPreferences(file).toggleMute()
    assertTrue(PlayerPreferences(file).muted)

    PlayerPreferences(file).toggleMute()
    assertFalse(PlayerPreferences(file).muted)
  }

  @Test
  fun aStartsMuteIsntSavedAsTheUsersOwn() {
    val file = InMemoryTextFile()
    PlayerPreferences(file).changeStartMuted(true)
    // Muted by the setting, never by the user; then the setting goes off.
    PlayerPreferences(file).apply {
      assertTrue(muted)
      changeStartMuted(false)
    }

    assertFalse(PlayerPreferences(file).muted, "the next run plays as the user left it: unmuted")
  }

  @Test
  fun aMuteTheUserChoseDuringAStartMutedRunIsKept() {
    val file = InMemoryTextFile()
    PlayerPreferences(file).changeStartMuted(true)
    PlayerPreferences(file).apply {
      // Unmuted, then muted again by hand: the user's own mute now.
      toggleMute()
      toggleMute()
      changeStartMuted(false)
    }

    assertTrue(PlayerPreferences(file).muted)
  }

  @Test
  fun turningStartMutedOnDoesntMuteTheVideoPlaying() {
    val preferences = PlayerPreferences(InMemoryTextFile())
    preferences.changeStartMuted(true)

    assertFalse(preferences.muted)
  }
}
