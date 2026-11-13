package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.shell.RetryWhenBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.github.panpf.zoomimage.zoom.ScalesCalculator
import dev.marzahn.sankaku.platformIsTouch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import androidx.compose.foundation.Image
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import androidx.compose.runtime.derivedStateOf
import dev.marzahn.sankaku.ui.components.LoadBar
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.compose.zoom.ScrollBarSpec
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.github.panpf.zoomimage.subsampling.ImageInfo
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.fromFile
import com.google.fonts.icons.open_in_new
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.data.url
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.size.Size as CoilSize
import dev.marzahn.sankaku.images.ImageFailure
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.fittedRequestSize
import dev.marzahn.sankaku.images.failure
import dev.marzahn.sankaku.images.fittedTo
import dev.marzahn.sankaku.images.imageFailure
import dev.marzahn.sankaku.images.postImageRequest
import dev.marzahn.sankaku.ui.components.ImageFailed
import dev.marzahn.sankaku.ui.components.ImageRetryChip
import dev.marzahn.sankaku.openUrl
import dev.marzahn.sankaku.ui.Messages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/**
 * The post's image. The best decoded copy in memory (the sample once loaded, else the preview) fills a box of exactly
 * the image's displayed size ([imageBox], which carries the shared-element transition), so nothing changes size when
 * the zoomable sample arrives over it. [showSample] holds the
 * zoomable sample back until a transition has settled. A GIF and a video carry [sampleBox] instead, the phone
 * viewer's, which is fitted to the image's box and carries them back into the cell as the card closes, so the frame
 * shown stays (#283). A video plays while [playing], and stays paused on its frame otherwise. Zooming past the
 * sample's resolution downloads the full file once and hands it to zoomimage, which then decodes only the zoomed region. A video loads while [playVideo] (the
 * phone viewer's settled page, so its neighbours stay still), already while the transition runs, and plays in the
 * same box ([PostVideo]) once settled; on touch a double-tap on either half seeks it. Flash shows its preview with a
 * note. [onShown] runs once the post is fully shown (its sample, its GIF, its video's first frame, or Flash's preview), for seen (#174).
 * [onLongPress] runs on a long-press anywhere on the picture, zoomed or not, after the platform's long-press timeout
 * (the phone viewer's favourite, #527); a long-press is never also a tap.
 */
