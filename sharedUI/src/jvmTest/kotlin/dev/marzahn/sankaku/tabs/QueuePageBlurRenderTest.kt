package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.memory.MemoryCache
import dev.marzahn.sankaku.Route
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * #177: the blur drawn, with synthetic fixture pictures (never a real post): a G post shows its picture, a post with a
 * mocked R15 rating shows it blurred under an eye-slash. With `EVIDENCE_DIR` set, the frame is saved there as a PNG for
 * the PR, since the emulator's `safeOnly` run hides R15 items altogether.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class QueuePageBlurRenderTest {
  private val tabs = TabsController()

  @AfterTest
  fun tearDown() = SingletonImageLoader.reset()

  /** A 256 px picture of coloured stripes and a square: sharp edges, so a blur is plain to see. */
  private fun fixture(first: Int, second: Int): Bitmap {
    val bitmap = Bitmap().apply { allocN32Pixels(256, 256) }
    Canvas(bitmap).apply {
      for (i in 0 until 8) drawRect(Rect.makeXYWH(i * 32f, 0f, 32f, 256f), Paint().apply { color = if (i % 2 == 0) first else second })
      drawRect(Rect.makeXYWH(80f, 80f, 96f, 96f), Paint().apply { color = 0xFFFFFFFF.toInt() })
    }
    return bitmap
  }

  @Test
  fun anR15FixtureIsBlurredAndAGFixtureIsNot() = runDesktopComposeUiTest(width = 1080, height = 1300) {
    val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
      .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
      .build()
    loader.memoryCache!![MemoryCache.Key("md5g-Preview")] = MemoryCache.Value(fixture(0xFF2E7D32.toInt(), 0xFFFFC107.toInt()).asImage())
    loader.memoryCache!![MemoryCache.Key("md5q-Preview")] = MemoryCache.Value(fixture(0xFF1565C0.toInt(), 0xFFE53935.toInt()).asImage())
    SingletonImageLoader.setUnsafe(loader)
    tabs.open(Route.Grid(listOf("scenery")), background = false)
    tabs.openInBlock(listOf(Route.Grid(listOf("scenery"), at = "g"), Route.Post("g", "md5g", listOf("scenery"), "G fixture", "s")))
    tabs.openInBlock(listOf(Route.Grid(listOf("scenery"), at = "q"), Route.Post("q", "md5q", listOf("scenery"), "Mocked R15 fixture", "q")))

    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
        SankakuTheme {
          QueuePage(
            onDismiss = {},
            controller = tabs,
            posts = TabPosts.None,
            privacy = PrivacySettings(InMemoryTextFile()),
            seen = null,
            clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages()),
            interacted = { false }, onInteract = {},
          )
        }
      }
    }
    waitForIdle()

    assertEquals(1, onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    System.getenv("EVIDENCE_DIR")?.let { dir ->
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "queue-blur-fixture.png"))
    }
  }

  /**
   * #264 through the real [QueueStart]: a launch shows the queue page blurred, its queue row and its Recently closed
   * row alike; one touch unblurs both; a return after more than 5 minutes opens the page again, still unblurred; only a
   * new process (a new [QueueStart]) blurs again. With `EVIDENCE_DIR` set, each step's frame is saved.
   */
  @Test
  fun theBlurLastsUntilTheFirstUseInTheProcess() = runDesktopComposeUiTest(width = 1080, height = 1500) {
    val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
      .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
      .build()
    loader.memoryCache!![MemoryCache.Key("md5g-Preview")] = MemoryCache.Value(fixture(0xFF2E7D32.toInt(), 0xFFFFC107.toInt()).asImage())
    loader.memoryCache!![MemoryCache.Key("md5q-Preview")] = MemoryCache.Value(fixture(0xFF1565C0.toInt(), 0xFFE53935.toInt()).asImage())
    loader.memoryCache!![MemoryCache.Key("md5c-Preview")] = MemoryCache.Value(fixture(0xFF6A1B9A.toInt(), 0xFF00838F.toInt()).asImage())
    SingletonImageLoader.setUnsafe(loader)
    tabs.open(Route.Grid(listOf("scenery")), background = false)
    tabs.openInBlock(listOf(Route.Grid(listOf("scenery"), at = "g"), Route.Post("g", "md5g", listOf("scenery"), "G fixture", "s")))
    tabs.openInBlock(listOf(Route.Grid(listOf("scenery"), at = "q"), Route.Post("q", "md5q", listOf("scenery"), "Mocked R15 fixture", "q")))
    tabs.openInBlock(listOf(Route.Grid(listOf("scenery"), at = "c"), Route.Post("c", "md5c", listOf("scenery"), "Closed R15 fixture", "q")))
    tabs.close(tabs.tabs.value.single { it.title == "Closed R15 fixture" }.id)
    val privacy = PrivacySettings(InMemoryTextFile())
    val time = TestTimeSource()
    var start by mutableStateOf(QueueStart(tabs, privacy, time))
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
        SankakuTheme {
          val current = start
          if (current.pageOpen) {
            QueuePage(
              onDismiss = current::closePage,
              controller = tabs,
              posts = TabPosts.None,
              privacy = privacy,
              seen = null,
              clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages()),
              interacted = current::interacted,
              onInteract = current::interact,
            )
          }
        }
      }
    }
    fun blurred() = onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size
    fun frame(name: String) = System.getenv("EVIDENCE_DIR")?.let { dir ->
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
    }

    // 1. A cold launch: the page opens, the R15 row and the R15 Recently closed row blurred.
    start.launched()
    waitForIdle()
    onNodeWithText("Recently closed").assertExists()
    assertEquals(2, blurred())
    frame("1-launch-blurred.png")

    // 2. One touch on the page, on the header's title, where nothing else happens.
    onNodeWithText("Queue").performTouchInput {
      down(center)
      up()
    }
    waitForIdle()
    assertEquals(0, blurred())
    frame("2-after-one-touch.png")

    // 3. Continue, then back after more than 5 minutes away: a start, the page opens again, still unblurred.
    onNodeWithText("Continue", substring = true).performClick()
    waitForIdle()
    start.left()
    time += QueueStart.AWAY + 1.seconds
    start.returned()
    waitForIdle()
    assertTrue(start.pageOpen)
    assertEquals(0, blurred())
    frame("3-return-after-6-min.png")

    // 4. The app killed and started again: a new process, so a new QueueStart, blurred again.
    start = QueueStart(tabs, privacy, time).apply { launched() }
    waitForIdle()
    assertEquals(2, blurred())
    frame("4-new-process-blurred.png")
  }
}
