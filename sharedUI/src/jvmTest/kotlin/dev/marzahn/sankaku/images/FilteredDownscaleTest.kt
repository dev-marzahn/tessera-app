package dev.marzahn.sankaku.images

import coil3.BitmapImage
import coil3.PlatformContext
import coil3.decode.ImageSource
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FilteredDownscaleTest {
  private fun options(size: Size, scale: Scale = Scale.FIT, precision: Precision = Precision.INEXACT) =
    Options(PlatformContext.INSTANCE, size = size, scale = scale, precision = precision)

  /** A [side] px square of alternating black and white pixels: the finest screentone there is. */
  private fun checkerboardPng(side: Int): ByteArray {
    val bitmap = Bitmap()
    bitmap.allocN32Pixels(side, side)
    for (y in 0 until side) for (x in 0 until side) {
      bitmap.erase(if ((x + y) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), IRect.makeXYWH(x, y, 1, 1))
    }
    return Image.makeFromBitmap(bitmap).use { assertNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes }
  }

  @Test
  fun aShrunkScreentoneIsAnEvenGrey() = runTest {
    val source = ImageSource(Buffer().write(checkerboardPng(300)), FileSystem.SYSTEM)
    val result = assertNotNull(FilteredSkiaDecoder(source, options(Size(70, 70))).decode())

    val bitmap = assertIs<BitmapImage>(result.image).bitmap
    assertEquals(70, bitmap.width)
    assertTrue(result.isSampled)
    // Nearest neighbour keeps every n-th pixel: patches of solid black or white. Averaging gives mid grey throughout.
    for (y in 0 until 70) for (x in 0 until 70) {
      val red = (bitmap.getColor(x, y) shr 16) and 0xFF
      assertTrue(red in 120..136, "pixel ($x, $y) is $red, not grey")
    }
  }

  @Test
  fun unreadableDataIsLeftForTheNextDecoder() = runTest {
    val source = ImageSource(Buffer().write(byteArrayOf(1, 2, 3)), FileSystem.SYSTEM)

    assertEquals(null, FilteredSkiaDecoder(source, options(Size(70, 70))).decode())
    assertEquals(3, source.source().readByteArray().size)
  }

  @Test
  fun fitsIntoTheRequestWithoutGrowing() {
    assertEquals(500 to 250, decodeSize(1000, 500, options(Size(800, 250))))
    assertEquals(1000 to 500, decodeSize(1000, 500, options(Size(2000, 2000))))
    assertEquals(1000 to 500, decodeSize(1000, 500, options(Size.ORIGINAL)))
    assertEquals(300 to 150, decodeSize(1000, 500, options(Size(Dimension.Undefined, Dimension(150)))))
  }

  @Test
  fun fillCoversTheRequest() {
    assertEquals(800 to 400, decodeSize(1000, 500, options(Size(800, 250), Scale.FILL)))
  }

  @Test
  fun exactGrowsToTheRequest() {
    assertEquals(2000 to 1000, decodeSize(1000, 500, options(Size(2000, 2000), precision = Precision.EXACT)))
  }
}
