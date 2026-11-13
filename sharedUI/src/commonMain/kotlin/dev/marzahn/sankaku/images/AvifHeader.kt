package dev.marzahn.sankaku.images

import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

/** What an AVIF's header says, read with a peek so the source stays unread. */
internal object AvifHeader {
  private val FTYP = "ftyp".encodeUtf8()
  private val BRANDS = listOf("avif", "avis").map { it.encodeUtf8() }
  private val AV1C = "av1C".encodeUtf8()

  /** The `meta` box, with `av1C` in it, comes before the image data; Sankaku's previews put `av1C` within 250 bytes. */
  private const val HEADER_BYTES = 1024L

  /** An ISO-BMFF `ftyp` box with an AVIF major brand. */
  fun isAvif(source: BufferedSource): Boolean {
    val peek = source.peek()
    return peek.rangeEquals(4, FTYP) && BRANDS.any { peek.rangeEquals(8, it) }
  }

  /**
   * The bit depth of an AVIF's AV1 stream (8, 10 or 12), from its `av1C` box's `high_bitdepth` and `twelve_bit`
   * flags; null for anything that isn't AVIF or whose `av1C` isn't in the first [HEADER_BYTES].
   */
  fun bitDepth(source: BufferedSource): Int? {
    if (!isAvif(source)) return null
    val peek = source.peek()
    peek.request(HEADER_BYTES)
    val header = peek.buffer.snapshot(minOf(peek.buffer.size, HEADER_BYTES).toInt())
    val at = header.indexOf(AV1C)
    // av1C's payload: marker and version, then profile and level, then the flags byte.
    if (at < 0 || at + AV1C.size + 3 > header.size) return null
    val flags = header[at + AV1C.size + 2].toInt()
    val high = flags and 0x40 != 0
    val twelve = flags and 0x20 != 0
    return when {
      !high -> 8
      twelve -> 12
      else -> 10
    }
  }
}

/**
 * The deepest AVIF the platform decodes correctly. Android's decoder (the Android 17 emulator's and a Samsung's on 16)
 * turns a 12-bit AVIF into green with vertical stripes, in any bitmap config, so Android says 10 (#576); macOS ImageIO
 * reads 12-bit.
 */
internal expect val platformMaxAvifBitDepth: Int
