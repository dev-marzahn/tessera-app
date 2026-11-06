package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstLaunchTest {
  @Test
  fun aFreshInstallHasNotAccepted() {
    assertFalse(FirstLaunch(InMemoryTextFile()).accepted.value)
  }

  @Test
  fun continueIsKeptAcrossARestart() {
    val file = InMemoryTextFile()
    val first = FirstLaunch(file)
    first.accept()
    assertTrue(first.accepted.value)

    assertTrue(FirstLaunch(file).accepted.value)
  }

  @Test
  fun anUnreadableFileAsksAgain() {
    assertFalse(FirstLaunch(InMemoryTextFile("not json")).accepted.value)
  }

  @Test
  fun aFailedWriteStillContinuesThisRun() {
    val broken = object : TextFile {
      override fun read(): String? = null
      override fun write(text: String) = throw IllegalStateException("disk full")
      override fun delete() = Unit
    }
    val launch = FirstLaunch(broken)
    launch.accept()
    assertTrue(launch.accepted.value)
  }
}
