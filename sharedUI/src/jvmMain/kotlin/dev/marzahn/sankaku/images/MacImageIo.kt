package dev.marzahn.sankaku.images

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo

/**
 * Decodes images through macOS ImageIO. Skia on desktop has no AVIF codec, and every preview is AVIF, so v1 (macOS
 * only) decodes them here. Returns null when ImageIO can't read the data or isn't available.
 */
internal object MacImageIo {
  @Suppress("FunctionName")
  private interface CoreFoundation : Library {
    fun CFDataCreate(allocator: Pointer?, bytes: ByteArray, length: Long): Pointer?
    fun CFRelease(ref: Pointer)
  }

  @Suppress("FunctionName")
  private interface ImageIo : Library {
    fun CGImageSourceCreateWithData(data: Pointer, options: Pointer?): Pointer?
    fun CGImageSourceCreateImageAtIndex(source: Pointer, index: Long, options: Pointer?): Pointer?
  }

  @Suppress("FunctionName")
  private interface CoreGraphics : Library {
    fun CGImageGetWidth(image: Pointer): Long
    fun CGImageGetHeight(image: Pointer): Long
    fun CGColorSpaceCreateDeviceRGB(): Pointer
    fun CGColorSpaceRelease(space: Pointer)
    fun CGBitmapContextCreate(
      data: Pointer, width: Long, height: Long, bitsPerComponent: Long, bytesPerRow: Long, space: Pointer, bitmapInfo: Int,
    ): Pointer?
    fun CGContextDrawImage(context: Pointer, rect: CGRect.ByValue, image: Pointer)
    fun CGContextRelease(context: Pointer)
    fun CGImageRelease(image: Pointer)
  }

  @Structure.FieldOrder("x", "y", "width", "height")
  open class CGRect(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
  ) : Structure() {
    class ByValue(width: Double, height: Double) : CGRect(0.0, 0.0, width, height), Structure.ByValue
  }

  /** RGBA, premultiplied, byte order matching Skia's `RGBA_8888`. */
  private const val PREMULTIPLIED_LAST_BIG_ENDIAN = 1 or (4 shl 12)

  private val libraries by lazy {
    runCatching {
      Triple(
        Native.load("CoreFoundation", CoreFoundation::class.java),
        Native.load("ImageIO", ImageIo::class.java),
        Native.load("CoreGraphics", CoreGraphics::class.java),
      )
    }.getOrNull()
  }

  fun decode(bytes: ByteArray): Bitmap? {
    val (cf, io, cg) = libraries ?: return null
    val data = cf.CFDataCreate(null, bytes, bytes.size.toLong()) ?: return null
    try {
      val source = io.CGImageSourceCreateWithData(data, null) ?: return null
      try {
        val image = io.CGImageSourceCreateImageAtIndex(source, 0, null) ?: return null
        try {
          return render(cg, image)
        } finally {
          cg.CGImageRelease(image)
        }
      } finally {
        cf.CFRelease(source)
      }
    } finally {
      cf.CFRelease(data)
    }
  }

  private fun render(cg: CoreGraphics, image: Pointer): Bitmap? {
    val width = cg.CGImageGetWidth(image)
    val height = cg.CGImageGetHeight(image)
    if (width <= 0 || height <= 0) return null
    val rowBytes = width * 4
    val pixels = Memory(rowBytes * height)
    val space = cg.CGColorSpaceCreateDeviceRGB()
    try {
      val context = cg.CGBitmapContextCreate(pixels, width, height, 8, rowBytes, space, PREMULTIPLIED_LAST_BIG_ENDIAN)
        ?: return null
      try {
        cg.CGContextDrawImage(context, CGRect.ByValue(width.toDouble(), height.toDouble()), image)
      } finally {
        cg.CGContextRelease(context)
      }
    } finally {
      cg.CGColorSpaceRelease(space)
    }
    val info = ImageInfo(width.toInt(), height.toInt(), ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    // One copy into a Skia bitmap; going through an Image would copy twice more.
    return Bitmap().takeIf { it.allocPixels(info) }?.apply {
      installPixels(info, pixels.getByteArray(0, (rowBytes * height).toInt()), rowBytes.toInt())
      setImmutable()
    }
  }
}
