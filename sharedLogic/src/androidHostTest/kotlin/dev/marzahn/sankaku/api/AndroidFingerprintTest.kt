package dev.marzahn.sankaku.api

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidFingerprintTest {
  @Test
  fun androidClaimsAndroidChrome() {
    val current = BrowserFingerprint.Current
    val android = BrowserFingerprint(BrowserPlatform.Android)
    assertEquals(android.userAgent, current.userAgent)
    assertEquals(android.clientHints, current.clientHints)
  }
}
