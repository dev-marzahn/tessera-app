package dev.marzahn.sankaku.images

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.Bitmap

/** Decodes AVIF (every preview) through macOS ImageIO, since Skia can't. Other formats fall through to Skia. */
class AvifDecoder(private val source: ImageSource) : Decoder {
  override suspend fun decode(): DecodeResult? {
    val image = MacImageIo.decode(source.source().readByteArray()) ?: return null
    return DecodeResult(image.asImage(), isSampled = false)
  }

  object Factory : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
      if (isAvif(result.source.source())) AvifDecoder(result.source) else null
  }

  internal companion object {
    private val FTYP = "ftyp".encodeUtf8()
    private val BRANDS = listOf("avif", "avis").map { it.encodeUtf8() }

    /** An ISO-BMFF `ftyp` box with an AVIF major brand. Only peeks, so the source stays unread. */
    fun isAvif(source: BufferedSource): Boolean {
      val peek = source.peek()
      return peek.rangeEquals(4, FTYP) && BRANDS.any { peek.rangeEquals(8, it) }
    }
  }
}

internal actual fun platformImageDecoders(): List<Decoder.Factory> = listOf(AvifDecoder.Factory)
