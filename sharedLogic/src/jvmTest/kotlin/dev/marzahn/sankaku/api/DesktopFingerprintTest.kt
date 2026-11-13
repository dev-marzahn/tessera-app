package dev.marzahn.sankaku.api

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFingerprintTest {
  @Test
  fun desktopClaimsMacChrome() {
    val current = BrowserFingerprint.Current
    val mac = BrowserFingerprint(BrowserPlatform.Mac)
    assertEquals(mac.userAgent, current.userAgent)
    assertEquals(mac.clientHints, current.clientHints)
  }
}
