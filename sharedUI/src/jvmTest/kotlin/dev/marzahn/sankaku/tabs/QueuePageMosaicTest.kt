package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageResult
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #232: a source restored at launch, never shown, shows its saved mosaic and count on the queue page. Its thumbnails
 * are cache-only requests, and the R15, R18 and unrated ones are blurred until the item is opened. The pictures are
 * synthetic fixtures in the memory cache, never a real post; with `EVIDENCE_DIR` set, the frame is saved there for the
 * PR, since the emulator's `safeOnly` run shows G posts only.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class QueuePageMosaicTest {
  private val requests = mutableListOf<Boolean>()

  @AfterTest
  fun tearDown() = SingletonImageLoader.reset()

  private fun restored(): TabsController {
    val saved = """{"version":2,"session":"s1","activeIndex":0,"tabs":[""" +
      """{"id":"b","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":[]}],"kind":"source","touched":1000,"addedIn":"s1"},""" +
      """{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}],"kind":"source","touched":1000,"addedIn":"s1",""" +
      """"mosaic":[{"md5":"m0","rating":"s"},{"md5":"m1","rating":"q"},{"md5":"m2","rating":"e"},{"md5":"m3"}],"posts":60}""" +
      """]}"""
    // The clock is at the file's `touched`, so nothing fades at launch.
    return TabsController(FileTabStateStore(InMemoryTextFile(saved)), clock = object : Clock {
      override fun now() = Instant.fromEpochSeconds(1000)
    })
  }

  /** A 256 px picture of coloured stripes and a square: sharp edges, so a blur is plain to see. */
  private fun fixture(first: Int, second: Int): Bitmap {
    val bitmap = Bitmap().apply { allocN32Pixels(256, 256) }
    Canvas(bitmap).apply {
      for (i in 0 until 8) drawRect(Rect.makeXYWH(i * 32f, 0f, 32f, 256f), Paint().apply { color = if (i % 2 == 0) first else second })
      drawRect(Rect.makeXYWH(80f, 80f, 96f, 96f), Paint().apply { color = 0xFFFFFFFF.toInt() })
    }
    return bitmap
  }

  private fun ComposeUiTest.show(tabs: TabsController, used: Boolean = false) {
    // The four previews are in the memory cache; each request records whether it may reach the network.
    val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
      .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
      .components {
        add(object : Interceptor {
          override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            synchronized(requests) { requests += chain.request.networkCachePolicy.readEnabled }
            return chain.proceed()
          }
        })
      }
      .build()
    listOf(0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFEF6C00).forEachIndexed { i, colour ->
      loader.memoryCache!![MemoryCache.Key("m$i-Preview")] = MemoryCache.Value(fixture(colour.toInt(), 0xFFFFC107.toInt()).asImage())
    }
    SingletonImageLoader.setUnsafe(loader)
    val seen = SeenStore(InMemoryTextFile())
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
        SankakuTheme {
          QueuePage(
            onDismiss = {},
            controller = tabs,
            posts = TabPosts.None,
            privacy = PrivacySettings(InMemoryTextFile()),
            seen = seen,
            clearViewed = ClearViewed(tabs, seen, Messages()),
            interacted = { used },
            onInteract = {},
          )
        }
      }
    }
    waitForIdle()
  }

  @Test
  fun aRestoredSourceShowsItsSavedMosaicCacheOnlyWithAdultTilesBlurred() = runDesktopComposeUiTest(width = 1080, height = 1300) {
    show(restored())

    onNodeWithText("Grid · 60 posts").assertExists()
    // m1 (R15), m2 (R18) and m3 (no rating) blur; m0 (G) doesn't.
    assertEquals(3, onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    assertEquals(4, synchronized(requests) { requests.size })
    assertTrue(synchronized(requests) { requests.none { it } }, "every thumbnail request is cache-only")
    System.getenv("EVIDENCE_DIR")?.let { dir ->
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "queue-mosaic-blur-fixture.png"))
    }
  }

  @Test
  fun noMosaicIsBlurredOnceTheQueueWasUsed() = runDesktopComposeUiTest(width = 1080, height = 1300) {
    show(restored(), used = true)

    assertTrue(onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
  }
}
