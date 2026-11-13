package dev.marzahn.sankaku.images

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options

/**
 * Decodes AVIF (every preview) through macOS ImageIO, since Skia can't, sized as [FilteredSkiaDecoder] sizes the
 * formats Skia reads.
 */
class AvifDecoder(private val source: ImageSource, private val options: Options) : Decoder {
  override suspend fun decode(): DecodeResult? {
    val bitmap = MacImageIo.decode(source.source().readByteArray()) ?: return null
    return fittedResult(bitmap, options)
  }

  object Factory : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
      if (AvifHeader.isAvif(result.source.source())) AvifDecoder(result.source, options) else null
  }
}

internal actual fun platformImageDecoders(): List<Decoder.Factory> = listOf(AvifDecoder.Factory, FilteredSkiaDecoder.Factory)

internal actual val platformMaxAvifBitDepth: Int = 12
