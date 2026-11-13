package dev.marzahn.sankaku.features.auth

import dev.marzahn.sankaku.auth.TokenStorageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermsNoticeTest {
  private val risk =
    "Unofficial client. Sankaku's terms prohibit 'unauthorized User software'; your account or IP may be restricted."

  @Test
  fun keychainStatesTheTermsRiskThenTheSession() {
    assertEquals(
      "$risk The app keeps your session in the system keychain and never stores your password.",
      termsNotice(TokenStorageStatus.Keychain),
    )
  }

  @Test
  fun everyStorageStartsWithTheTermsRisk() {
    for (storage in TokenStorageStatus.entries) {
      val text = termsNotice(storage)
      assertTrue(text.startsWith(risk), "$storage")
      assertFalse(text.contains("By signing in you accept"), "$storage")
    }
  }

  @Test
  fun encryptedDoesNotClaimTheKeychain() {
    val text = termsNotice(TokenStorageStatus.Encrypted)
    assertFalse(text.contains("keychain"))
    assertTrue(text.contains("encrypted on this device"))
    assertTrue(text.contains("never stores your password"))
  }

  @Test
  fun inMemoryDoesNotClaimTheKeychain() {
    val text = termsNotice(TokenStorageStatus.InMemory)
    assertFalse(text.contains("keychain"))
    assertTrue(text.contains("only while the app is open"))
    assertTrue(text.contains("never stores your password"))
  }
}
