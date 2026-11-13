package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the phone viewer's ♡ heart plays (#469): over the picture's centre, above the sheet. */
class PhoneBurstCentreTest {
  @Test
  fun atPeekItsThePicturesCentreClearOfTheInset() {
    // The room is 200–2000 px; the picture ends 180 px above the sheet, so its centre is at 1010.
    assertEquals(1010f, phoneBurstCentre(pictureTop = 200f, sheetTop = 2000f, inset = 180f, minCentre = 300f))
  }

  @Test
  fun itRisesWithTheSheet() {
    // At half the room is shorter, and the heart stays over what's left of the picture.
    assertEquals(610f, phoneBurstCentre(pictureTop = 200f, sheetTop = 1200f, inset = 180f, minCentre = 300f))
  }

  @Test
  fun nearFullItStaysWholeBelowTheStatusBar() {
    // No picture left: the heart stays at its floor instead of going above the window's top.
    assertEquals(300f, phoneBurstCentre(pictureTop = 200f, sheetTop = 0f, inset = 180f, minCentre = 300f))
  }
}
