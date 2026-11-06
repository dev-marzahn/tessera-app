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
import coil3.request.SuccessResult
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage
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
 * memory), and [AnimatedImage] plays the cached file. Until the first frame, the preview underneath shows.
 */
@Composable
fun AnimatedSample(post: PostDto, modifier: Modifier, onShown: () -> Unit) {
  val context = LocalPlatformContext.current
  val imageLoader = koinInject<ImageLoader>()
  var bytes by remember(post.id) { mutableStateOf<ByteArray?>(null) }
  LaunchedEffect(post.id) {
    val request = postImageRequest(context, PostImage.of(post, Variant.Sample)) {
      memoryCachePolicy(CachePolicy.DISABLED)
      size(1, 1)
    }
    if (imageLoader.execute(request) !is SuccessResult) return@LaunchedEffect
    val diskCache = imageLoader.diskCache ?: return@LaunchedEffect
    bytes = withContext(Dispatchers.Default) {
      diskCache.openSnapshot(PostImage.cacheKey(post.md5, Variant.Sample))?.use { snapshot ->
        diskCache.fileSystem.read(snapshot.data) { readByteArray() }
      }
    }
  }
  bytes?.let { AnimatedImage(it, modifier, onShown) }
}
