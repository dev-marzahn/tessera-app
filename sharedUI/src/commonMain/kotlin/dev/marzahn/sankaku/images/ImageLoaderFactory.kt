package dev.marzahn.sankaku.images

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ConnectivityChecker
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import io.ktor.client.HttpClient
import okio.Path
import kotlin.time.Clock

/**
 * Decoders ahead of the platform's defaults (desktop: AVIF, which Skia can't read, and Skia's formats shrunk with
 * filtering, which Coil's Skia decoder doesn't do).
 */
internal expect fun platformImageDecoders(): List<Decoder.Factory>

private const val MEMORY_CACHE_BYTES = 256L * 1024 * 1024

/** Until Settings (M4) makes it adjustable. */
private const val DISK_CACHE_BYTES = 1024L * 1024 * 1024

/**
 * One loader for the whole app, through the main client, so images get its User-Agent, timeouts and rate limiter.
 * One disk cache holds previews, samples and full files (zoomimage re-reads full files from it for zoomed regions).
 */
@OptIn(ExperimentalCoilApi::class) // serviceLoaderEnabled
fun createImageLoader(
  context: PlatformContext,
  mainClient: HttpClient,
  repository: PostsRepository,
  store: PostStateStore,
  clock: Clock,
  cacheDir: Path,
  maxAvifBitDepth: Int = platformMaxAvifBitDepth,
): ImageLoader {
  // Always to the network, even when Android says it's offline: Coil's own check failed those loads with a "504
  // only-if-cached" that never reached the client, so the app wasn't marked offline and nothing retried them when the
  // network came back (#128, #524). Through the client they fail to connect, which the retry on reconnect catches.
  val network = KtorNetworkFetcherFactory(
    httpClient = { mainClient },
    cacheStrategy = { ImmutableCacheStrategy },
    connectivityChecker = { ConnectivityChecker.ONLINE },
  )
  return ImageLoader.Builder(context)
    .serviceLoaderEnabled(false)
    .components {
      add(SmallDecodeGuard)
      add(PostImageKeyer)
      add(PostImageFetcher.Factory(network, repository, store, clock, maxAvifBitDepth))
      add(network)
      platformImageDecoders().forEach(::add)
    }
    .memoryCache { MemoryCache.Builder().maxSizeBytes(MEMORY_CACHE_BYTES).build() }
    .diskCache { DiskCache.Builder().directory(cacheDir).maxSizeBytes(DISK_CACHE_BYTES).build() }
    .build()
}
