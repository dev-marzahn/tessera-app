package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class SignedUrlTest {
  private fun url(expiresAt: Long) = "https://s.sankakucomplex.com/data/01/23/x.png?e=$expiresAt&m=sig"

  @Test
  fun expiryIsReadFromE() {
    assertEquals(NOW + 1.hours, signedUrlExpiresAt(url((NOW + 1.hours).epochSeconds)))
  }

  @Test
  fun aUrlValidForAnHourIsUsable() {
    assertTrue(isSignedUrlUsable(url((NOW + 1.hours).epochSeconds), NOW))
  }

  @Test
  fun expiredAndNearlyExpiredUrlsAreNot() {
    assertFalse(isSignedUrlUsable(url((NOW - 1.seconds).epochSeconds), NOW))
    assertFalse(isSignedUrlUsable(url((NOW + 30.seconds).epochSeconds), NOW))
  }

  @Test
  fun unsignedUrlsAreNotUsable() {
    assertNull(signedUrlExpiresAt("https://s.sankakucomplex.com/data/01/23/x.png"))
    assertFalse(isSignedUrlUsable("https://s.sankakucomplex.com/data/01/23/x.png", NOW))
    assertFalse(isSignedUrlUsable("not a url ::", NOW))
  }
}
