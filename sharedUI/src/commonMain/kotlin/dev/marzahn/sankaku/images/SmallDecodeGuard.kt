package dev.marzahn.sankaku.images

import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size

/**
 * Keeps small decodes of a post's image out of the memory cache. Every request for one image shares one memory key
 * ([PostImage.cacheKey], whatever the size), and [dev.marzahn.sankaku.features.post.decodedInMemory] serves that entry
 * at any size without checking it. So a decode for a tiny box would stand in for the image everywhere: on a phone the
 * viewer's image room is 1 px tall while the details sheet is full, and its 1 px sample, stretched, was the uniform
 * grey cell and viewer of #119. Such a request still reads the cache; it only doesn't write.
 */
object SmallDecodeGuard : Interceptor {
  override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
    val request = chain.request
    if (request.data !is PostImage || isShareableSize(chain.size) || !request.memoryCachePolicy.writeEnabled) {
      return chain.proceed()
    }
    val policy = if (request.memoryCachePolicy.readEnabled) CachePolicy.READ_ONLY else CachePolicy.DISABLED
    return chain.withRequest(request.newBuilder().memoryCachePolicy(policy).build()).proceed()
  }
}

/**
 * Whether a decode at [size] may stand in for its image in the memory cache: its longer side at least
 * [MIN_SHARED_PX] and its shorter at least [MIN_SHARED_SIDE_PX], an undefined side counting as unlimited (the original
 * size). Every grid cell (80 dp wide and up on a phone, #468; a wide image's cell too) passes at 2× density and up;
 * the sheet's 40 dp thumbnail and a collapsed image room (1 px tall) don't.
 */
internal fun isShareableSize(size: Size): Boolean {
  val sides = listOf(size.width, size.height).map { (it as? Dimension.Pixels)?.px ?: Int.MAX_VALUE }
  return sides.max() >= MIN_SHARED_PX && sides.min() >= MIN_SHARED_SIDE_PX
}

internal const val MIN_SHARED_PX = 160
internal const val MIN_SHARED_SIDE_PX = 16
