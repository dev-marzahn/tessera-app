package dev.marzahn.sankaku.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataMigrationTest {
  private val root = Files.createTempDirectory("app-data-migration").toFile()
  private val old = File(root, "Sankaku")
  private val new = File(root, "Tessera")

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun File.put(name: String, text: String) {
    mkdirs()
    File(this, name).writeText(text)
  }

  private fun File.text(name: String) = File(this, name).readText()

  @Test
  fun movesSavedTabsKnownTagsAndPlayerSettingsAndRemovesTheOldFolder() {
    old.put("tabs.json", """{"activeIndex":0,"tabs":[]}""")
    old.put("tags.json", "[]")
    old.put("player.json", """{"volume":0.5}""")

    AppDataMigration.run(old, new)

    assertEquals("""{"activeIndex":0,"tabs":[]}""", new.text("tabs.json"))
    assertEquals("[]", new.text("tags.json"))
    assertEquals("""{"volume":0.5}""", new.text("player.json"))
    assertFalse(old.exists())
  }

  @Test
  fun movesFilesItDoesNotKnowAbout() {
    old.put("settings.json", "{}")

    AppDataMigration.run(old, new)

    assertEquals("{}", new.text("settings.json"))
  }

  @Test
  fun keepsAFileTheNewFolderAlreadyHasAndTheOldFolderWithIt() {
    old.put("tabs.json", "old")
    old.put("tags.json", "old tags")
    new.put("tabs.json", "new")

    AppDataMigration.run(old, new)

    assertEquals("new", new.text("tabs.json"))
    assertEquals("old tags", new.text("tags.json"))
    assertEquals("old", old.text("tabs.json"))
    assertTrue(old.exists())
  }

  @Test
  fun doesNothingWithoutAnOldFolder() {
    new.put("tabs.json", "new")

    AppDataMigration.run(old, new)

    assertEquals("new", new.text("tabs.json"))
    assertEquals(listOf("tabs.json"), new.list()!!.toList())
    assertFalse(old.exists())
  }

  @Test
  fun leavesEverythingWhileAPreRenameBuildStillRuns() {
    old.put("tabs.json", "tabs")
    RandomAccessFile(File(old, "instance.lock"), "rw").use { file ->
      file.channel.lock().use {
        AppDataMigration.run(old, new)
      }
    }

    assertEquals("tabs", old.text("tabs.json"))
    assertTrue(new.list().isNullOrEmpty())
  }

  @Test
  fun leavesTheOldLockBehindAndRemovesItOnceNothingHoldsIt() {
    old.put("tabs.json", "tabs")
    old.put("instance.lock", "12345\n")

    AppDataMigration.run(old, new)

    assertEquals("tabs", new.text("tabs.json"))
    assertFalse(File(new, "instance.lock").exists())
    assertFalse(old.exists())
  }

  @Test
  fun aSecondLaunchChangesNothing() {
    old.put("tabs.json", "tabs")
    AppDataMigration.run(old, new)
    new.put("tabs.json", "changed since")

    AppDataMigration.run(old, new)

    assertEquals("changed since", new.text("tabs.json"))
  }
}
