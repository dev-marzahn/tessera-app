package dev.marzahn.sankaku.images

import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import kotlin.math.roundToInt

/**
 * Builds every request for a [PostImage]. Coil's network fetcher reads and writes the disk cache under
 * `diskCacheKey`, so setting it here is what makes a cached image survive a URL change; a Keyer alone only sets the
 * memory key.
 */
fun postImageRequest(context: PlatformContext, image: PostImage, configure: ImageRequest.Builder.() -> Unit = {}) =
  ImageRequest.Builder(context)
    .data(image)
    .diskCacheKey(image.cacheKey)
    .memoryCacheKey(image.cacheKey)
    .apply(configure)
    .build()

/**
 * From the memory or disk cache only: a miss fails with [NotCachedException] instead of asking the API or the image
 * host, for things that must cost no request (tab previews, tab icons).
 */
fun cachedPostImageRequest(context: PlatformContext, image: PostImage) =
  postImageRequest(context, image) { networkCachePolicy(CachePolicy.DISABLED) }

/**
 * A post image's request size for the viewer: the image fitted into the window ([windowWidth] × [windowHeight] px),
 * whatever room the layout gives it at the moment. A request sized by its layout keeps the first size it sees, and the
 * phone's details sheet can squeeze that room to 1 px (#119). Null for a window not measured yet: the layout's size.
 */
fun fittedRequestSize(ratio: Float, windowWidth: Int, windowHeight: Int): Size? {
  if (windowWidth <= 0 || windowHeight <= 0 || ratio <= 0f) return null
  val (width, height) = if (windowWidth.toFloat() / windowHeight > ratio) {
    (windowHeight * ratio).roundToInt() to windowHeight
  } else {
    windowWidth to (windowWidth / ratio).roundToInt()
  }
  return Size(width.coerceAtLeast(1), height.coerceAtLeast(1))
}

/** Decodes at most at [size] (never scaled up past the image's own size); null leaves the layout's size. */
fun ImageRequest.Builder.fittedTo(size: Size?): ImageRequest.Builder =
  if (size == null) this else size(size).precision(Precision.INEXACT)
