package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AnimatedImageDisposeTest {
  /**
   * Flipping tabs quickly disposes GIFs while a frame is still decoding off the main thread. The codec must outlive
   * that decode: freeing it under the decode crashed the whole JVM in `SkWuffsCodec::decodeFrame`.
   */
  @Test
  fun disposingMidDecodeDoesNotFreeTheCodecUnderIt() = runComposeUiTest {
    val gif = gif(width = 1500, height = 1500, frames = 2)
    var shown by mutableStateOf(false)
    setContent {
      if (shown) AnimatedImage(gif, Modifier.size(100.dp)) {}
    }
    repeat(150) {
      shown = true
      waitForIdle()
      shown = false
      waitForIdle()
    }
  }
}

/** A GIF of [frames] full frames with a 256-colour table, LZW-coded as 9-bit literals with a clear every 250. */
private fun gif(width: Int, height: Int, frames: Int): ByteArray {
  val out = ByteArrayOutputStream()
  fun short(value: Int) = out.apply { write(value and 0xFF); write(value shr 8 and 0xFF) }
  out.write("GIF89a".toByteArray())
  short(width); short(height)
  out.write(0xF7); out.write(0); out.write(0)
  repeat(256) { out.write(it); out.write(255 - it); out.write(it / 2) }
  repeat(frames) { frame ->
    out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
    out.write(0x2C); short(0); short(0); short(width); short(height); out.write(0)
    out.write(8)
    val codes = ByteArrayOutputStream()
    var bits = 0L
    var count = 0
    fun code(value: Int) {
      bits = bits or (value.toLong() shl count)
      count += 9
      while (count >= 8) { codes.write((bits and 0xFF).toInt()); bits = bits shr 8; count -= 8 }
    }
    code(256)
    for (pixel in 0 until width * height) {
      if (pixel > 0 && pixel % 250 == 0) code(256)
      code((pixel % width + pixel / width + frame * 40) and 0xFF)
    }
    code(257)
    if (count > 0) codes.write((bits and 0xFF).toInt())
    codes.toByteArray().asList().chunked(255).forEach { block ->
      out.write(block.size)
      out.write(block.toByteArray())
    }
    out.write(0)
  }
  out.write(0x3B)
  return out.toByteArray()
}
