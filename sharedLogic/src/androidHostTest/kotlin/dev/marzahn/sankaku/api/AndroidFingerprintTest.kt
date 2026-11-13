package dev.marzahn.sankaku.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AndroidFingerprintTest {
  /** Sankaku leaves pages out of books for an Android User-Agent (#475), so the phone claims its Chrome's desktop site. */
  @Test
  fun androidClaimsChromesDesktopSite() {
    val current = BrowserFingerprint.Current
    val desktopSite = BrowserFingerprint(BrowserPlatform.AndroidDesktopSite)
    assertEquals(desktopSite.userAgent, current.userAgent)
    assertEquals(desktopSite.clientHints, current.clientHints)
    assertFalse("Android" in current.userAgent)
  }
}
