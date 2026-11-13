package dev.marzahn.sankaku.images

import coil3.decode.Decoder

/** None: `minSdk` 31 decodes AVIF natively (`docs/adr/0002-minsdk-31.md`). GIFs animate through `AnimatedImage`. */
internal actual fun platformImageDecoders(): List<Decoder.Factory> = emptyList()

internal actual val platformMaxAvifBitDepth: Int = 10
