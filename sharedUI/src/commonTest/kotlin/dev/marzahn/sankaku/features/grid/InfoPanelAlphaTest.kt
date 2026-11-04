package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfoPanelAlphaTest {
  @Test
  fun aPanelAFewPixelsWideIsAlmostInvisible() {
    // 0.003 of the 300 dp panel is about a pixel: it used to show as a full-height light line.
    assertTrue(infoPanelAlpha(0.003f) < 0.05f)
  }

  @Test
  fun theSplitPanelIsOpaque() {
    assertEquals(1f, infoPanelAlpha(0.1f))
    assertEquals(1f, infoPanelAlpha(1f))
    // The spring overshoots past 1.
    assertEquals(1f, infoPanelAlpha(1.04f))
  }
}
