package dev.marzahn.sankaku.images

import dev.marzahn.sankaku.api.ImageRefusedException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Only an image whose host couldn't be reached retries by itself when the network comes back (#128). */
class ImageFailureTest {
  @Test
  fun aConnectionErrorAnywhereInTheCausesIsUnreachable() {
    assertEquals(ImageFailure.Unreachable, imageFailure(IOException("reset")))
    assertEquals(ImageFailure.Unreachable, imageFailure(RuntimeException("load failed", IOException("timeout"))))
  }

  @Test
  fun aRefusalOrADecodeErrorIsFailedAndNoUrlIsNoImage() {
    assertEquals(ImageFailure.Failed, imageFailure(ImageRefusedException("example.org")))
    assertEquals(ImageFailure.Failed, imageFailure(IllegalStateException("decode")))
    assertEquals(ImageFailure.NoImage, imageFailure(RuntimeException(NoImageUrlException("preview"))))
  }
}
