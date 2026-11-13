package dev.marzahn.sankaku.features.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.SeriesDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.BookListItem
import dev.marzahn.sankaku.data.BookSearch
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.features.grid.ratingLocked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The rating lock for Books, as on grids ([ratingLocked]): signed out, or G only on. Locked, the list sends
 * `rating:s`, a series shows only its G books, and a row shows no picture of another rating.
 */
internal fun AuthSession.booksRatingLocked(privacy: PrivacySettings, scope: CoroutineScope): StateFlow<Boolean> =
  combine(isSignedIn, privacy.gOnly) { signedIn, gOnly -> ratingLocked(signedIn, gOnly) }
    .stateIn(scope, SharingStarted.Eagerly, ratingLocked(isSignedIn.value, privacy.gOnly.value))

/**
 * One Books search's rows (#380). A new pager is built when the search sent changes (G only on or off, signing in or
 * out, [BookSearch.searched]), and on signing in or out with G only on too: a list loaded signed out says no book is
 * ♡ed (#383).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookListViewModel(
  val tags: List<String>,
  repository: BooksRepository,
  session: AuthSession,
  privacy: PrivacySettings,
) : ViewModel() {
  val ratingLocked: StateFlow<Boolean> = session.booksRatingLocked(privacy, viewModelScope)

  val books: Flow<PagingData<BookListItem>> = ratingLocked
    .combine(session.isSignedIn) { locked, signedIn -> locked to signedIn }
    .distinctUntilChanged()
    .flatMapLatest { (locked, _) -> repository.list(BookSearch.searched(tags, locked)) }
    .cachedIn(viewModelScope)
}

/** A series' books, once loaded. */
sealed interface SeriesState {
  data object Loading : SeriesState

  data class Loaded(val series: SeriesDto) : SeriesState

  data class Failed(val error: ApiError) : SeriesState
}

/**
 * The series [id]'s books (#380), loaded again when the rating lock changes, since locked it holds only the G ones
 * ([BooksRepository.series]).
 */
class BookSeriesViewModel(
  val id: String,
  private val repository: BooksRepository,
  session: AuthSession,
  privacy: PrivacySettings,
) : ViewModel() {
  val ratingLocked: StateFlow<Boolean> = session.booksRatingLocked(privacy, viewModelScope)

  private val _state = MutableStateFlow<SeriesState>(SeriesState.Loading)
  val state: StateFlow<SeriesState> = _state.asStateFlow()

  private val retries = MutableStateFlow(0)

  init {
    viewModelScope.launch {
      // Signing in or out loads it again even with G only on: signed out a series has no books.
      combine(ratingLocked, session.isSignedIn, retries) { locked, _, _ -> locked }.collectLatest { locked ->
        _state.value = SeriesState.Loading
        _state.value = try {
          SeriesState.Loaded(repository.series(id, locked))
        } catch (e: ApiError) {
          SeriesState.Failed(e)
        }
      }
    }
  }

  fun retry() {
    retries.value++
  }
}
