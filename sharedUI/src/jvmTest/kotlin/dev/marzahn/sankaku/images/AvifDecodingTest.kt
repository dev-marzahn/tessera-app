package dev.marzahn.sankaku.images

import org.jetbrains.skia.Codec
import org.junit.Assume.assumeTrue
import org.jetbrains.skia.Data
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Previews are AVIF (see `docs/api.md`). Coil decodes through Skia on desktop, which can't read them. */
class AvifDecodingTest {
  private val avif = javaClass.getResourceAsStream("/sample.avif")!!.readBytes()

  // ImageIO is macOS only; CI builds on Linux, where the decoder tests are reported as skipped.
  private val onMac = System.getProperty("os.name").startsWith("Mac")

  @Test
  fun skiaHasNoAvifCodec() {
    assertFailsWith<IllegalArgumentException> { Codec.makeFromData(Data.makeFromBytes(avif)) }
  }

  @Test
  fun macImageIoDecodesAvif() {
    assumeTrue(onMac)
    val image = assertNotNull(MacImageIo.decode(avif))

    assertEquals(64, image.width)
    assertEquals(64, image.height)
    val pixels = assertNotNull(image.readPixels())
    assertTrue(pixels.any { it != 0.toByte() }, "decoded image is blank")
  }

  @Test
  fun garbageReturnsNull() {
    assumeTrue(onMac)
    assertEquals(null, MacImageIo.decode(byteArrayOf(1, 2, 3)))
  }
}
