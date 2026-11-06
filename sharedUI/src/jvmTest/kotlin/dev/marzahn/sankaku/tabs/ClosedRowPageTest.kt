package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #250 on the queue page: Recently closed rows show the closed item's thumbnail with cache-only requests, R15 and R18
 * pictures blur as a queue row's do (until the queue's first use, #264), G only leaves those entries out and counts
 * them, and TalkBack hears the row. The pictures are synthetic fixtures in the memory cache, never a real post; with `EVIDENCE_DIR` set, the frame is saved
 * there for the PR, since the emulator's `safeOnly` run shows G posts only.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class ClosedRowPageTest {
  private val requests = mutableListOf<Boolean>()

  @AfterTest
  fun tearDown() = SingletonImageLoader.reset()

  // Restored at launch: Browse, a source with its saved mosaic, a G post and an R18 post. The clock is at the file's
  // `touched`, so nothing fades.
  private fun restored(): TabsController {
    fun post(id: String, md5: String, label: String, rating: String) =
      """{"id":"$id","routes":[{"type":"dev.marzahn.sankaku.Route.Post","id":"$id","md5":"$md5","originTags":[],"label":"$label","rating":"$rating"}],""" +
        """"kind":"post","touched":1000,"addedIn":"s1","keptAt":1000}"""
    val saved = """{"version":2,"session":"s1","activeIndex":0,"tabs":[""" +
      """{"id":"b","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":[]}],"kind":"source","touched":1000,"addedIn":"s1"},""" +
      """{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}],"kind":"source","touched":1000,"addedIn":"s1",""" +
      """"mosaic":[{"md5":"m0","rating":"s"},{"md5":"m1","rating":"q"},{"md5":"m2","rating":"s"},{"md5":"m3","rating":"s"}],"posts":60},""" +
      post("g", "k0", "Kept G", "s") + "," + post("x", "k1", "Kept R18", "e") +
      """]}"""
    return TabsController(FileTabStateStore(InMemoryTextFile(saved)), clock = object : Clock {
      override fun now() = Instant.fromEpochSeconds(1000)
    })
  }

  private fun TabsController.closeAllButBrowse() {
    tabs.value.drop(1).forEach { close(it.id) }
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

  private fun ComposeUiTest.show(tabs: TabsController, gOnly: Boolean = false, used: Boolean = false) {
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
    val colours = listOf(0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFEF6C00, 0xFF00838F, 0xFFC62828)
    listOf("m0", "m1", "m2", "m3", "k0", "k1").forEachIndexed { i, md5 ->
      loader.memoryCache!![MemoryCache.Key("$md5-Preview")] =
        MemoryCache.Value(fixture(colours[i].toInt(), 0xFFFFC107.toInt()).asImage())
    }
    SingletonImageLoader.setUnsafe(loader)
    val privacy = PrivacySettings(InMemoryTextFile()).apply { if (gOnly) setGOnly(true) }
    val seen = SeenStore(InMemoryTextFile())
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
        SankakuTheme {
          QueuePage(
            onDismiss = {}, controller = tabs, posts = TabPosts.None, privacy = privacy, seen = seen,
            clearViewed = ClearViewed(tabs, seen, Messages()), interacted = { used }, onInteract = {},
          )
        }
      }
    }
    waitForIdle()
  }

  @Test
  fun closedRowsShowTheirThumbnailsCacheOnlyWithAdultOnesBlurred() = runDesktopComposeUiTest(width = 1080, height = 2000) {
    val tabs = restored()
    tabs.closeAllButBrowse()
    show(tabs)

    onNodeWithText("Recently closed").assertExists()
    // The source's mosaic (4), each cache-only. The two posts' previews are read straight from the memory cache, as a
    // kept row's are, so the post each reopens can grow out of it from the first frame (#322): no request at all.
    assertEquals(4, synchronized(requests) { requests.size })
    assertTrue(synchronized(requests) { requests.none { it } }, "every thumbnail request is cache-only")
    // m1 (R15) in the mosaic and the R18 post blur; the G tiles and the G post don't.
    assertEquals(2, onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    System.getenv("EVIDENCE_DIR")?.let { dir ->
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "recently-closed-thumbnails-fixture.png"))
    }
  }

  // #264: by the queue rows' rule, unblurred once the queue was used since the app started.
  @Test
  fun closedRowsAreNotBlurredOnceTheQueueWasUsed() = runDesktopComposeUiTest(width = 1080, height = 2000) {
    val tabs = restored()
    tabs.closeAllButBrowse()
    show(tabs, used = true)

    onNodeWithText("Kept R18").assertExists()
    assertTrue(onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun gOnlyLeavesOutTheR18EntryAndCountsIt() = runDesktopComposeUiTest(width = 1080, height = 2000) {
    val tabs = restored()
    tabs.closeAllButBrowse()
    show(tabs, gOnly = true)

    onNodeWithText("Kept G").assertExists()
    onNodeWithText("Kept R18").assertDoesNotExist()
    onNodeWithText("Hidden by G only · 1").assertExists()
    assertTrue(onNodeWithText("Hidden by G only · 1").fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
  }

  @Test
  fun talkBackHearsTheRowsTitleKindAndBlurAndCanReopenIt() = runDesktopComposeUiTest(width = 1080, height = 2000) {
    val tabs = restored()
    tabs.close(tabs.tabs.value.single { it.title == "Kept R18" }.id)
    show(tabs)
    onNode(hasScrollAction()).performScrollToNode(hasText("Kept R18"))

    val row = onNode(hasClickAction() and hasText("Kept R18")).fetchSemanticsNode().config
    assertEquals(listOf("Kept R18", "Post"), row.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
    assertEquals(listOf("Blurred"), row.getOrNull(SemanticsProperties.ContentDescription))
    assertEquals("Reopen", row.getOrNull(SemanticsActions.OnClick)?.label)
  }
}
