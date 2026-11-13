package dev.marzahn.sankaku.features.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.data.BookFavourites
import dev.marzahn.sankaku.api.dto.PostDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BookUiState {
  data object Loading : BookUiState

  /** [unreachable]: Sankaku couldn't be reached, so the network coming back retries it (#128, #491). */
  data class Failed(val error: ApiError) : BookUiState {
    val unreachable: Boolean get() = error is ApiError.Network
  }

  /** [pages] are the book's `posts[]` in reading order ([readingOrder]). */
  data class Loaded(val book: BookDto, val pages: List<PostDto>) : BookUiState
}

/** The book [bookId] with its pages, from `/pools/{id}` (`BooksApi.pages`). */
class BookReaderViewModel(
  private val bookId: String,
  private val books: BooksApi,
  private val favourites: BookFavourites? = null,
) : ViewModel() {
  private val _state = MutableStateFlow<BookUiState>(BookUiState.Loading)
  val state: StateFlow<BookUiState> = _state.asStateFlow()

  init {
    load()
  }

  private var loading: Job? = null

  /** Loads the book, in place of a load still running, so an older answer can't land last. */
  fun load() {
    loading?.cancel()
    _state.value = BookUiState.Loading
    loading = viewModelScope.launch {
      _state.value = try {
        // What it says of the ♡ replaces an earlier ♡ made in the app (#383).
        val book = if (favourites == null) books.pages(bookId) else favourites.fresh({ books.pages(bookId) }) { listOf(it) }
        BookUiState.Loaded(book, readingOrder(book.posts))
      } catch (e: ApiError) {
        BookUiState.Failed(e)
      }
    }
  }
}

/**
 * A book's pages in `sequence` order. The API sends them in that order already (`:cli` step b4 checks it); a page
 * without a `sequence` keeps its place after those with one. A book can hold fewer pages than its `post_count`
 * (`docs/api.md` → Books), so the reader counts these.
 */
internal fun readingOrder(posts: List<PostDto>): List<PostDto> = posts.sortedBy { it.sequence ?: Int.MAX_VALUE }
