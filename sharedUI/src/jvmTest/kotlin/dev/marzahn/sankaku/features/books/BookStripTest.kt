package dev.marzahn.sankaku.features.books

import coil3.PlatformContext
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.SavedReading
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookStripTest {
  private fun page(id: String, sequence: Int? = null, rating: String = "s") =
    PostDto(id = id, md5 = "md5-$id", rating = rating, status = "active", sequence = sequence)

  @Test
  fun pagesAreInSequenceOrderWithUnnumberedOnesLast() {
    val pages = listOf(page("c", 2), page("x"), page("a", 0), page("b", 1))
    assertEquals(listOf("a", "b", "c", "x"), readingOrder(pages).map { it.id })
  }

  @Test
  fun theNextThreePagesPreloadAsFarAsTheBookGoes() {
    assertEquals(1..3, preloadedPages(0, 27))
    assertEquals(25..26, preloadedPages(24, 27))
    assertTrue(preloadedPages(26, 27).isEmpty())
  }

  @Test
  fun theCurrentPageIsTheOneTakingMostOfTheViewport() {
    // Page 3 ends 100 px into a 1000 px viewport; page 4 fills the other 900.
    assertEquals(4, mostShownPage(listOf(PageSpan(3, -900, 1000), PageSpan(4, 100, 1500)), 0, 1000))
    // A tie goes to the earlier page.
    assertEquals(3, mostShownPage(listOf(PageSpan(3, -500, 1000), PageSpan(4, 500, 1000)), 0, 1000))
    assertEquals(0, mostShownPage(emptyList(), 0, 1000))
  }

  @Test
  fun gOnlyHidesEveryRatingButG() {
    assertFalse(hiddenByGOnly(page("a", rating = "s"), gOnly = true))
    assertTrue(hiddenByGOnly(page("a", rating = "q"), gOnly = true))
    assertTrue(hiddenByGOnly(page("a", rating = "e"), gOnly = true))
    assertFalse(hiddenByGOnly(page("a", rating = "e"), gOnly = false))
  }

  @Test
  fun aPageWhoseSampleIsTheFileShowsItsPreviewUnderIt() {
    val file = "https://s.sankakucomplex.com/o/ab/cd/x.png?e=1"
    val still = page("a").copy(fileType = "image/png", previewUrl = "https://v.sankakucomplex.com/data/preview/x.avif?e=1", sampleUrl = file, fileUrl = file)
    val preview = assertNotNull(pagePreviewRequest(PlatformContext.INSTANCE, still, 1000)).data as PostImage
    assertEquals(Variant.Preview, preview.variant)
    assertEquals("https://v.sankakucomplex.com/data/preview/x.avif?e=1", preview.url)
    // With only a file, the page loads the file in its place.
    assertNotNull(pagePreviewRequest(PlatformContext.INSTANCE, still.copy(sampleUrl = null), 1000))
    // A sample on the CDN loads about as fast as its preview.
    assertNull(pagePreviewRequest(PlatformContext.INSTANCE, still.copy(sampleUrl = "https://v.sankakucomplex.com/data/sample/x.jpg?e=1"), 1000))
    // A video or a GIF already asks for its preview first.
    assertNull(pagePreviewRequest(PlatformContext.INSTANCE, still.copy(fileType = "video/mp4"), 1000))
    assertNull(pagePreviewRequest(PlatformContext.INSTANCE, still.copy(fileType = "image/gif"), 1000))
  }

  @Test
  fun theFullFileLoadsOnlyPastTheSamplesResolution() {
    val image = page("a").copy(fileType = "image/jpeg", fileWidth = 3000, fileHeight = 4000, sampleWidth = 1500, sampleUrl = "https://s/x")
    assertFalse(needsFullFile(image, 1500f))
    assertTrue(needsFullFile(image, 1501f))
    // A sample as large as the file, a GIF or a video: nothing sharper to load.
    assertFalse(needsFullFile(image.copy(fileWidth = 1500), 4000f))
    assertFalse(needsFullFile(image.copy(fileType = "video/mp4"), 4000f))
  }

  @Test
  fun theFullFileDecodesAsWideAsTheZoomShowsItByStepsWithinTheCaps() {
    val square = page("a").copy(fileWidth = 3000, fileHeight = 3000)
    // At 1× only as wide as the page is shown, as the sample would be.
    assertEquals(1080, fullFileWidth(square, 1080, 1f))
    // A pinch to 1.3× decodes at the next step, 2.5×, not at every frame's zoom.
    assertEquals(2700, fullFileWidth(square, 1080, 1.3f))
    assertEquals(2700, fullFileWidth(square, 1080, 2.5f))
    // Never past the file itself.
    assertEquals(3000, fullFileWidth(square, 1080, 6f))
    // A 20000 px tall strip page is decoded narrower, so it stays within the pixel cap.
    val tall = fullFileWidth(page("a").copy(fileWidth = 2000, fileHeight = 20000), 1080, 6f)
    assertTrue(tall < 2000 && tall.toLong() * tall * 10 <= 12_000_000L, "$tall")
  }

  @Test
  fun thePeekBandCountsFromOne() {
    assertEquals("page 4 / 27", pageLabel(3, 27))
  }

  @Test
  fun aSavedPlaceIsTheAPIsSequenceAndOpensAtItsPageOrJustBefore() {
    // Signed out a book can come with gaps in its sequence (`docs/api.md` → Books).
    val pages = listOf(page("a", 0), page("b", 1), page("d", 3), page("f", 5))
    assertEquals(0, savedPageIndex(pages, null))
    assertEquals(2, savedPageIndex(pages, 3))
    assertEquals(2, savedPageIndex(pages, 4), "a page this strip lacks opens just before it")
    assertEquals(3, savedPageIndex(pages, 40))
    assertEquals(dev.marzahn.sankaku.data.BookPlace(sequence = 3, page = 3, pages = 4), bookPlace(pages, 2))
    assertEquals("Page 12", resumeLabel(12))
  }

  @Test
  fun aBookOpensAtItsSavedPageUnlessItWasRead() {
    // #503: the saved place, or page 1 with none or once finished.
    val pages = listOf(page("a", 0), page("b", 1), page("c", 2))
    assertEquals(0, openingPageIndex(pages, null))
    assertEquals(1, openingPageIndex(pages, SavedReading(sequence = 1, page = 2, pages = 3, finished = false)))
    assertEquals(0, openingPageIndex(pages, SavedReading(sequence = 2, page = 3, pages = 3, finished = true)))
  }
}
