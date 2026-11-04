package dev.marzahn.sankaku

import android.content.ActivityNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals

/** No app for the link comes back as a result, not a crash (#358). */
class OpenUrlAndroidTest {
  @Test
  fun noAppForTheLinkFails() {
    assertEquals(OpenUrlResult.Failed, launchUrl("https://example.com/") { throw ActivityNotFoundException() })
  }

  @Test
  fun aLinkNoAppMayOpenFails() {
    assertEquals(OpenUrlResult.Failed, launchUrl("https://example.com/") { throw SecurityException() })
  }

  @Test
  fun aLaunchedLinkOpens() {
    var started = 0
    assertEquals(OpenUrlResult.Opened, launchUrl("https://example.com/") { started++ })
    assertEquals(1, started)
  }
}
