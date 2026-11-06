package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacySettingsTest {
  @Test
  fun hidesInRecentsAndShowsAllRatingsByDefault() {
    val settings = PrivacySettings(InMemoryTextFile())

    assertTrue(settings.hideInRecents.value)
    assertFalse(settings.gOnly.value)
    assertFalse(settings.gOnlySwitch.value)
  }

  @Test
  fun bothSwitchesSurviveARestart() {
    val file = InMemoryTextFile()
    PrivacySettings(file).apply {
      setGOnly(true)
      setHideInRecents(false)
    }

    val restarted = PrivacySettings(file)
    assertTrue(restarted.gOnly.value)
    assertFalse(restarted.hideInRecents.value)
  }

  @Test
  fun theDebugLaunchFlagLocksTheSameWayWithoutBeingSaved() {
    val file = InMemoryTextFile()
    val forced = PrivacySettings(file, gOnlyForced = true)

    assertTrue(forced.gOnly.value)
    assertFalse(forced.gOnlySwitch.value)
    // Turning the switch off cannot unlock a forced run, and the flag never reaches the file.
    forced.setGOnly(false)
    assertTrue(forced.gOnly.value)
    assertFalse(PrivacySettings(file).gOnly.value)
  }

  @Test
  fun startsOnTheQueueWithoutForcingGOnlyByDefault() {
    val settings = PrivacySettings(InMemoryTextFile())

    assertEquals(StartOn.Queue, settings.startOn.value)
    assertFalse(settings.alwaysStartGOnly.value)
  }

  @Test
  fun startOnSurvivesARestart() {
    val file = InMemoryTextFile()
    PrivacySettings(file).setStartOn(StartOn.Browse)

    assertEquals(StartOn.Browse, PrivacySettings(file).startOn.value)
  }

  @Test
  fun alwaysStartInGOnlySwitchesItOnAtEveryLaunchButNotForTheRestOfTheRun() {
    val file = InMemoryTextFile()
    PrivacySettings(file).setAlwaysStartGOnly(true)

    val launched = PrivacySettings(file)
    assertTrue(launched.gOnly.value)
    assertTrue(launched.gOnlySwitch.value)
    // Switched off for this run: it stays off until the next launch, which switches it on again.
    launched.setGOnly(false)
    assertFalse(launched.gOnly.value)
    assertTrue(PrivacySettings(file).gOnly.value)
  }

  @Test
  fun aFileFromBeforeTheStartSettingsKeepsItsSwitches() {
    val settings = PrivacySettings(InMemoryTextFile("""{"hideInRecents":false,"gOnly":true}"""))

    assertFalse(settings.hideInRecents.value)
    assertTrue(settings.gOnly.value)
    assertEquals(StartOn.Queue, settings.startOn.value)
  }

  @Test
  fun anUnreadableFileFallsBackToTheDefaults() {
    val settings = PrivacySettings(InMemoryTextFile("not json"))

    assertTrue(settings.hideInRecents.value)
    assertFalse(settings.gOnly.value)
    assertEquals(false, settings.gOnlySwitch.value)
  }
}
