package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.memory.MemoryCache
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.FeedItems
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

/**
 * #265: the queue page drawn with posts kept from Following and For you nested under each feed, with synthetic fixture
 * pictures (never a real post). With `EVIDENCE_DIR` set, the frames before and after keeping are saved there as PNGs.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class FeedBlocksRenderTest {
  private val tabs = TabsController()

  @AfterTest
  fun tearDown() = SingletonImageLoader.reset()

  // A 256 px picture of two-colour stripes: plainly a placeholder.
  private fun fixture(first: Int, second: Int): Bitmap {
    val bitmap = Bitmap().apply { allocN32Pixels(256, 256) }
    Canvas(bitmap).apply {
      for (i in 0 until 8) drawRect(Rect.makeXYWH(i * 32f, 0f, 32f, 256f), Paint().apply { color = if (i % 2 == 0) first else second })
    }
    return bitmap
  }

  private fun post(id: String, label: String) = Route.Post(id, "md5$id", listOf("fixture"), label, "s")

  @Test
  fun postsKeptFromAFeedAreNestedUnderIt() = runDesktopComposeUiTest(width = 1080, height = 2200) {
    val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
      .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
      .build()
    val colours = listOf(0xFF2E7D32 to 0xFFFFC107, 0xFF1565C0 to 0xFFE53935, 0xFF6A1B9A to 0xFF4DD0E1, 0xFFEF6C00 to 0xFF8D6E63)
    listOf("s1", "f1", "f2", "y1").forEachIndexed { i, id ->
      val (a, b) = colours[i]
      loader.memoryCache!![MemoryCache.Key("md5$id-Preview")] = MemoryCache.Value(fixture(a.toInt(), b.toInt()).asImage())
    }
    SingletonImageLoader.setUnsafe(loader)
    val navigator = TabsNavigator(tabs)
    tabs.open(Route.Grid(listOf("scenery")), background = false)
    navigator.openInNewTab(post("s1", "Fixture kept from Scenery"))
    tabs.open(Route.Grid(listOf("landscape")), background = true)
    tabs.pinFeeds(FeedItems.pinned(signedIn = true, forYouInQueue = true))
    val following = tabs.tabs.value.single { it.title == "Following" }
    tabs.activate(following.id)

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
            interacted = { false },
            onInteract = {},
          )
        }
      }
    }
    waitForIdle()
    val dir = System.getenv("EVIDENCE_DIR")
    dir?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "feed-kept-before.png")) }

    // Long-presses on Following's grid, then on For you's.
    runOnIdle {
      navigator.openInNewTab(post("f1", "Fixture kept from Following"))
      navigator.openInNewTab(post("f2", "Second fixture kept from Following"))
      tabs.activate(tabs.tabs.value.single { it.title == "For you" }.id)
      navigator.openInNewTab(post("y1", "Fixture kept from For you"))
    }
    waitForIdle()

    assertEquals(
      listOf("Browse", "Scenery", "Fixture kept from Scenery", "Landscape", "Following", "Fixture kept from Following",
        "Second fixture kept from Following", "For you", "Fixture kept from For you"),
      tabs.tabs.value.map { it.title },
    )
    // TalkBack's second line (#234); the screen shows "Kept" with the indent and the line.
    assertEquals(2, onAllNodesWithContentDescription("Kept from Following", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    assertEquals(1, onAllNodesWithContentDescription("Kept from For you", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    dir?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "feed-kept-after.png")) }
  }
}
