package dev.marzahn.sankaku.features.books

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.data.SavedReading
import dev.marzahn.sankaku.features.grid.openInBackgroundLabel
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.spatialSpec
import dev.marzahn.sankaku.ui.openOnClick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Continue reading shelf's books (#503): Sankaku's own list of the books the account has a place in
 * ([BooksRepository.reading], the most recently saved first), signed in only. It asks only while [shown] (REVIEW.md:
 * a screen out of sight doesn't load): when shown, whenever Sankaku takes a place ([ReadingProgress.landed]), when the
 * rating lock changes, and on [refresh]. A failed ask keeps what it showed; one that failed for want of a connection
 * is asked again once back online ([retryIfUnreachable]). Signed out there's no shelf: the places kept on the device
 * have no title or cover to show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueReadingViewModel(
  private val repository: BooksRepository,
  session: AuthSession,
  privacy: PrivacySettings,
  progress: ReadingProgress,
) : ViewModel() {
  private val _books = MutableStateFlow<List<BookDto>>(emptyList())

  /** The books with a place, finished ones too: the shelf leaves those out by what's saved here ([shelfBooks]). */
  val books: StateFlow<List<BookDto>> = _books.asStateFlow()

  private val asks = MutableStateFlow(0)
  private val shown = MutableStateFlow(false)
  private var unreachable = false

  init {
    // Another account, or none, even while not shown: what was listed was the last one's (#503 review).
    viewModelScope.launch {
      session.state
        .filter { it !is AuthState.Restoring }
        .map { (it as? AuthState.SignedIn)?.user?.id }
        .distinctUntilChanged()
        .drop(1)
        .collect { _books.value = emptyList() }
    }
    viewModelScope.launch {
      val locked = session.booksRatingLocked(privacy, viewModelScope)
      shown.flatMapLatest { on ->
        if (!on) emptyFlow() else combine(locked, session.isSignedIn, progress.landed, asks) { l, signedIn, _, _ -> l to signedIn }
      }.collectLatest { (l, signedIn) ->
        if (!signedIn) {
          _books.value = emptyList()
          return@collectLatest
        }
        try {
          _books.value = repository.reading(l)
          unreachable = false
        } catch (e: ApiError) {
          unreachable = e is ApiError.Network
        }
      }
    }
  }

  /** Whether the shelf is on screen: it asks only then, and again each time it comes back. */
  fun shown(on: Boolean) {
    shown.value = on
  }

  /** Asks for the shelf again: the list's refresh. */
  fun refresh() {
    asks.value++
  }

  /** Back online: asks again if the last ask couldn't reach Sankaku. */
  fun retryIfUnreachable() {
    if (unreachable) refresh()
  }
}

/** The shelf's books: those started and not finished, by what's saved ([readingOf]), in Sankaku's order. */
internal fun shelfBooks(books: List<BookDto>, readingOf: (BookDto) -> SavedReading?): List<BookDto> =
  books.filter { book -> readingOf(book)?.finished == false }

/** The shelf as the reader's results (#382): ← → and swipes move along it, and closing shrinks into its cover. */
internal class ShelfResults(val row: LazyListState, private val isLocked: () -> Boolean) : BookResults() {
  /** What the shelf shows; held still while the reader is open from it, so result `i` stays the book it was. */
  var books: List<BookDto> by mutableStateOf(emptyList())

  override val count: Int get() = books.size
  override val locked: Boolean get() = isLocked()

  override fun bookAt(index: Int): ResultBook? = peek(index)

  override fun peek(index: Int): ResultBook? = books.getOrNull(index)?.let(::ResultBook)

  override suspend fun reveal(index: Int) {
    val info = row.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null || item.offset < info.viewportStartOffset || item.offset + item.size > info.viewportEndOffset) {
      row.scrollToItem(index)
    }
  }
}

/** The shelf's row state and its [ShelfResults], kept with the screen. */
@Composable
internal fun rememberShelfResults(locked: () -> Boolean): ShelfResults {
  val row = rememberLazyListState()
  return remember(row) { ShelfResults(row, locked) }
}

/**
 * "Continue reading" (#503): a row of covers, each with its progress bar, title and "Page 13 / 32", the most recently
 * read first. A tap opens the book at its saved page, with the shelf as its results; a long-press (desktop: a middle or
 * Cmd/Ctrl-click) keeps it, as a row does.
 */
@Composable
internal fun ContinueReadingShelf(
  results: ShelfResults,
  locked: Boolean,
  readingOf: (BookDto) -> SavedReading?,
  onOpen: (index: Int) -> Unit,
  onKeep: (BookDto) -> Unit,
  modifier: Modifier = Modifier,
) {
  val books = results.books
  Column(modifier.fillMaxWidth().semantics { testTag = SHELF_TAG }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SHELF_EDGE + 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(CONTINUE_READING, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
      Text("${books.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyRow(
      state = results.row,
      contentPadding = PaddingValues(horizontal = SHELF_EDGE),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
        ShelfBook(
          book = book,
          reading = readingOf(book),
          locked = locked,
          coverHidden = results.hiddenCover == book.id,
          coverModifier = results.coverTracker(book.id),
          onOpen = { onOpen(index) },
          onKeep = { onKeep(book) },
        )
      }
    }
  }
}

