package dev.marzahn.sankaku.images

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkFetcher
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.toUri
import dev.marzahn.sankaku.api.ImageRefusedException
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.data.isSignedUrlUsable
import dev.marzahn.sankaku.data.url
import kotlin.time.Clock

/** The post has no URL for this variant, even after a refetch (for example a video's sample). */
class NoImageUrlException(variant: String) : Exception("No $variant URL")

/** A cache-only request ([cachedPostImageRequest]) for an image that isn't in the disk cache. */
class NotCachedException(variant: String) : Exception("$variant not cached")

/**
 * Loads a [PostImage] from the disk cache, or from the network through Coil's Ktor fetcher:
 * - a cached image is served without a request, whatever its URL
 * - with the network disabled (a cache-only request), a miss fails without refetching the post
 * - a missing or expired URL refetches the post first, because an expired `s.` URL hangs instead of failing
 * - a refused URL refetches the post and retries once; a 429 isn't a refusal
 * - a preview the platform can't decode (a 12-bit AVIF on Android, #576) is replaced by the post's sample, downloaded
 *   under the preview's cache key, so the next load finds the sample on disk; a post without a still sample of its
 *   own fails as no image rather than drawing garbage
 */
class PostImageFetcher(
  private val image: PostImage,
  private val options: Options,
  private val imageLoader: ImageLoader,
  private val network: NetworkFetcher.Factory,
  private val repository: PostsRepository,
  private val store: PostStateStore,
  private val clock: Clock,
  private val maxAvifBitDepth: Int,
) : Fetcher {
  override suspend fun fetch(): FetchResult? {
    val result = cached() ?: fromNetwork(image.variant, image.url, options)
    if (image.variant != Variant.Preview || result !is SourceFetchResult || !isUndecodable(result)) return result
    result.source.close()
    // A cache-only request leaves the disk as it is: the swap happens on the next load that may use the network.
    if (!options.networkCachePolicy.readEnabled) throw NotCachedException(image.variant.name)

    val post = repository.cachedPost(image.postId) ?: repository.refetch(image.postId)
    if (!post.hasStillSample()) throw NoImageUrlException(Variant.Sample.name)
    // Write only: Coil's network fetcher would read the cached AVIF again under the same key. The AVIF stays until
    // the sample replaces it, so a failed sample doesn't download the preview again next time.
    return fromNetwork(Variant.Sample, post.url(Variant.Sample), options.copy(diskCachePolicy = CachePolicy.WRITE_ONLY))
  }

  /** [variant]'s image from the network, from [known] unless the store has a newer URL, under the request's key. */
  private suspend fun fromNetwork(variant: Variant, known: String?, options: Options): FetchResult? {
    if (!options.networkCachePolicy.readEnabled) throw NotCachedException(image.variant.name)

    val url = store.overrides.value[image.postId]?.urls?.forVariant(variant) ?: known
    if (url == null || !isSignedUrlUsable(url, clock.now())) return download(freshUrl(variant), options)

    return try {
      download(url, options)
    } catch (e: Exception) {
      if (!e.isRefusal()) throw e
      download(freshUrl(variant), options)
    }
  }

  /** An AVIF deeper than the platform decodes correctly: Android draws a 12-bit one as green stripes. */
  private fun isUndecodable(result: SourceFetchResult): Boolean {
    val depth = AvifHeader.bitDepth(result.source.source()) ?: return false
    return depth > maxAvifBitDepth
  }

  /**
   * A sample that can stand in for the preview: a still picture of its own, not a video's mp4, a GIF, or the full
   * file (a book page's `sample_url` can be, `docs/api.md`), which could be a 9 MB PNG.
   */
  private fun PostDto.hasStillSample(): Boolean =
    fileType in STILL_TYPES && sampleUrl != null && sampleUrl != fileUrl

  private fun cached(): FetchResult? {
    if (!options.diskCachePolicy.readEnabled) return null
    val cache = imageLoader.diskCache ?: return null
    val snapshot = cache.openSnapshot(image.cacheKey) ?: return null
    return SourceFetchResult(
      source = ImageSource(snapshot.data, cache.fileSystem, image.cacheKey, snapshot),
      mimeType = null,
      dataSource = DataSource.DISK,
    )
  }

  private suspend fun freshUrl(variant: Variant): String =
    repository.refetch(image.postId).url(variant) ?: throw NoImageUrlException(variant.name)

  private suspend fun download(url: String, options: Options): FetchResult? =
    network.create(url.toUri(), options, imageLoader)?.fetch()

  private fun Throwable.isRefusal(): Boolean = generateSequence(this) { it.cause }.any {
    it is ImageRefusedException || (it is HttpException && it.response.code in REFUSED_STATUSES)
  }

  class Factory(
    private val network: NetworkFetcher.Factory,
    private val repository: PostsRepository,
    private val store: PostStateStore,
    private val clock: Clock,
    private val maxAvifBitDepth: Int,
  ) : Fetcher.Factory<PostImage> {
    override fun create(data: PostImage, options: Options, imageLoader: ImageLoader): Fetcher =
      PostImageFetcher(data, options, imageLoader, network, repository, store, clock, maxAvifBitDepth)
  }

  private companion object {
    val REFUSED_STATUSES = setOf(401, 403, 404, 410)
    val STILL_TYPES = setOf("image/png", "image/jpeg", "image/webp")
  }
}
