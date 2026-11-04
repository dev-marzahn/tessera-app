package dev.marzahn.sankaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Desktop's half of Copy log's header (#207): the version and, on a Mac, the platform, OS and model. */
class PlatformEnvironmentTest {
  private val onMac = System.getProperty("os.name").startsWith("Mac")

  @Test
  fun namesTheVersionPlatformOsAndDevice() {
    val environment = platformEnvironment()

    assertEquals(APP_VERSION, environment.version)
    assertTrue(environment.os.startsWith(environment.platform), environment.os)
    assertTrue(environment.device.isNotBlank())
    if (onMac) {
      assertEquals("macOS", environment.platform)
      // `Mac15,12 (Apple M3)`, or a model alone: read through sysctl, never the host name or serial.
      assertTrue(Regex("""[A-Za-z]+\d+,\d+( \(.+\))?""").matches(environment.device), environment.device)
    }
  }
}