@Composable
private fun ShelfBook(
  book: BookDto,
  reading: SavedReading?,
  locked: Boolean,
  coverHidden: Boolean,
  coverModifier: Modifier,
  onOpen: () -> Unit,
  onKeep: () -> Unit,
) {
  val title = book.title.ifBlank { UNTITLED }
  Column(
    Modifier
      .width(SHELF_COVER_WIDTH)
      .openOnClick(onOpen, onKeep, longPressLabel = openInBackgroundLabel())
      .semantics(mergeDescendants = true) {
        role = Role.Button
        testTag = SHELF_BOOK_TAG
        customActions = listOf(CustomAccessibilityAction(openInBackgroundLabel()) { onKeep(); true })
      },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // The reader grows from the cover and shrinks back into it (#382), standing in for it meanwhile.
    Box(coverModifier.size(SHELF_COVER_WIDTH, SHELF_COVER_WIDTH * 1.5f).alpha(if (coverHidden) 0f else 1f)) {
      BookPicture(book.coverPost, locked, Modifier.matchParentSize())
      reading?.let { CoverProgress(it, Modifier.matchParentSize()) }
    }
    Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    reading?.let {
      Text(readingLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/**
 * [top] over [body], [top] scrolling away as [body] scrolls up and back as it comes back to its top, as if it were the
 * body's first item (#503: the shelf over the Books rows, which keep their own indices for the cursor and the reader).
 * [body] gets the height [top] leaves. When [top] first takes room while [bodyAtTop] is false, it starts scrolled away,
 * so a shelf that comes in late doesn't push the rows down under the reader's eyes.
 *
 * A body already at its top gets no scroll from a mouse wheel turned up, so nothing would bring [top] back on desktop:
 * a wheel turned up there brings it back. A drag or fling past the top does through the nested scroll, and the caller
 * brings it back with [ScrollAway.reveal] when it puts the body back at its top itself (a refresh).
 */
@Composable
internal fun ScrollAwayTop(
  bodyAtTop: () -> Boolean,
  top: @Composable () -> Unit,
  body: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  state: ScrollAway = remember { ScrollAway() },
) {
  val scope = rememberCoroutineScope()
  val atTop by rememberUpdatedState(bodyAtTop)
  // A move: the spatial spring.
  val spec by rememberUpdatedState(spatialSpec<Float>())
  Layout(
    content = {
      Box { top() }
      Box { body() }
    },
    modifier = modifier
      .nestedScroll(state)
      .pointerInput(state) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val up = event.type == PointerEventType.Scroll && event.changes.any { it.scrollDelta.y < 0f }
            if (up && atTop() && state.hidden > 0f) scope.launch { state.reveal(spec) }
          }
        }
      }
      .clipToBounds(),
  ) { measurables, constraints ->
    val topPlaceable = measurables[0].measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    // An empty top has nothing scrolled away; one that comes in starts scrolled away unless the body is at its top.
    if (topPlaceable.height == 0) {
      state.hidden = 0f
    } else if (state.height == 0) {
      state.hidden = if (bodyAtTop()) 0f else topPlaceable.height.toFloat()
    }
    state.height = topPlaceable.height
    val hidden = state.hidden.coerceIn(0f, topPlaceable.height.toFloat()).roundToInt()
    val shown = topPlaceable.height - hidden
    val bodyHeight = (constraints.maxHeight - shown).coerceAtLeast(0)
    val bodyPlaceable = measurables[1].measure(constraints.copy(minHeight = bodyHeight, maxHeight = bodyHeight))
    layout(constraints.maxWidth, constraints.maxHeight) {
      topPlaceable.place(0, -hidden)
      bodyPlaceable.place(0, shown)
    }
  }
}

/** How much of the top is scrolled away: a scroll up takes it first, and what a scroll down leaves over gives it back. */
@Stable
internal class ScrollAway : NestedScrollConnection {
  // Measured, not state: written as the layout measures.
  var height = 0
  var hidden by mutableFloatStateOf(0f)

  private val revealing = MutatorMutex()

  /** Shows the whole top at once, as the body jumps back to its top (a refresh). */
  suspend fun show() {
    revealing.mutate { hidden = 0f }
  }

  /** Brings the whole top back on [spec]; a newer reveal replaces a running one. */
  suspend fun reveal(spec: AnimationSpec<Float>) {
    if (hidden <= 0f) return
    revealing.mutate { animate(hidden, 0f, animationSpec = spec) { value, _ -> hidden = value } }
  }

  override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
    if (available.y >= 0f || height == 0) return Offset.Zero
    val take = maxOf(available.y, -(height - hidden))
    hidden -= take
    return Offset(0f, take)
  }

  override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
    if (available.y <= 0f || height == 0) return Offset.Zero
    val give = minOf(available.y, hidden)
    hidden -= give
    return Offset(0f, give)
  }
}

internal const val CONTINUE_READING = "Continue reading"
internal const val SHELF_TAG = "continue-reading"
internal const val SHELF_BOOK_TAG = "continue-reading-book"

private val SHELF_EDGE = 12.dp
private val SHELF_COVER_WIDTH = if (platformIsTouch) 88.dp else 104.dp
