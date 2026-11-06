package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.MovieDrawable
import coil3.gif.repeatCount
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import org.koin.compose.koinInject

/** Coil's GIF decoder: the platform's `AnimatedImageDrawable`. */
actual val platformAnimatesGifs: Boolean = true

/**
 * `AnimatedImageDrawable` decodes each frame as it's drawn, so a long GIF never sits in memory whole. The bytes are
 * already in hand, so neither of Coil's caches is used. Frames are decoded at the size laid out, again whenever that
 * changes (the viewer's details panel opening or closing), so the GIF stays sharp.
 */
@Composable
actual fun AnimatedImage(bytes: ByteArray, modifier: Modifier, onShown: () -> Unit) {
  val context = LocalPlatformContext.current
  val imageLoader = koinInject<ImageLoader>()
  val shown by rememberUpdatedState(onShown)
  BoxWithConstraints(modifier) {
    // Unbounded, the GIF decodes at its own size.
    val size = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
      Size(constraints.maxWidth, constraints.maxHeight)
    } else {
      Size.ORIGINAL
    }
    val request = remember(bytes, size) {
      ImageRequest.Builder(context)
        .data(bytes)
        .size(size)
        .decoderFactory(AnimatedImageDecoder.Factory())
        .repeatCount(MovieDrawable.REPEAT_INFINITE)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()
    }
    AsyncImage(
      model = request,
      imageLoader = imageLoader,
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize(),
      onSuccess = { shown() },
    )
  }
}
