package dev.marzahn.sankaku.images

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #576: the bit depth decides whether Android can decode a preview. */
class AvifHeaderTest {
  private fun resource(name: String) = Buffer().write(javaClass.getResourceAsStream("/$name")!!.readBytes())

  @Test
  fun readsTheBitDepthFromTheAv1cBox() {
    assertEquals(8, AvifHeader.bitDepth(resource("sample.avif")))
    assertEquals(10, AvifHeader.bitDepth(resource("ten-bit.avif")))
    assertEquals(12, AvifHeader.bitDepth(resource("twelve-bit.avif")))
  }

  @Test
  fun leavesTheSourceUnread() {
    val source = resource("twelve-bit.avif")
    val size = source.size

    AvifHeader.bitDepth(source)

    assertEquals(size, source.size)
  }

  @Test
  fun anythingElseHasNoBitDepth() {
    assertNull(AvifHeader.bitDepth(Buffer().write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))))
    assertNull(AvifHeader.bitDepth(Buffer()))
  }
}
