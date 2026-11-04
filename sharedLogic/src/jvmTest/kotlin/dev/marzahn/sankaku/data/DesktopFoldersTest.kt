package dev.marzahn.sankaku.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFoldersTest {
  private val home = File("/Users/someone")
  private val folders = DesktopFolders(home)

  @Test
  fun appDataIsTessera() {
    assertEquals(File(home, "Library/Application Support/Tessera"), folders.appData)
  }

  @Test
  fun cacheIsTessera() {
    assertEquals(File(home, "Library/Caches/Tessera"), folders.cache)
  }

  @Test
  fun savedFilesGoToDownloadsTessera() {
    assertEquals(File(home, "Downloads/Tessera"), folders.downloads)
  }

  @Test
  fun theOldAppDataIsSankaku() {
    assertEquals(File(home, "Library/Application Support/Sankaku"), folders.oldAppData)
  }
}
