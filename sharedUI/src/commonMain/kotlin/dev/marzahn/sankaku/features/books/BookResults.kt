package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.paging.compose.LazyPagingItems
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.data.BookListItem

/**
 * A result the reader can show: [book], and the series it's from when the reader knows it ([seriesId]), which a
 * series' row and a series' list say. Only then does the end card offer the next volume (#377 decision 13, #503): a
 * book's own `/pools/{id}` doesn't say which series it's in (`docs/api.md` → Books).
 */
data class ResultBook(val book: BookDto, val seriesId: String? = null) {
  /** The book as the reader opens it. */
  val open: OpenBook get() = OpenBook(book.id, book.title)
}

/**
 * The results a book was opened from (#382): what ← → (A/D) and sideways swipes move between, and where each row's cover
 * is on screen, so the reader grows from it and shrinks back into it. The Books list and a series' list make one each.
 *
 * Result `i` is row `i` of the list: a series' row is the book its row shows, in its series ([ResultBook.seriesId]).
 */
@Stable
abstract class BookResults {
  /** The results loaded so far. */
  abstract val count: Int

  /** Result [index], asking for more results near the end of those loaded, as the post viewer does. */
  abstract fun bookAt(index: Int): ResultBook?

  /** Result [index] without asking for more. */
  abstract fun peek(index: Int): ResultBook?

  /** Whether the pictures of a book of another rating stay grey (signed out, or G only), as on the rows. */
  abstract val locked: Boolean

  /** Scrolls the list the least it takes to show result [index]'s row whole, so the reader shrinks into it. */
  abstract suspend fun reveal(index: Int)

  /** The series' list's results are that series' books: the next one is the next result, not a book from elsewhere. */
  open val seriesId: String? get() = null

  // Each row's cover, in window coordinates, by its book's id: written as rows are placed, read only when the reader
  // opens or closes, so it isn't state.
  private val covers = HashMap<String, Rect>()

  internal fun coverPlaced(bookId: String, bounds: Rect) {
    covers[bookId] = bounds
  }

  internal fun coverGone(bookId: String) {
    covers.remove(bookId)
  }

  /** Where [bookId]'s row shows its cover, in window coordinates, if it's laid out. */
  internal fun coverOf(bookId: String): Rect? = covers[bookId]

  /** The book whose row's cover the reader stands in for, so it isn't drawn twice while the reader grows or shrinks. */
  var hiddenCover: String? by mutableStateOf(null)
    internal set
}

/** The Books list's rows as results ([BookListScreen]). */
internal class PagedBookResults(
  private val items: LazyPagingItems<BookListItem>,
  private val listState: LazyGridState,
  private val isLocked: () -> Boolean,
) : BookResults() {
  override val count: Int get() = items.itemCount
  override val locked: Boolean get() = isLocked()

  // items[i] (not peek) so the pages near the end load the next page.
  override fun bookAt(index: Int): ResultBook? = items.takeIf { index in 0 until it.itemCount }?.get(index)?.asResult()

  override fun peek(index: Int): ResultBook? = items.takeIf { index in 0 until it.itemCount }?.peek(index)?.asResult()

  override suspend fun reveal(index: Int) = listState.reveal(index)
}

/** A series' books as results ([BookSeriesScreen]): all loaded, in series order. */
internal class SeriesBookResults(
  private val books: List<BookDto>,
  override val seriesId: String,
  private val listState: LazyGridState,
  private val isLocked: () -> Boolean,
) : BookResults() {
  override val count: Int get() = books.size
  override val locked: Boolean get() = isLocked()

  override fun bookAt(index: Int): ResultBook? = peek(index)

  override fun peek(index: Int): ResultBook? = books.getOrNull(index)?.let { ResultBook(it, seriesId) }

  override suspend fun reveal(index: Int) = listState.reveal(index)
}

/** Goes on a row's cover: keeps where [bookId]'s cover is on screen, for the reader to grow from and shrink into. */
@Composable
internal fun BookResults.coverTracker(bookId: String): Modifier {
  DisposableEffect(this, bookId) { onDispose { coverGone(bookId) } }
  return Modifier.onGloballyPositioned { coverPlaced(bookId, it.boundsInWindow()) }
}

internal fun BookListItem.asResult(): ResultBook = when (this) {
  is BookListItem.Book -> ResultBook(book)
  is BookListItem.Series -> ResultBook(first, series.id)
}

/**
 * Scrolls the least distance that shows the whole item at [index]: up to put it at the top if it's above (or not laid
 * out), down by what it's cut off by at the bottom. Instant: the reader covers the list.
 */
private suspend fun LazyGridState.reveal(index: Int) {
  val info = layoutInfo
  val item = info.visibleItemsInfo.firstOrNull { it.index == index }
  if (item == null) {
    scrollToItem(index)
    return
  }
  val top = item.offset.y
  val bottom = top + item.size.height
  when {
    top < info.viewportStartOffset -> scrollToItem(index)
    bottom > info.viewportEndOffset - info.afterContentPadding -> scrollBy((bottom - info.viewportEndOffset + info.afterContentPadding).toFloat())
  }
}

/**
 * The book after [currentId] in a series' [books] (series order), or null when it's the last, or not among them (G only
 * left it out).
 */
internal fun nextInSeries(books: List<BookDto>, currentId: String): BookDto? {
  val at = books.indexOfFirst { it.id == currentId }
  return if (at < 0) null else books.getOrNull(at + 1)
}
