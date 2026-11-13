package dev.marzahn.sankaku.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GridSettingsTest {
  @Test
  fun thePostSizeIsThePlatformsUntilSet() {
    assertNull(GridSettings(InMemoryTextFile()).cellWidth.value)
  }

  @Test
  fun thePostSizeSurvivesARestart() {
    val file = InMemoryTextFile()
    GridSettings(file).setCellWidth(400)

    assertEquals(400, GridSettings(file).cellWidth.value)
  }

  @Test
  fun aSizeIsSnappedToTheRangeAndItsSteps() {
    val settings = GridSettings(InMemoryTextFile())

    settings.setCellWidth(409)
    assertEquals(400, settings.cellWidth.value)
    settings.setCellWidth(411)
    assertEquals(420, settings.cellWidth.value)
    settings.setCellWidth(10)
    assertEquals(PostSizes.Desktop.min, settings.cellWidth.value)
    settings.setCellWidth(10_000)
    assertEquals(PostSizes.Desktop.max, settings.cellWidth.value)
  }

  @Test
  fun aPhonesSizesAreItsOwn() {
    // #468: 80 to 200 dp in 10 dp steps; desktop's stay 160 to 560 in 20s (#445).
    assertEquals(PostSizes(min = 80, max = 200, step = 10, default = 110), PostSizes.Phone)
    assertEquals(PostSizes(min = 160, max = 560, step = 20, default = 220), PostSizes.Desktop)
    val settings = GridSettings(InMemoryTextFile(), PostSizes.Phone)

    settings.setCellWidth(114)
    assertEquals(110, settings.cellWidth.value)
    settings.setCellWidth(10)
    assertEquals(80, settings.cellWidth.value)
    settings.setCellWidth(400)
    assertEquals(200, settings.cellWidth.value)
  }

  @Test
  fun aSizeSavedWithAnotherRangeIsSnappedToThisOne() {
    val file = InMemoryTextFile().apply { write("""{"cellWidth":400}""") }

    assertEquals(200, GridSettings(file, PostSizes.Phone).cellWidth.value)
  }

  @Test
  fun aRangeMustBeWholeSteps() {
    assertFailsWith<IllegalArgumentException> { PostSizes(min = 80, max = 205, step = 10, default = 110) }
    // The default is one of the slider's sizes.
    assertFailsWith<IllegalArgumentException> { PostSizes(min = 80, max = 200, step = 10, default = 115) }
    assertFailsWith<IllegalArgumentException> { PostSizes(min = 80, max = 200, step = 10, default = 220) }
  }

  @Test
  fun aHandEditedSizeIsSnappedAsItsRead() {
    val file = InMemoryTextFile().apply { write("""{"cellWidth":9999}""") }

    assertEquals(PostSizes.Desktop.max, GridSettings(file).cellWidth.value)
  }

  @Test
  fun anUnreadableFileIsTheDefaults() {
    val file = InMemoryTextFile().apply { write("not json") }

    assertNull(GridSettings(file).cellWidth.value)
  }
}
