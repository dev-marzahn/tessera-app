package dev.marzahn.sankaku.images

import coil3.compose.AsyncImagePainter
import kotlinx.io.IOException

/** Why a post's image isn't showing, for the cell's or the viewer's failed state. */
enum class ImageFailure(val retryable: Boolean) {
  /** The post has no URL for the image even after a refetch: nothing to retry. */
  NoImage(false),

  /**
   * The image's host couldn't be reached (a connection error or timeout): Retry may work, and the network coming back
   * retries it by itself (#128).
   */
  Unreachable(true),

  /** Anything else (a refusal that survived the refetch, a decode error): only a Retry tap tries again. */
  Failed(true),
}

/**
 * [error] as the user sees it. A retry needs no refetch of its own: [PostImageFetcher] already asks for the post again
 * when its URL is missing, expired or refused.
 */
fun imageFailure(error: Throwable): ImageFailure {
  val causes = generateSequence(error) { it.cause }
  return when {
    causes.any { it is NoImageUrlException } -> ImageFailure.NoImage
    causes.any { it is IOException } -> ImageFailure.Unreachable
    else -> ImageFailure.Failed
  }
}

/** A painter state's failure, or null while it loads or once it has an image. */
fun AsyncImagePainter.State.failure(): ImageFailure? =
  (this as? AsyncImagePainter.State.Error)?.result?.throwable?.let(::imageFailure)
