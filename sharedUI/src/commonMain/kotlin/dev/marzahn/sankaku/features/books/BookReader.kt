package dev.marzahn.sankaku.features.books

import dev.marzahn.sankaku.data.BookPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A book open in the reader: its [id], and the [title] to show until its pages have loaded, if the opener knows it. */
data class OpenBook(val id: String, val title: String? = null)

/**
 * What the reader shows: [book], result [index] of the [results] it was opened from, if any (#382). [opening] counts
 * the reader's openings, so moving between results is the same opening and a new [BookReader.open] isn't.
 */
data class ReaderSession(
  val book: OpenBook,
  val results: BookResults? = null,
  val index: Int = 0,
  val opening: Int = 0,
)

/**
 * Which book the reader shows, over the whole window, if any (#381). The Books list's row tap opens one here with the
 * list as its results ([open] with [BookResults]), and moving between them ([showing]) changes the book; the reader's ✕,
 * Esc, Back and drag down close it.
 *
 * It also sends reading progress (#384) at the three moments #377 decision 12 names, never per page: whenever the
 * book shown changes or the reader closes, the book left is sent ([sendProgress]: a close, a swipe or ← → to another
 * result, the end card's next volume or next result, a new [open]), and when the app goes to the background
 * ([sendInBackground]). What's sent is the place the strip last [report]ed; a book opens at its saved page (#503) and
 * reports nothing until the strip moves from it or Start over is chosen, so opening it to look and closing it keeps
 * the saved place (#490). [saveProgress] saves a place (`ReadingProgress.save`). Main thread only.
 */
class BookReader(private val saveProgress: (bookId: String, place: BookPlace) -> Unit) {
  private val _session = MutableStateFlow<ReaderSession?>(null)
  val session: StateFlow<ReaderSession?> = _session.asStateFlow()

  private val _shown = MutableStateFlow<OpenBook?>(null)

  /** The book the reader shows, if any: the session's, which moving between results changes. */
  val shown: StateFlow<OpenBook?> = _shown.asStateFlow()

  private var openings = 0

  // Where each open book's strip is, once it has moved: by book id, until sent.
  private val places = mutableMapOf<String, BookPlace>()

  /** Opens the book [bookId] at its saved page (#503), on its own, in place of any book open. */
  fun open(bookId: String, title: String? = null) {
    set(ReaderSession(OpenBook(bookId, title), opening = ++openings))
  }

  /** Opens result [index] of [results] at its saved page (#503); ← → and swipes then move between them. */
  fun open(results: BookResults, index: Int) {
    val result = results.peek(index) ?: return
    set(ReaderSession(result.open, results, index, ++openings))
  }

  /**
   * The reader shows [book] at result [index] of the results it was opened from: the result's own book after a swipe,
   * or the book after it in its series once its "Next" card continued into it in place.
   */
  fun showing(index: Int, book: OpenBook) {
    val session = _session.value ?: return
    if (session.results == null || session.index == index && session.book == book) return
    set(session.copy(book = book, index = index))
  }

  /** Closes the reader; the book's progress is sent. */
  fun close() {
    set(null)
  }

  /** The strip of [bookId] is at [place] (the page taking most of the screen); nothing is sent yet. */
  fun report(bookId: String, place: BookPlace) {
    places[bookId] = place
  }

  /**
   * Sends where [bookId] was left, once. Nothing if its strip hasn't moved since it opened or since the last send.
   * The reader calls it itself whenever the book shown changes or it closes.
   */
  fun sendProgress(bookId: String) {
    places.remove(bookId)?.let { saveProgress(bookId, it) }
  }

  /**
   * The app went to the background: sends the shown book's place. A later close sends again only if the strip has
   * moved to another page since.
   */
  fun sendInBackground() {
    _shown.value?.id?.let(::sendProgress)
  }

  private fun set(session: ReaderSession?) {
    val left = _shown.value?.id?.takeIf { it != session?.book?.id }
    _session.value = session
    _shown.value = session?.book
    left?.let(::sendProgress)
  }
}
