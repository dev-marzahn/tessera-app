package dev.marzahn.sankaku.images

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.pxOrElse
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes what Skia reads (JPEG, PNG, WebP, the first frame of a GIF) in place of Coil's own Skia decoder, which
 * shrinks an image to its request size by nearest neighbour. That kept every n-th pixel of a manga page's screentone,
 * whose dots are about as fine as a pixel, and turned an even grey into a patchwork of chequered squares. AVIF goes
 * to [AvifDecoder]; data Skia can't read falls through to the next decoder, which gets the source unread: this only
 * reads a peek of it.
 */
class FilteredSkiaDecoder(private val source: ImageSource, private val options: Options) : Decoder {
  override suspend fun decode(): DecodeResult? {
    val bytes = source.source().peek().readByteArray()
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    return image.use { fittedResult(it, options) }
  }

  object Factory : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
      FilteredSkiaDecoder(result.source, options)
  }
}

/** [image] at the size [options] ask for, as Coil sizes a decode, resized with [resized]. */
internal fun fittedResult(image: Image, options: Options): DecodeResult {
  val (width, height) = decodeSize(image.width, image.height, options)
  return DecodeResult(resized(image, width, height).asImage(), isSampled = width < image.width || height < image.height)
}

/** [bitmap] at the size [options] ask for; [bitmap] itself when that is its own size. */
internal fun fittedResult(bitmap: Bitmap, options: Options): DecodeResult {
  if (decodeSize(bitmap.width, bitmap.height, options) == bitmap.width to bitmap.height) {
    return DecodeResult(bitmap.asImage(), isSampled = false)
  }
  val result = Image.makeFromBitmap(bitmap).use { fittedResult(it, options) }
  bitmap.close()
  return result
}

/**
 * The size Coil decodes a [srcWidth] × [srcHeight] image at for [options]: fitted into (or, for [Scale.FILL],
 * filling) the request size, an undefined side not limiting it, and never larger than the image unless the request
 * is [Precision.EXACT].
 */
internal fun decodeSize(srcWidth: Int, srcHeight: Int, options: Options): Pair<Int, Int> {
  val xScale = options.size.width.pxOrElse { -1 }.takeIf { it > 0 }?.let { it.toDouble() / srcWidth }
  val yScale = options.size.height.pxOrElse { -1 }.takeIf { it > 0 }?.let { it.toDouble() / srcHeight }
  val scale = when {
    xScale == null && yScale == null -> 1.0
    xScale == null -> yScale!!
    yScale == null -> xScale
    options.scale == Scale.FIT -> min(xScale, yScale)
    else -> max(xScale, yScale)
  }
  if (scale == 1.0 || scale > 1.0 && options.precision != Precision.EXACT) return srcWidth to srcHeight
  return (srcWidth * scale).roundToInt().coerceAtLeast(1) to (srcHeight * scale).roundToInt().coerceAtLeast(1)
}

/**
 * [image] drawn at [width] × [height]. Shrinking goes through a mipmap, so each pixel averages the source pixels it
 * covers and fine patterns shrink to their tone instead of aliasing.
 */
internal fun resized(image: Image, width: Int, height: Int): Bitmap {
  val bitmap = Bitmap()
  check(bitmap.allocN32Pixels(width, height)) { "Couldn't allocate a $width × $height bitmap" }
  val sampling = when {
    width == image.width && height == image.height -> FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
    width < image.width || height < image.height -> FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
    else -> FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
  }
  Canvas(bitmap).use { canvas ->
    val src = Rect.makeWH(image.width.toFloat(), image.height.toFloat())
    // Not strict: a strict source rect makes Skia skip the mipmap.
    canvas.drawImageRect(image, src, Rect.makeWH(width.toFloat(), height.toFloat()), sampling, null, false)
  }
  bitmap.setImmutable()
  return bitmap
}
