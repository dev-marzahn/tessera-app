package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import androidx.compose.runtime.rememberUpdatedState
import coil3.request.ErrorResult
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.ImageFailure
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.imageFailure
import dev.marzahn.sankaku.images.postImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Whether the platform plays animated GIFs itself ([AnimatedImage]); elsewhere Coil shows the first frame. */
expect val platformAnimatesGifs: Boolean

/**
 * Plays an animated image from its encoded [bytes], fitted to [modifier]'s size, looping. [onShown] runs once the
 * first frame is drawn. Frames are decoded one at a time, in order, so a long GIF never sits in memory whole.
 */
@Composable
expect fun AnimatedImage(bytes: ByteArray, modifier: Modifier, onShown: () -> Unit)

/** A GIF post the viewer animates: `animated_gif` posts are `image/gif` with a GIF sample (discovery step 14). */
fun isAnimated(post: PostDto): Boolean = platformAnimatesGifs && post.fileType == "image/gif"

/**
 * [post]'s sample, animated. Coil fetches it into the disk cache (its own decode is a 1×1 throwaway, never kept in
 * memory), and [AnimatedImage] plays the cached file. Until the first frame, the preview underneath shows. A failed
 * fetch goes to [onFailed], and a new [attempt] (Retry, the network back) fetches it again (#524); one that has
 * loaded keeps playing.
 */
@Composable
fun AnimatedSample(post: PostDto, modifier: Modifier, onShown: () -> Unit, attempt: Int = 0, onFailed: (ImageFailure) -> Unit = {}) {
  val context = LocalPlatformContext.current
  val imageLoader = koinInject<ImageLoader>()
  var bytes by remember(post.id) { mutableStateOf<ByteArray?>(null) }
  val reportFailure by rememberUpdatedState(onFailed)
  LaunchedEffect(post.id, attempt) {
    if (bytes != null) return@LaunchedEffect
    val request = postImageRequest(context, PostImage.of(post, Variant.Sample)) {
      memoryCachePolicy(CachePolicy.DISABLED)
      size(1, 1)
    }
    val result = imageLoader.execute(request)
    if (result is ErrorResult) return@LaunchedEffect reportFailure(imageFailure(result.throwable))
    val diskCache = imageLoader.diskCache ?: return@LaunchedEffect reportFailure(ImageFailure.Failed)
    bytes = withContext(Dispatchers.Default) {
      diskCache.openSnapshot(PostImage.cacheKey(post.md5, Variant.Sample))?.use { snapshot ->
        diskCache.fileSystem.read(snapshot.data) { readByteArray() }
      }
    }
    if (bytes == null) reportFailure(ImageFailure.Failed)
  }
  bytes?.let { AnimatedImage(it, modifier, onShown) }
}
