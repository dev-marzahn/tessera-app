package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.LoadBarDescription
import kotlinx.coroutines.awaitCancellation
import kotlinx.io.IOException
import org.jetbrains.skia.Bitmap
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The viewer's loads over the preview (#524): a sample or GIF still loading after half a second shows the thin load
 * bar, and a GIF whose sample fails shows Retry, which loads it again, as the network coming back does. Before, a
 * failed GIF stayed still for good.
 */
@OptIn(ExperimentalTestApi::class)
class MediaLoadTest {
  /** Answers every image from memory: a 1 px picture, a connection error for [failing], or never for [hanging]. */
  private class Gate : Interceptor {
    val failing = mutableSetOf<Variant>()
    val hanging = mutableSetOf<Variant>()
    val calls = mutableListOf<Variant>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
      val variant = (chain.request.data as PostImage).variant
      calls += variant
      if (variant in hanging) awaitCancellation()
      if (variant in failing) return ErrorResult(null, chain.request, IOException("unreachable"))
      // A sample of its post's sample size, so zooming past it asks for the full file.
      val (w, h) = if (variant == Variant.Sample) SAMPLE_WIDTH to SAMPLE_HEIGHT else 1 to 1
      val bitmap = Bitmap().apply { allocN32Pixels(w, h) }
      return SuccessResult(bitmap.asImage(), chain.request)
    }
  }

  private val gate = Gate()
  private val connectivity = ConnectivityState()

  private val still = PostDto(
    id = "1", md5 = "abc", rating = "g", status = "active", fileType = "image/jpeg",
    previewUrl = "https://s.example.test/p.avif?e=9999999999", sampleUrl = "https://s.example.test/s.jpg?e=9999999999",
    fileUrl = "https://s.example.test/f.jpg?e=9999999999", fileWidth = 400, fileHeight = 300,
  )
  private val gif = still.copy(id = "2", md5 = "def", fileType = "image/gif", fileExt = "gif")

  /** A still whose file is ten times its sample: zoomed past the sample, the viewer fetches the file. */
  private val large = still.copy(
    id = "3", md5 = "ghi", fileWidth = SAMPLE_WIDTH * 10, fileHeight = SAMPLE_HEIGHT * 10,
    sampleWidth = SAMPLE_WIDTH, sampleHeight = SAMPLE_HEIGHT,
  )

  /** Shows [large], waits for its sample, and zooms in with a double-click, as a user does. */
  private fun ComposeUiTest.zoomPastTheSample() {
    setContent { PostImageView(large, Modifier.size(400.dp, 300.dp)) }
    waitUntil(timeoutMillis = 5_000) { Variant.Sample in gate.calls }
    waitForIdle()
    onRoot().performTouchInput { doubleClick(center) }
    waitForIdle()
  }

  @Test
  fun aFullFileThatFailsShowsRetryWhichFetchesOnlyItAgain() = runComposeUiTest {
    gate.failing += Variant.File
    zoomPastTheSample()
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.File } == 1 }
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithContentDescription("Retry loading the image").fetchSemanticsNodes().isNotEmpty()
    }
    val samples = gate.calls.count { it == Variant.Sample }

    gate.failing.clear()
    onNodeWithContentDescription("Retry loading the image").performClick()
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.File } == 2 }
    // The zoomed sample stays: only the full file is asked for again.
    assertEquals(samples, gate.calls.count { it == Variant.Sample })
  }

  @Test
  fun aFullFileThatFailedOfflineLoadsAgainWhenTheConnectionComesBack() = runComposeUiTest {
    gate.failing += Variant.File
    zoomPastTheSample()
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.File } == 1 }
    waitForIdle()

    gate.failing.clear()
    connectivity.requestRetry()
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.File } == 2 }
  }

  @Test
  fun aFullFileStillLoadingShowsTheLoadBarAfterHalfASecond() = runComposeUiTest {
    gate.hanging += Variant.File
    zoomPastTheSample()
    waitUntil(timeoutMillis = 5_000) { Variant.File in gate.calls }
    onNodeWithContentDescription(LoadBarDescription).assertDoesNotExist()

    mainClock.advanceTimeBy(700)
    onNodeWithContentDescription(LoadBarDescription).assertExists()
  }

  @BeforeTest
  fun startFixtureKoin() {
    val loader = ImageLoader.Builder(PlatformContext.INSTANCE).components { add(gate) }.build()
    startKoin {
      modules(
        module {
          single { loader }
          single { connectivity }
          single { Messages() }
        },
      )
    }
  }

  @AfterTest
  fun stopFixtureKoin() = stopKoin()

  @Test
  fun aGifWhoseSampleFailsShowsRetryAndRetryLoadsItAgain() = runComposeUiTest {
    gate.failing += Variant.Sample
    setContent { PostImageView(gif, Modifier.size(400.dp, 300.dp)) }
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.Sample } == 1 }

    onNodeWithContentDescription("Retry loading the image").assertExists()
    gate.failing.clear()
    onNodeWithContentDescription("Retry loading the image").performClick()
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.Sample } == 2 }
  }

  @Test
  fun aGifThatFailedOfflineLoadsAgainWhenTheConnectionComesBack() = runComposeUiTest {
    gate.failing += Variant.Sample
    setContent { PostImageView(gif, Modifier.size(400.dp, 300.dp)) }
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.Sample } == 1 }
    waitForIdle()

    gate.failing.clear()
    connectivity.requestRetry()
    waitUntil(timeoutMillis = 5_000) { gate.calls.count { it == Variant.Sample } == 2 }
  }

  @Test
  fun aSampleStillLoadingShowsTheLoadBarOnlyAfterHalfASecond() = runComposeUiTest {
    gate.hanging += Variant.Sample
    mainClock.autoAdvance = false
    setContent { PostImageView(still, Modifier.size(400.dp, 300.dp)) }
    mainClock.advanceTimeBy(300)

    onNodeWithContentDescription(LoadBarDescription).assertDoesNotExist()
    mainClock.advanceTimeBy(400)
    onNodeWithContentDescription(LoadBarDescription).assertExists()
  }

  @Test
  fun aGifStillLoadingShowsTheLoadBarToo() = runComposeUiTest {
    gate.hanging += Variant.Sample
    mainClock.autoAdvance = false
    setContent { PostImageView(gif, Modifier.size(400.dp, 300.dp)) }
    mainClock.advanceTimeBy(700)

    onNodeWithContentDescription(LoadBarDescription).assertExists()
  }

  @Test
  fun aLoadedSampleShowsNoLoadBar() = runComposeUiTest {
    setContent { PostImageView(still, Modifier.size(400.dp, 300.dp)) }
    waitUntil(timeoutMillis = 5_000) { Variant.Sample in gate.calls }
    mainClock.advanceTimeBy(1_000)

    onNodeWithContentDescription(LoadBarDescription).assertDoesNotExist()
    assertEquals(0, gate.calls.count { it == Variant.File })
  }

  private companion object {
    const val SAMPLE_WIDTH = 400
    const val SAMPLE_HEIGHT = 300
  }
}
