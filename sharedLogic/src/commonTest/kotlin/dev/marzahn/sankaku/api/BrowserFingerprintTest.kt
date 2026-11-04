package dev.marzahn.sankaku.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserFingerprintTest {
  @Test
  fun androidIsTheReducedAndroidChrome() {
    val android = BrowserFingerprint(BrowserPlatform.Android, 157)
    assertEquals(
      "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36",
      android.userAgent,
    )
    assertEquals(
      mapOf(
        "sec-ch-ua" to "\"Not-A.Brand\";v=\"99\", \"Google Chrome\";v=\"157\", \"Chromium\";v=\"157\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
      ),
      android.clientHints,
    )
  }

  @Test
  fun macIsTheReducedMacChrome() {
    val mac = BrowserFingerprint(BrowserPlatform.Mac, 157)
    assertEquals(
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36",
      mac.userAgent,
    )
    assertEquals(
      mapOf(
        "sec-ch-ua" to "\"Not-A.Brand\";v=\"99\", \"Google Chrome\";v=\"157\", \"Chromium\";v=\"157\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"macOS\"",
      ),
      mac.clientHints,
    )
  }

  /** What these Chrome versions sent, so a bump of [CHROME_MAJOR_VERSION] needs no hand-written brand list. */
  @Test
  fun brandListFollowsChromesSeededOrderAndGrease() {
    assertEquals("\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"", BrowserFingerprint.brandList(120))
    assertEquals("\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"", BrowserFingerprint.brandList(131))
    assertEquals("\"Google Chrome\";v=\"135\", \"Not-A.Brand\";v=\"8\", \"Chromium\";v=\"135\"", BrowserFingerprint.brandList(135))
    assertEquals("\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"", BrowserFingerprint.brandList(138))
  }

  @Test
  fun bothPlatformsShareOneChromeVersion() {
    for (platform in BrowserPlatform.entries) {
      val fingerprint = BrowserFingerprint(platform, CHROME_MAJOR_VERSION)
      assertTrue("Chrome/$CHROME_MAJOR_VERSION.0.0.0 " in fingerprint.userAgent)
      assertTrue("\"Google Chrome\";v=\"$CHROME_MAJOR_VERSION\"" in fingerprint.clientHints.getValue("sec-ch-ua"))
    }
  }
}