@Composable
fun PostImageView(
  post: PostDto,
  modifier: Modifier = Modifier,
  imageBox: Modifier = Modifier,
  sampleBox: Modifier? = null,
  showSample: Boolean = true,
  onTap: (() -> Unit)? = null,
  onLongPress: (() -> Unit)? = null,
  zoom: PostZoom? = null,
  playVideo: Boolean = true,
  playing: Boolean = showSample,
  onShown: () -> Unit = {},
  actions: List<CustomAccessibilityAction> = emptyList(),
) {
  // The zoomable sample reports single taps itself, after ruling out a double-tap (which zooms); anything else here
  // takes them plainly.
  val zoomable = showSample && !post.redirectToSignup && !isFlash(post) && !isVideo(post) && !isAnimated(post)
  // A video this platform's player can't open shows its preview and a note, as Flash does (#192).
  val unplayable = isVideo(post) && !platformPlaysVideo(post)
  // The detector starts once: a new lambda on every recomposition would restart it and drop a tap in progress.
  val tap by rememberUpdatedState(onTap)
  // Touch: videos aren't zoomable, so a double-tap on the left or right half seeks back or forward instead.
  val videoKeys = LocalVideoKeys.current
  val seeks = platformIsTouch && isVideo(post) && videoKeys != null
  var seek by remember(post.id) { mutableStateOf<SeekShown?>(null) }
  LaunchedEffect(seek) {
    if (seek == null) return@LaunchedEffect
    delay(SEEK_SHOWN_MS)
    seek = null
  }
  val longPress by rememberUpdatedState(onLongPress)
  val tapModifier = when {
    (onTap == null && onLongPress == null) || zoomable -> Modifier
    else -> Modifier.pictureTaps(
      key = listOf(videoKeys.takeIf { seeks }, post.id, onLongPress != null),
      onTap = { tap?.invoke() },
      // Without one, a press held long is still a tap, as before.
      onLongPress = if (onLongPress == null) null else ({ longPress?.invoke() }),
      onDoubleTap = if (!seeks) {
        null
      } else {
        { offset, width ->
          val direction = SeekDirection.at(offset.x, width)
          if (videoKeys.seek(forward = direction == SeekDirection.Forward)) {
            seek = SeekShown(direction, (seek?.id ?: 0) + 1)
          }
        }
      },
    )
  }
  if (post.redirectToSignup) {
    Box(modifier.then(tapModifier), contentAlignment = Alignment.Center) { Text("Sign in to view this post.") }
    return
  }
  val context = LocalPlatformContext.current
  val imageLoader = koinInject<ImageLoader>()
  var sampleLoaded by remember(post.id) { mutableStateOf(false) }
  var videoShown by remember(post.id) { mutableStateOf(false) }
  val shown by rememberUpdatedState(onShown)
  // The image is requested at its size fitted to the window, never to this box: on a phone the details sheet squeezes
  // the box to 1 px at full, and a request sized then decoded a 1 px image that showed as a grey box (#119).
  val window = LocalWindowInfo.current.containerSize
  val requestSize = remember(post.id, window) { fittedRequestSize(aspectRatio(post), window.width, window.height) }
  // A retry recomposes the image's requests (a new attempt); the fetcher refetches the post if its URLs went stale.
  var attempt by remember(post.id) { mutableIntStateOf(0) }
  var previewFailure by remember(post.id, attempt) { mutableStateOf<ImageFailure?>(null) }
  var previewShown by remember(post.id, attempt) { mutableStateOf(false) }
  // The sample's failure, or the GIF's (#524).
  var sampleFailure by remember(post.id, attempt) { mutableStateOf<ImageFailure?>(null) }
  // The full file for zoom (#524): its own attempts, so retrying it keeps the zoom.
  var fullAttempt by remember(post.id) { mutableIntStateOf(0) }
  var fullLoading by remember(post.id) { mutableStateOf(false) }
  // A sample Retry starts the zoomable sample over at 1×, so it clears this too.
  var fullFailure by remember(post.id, attempt, fullAttempt) { mutableStateOf<ImageFailure?>(null) }
  // Back online, an image that failed loads again, as Retry would.
  RetryWhenBack {
    if (previewFailure == ImageFailure.Unreachable || sampleFailure == ImageFailure.Unreachable) attempt++
    if (fullFailure == ImageFailure.Unreachable) fullAttempt++
  }
  // Named for TalkBack by its title and kind, never its id (#143); the images inside stay unnamed. No image role: the
  // kind says it, and a video isn't one.
  val picture = Modifier.semantics {
    contentDescription = pictureDescription(post)
    // A lone post's gestures (a queue swipe, a drag down to close) for TalkBack (#181).
    if (actions.isNotEmpty()) customActions = actions
  }
  BoxWithConstraints(modifier.then(tapModifier).then(picture), contentAlignment = Alignment.Center) {
    val ratio = aspectRatio(post)
    val (width, height) = fittedBox(maxWidth, maxHeight, ratio)
    // Once the zoomable sample shows, it alone draws the image: zoomed out past 1× it's smaller than this box, which
    // would show around it as a second copy. The layer is outside the shared element, so a transition's overlay, which
    // draws the shared element itself, still shows this box while the card opens or closes.
    val covered = zoomable && sampleLoaded
    // The best copy already decoded in memory, drawn in the first frame: the sample once it has loaded, else the
    // grid's preview. While the card opens or closes the box below is drawn above the panel as a shared element, so
    // it must hold the sample too: with the preview it covered the sample, a soft veil until the card settled,
    // and every reopening looked like a reload. Only a miss loads the preview.
    val inMemory = remember(post.md5, sampleLoaded) { decodedInMemory(imageLoader, post.md5) }
    // Only the preview and the zoomable sample restart on a retry; a playing video or GIF doesn't.
    key(attempt) {
      Box(Modifier.size(width, height).graphicsLayer { alpha = if (covered) 0f else 1f }.then(imageBox)) {
        if (inMemory != null) {
          Image(inMemory.asPainter(context), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        } else {
          AsyncImage(
            model = previewRequest(context, post, requestSize),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            onState = {
              previewShown = it is AsyncImagePainter.State.Success
              previewFailure = it.failure()
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
    when {
      isFlash(post) -> UnplayableFallback(post, "Flash isn't supported")
      unplayable -> UnplayableFallback(post, "WebM videos don't play on macOS")
      // Fitted over the preview, which has its aspect ratio, so the video starts where the preview was.
      // It loads while the transition runs and shows once settled (#180). With a [sampleBox] its box is the picture's,
      // as the GIF's is; without, the desktop controls span the panel.
      isVideo(post) -> if (playVideo) {
        PostVideo(
          post,
          if (sampleBox != null) Modifier.size(width, height).then(sampleBox) else Modifier.fillMaxSize(),
          play = showSample && playing,
          shown = showSample,
          onFirstFrame = { videoShown = true },
        )
      }
      // Not zoomable: a GIF is small, and zooming would need every frame at full size.
      isAnimated(post) -> if (showSample) {
        AnimatedSample(
          post,
          Modifier.size(width, height).then(sampleBox ?: Modifier),
          onShown = { sampleLoaded = true },
          attempt = attempt,
          onFailed = { sampleFailure = it },
        )
      }
      showSample -> key(attempt) {
        ZoomableSample(
          post,
          requestSize,
          Modifier.fillMaxSize(),
          onTap,
          onLongPress,
          zoom,
          onLoaded = { sampleLoaded = true },
          onFailed = { sampleFailure = imageFailure(it) },
          fullAttempt = fullAttempt,
          onFullLoading = { fullLoading = it },
          onFullFailed = { fullFailure = it },
        )
      }
    }
    // A sharper copy on its way over what shows (#524), once it has taken half a second: the sample or the GIF along
    // the picture's bottom edge; the full file, loaded while zoomed, along the panel's, as the picture's may be off
    // screen then.
    val sampleLoading = showSample && (zoomable || isAnimated(post)) && !sampleLoaded && sampleFailure == null
    Box(Modifier.size(width, height)) {
      LoadBar(sampleLoading, Modifier.align(Alignment.BottomCenter))
    }
    LoadBar(fullLoading, Modifier.align(Alignment.BottomCenter))
    // Failed with nothing to show: the failed state in the image's place. The sample failed over a preview: only Retry
    // on it, so the post stays visible. A failed preview alone waits for the sample, except on Flash, which has none
    // (videos and GIFs show their own player over it). "No preview" only when the preview didn't fail retryably too.
    val nothingShown = inMemory == null && !previewShown && !sampleLoaded
    val failure = when {
      sampleLoaded -> null
      sampleFailure != null -> (if (previewFailure?.retryable == true) previewFailure else sampleFailure).takeIf { nothingShown }
      isFlash(post) || unplayable -> previewFailure
      else -> null
    }
    val retry: () -> Unit = { attempt++ }
    if (failure != null) {
      ImageFailed(noImage = failure == ImageFailure.NoImage, onRetry = retry, modifier = Modifier.size(width, height))
    } else if (sampleFailure?.retryable == true && !sampleLoaded) {
      ImageRetryChip(onRetry = retry)
    } else if (fullFailure?.retryable == true) {
      // The zoomed sample stays; Retry fetches only the full file again.
      ImageRetryChip(onRetry = { fullAttempt++ })
    }
    if (seeks) SeekRipple(seek, Modifier.size(width, height))
    // Fully shown (#174): the sample or GIF has loaded, a video shows its first frame, or Flash shows its preview.
    val fullyShown = when {
      isFlash(post) || unplayable -> previewShown || inMemory != null
      isVideo(post) -> videoShown
      else -> sampleLoaded
    }
    LaunchedEffect(post.id, fullyShown) { if (fullyShown) shown() }
  }
}

/**
 * The phone viewer's handle on the shown post's zoom: whether it's past 1× ([zoomed]), and [zoomOut] back to 1×.
 * Given to one [PostImageView] at a time, the page on screen.
 */
@Stable
class PostZoom {
  var zoomed by mutableStateOf(false)
    internal set

  /** The zoom relative to the fitted image: 1 at 1×, 2.5 after a double-tap. */
  var level by mutableFloatStateOf(1f)
    internal set

  /** Zoomed, with no gesture or zoom animation running: the viewer may resize the image's room then. */
  var settled by mutableStateOf(false)
    internal set

  internal var toMin: (suspend () -> Unit)? = null

  /** Animates the shown post back to 1×. */
  suspend fun zoomOut() {
    toMin?.invoke()
  }
}

/**
 * The sharpest copy of a post's image already decoded in memory: the sample, else the preview, or null. No request:
 * a lookup in Coil's memory cache under the md5-and-variant keys every request uses.
 */
fun decodedInMemory(imageLoader: ImageLoader, md5: String, previewToo: Boolean = true): coil3.Image? {
  val cache = imageLoader.memoryCache ?: return null
  return cache[MemoryCache.Key(PostImage.cacheKey(md5, Variant.Sample))]?.image
    ?: if (previewToo) cache[MemoryCache.Key(PostImage.cacheKey(md5, Variant.Preview))]?.image else null
}

/** The picture's box: [ratio] (width over height) fitted into [maxWidth] × [maxHeight], as the view draws it. */
internal fun fittedBox(maxWidth: Dp, maxHeight: Dp, ratio: Float): Pair<Dp, Dp> =
  if (maxWidth / maxHeight > ratio) maxHeight * ratio to maxHeight else maxWidth to maxWidth / ratio

/** Posts without a separate sample use the file throughout, so there's nothing to switch to. */
internal fun sampleVariant(post: PostDto): Variant =
  if (post.sampleUrl != null || post.url(Variant.File) == null) Variant.Sample else Variant.File

/**
 * What [PostImageView] requests first for [post] in a [windowWidth] × [windowHeight] px window, for the queue's
 * preload (#263): the preview, and for a still image the zoomable sample, the same requests at the same size, so they
 * fill the entries the view reads. A video, a GIF or Flash plays or explains itself over the preview, so only the
 * preview; a post that needs signing in shows neither. Keep it in step with the view's `when` above.
 */
internal fun firstShownRequests(context: PlatformContext, post: PostDto, windowWidth: Int, windowHeight: Int): List<ImageRequest> {
  if (post.redirectToSignup) return emptyList()
  val size = fittedRequestSize(aspectRatio(post), windowWidth, windowHeight)
  val preview = previewRequest(context, post, size)
  return if (isVideo(post) || isAnimated(post) || isFlash(post)) listOf(preview) else listOf(preview, sampleRequest(context, post, size))
}

/**
 * A post just kept from a grid (#263): its sample into the disk cache only, as the grid prefetches previews, so a
 * kept post's picture is on disk before its turn in the queue, and its neighbour's preload ([firstShownRequests])
 * reads it from there. Decoded at 1 px, as the full file's fetch for zoom is, since nothing draws it. Null where the
 * view shows no sample ([firstShownRequests]'s rule): the grid has the preview on disk already.
 */
internal fun keptSampleRequest(context: PlatformContext, post: PostDto): ImageRequest? {
  if (post.redirectToSignup || isVideo(post) || isAnimated(post) || isFlash(post)) return null
  return postImageRequest(context, PostImage.of(post, sampleVariant(post))) {
    memoryCachePolicy(CachePolicy.DISABLED)
    size(1, 1)
  }
}

/** The viewer's preview of [post], under the sample, at [requestSize] ([fittedRequestSize]). */
internal fun previewRequest(context: PlatformContext, post: PostDto, requestSize: CoilSize?): ImageRequest =
  postImageRequest(context, PostImage.of(post, Variant.Preview)) { fittedTo(requestSize) }

/** The viewer's zoomable sample of [post] at [requestSize], as [previewRequest] for the preview. */
internal fun sampleRequest(context: PlatformContext, post: PostDto, requestSize: CoilSize?): ImageRequest =
  postImageRequest(context, PostImage.of(post, sampleVariant(post))) {
    placeholderMemoryCacheKey(PostImage.cacheKey(post.md5, Variant.Preview))
    fittedTo(requestSize)
  }

@Composable
private fun ZoomableSample(
  post: PostDto,
  requestSize: CoilSize?,
  modifier: Modifier,
  onTap: (() -> Unit)?,
  onLongPress: (() -> Unit)?,
  zoom: PostZoom?,
  onLoaded: () -> Unit,
  onFailed: (Throwable) -> Unit,
  fullAttempt: Int,
  onFullLoading: (Boolean) -> Unit,
  onFullFailed: (ImageFailure?) -> Unit,
) {
  val context = LocalPlatformContext.current
  val imageLoader = koinInject<ImageLoader>()
  val zoomState = rememberCoilZoomState()
  var shown by remember(post.id) { mutableStateOf(false) }

  // Touch (spike #18): double-tap goes 1× → 2.5× → 1×, and a pinch up to 2.5² = 6.25×. zoomimage's default scales
  // depend on the image. A pinch stops at 1× and 6.25× instead of rubber-banding past them: below 1× the image shrank
  // away from the copy under it, which showed as a second image behind the pinched one.
  if (platformIsTouch) {
    LaunchedEffect(zoomState) {
      zoomState.zoomable.setScalesCalculator(ScalesCalculator.fixed(TOUCH_DOUBLE_TAP_SCALE))
      zoomState.zoomable.setRubberBandScale(false)
    }
  }
  val zoomed by remember(zoomState) {
    derivedStateOf { zoomState.zoomable.transform.scaleX > zoomState.zoomable.minScale * ZOOMED_FROM }
  }
  if (zoom != null) {
    DisposableEffect(zoom, zoomState) {
      val toMin: suspend () -> Unit = { zoomState.zoomable.scale(zoomState.zoomable.minScale, animated = true) }
      zoom.toMin = toMin
      onDispose {
        if (zoom.toMin === toMin) {
          zoom.toMin = null
          zoom.zoomed = false
          zoom.settled = false
          zoom.level = 1f
        }
      }
    }
    LaunchedEffect(zoom, zoomState) {
      snapshotFlow { zoomed }.collect { zoom.zoomed = it }
    }
    LaunchedEffect(zoom, zoomState) {
      snapshotFlow { zoom.zoomed && zoomState.zoomable.continuousTransformType == 0 }.collect { zoom.settled = it }
    }
    LaunchedEffect(zoom, zoomState) {
      snapshotFlow { zoomState.zoomable.transform.scaleX / zoomState.zoomable.minScale }.collect { zoom.level = it }
    }
  }

  val sampleVariant = sampleVariant(post)
  val sample = remember(post.id, requestSize) { sampleRequest(context, post, requestSize) }

  val reportFullLoading by rememberUpdatedState(onFullLoading)
  val reportFullFailure by rememberUpdatedState(onFullFailed)
  LaunchedEffect(post.id, fullAttempt) {
    val fileWidth = post.fileWidth ?: return@LaunchedEffect
    val fileHeight = post.fileHeight ?: return@LaunchedEffect
    if (sampleVariant == Variant.File || fileWidth <= (post.sampleWidth ?: fileWidth)) return@LaunchedEffect
    snapshotFlow {
      val zoomable = zoomState.zoomable
      zoomable.contentSize.width * zoomable.transform.scaleX > (post.sampleWidth ?: fileWidth)
    }.first { it }
    val full = postImageRequest(context, PostImage.of(post, Variant.File)) {
      memoryCachePolicy(CachePolicy.DISABLED)
      size(1, 1)
    }
    // A failure shows Retry over the zoomed sample instead of leaving it soft for good (#524).
    reportFullLoading(true)
    val result = try {
      imageLoader.execute(full)
    } finally {
      reportFullLoading(false)
    }
    if (result is ErrorResult) return@LaunchedEffect reportFullFailure(imageFailure(result.throwable))
    val path = imageLoader.diskCache?.openSnapshot(PostImage.cacheKey(post.md5, Variant.File))?.use { it.data }
      ?: return@LaunchedEffect reportFullFailure(ImageFailure.Failed)
    zoomState.setSubsamplingImage(
      ImageSource.fromFile(path),
      ImageInfo(fileWidth, fileHeight, post.fileType ?: "image/${post.fileExt ?: "jpeg"}"),
    )
  }

  CoilZoomAsyncImage(
    model = sample,
    contentDescription = null,
    imageLoader = imageLoader,
    zoomState = zoomState,
    // Only while zoomed, where there's something to scroll (#318): zoomimage's bar shows on every change of the
    // visible area and fades out 800 ms later over 300 ms, redrawing each of those frames even at 1×, where it draws
    // nothing. A post shown, or a sheet lifted, ran that fade for no pixel.
    scrollBar = if (zoomed) ScrollBarSpec.Default else null,
    // Invisible until decoded, so the preview underneath shows instead of an empty frame.
    alpha = if (shown) 1f else 0f,
    onState = {
      if (it is AsyncImagePainter.State.Success) {
        shown = true
        onLoaded()
      }
      if (it is AsyncImagePainter.State.Error) onFailed(it.result.throwable)
    },
    onTap = onTap?.let { tap -> { _: Offset -> tap() } },
    // zoomimage's own detector, which tells a long-press from its double-tap and pan, at any zoom.
    onLongPress = onLongPress?.let { press -> { _: Offset -> press() } },
    modifier = modifier,
  )
}

/**
 * The picture's taps where it isn't zoomable (a video, a GIF, Flash, a post that needs signing in): [onTap], and where
 * given a long-press ([onLongPress], after the platform's timeout, never also a tap) and a double-tap ([onDoubleTap],
 * with where and the box's width: a video's seek), which then holds a tap back until it's ruled out. A drag past the
 * touch slop is none of them, so the pager's swipe and the sheet's drag around it keep theirs. The detector restarts
 * only when [key] changes.
 */
internal fun Modifier.pictureTaps(
  key: Any?,
  onTap: () -> Unit,
  onLongPress: (() -> Unit)?,
  onDoubleTap: ((Offset, Float) -> Unit)?,
): Modifier = pointerInput(key) {
  detectTapGestures(
    onDoubleTap = onDoubleTap?.let { double -> { offset: Offset -> double(offset, size.width.toFloat()) } },
    onLongPress = onLongPress?.let { press -> { _: Offset -> press() } },
    onTap = { onTap() },
  )
}

private const val TOUCH_DOUBLE_TAP_SCALE = 2.5f

/** Past 1× by more than this factor counts as zoomed, so a pinch that settles a hair above 1× doesn't. */
private const val ZOOMED_FROM = 1.01f

/**
 * What the app can't play, over the preview, with a note and the post's page on the site, which may still offer it:
 * Flash, which nothing plays any more, and on desktop a WebM video, which AVPlayer can't open (#192).
 */
@Composable
private fun UnplayableFallback(post: PostDto, note: String, messages: Messages = koinInject()) {
  Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)) {
    Column(
      Modifier.padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(note, style = MaterialTheme.typography.titleSmall)
      Button(onClick = { messages.openUrl(postPageUrl(post)) }) {
        Icon(open_in_new, contentDescription = null)
        Text("Open in browser")
      }
    }
  }
}

/** The file's aspect ratio, which the sample shares; the preview's is rounded. */
fun aspectRatio(post: PostDto): Float {
  val w = post.fileWidth ?: post.previewWidth ?: return 1f
  val h = post.fileHeight ?: post.previewHeight ?: return 1f
  return if (w > 0 && h > 0) w.toFloat() / h else 1f
}
