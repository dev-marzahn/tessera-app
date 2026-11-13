package dev.marzahn.sankaku.features.books

import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.SavedReading
import dev.marzahn.sankaku.features.grid.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookRowTest {
  private fun post(md5: String) = PostDto(id = md5, md5 = md5, rating = "s", status = "active")

  @Test
  fun aRowShowsTheFirstPagesWithoutTheCover() {
    val pages = listOf(post("a"), post("b"), post("c"), post("d"))
    assertEquals(listOf("b", "c", "d"), rowPages(pages, cover = post("a"), count = 3).map { it.md5 })
    assertEquals(listOf("b", "c"), rowPages(pages, cover = post("a"), count = 2).map { it.md5 })
    // A cover that isn't page 1: pages 1 to 3.
    assertEquals(listOf("a", "b", "c"), rowPages(pages, cover = post("x"), count = 3).map { it.md5 })
  }

  @Test
  fun phoneRowsShowTwoPagesWiderRowsThreeAndDesktopHasTwoColumnsPast1000dp() {
    assertEquals(2, pageThumbs(380.dp))
    assertEquals(3, pageThumbs(620.dp))
    assertEquals(1, booksColumns(999.dp))
    assertEquals(2, booksColumns(1000.dp))
  }

  @Test
  fun aBooksTextHasItsCountsRatingAndProgress() {
    val book = BookDto(id = "b1", nameEn = "Title", postCount = 34, favCount = 11, rating = "s")
    val text = book.rowText(SavedReading(sequence = 11, page = 12, pages = 34, finished = false))
    assertEquals("Title", text.title)
    assertEquals("34 pages", text.count)
    assertEquals(11, text.favCount)
    assertEquals(Rating.G, text.rating)
    assertEquals("Page 12 / 34", text.reading?.let(::readingLabel))
    assertNull(book.rowText(null).reading)
    assertEquals(READ, readingLabel(SavedReading(sequence = 33, page = 34, pages = 34, finished = true)))
    assertEquals("1 page", pagesLabel(1))
  }

  @Test
  fun aSeriesCountSaysWhenGOnlyLeftBooksOut() {
    assertEquals("6 books", seriesCount(6, 6))
    assertEquals("1 of 6 books · G only", seriesCount(1, 6))
  }
}

class BookSheetTest {
  @Test
  fun aDescriptionsWikiLinksAndLinksReadAsTheirText() {
    assertEquals(
      "Drawn by Hinghoi and written by Merryweather",
      plainDescription("Drawn by [[Hinghoi]] and written by \"Merryweather\":https://example.invalid/m"),
    )
    assertEquals("See Volume 2.", plainDescription("See [[volume_2|Volume 2]]."))
    assertEquals("Plain text", plainDescription(" Plain text "))
  }

  @Test
  fun aSeriesSaysTheBooksPlaceWhenItIsKnown() {
    val route = dev.marzahn.sankaku.Route.BookSeries("s1", "S")
    assertEquals("Book 2 of 6", seriesPlace(InSeries(route, "S", position = 2, count = 6)))
    assertEquals("6 books", seriesPlace(InSeries(route, "S", position = null, count = 6)))
    // A loaded series: the book's own sequence, of all the series' books (G only may have left some out).
    val series = dev.marzahn.sankaku.api.dto.SeriesDto(
      id = "s1", nameEn = "S", poolCount = 6,
      books = listOf(BookDto(id = "a", sequence = 1), BookDto(id = "b", sequence = 2)),
    )
    assertEquals(InSeries(route, "S", position = 2, count = 6), inSeries(series, "b"))
    assertEquals(null, inSeries(series, "elsewhere").position)
  }
}
