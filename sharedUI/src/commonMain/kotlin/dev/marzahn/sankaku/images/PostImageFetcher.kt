package dev.marzahn.sankaku.images

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkFetcher
import coil3.request.Options
import coil3.toUri
import dev.marzahn.sankaku.api.ImageRefusedException
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
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
 */
class PostImageFetcher(
  private val image: PostImage,
  private val options: Options,
  private val imageLoader: ImageLoader,
  private val network: NetworkFetcher.Factory,
  private val repository: PostsRepository,
  private val store: PostStateStore,
  private val clock: Clock,
) : Fetcher {
  override suspend fun fetch(): FetchResult? {
    cached()?.let { return it }
    if (!options.networkCachePolicy.readEnabled) throw NotCachedException(image.variant.name)

    val known = store.overrides.value[image.postId]?.urls?.forVariant(image.variant) ?: image.url
    if (known == null || !isSignedUrlUsable(known, clock.now())) return download(freshUrl())

    return try {
      download(known)
    } catch (e: Exception) {
      if (!e.isRefusal()) throw e
      download(freshUrl())
    }
  }

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

  private suspend fun freshUrl(): String =
    repository.refetch(image.postId).url(image.variant) ?: throw NoImageUrlException(image.variant.name)

  private suspend fun download(url: String): FetchResult? =
    network.create(url.toUri(), options, imageLoader)?.fetch()

  private fun Throwable.isRefusal(): Boolean = generateSequence(this) { it.cause }.any {
    it is ImageRefusedException || (it is HttpException && it.response.code in REFUSED_STATUSES)
  }

  class Factory(
    private val network: NetworkFetcher.Factory,
    private val repository: PostsRepository,
    private val store: PostStateStore,
    private val clock: Clock,
  ) : Fetcher.Factory<PostImage> {
    override fun create(data: PostImage, options: Options, imageLoader: ImageLoader): Fetcher =
      PostImageFetcher(data, options, imageLoader, network, repository, store, clock)
  }

  private companion object {
    val REFUSED_STATUSES = setOf(401, 403, 404, 410)
  }
}
