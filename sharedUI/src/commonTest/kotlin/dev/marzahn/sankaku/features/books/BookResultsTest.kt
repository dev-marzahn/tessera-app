package dev.marzahn.sankaku.features.books

import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.SeriesSummaryDto
import dev.marzahn.sankaku.data.BookListItem
import dev.marzahn.sankaku.data.BookPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #382: what the reader moves between, and what comes next in a series. */
class BookResultsTest {
  private val one = BookDto(id = "1", nameEn = "One")
  private val two = BookDto(id = "2", nameEn = "Two")
  private val three = BookDto(id = "3", nameEn = "Three")

  private class ListResults(private val books: List<ResultBook>) : BookResults() {
    override val count get() = books.size
    override val locked get() = false

    override fun bookAt(index: Int) = books.getOrNull(index)

    override fun peek(index: Int) = books.getOrNull(index)

    override suspend fun reveal(index: Int) = Unit
  }

  @Test
  fun theNextBookInASeriesIsTheOneAfterInSeriesOrder() {
    val series = listOf(one, two, three)
    assertEquals(two, nextInSeries(series, "1"))
    assertEquals(three, nextInSeries(series, "2"))
    assertNull(nextInSeries(series, "3"), "the last has no next")
    // G only left the book out of its series: no next rather than a guess.
    assertNull(nextInSeries(listOf(one, three), "2"))
  }

  @Test
  fun aSeriesRowIsTheBookItShowsInItsSeries() {
    val series = SeriesSummaryDto(id = "s", poolCount = 3)
    assertEquals(ResultBook(one, "s"), BookListItem.Series(series, one).asResult())
    assertEquals(ResultBook(two), BookListItem.Book(two).asResult())
  }

  @Test
  fun movingBetweenResultsKeepsTheOpeningAndChangesTheBook() {
    val reader = BookReader { _, _ -> }
    val results = ListResults(listOf(ResultBook(one), ResultBook(two, "s")))
    reader.open(results, 0)
    val opening = reader.session.value!!.opening
    reader.showing(1, OpenBook("2", "Two"))
    val session = reader.session.value!!
    assertEquals(OpenBook("2", "Two") to 1, session.book to session.index)
    assertEquals(opening, session.opening)
    assertEquals(OpenBook("2", "Two"), reader.shown.value)
    // Its Next card continued into the series' next book in place: still result 1, showing that book.
    reader.showing(1, OpenBook("3", "Three"))
    assertEquals(OpenBook("3", "Three") to 1, reader.session.value!!.let { it.book to it.index })
    reader.open(results, 0)
    assertEquals(opening + 1, reader.session.value!!.opening, "a new opening")
    reader.close()
    assertNull(reader.session.value)
    assertNull(reader.shown.value)
  }

  @Test
  fun eachMoveSendsTheBookLeftOnceAndOnlyIfItMoved() {
    val sent = mutableListOf<Pair<String, Int>>()
    val reader = BookReader { id, place -> sent += id to place.sequence }
    val results = ListResults(listOf(ResultBook(one), ResultBook(two), ResultBook(three)))
    reader.open(results, 0)
    reader.report("1", BookPlace(sequence = 3, page = 4, pages = 10))
    reader.report("1", BookPlace(sequence = 5, page = 6, pages = 10))
    // A swipe to the next result (#382): the book left, at its last place.
    reader.showing(1, OpenBook("2", "Two"))
    assertEquals(listOf("1" to 5), sent)
    // Book 2 was only looked at: the next swipe and the close send nothing for it.
    reader.showing(2, OpenBook("3", "Three"))
    reader.report("3", BookPlace(sequence = 1, page = 2, pages = 10))
    reader.close()
    assertEquals(listOf("1" to 5, "3" to 1), sent)
  }

  @Test
  fun aBookOpenedOnItsOwnHasNoResults() {
    val reader = BookReader { _, _ -> }
    reader.open("1", "One")
    reader.showing(1, OpenBook("2", "Two"))
    assertEquals(ReaderSession(OpenBook("1", "One"), opening = 1), reader.session.value)
  }

  @Test
  fun theStripFadesInOverTheSecondHalfAndTheBlackOverTheFirstQuarter() {
    assertEquals(0f, stripAlpha(0.5f))
    assertEquals(1f, stripAlpha(1f))
    assertEquals(1f, stripAlpha(1.05f), "the spring's overshoot holds")
    assertEquals(0f, frameAlpha(-0.02f), "the close's undershoot holds")
    assertEquals(1f, frameAlpha(0.25f))
  }
}
