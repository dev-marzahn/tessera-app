package dev.marzahn.sankaku.features.settings

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.TabsNavigator
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Storage (#205): the cache's size in use, Clear cache and Clear saved tabs, the narrower wipes. */
class StorageTest {
  private val dir = Files.createTempDirectory("storage-test")

  @AfterTest
  fun tearDown() {
    dir.toFile().deleteRecursively()
  }

  private fun loader() = ImageLoader.Builder(PlatformContext.INSTANCE)
    .diskCache { DiskCache.Builder().directory(dir.toOkioPath()).fileSystem(FileSystem.SYSTEM).build() }
    .build()

  private fun DiskCache.put(key: String, bytes: Int) {
    val editor = openEditor(key)!!
    fileSystem.write(editor.data) { write(ByteArray(bytes)) }
    editor.commit()
  }

  @Test
  fun theSizeIsTheImagesOnDiskAndTheVideosAndClearCacheEmptiesBoth() = runBlocking {
    val images = loader()
    images.diskCache!!.put("a", 300_000)
    images.diskCache!!.put("b", 200_000)
    var videos = 1_000_000L
    val cache = ClearCache(images, clearVideos = { videos = 0 }, videoBytes = { videos })

    assertEquals(1_500_000L, cache.bytesInUse())

    cache.run()

    assertEquals(0L, cache.bytesInUse())
    assertNull(images.diskCache!!.openSnapshot("a"))
  }

  @Test
  fun clearSavedTabsEmptiesTheQueueAndItsFilesButNotTheSeenPostsOrTheCache() = runBlocking {
    val tabsFile = InMemoryTextFile()
    val backup = InMemoryTextFile("a v1 file")
    val broken = InMemoryTextFile("a broken file")
    val store = FileTabStateStore(tabsFile, backup, broken)
    val tabs = TabsController(store)
    tabs.open(Route.Grid(listOf("artist_x")), background = false)
    TabsNavigator(tabs).openInNewTab(Route.Post("p1", "m1", listOf("artist_x")))
    tabs.close(tabs.tabs.value.last().id)
    val seen = SeenStore(InMemoryTextFile())
    seen.record("p1", "m1")
    val images = loader()
    images.diskCache!!.put("a", 1000)

    ClearSavedTabs(tabs, store).run()

    assertEquals(listOf("Browse"), tabs.tabs.value.map { it.title })
    assertTrue(tabs.closed.value.isEmpty())
    assertEquals("{}", tabsFile.read())
    assertNull(backup.read())
    assertNull(broken.read())
    assertEquals(1, seen.size)
    assertTrue(images.diskCache!!.size > 0)
  }

  @Test
  fun theSizeReadsInWholeMegabytesBelowAGigabyte() {
    val mb = 1024L * 1024
    assertEquals("Empty", storageSize(0))
    assertEquals("Under 1 MB", storageSize(500_000))
    assertEquals("1 MB", storageSize(mb))
    assertEquals("312 MB", storageSize(312 * mb + 1000))
    assertEquals("1023 MB", storageSize(1024 * mb - 1))
    assertEquals("1 GB", storageSize(1024 * mb))
    assertEquals("1.1 GB", storageSize(1152 * mb))
  }

  @Test
  fun thePhoneClearsTheQueueAndDesktopItsTabs() {
    assertEquals("Clear the queue", clearSavedTabsLabel(touch = true))
    assertEquals("Clear saved tabs", clearSavedTabsLabel(touch = false))
    val tab = Regex("\\btabs?\\b", RegexOption.IGNORE_CASE)
    for (words in listOf(clearSavedTabsLabel(true), clearSavedTabsConfirmText(true), clearCacheConfirmText(true))) {
      assertFalse(tab.containsMatchIn(words), words)
    }
    assertTrue(clearCacheConfirmText(touch = true).contains("images and videos"))
    assertFalse(clearCacheConfirmText(touch = false).contains("videos"))
  }
}
