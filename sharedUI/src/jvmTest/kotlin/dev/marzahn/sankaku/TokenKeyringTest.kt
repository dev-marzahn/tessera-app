package dev.marzahn.sankaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenKeyringTest {
  @Test
  fun aRunWithItsOwnDataFolderNeverProbesTheKeychain() {
    var probes = 0
    assertNull(tokenKeyring(ownDataDir = true) { probes++; error("touched the keychain") })
    assertEquals(0, probes)
  }

  @Test
  fun theAppsOwnRunProbesItOnceAndFallsBackToMemoryWithoutOne() {
    var probes = 0
    assertNull(tokenKeyring(ownDataDir = false) { probes++; error("no backend") })
    assertEquals(1, probes)
  }
}
