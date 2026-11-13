package dev.marzahn.sankaku.images

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import coil3.memory.MemoryCache
import coil3.size.Dimension
import coil3.size.Size
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class PostImageFetcherTest {
  private val now = Instant.parse("2026-10-29T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val scope = CoroutineScope(SupervisorJob())
  private fun png(side: Int) =
    ByteArrayOutputStream().also { ImageIO.write(BufferedImage(side, side, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

  private fun previewUrl(expiresAt: Instant, version: String = "old") =
    "https://v.sankakucomplex.com/data/preview/x.png?e=${expiresAt.epochSeconds}&m=$version"

  private val fresh = previewUrl(now + 1.hours, "fresh")

  /**
   * [image] answers each image request with a [side] × [side] PNG, or with [body]'s bytes where it gives some; the
   * keyset endpoint returns the post, a [fileType], with [refetched] as its preview URL (none for null) and
   * [refetchedSample] as its sample's. The loader decodes AVIFs up to [maxAvifBitDepth] bits (Android's is 10).
   */
  private inner class Rig(
    val side: Int = 2,
    val refetched: String? = fresh,
    val refetchedSample: String? = null,
    val fileType: String = "image/png",
    val maxAvifBitDepth: Int = 12,
    val body: (HttpRequestData) -> ByteArray? = { null },
    val image: (HttpRequestData) -> HttpStatusCode,
  ) {
    val requests = mutableListOf<HttpRequestData>()
    private val png = png(side)
    private val engine = MockEngine { request ->
      requests += request
      if (request.url.host == "sankakuapi.com") {
        val preview = refetched?.let { ""","preview_url":"$it"""" } ?: ""
        val sample = refetchedSample?.let { ""","sample_url":"$it"""" } ?: ""
        respond(
          """{"meta":{"next":null},"data":[{"id":"p1","md5":"m1","rating":"s","status":"active","file_type":"$fileType"$preview$sample}]}""",
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
      } else {
        val status = image(request)
        val bytes = if (status == HttpStatusCode.OK) body(request) ?: png else ByteArray(0)
        respond(bytes, status, headersOf(HttpHeaders.ContentType, "image/png"))
      }
    }
    private val log = RequestLog(clock)
    private val limiter = RateLimiter(limits = emptyMap())
    private val connectivity = ConnectivityState()
    private val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), clock), InMemoryTokenStore(), clock)
    private val client = createMainClient(engine, log, limiter, connectivity, session)
    val store = PostStateStore()
    private val repository = PostsRepository(PostsApi(client), store, session, scope)
    val cacheDir = Files.createTempDirectory("images").toOkioPath()
    val loader = createImageLoader(PlatformContext.INSTANCE, client, repository, store, clock, cacheDir, maxAvifBitDepth)

    fun load(url: String?, size: Int? = null) = runBlocking {
      loader.execute(
        postImageRequest(PlatformContext.INSTANCE, PostImage("p1", "m1", Variant.Preview, url)) {
          if (size != null) size(size, size)
        },
      )
    }

    /** What `decodedInMemory` would draw for the post: the shared memory entry, at whatever size it was decoded. */
    fun inMemory() = loader.memoryCache?.get(MemoryCache.Key("m1-Preview"))?.image

    fun imageRequests() = requests.filter { it.url.host != "sankakuapi.com" }

    fun refetches() = requests.filter { it.url.host == "sankakuapi.com" }
  }

  @AfterTest
  fun tearDown() = scope.cancel()

  @Test
  fun aCachedImageIsServedWithoutARequestEvenWhenItsUrlExpired() {
    val rig = Rig { HttpStatusCode.OK }
    assertIs<SuccessResult>(rig.load(previewUrl(now + 1.hours)))
    rig.loader.memoryCache?.clear()
    rig.requests.clear()

    assertIs<SuccessResult>(rig.load(previewUrl(now - 1.hours)))

    assertEquals(0, rig.requests.size)
  }

  @Test
  fun aCacheOnlyRequestNeverMakesARequest() {
    val rig = Rig { HttpStatusCode.OK }
    val image = PostImage("p1", "m1", Variant.Preview, null)

    assertIs<ErrorResult>(runBlocking { rig.loader.execute(cachedPostImageRequest(PlatformContext.INSTANCE, image)) })
    assertEquals(0, rig.requests.size)

    assertIs<SuccessResult>(rig.load(fresh))
    rig.loader.memoryCache?.clear()
    rig.requests.clear()
    assertIs<SuccessResult>(runBlocking { rig.loader.execute(cachedPostImageRequest(PlatformContext.INSTANCE, image)) })
    assertEquals(0, rig.requests.size)
  }

  @Test
  fun anExpiredUrlRefetchesThePostBeforeAnyImageRequest() {
    val rig = Rig { HttpStatusCode.OK }

    assertIs<SuccessResult>(rig.load(previewUrl(now - 1.hours)))

    assertEquals(1, rig.refetches().size)
    assertEquals(listOf(fresh), rig.imageRequests().map { it.url.toString() })
  }

  @Test
  fun aMissingUrlRefetchesFirst() {
    val rig = Rig { HttpStatusCode.OK }

    assertIs<SuccessResult>(rig.load(null))

    assertEquals(1, rig.refetches().size)
  }

  @Test
  fun aRefusedUrlRefetchesOnceAndRetries() {
    val rig = Rig { if (it.url.parameters["m"] == "fresh") HttpStatusCode.OK else HttpStatusCode.Forbidden }

    assertIs<SuccessResult>(rig.load(previewUrl(now + 1.hours)))

    assertEquals(1, rig.refetches().size)
    assertEquals(2, rig.imageRequests().size)
  }

  @Test
  fun aSecondRefusalSurfacesAnError() {
    val rig = Rig { HttpStatusCode.Gone }

    assertIs<ErrorResult>(rig.load(previewUrl(now + 1.hours)))

    assertEquals(1, rig.refetches().size)
    assertEquals(2, rig.imageRequests().size)
    assertEquals(null, rig.loader.diskCache?.openSnapshot("m1-Preview"), "a refusal must not be cached")
  }

  @Test
  fun aRateLimitIsNotARefusal() {
    val rig = Rig { HttpStatusCode.TooManyRequests }

    assertIs<ErrorResult>(rig.load(previewUrl(now + 1.hours)))

    assertEquals(0, rig.refetches().size)
  }

  @Test
  fun aTinyDecodeNeverBecomesTheSharedMemoryCopy() {
    // #119: the viewer's image room is 1 px tall while the phone's details sheet is full.
    val rig = Rig(side = 800) { HttpStatusCode.OK }

    assertIs<SuccessResult>(rig.load(fresh, size = 1))
    assertNull(rig.inMemory(), "a 1 px decode would stand in for the image everywhere")

    assertIs<SuccessResult>(rig.load(fresh, size = 400))
    val shared = assertNotNull(rig.inMemory())
    assertEquals(400, shared.width)

    // A tiny request afterwards is served the shared copy and leaves it as it was.
    assertIs<SuccessResult>(rig.load(fresh, size = 1))
    assertEquals(400, assertNotNull(rig.inMemory()).width)
  }

  @Test
  fun aPostWithoutAPreviewIsNoImage() {
    val rig = Rig(refetched = null) { HttpStatusCode.OK }

    val result = assertIs<ErrorResult>(rig.load(null))

    assertEquals(ImageFailure.NoImage, imageFailure(result.throwable))
    assertEquals(0, rig.imageRequests().size)
  }

  @Test
  fun aRefusalThatSurvivesTheRefetchIsRetryable() {
    val rig = Rig { HttpStatusCode.Gone }

    val result = assertIs<ErrorResult>(rig.load(previewUrl(now + 1.hours)))

    assertEquals(ImageFailure.Failed, imageFailure(result.throwable))
  }

  private val twelveBit = javaClass.getResourceAsStream("/twelve-bit.avif")!!.readBytes()
  private val freshSample = "https://v.sankakucomplex.com/data/sample/x.webp?e=${(now + 1.hours).epochSeconds}&m=s"

  /** The preview's URL answers with the 12-bit AVIF, any other image URL with the PNG, or [sampleStatus]. */
  private fun twelveBitPreviewRig(
    maxAvifBitDepth: Int = 10,
    refetchedSample: String? = freshSample,
    fileType: String = "image/png",
    sampleStatus: () -> HttpStatusCode = { HttpStatusCode.OK },
  ) = Rig(
    side = 40,
    refetchedSample = refetchedSample,
    fileType = fileType,
    maxAvifBitDepth = maxAvifBitDepth,
    body = { if ("preview" in it.url.encodedPath) twelveBit else null },
  ) { if ("preview" in it.url.encodedPath) HttpStatusCode.OK else sampleStatus() }

  private fun Rig.cachedPreview() =
    loader.diskCache?.openSnapshot("m1-Preview")?.use { loader.diskCache!!.fileSystem.read(it.data) { readByteArray() } }

  @Test
  fun aCacheOnlyRequestLeavesAnUndecodablePreviewOnDiskAndMisses() {
    val rig = twelveBitPreviewRig(maxAvifBitDepth = 12)
    // Cached as an app without the fix would have. Only macOS decodes AVIF here, so the result itself isn't checked.
    rig.load(fresh, size = 400)
    assertTrue(assertNotNull(rig.cachedPreview()).contentEquals(twelveBit))
    val android = twelveBitPreviewRig()
    rig.cacheDir.toFile().copyRecursively(android.cacheDir.toFile(), overwrite = true)

    val result = runBlocking {
      android.loader.execute(cachedPostImageRequest(PlatformContext.INSTANCE, PostImage("p1", "m1", Variant.Preview, fresh)))
    }

    assertIs<ErrorResult>(result)
    assertEquals(0, android.requests.size)
    assertTrue(assertNotNull(android.cachedPreview()).contentEquals(twelveBit), "still on disk for the next load")
  }

  @Test
  fun aVideoPostsUndecodablePreviewFailsWithoutLoadingItsSample() {
    val rig = twelveBitPreviewRig(fileType = "video/mp4")

    val result = assertIs<ErrorResult>(rig.load(fresh, size = 400))

    assertEquals(ImageFailure.NoImage, imageFailure(result.throwable))
    assertEquals(listOf(fresh), rig.imageRequests().map { it.url.toString() })
  }

  @Test
  fun aFailedSampleKeepsThePreviewSoItIsntDownloadedAgain() {
    var sampleStatus = HttpStatusCode.TooManyRequests
    val rig = twelveBitPreviewRig(sampleStatus = { sampleStatus })
    assertIs<ErrorResult>(rig.load(fresh, size = 400))
    rig.requests.clear()
    sampleStatus = HttpStatusCode.OK

    assertIs<SuccessResult>(rig.load(fresh, size = 400))

    assertEquals(listOf(freshSample), rig.imageRequests().map { it.url.toString() })
  }

  @Test
  fun aPreviewDeeperThanThePlatformDecodesIsReplacedByTheSample() {
    // #576: Android draws a 12-bit AVIF as green stripes, so the cell shows the post's sample instead.
    val rig = twelveBitPreviewRig()

    val result = assertIs<SuccessResult>(rig.load(fresh, size = 400))

    assertEquals(listOf(fresh, freshSample), rig.imageRequests().map { it.url.toString() })
    assertEquals(result.image.width, result.image.height, "the sample's square picture, not the AVIF's 378 × 600")
    val cached = assertNotNull(rig.cachedPreview())
    assertFalse(cached.contentEquals(twelveBit), "the preview's disk entry holds the sample now")
  }

  @Test
  fun theReplacedPreviewIsServedFromDiskAfterwards() {
    val rig = twelveBitPreviewRig()
    assertIs<SuccessResult>(rig.load(fresh, size = 400))
    rig.loader.memoryCache?.clear()
    rig.requests.clear()

    assertIs<SuccessResult>(rig.load(fresh, size = 400))

    assertEquals(0, rig.requests.size)
  }

  @Test
  fun aPreviewThePlatformDecodesIsKept() {
    val rig = twelveBitPreviewRig(maxAvifBitDepth = 12)

    rig.load(fresh, size = 400) // decoded only on macOS (ImageIO); CI's Linux has no AVIF decoder

    assertEquals(listOf(fresh), rig.imageRequests().map { it.url.toString() })
  }

  @Test
  fun withoutASampleTheUndecodablePreviewFailsRatherThanShowingGarbage() {
    val rig = twelveBitPreviewRig(refetchedSample = null)

    val result = assertIs<ErrorResult>(rig.load(fresh, size = 400))

    assertEquals(ImageFailure.NoImage, imageFailure(result.throwable))
  }

  @Test
  fun aDecodeSizeIsShareableUnlessItIsTiny() {
    assertTrue(isShareableSize(Size(400, 600)))
    assertTrue(isShareableSize(Size.ORIGINAL))
    assertTrue(isShareableSize(Size(Dimension.Undefined, Dimension(400))))
    assertTrue(isShareableSize(Size(220, 60)), "a wide image's desktop cell at 1×")
    assertFalse(isShareableSize(Size(1280, 1)), "the collapsed viewer room")
    assertFalse(isShareableSize(Size(140, 140)), "the sheet's 40 dp thumbnail")
  }

  @Test
  fun theViewerRequestIsTheImageFittedToTheWindow() {
    assertEquals(Size(1280, 1829), fittedRequestSize(0.7f, 1280, 2856))
    assertEquals(Size(1000, 1000), fittedRequestSize(1f, 1600, 1000))
    assertNull(fittedRequestSize(0.7f, 0, 0), "a window not measured yet leaves the layout's size")
  }

  @Test
  fun theCacheKeyIgnoresTheUrl() {
    val a = PostImage("p1", "m1", Variant.Sample, "https://a")
    val b = PostImage("p1", "m1", Variant.Sample, "https://b")

    assertEquals("m1-Sample", a.cacheKey)
    assertEquals(PostImageKeyer.key(a, coil3.request.Options(PlatformContext.INSTANCE)), PostImageKeyer.key(b, coil3.request.Options(PlatformContext.INSTANCE)))
  }
}
