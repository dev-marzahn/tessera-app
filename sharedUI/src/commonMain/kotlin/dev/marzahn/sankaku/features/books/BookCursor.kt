package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.IntRect
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.LocalKeyFocus
import dev.marzahn.sankaku.features.grid.GridMove
import dev.marzahn.sankaku.features.grid.cellNear
import dev.marzahn.sankaku.features.grid.firstShown
import dev.marzahn.sankaku.features.grid.gridMoveFor
import dev.marzahn.sankaku.features.grid.nextCell
import dev.marzahn.sankaku.ui.components.OncePerPress
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.tabStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What a key does to the row under the Books list's cursor (#417). */
internal enum class BookKey {
  /** Space: the book in the reader, or the series' books. */
  Open,

  /** E: the book kept for later, as a middle-click does (#385); a series opens in a background tab. */
  Keep,

  /** F: the book's ♡. A series has none. */
  Favourite,
}

/**
 * The Books list's keyboard cursor (#417), the grid's over the book rows: a row index, null until the first WASD (or
 * arrow) press, which shows it on the first wholly visible row. The moves are the grid's [nextCell] over the laid-out
 * rows, so two columns move sideways as well. Closing the reader leaves it on the book the reader closed on, as the
 * viewer does for posts.
 */
@Stable
internal class BookCursor(
  private val listState: LazyGridState,
  private val scope: CoroutineScope,
  private val saved: MutableState<Int?>,
) {
  /** The row the cursor is on, if it shows. */
  val index: Int? get() = saved.value

  /**
   * The row that takes the keyboard focus next, once, so focus and the cursor stay on one row (#418): Space, E and F
   * act on the ringed row, and Tab goes on from it.
   */
  var pendingFocus: Int? by mutableStateOf(null)
    private set

  /** Puts the cursor on [index] (null hides it), and the focus with it. */
  private fun put(index: Int?) {
    saved.value = index
    pendingFocus = index
  }

  /** A row took the focus (Tab, Shift-Tab): the cursor goes there. */
  fun focused(index: Int) {
    saved.value = index
  }

  fun focusTaken() {
    pendingFocus = null
  }

  /** The keys came back to the list (the search panel closing, the reader closing): they go to the cursor's row. */
  fun keysReturned() {
    pendingFocus = saved.value?.takeIf(::laidOut)
  }

  // Only a row on screen takes the focus: one scrolled away would take it whenever it came back into view, from
  // whatever has it then.
  private fun laidOut(index: Int) = listState.layoutInfo.visibleItemsInfo.any { it.index == index }

  // Held keys repeat faster than a step that scrolls; a press during one is dropped.
  private var step: Job? by mutableStateOf(null)

  // Space and F act once per press, as the grid's Space and the reader's F do (#424, #432): held, F would favourite and
  // unfavourite by turns. E repeats, as the grid's does: keeping again only says "Already tab N".
  private val presses = OncePerPress()

  // The row Space went down on: it opens on Space's release (#433 review). Opened on the press, the reader would take the
  // focus with Space still down and page through the book on its repeats; and its release can't be relied on to reach
  // the reader, which takes the focus a frame or more later. The list has the focus until the release, so it sees it.
  private var opening: Int? = null

  /** The list lost the focus: a held key's release may go elsewhere, so the next press is a new one, and opens nothing. */
  fun letGo() {
    presses.letGo()
    opening = null
  }

  /**
   * The list's keys while no reader covers it: WASD (Shift+W/S a screen), and [act] with the cursor's row for Space
   * (on its release), E and F, which says whether it took the key (F on a series' row doesn't, so it reaches the shell).
   * A held Space or F's repeats are taken and do nothing. Letter keys with Cmd, Ctrl or Alt are app shortcuts, and
   * Shift+A/D the shell's.
   */
  fun onKey(event: KeyEvent, count: Int, act: (BookKey, Int) -> Boolean): Boolean {
    val newPress = presses.isNewPress(event)
    if (event.type == KeyEventType.KeyUp && event.key == Shortcuts.OpenHere.key) {
      val at = opening ?: return false
      opening = null
      // Only the row still under the cursor: a move or a reload while Space was down cancels the open.
      if (at < count && saved.value == at) act(BookKey.Open, at)
      return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCommand || event.isAltPressed || event.tabStep != null) return false
    val move = gridMoveFor(event.key)
    if (move != null) {
      if (step?.isActive != true) {
        val from = saved.value
        val page = event.isShiftPressed && (move == GridMove.Up || move == GridMove.Down)
        step = scope.launch { put(listState.moveBookCursor(from, move, page, count)) }
      }
      return true
    }
    val at = saved.value?.takeIf { it < count } ?: return false
    val key = when (event.key) {
      Shortcuts.OpenHere.key -> BookKey.Open
      Shortcuts.OpenInTab.key -> BookKey.Keep
      Shortcuts.Favourite.key -> BookKey.Favourite
      else -> return false
    }
    if (!newPress && key != BookKey.Keep) return true
    if (key == BookKey.Open) {
      opening = at
      return true
    }
    return act(key, at)
  }

  /** Hides the cursor, as before the first press: the list reloaded, so its row may hold another book. */
  fun hide() {
    put(null)
    opening = null
  }

  /** The reader opened from this list closed on result [index]: a cursor that shows moves to it, and the focus too. */
  internal fun readerClosedOn(index: Int) {
    if (saved.value == null) return
    saved.value = index
    pendingFocus = index.takeIf(::laidOut)
  }
}

/**
 * On desktop, the row at [index] is [cursor]'s focus target (#418): it takes the focus when the cursor moves to it,
 * and moves the cursor to it when it takes the focus. Android has no Books cursor keys, so it's left alone there.
 *
 * With the row itself focused, Space goes to the list's [onKey] before the row's click sees it: the click would take
 * its press, and the cursor opens on the release of a press it saw (#433). Its ♡ keeps its own Space.
 */
@Composable
internal fun Modifier.bookCursorFocus(cursor: BookCursor, index: Int, onKey: (KeyEvent) -> Boolean): Modifier {
  if (platformIsTouch) return this
  val focus = remember { FocusRequester() }
  var ownFocus by remember { mutableStateOf(false) }
  if (cursor.pendingFocus == index) {
    LaunchedEffect(Unit) {
      // A frame later, so it comes after the frame's KeyFocusHolder, which asks for the focus in the same frame as the
      // reader closes.
      withFrameNanos {}
      focus.requestFocus()
      cursor.focusTaken()
    }
  }
  return focusRequester(focus)
    .onFocusChanged {
      ownFocus = it.isFocused
      if (it.hasFocus) cursor.focused(index)
    }
    .onPreviewKeyEvent { ownFocus && it.key == Shortcuts.OpenHere.key && onKey(it) }
}

/** The cursor of a Books list over [listState], following the [reader] opened from its [results] (none yet: null). */
@Composable
internal fun rememberBookCursor(listState: LazyGridState, results: BookResults?, reader: BookReader): BookCursor {
  val saved = rememberSaveable { mutableStateOf<Int?>(null) }
  val scope = rememberCoroutineScope()
  val cursor = remember(listState) { BookCursor(listState, scope, saved) }
  // Keys handed back to the list go to the cursor's row, not the frame's KeyFocusHolder, or the next Tab would start
  // from the top and drag the ring there. Not while the reader is open: it holds the keys.
  val keyReturns = LocalKeyFocus.current.returns
  LaunchedEffect(cursor, keyReturns) { if (reader.session.value == null) cursor.keysReturned() }
  LaunchedEffect(cursor, results) {
    if (results == null) return@LaunchedEffect
    // The last result the reader showed from these results, until it closes.
    var last: Int? = null
    reader.session.filter { last != null || it?.results === results }.collect { session ->
      if (session?.results === results) {
        last = session.index
      } else {
        last?.let(cursor::readerClosedOn)
        last = null
      }
    }
  }
  return cursor
}

/** Every laid-out row of the list (not the footer) by index, in the list's item coordinates. */
private fun LazyGridState.rowBounds(count: Int): Map<Int, IntRect> =
  layoutInfo.visibleItemsInfo.filter { it.index < count }.associate { it.index to IntRect(it.offset, it.size) }

/**
 * The lowest item offset a row may end at to show whole. Item offsets start after the top content padding, which is
 * the ring's reach. The bottom padding is kept clear as well: on desktop it has room for the ring, and on touch it's
 * the room under the count pill, which would cover a row ending in it.
 */
private fun LazyGridState.shownBottom(): Int =
  layoutInfo.viewportSize.height - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding

private fun LazyGridState.fullyShows(r: IntRect): Boolean = r.top >= 0 && r.bottom <= shownBottom()

/**
 * Moves the cursor from [from] and returns where it lands, scrolling so that row shows whole. With no cursor yet, or
 * one scrolled out of view, it appears on the first wholly visible row and the list stays put. Up and down scroll half
 * a screen to find a row not laid out yet; [page] jumps a screen, to the row at the cursor's place on screen.
 */
private suspend fun LazyGridState.moveBookCursor(from: Int?, move: GridMove, page: Boolean, count: Int): Int? {
  val rows = rowBounds(count)
  val at = from?.let(rows::get) ?: return firstShown(rows) { fullyShows(it) } ?: from
  val height = layoutInfo.viewportSize.height.toFloat()
  val down = if (move == GridMove.Down) 1 else -1
  var scrolled = 0
  // The rows once the list has scrolled on by [share] of a screen, with [from] where it is now.
  suspend fun rowsScrolled(share: Float): Map<Int, IntRect> {
    scrolled += scrollBy(down * height * share).roundToInt()
    withFrameNanos {}
    return rowBounds(count) + (from to at.translate(0, -scrolled))
  }
  val next = when {
    page -> cellNear(rowsScrolled(PAGE_JUMP), at.center.x, at.center.y)
    move == GridMove.Left || move == GridMove.Right -> nextCell(rows, from, move)
    else -> nextCell(rows, from, move) ?: (1..LOOK_AHEAD_STEPS).firstNotNullOfOrNull { nextCell(rowsScrolled(0.5f), from, move) }
  }
  showWhole(next ?: from)
  return next ?: from
}

/** Scrolls the least distance that shows the whole row at [index], with room for the ring. */
private suspend fun LazyGridState.showWhole(index: Int) {
  if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
  val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
  val top = item.offset.y
  val bottom = top + item.size.height
  val delta = when {
    top < 0 -> top
    bottom > shownBottom() -> minOf(bottom - shownBottom(), top)
    else -> 0
  }
  if (delta != 0) scrollBy(delta.toFloat())
}

/** A Shift+W/S page jump, as a share of the viewport, so a row stays in sight across it. */
private const val PAGE_JUMP = 0.85f

/** How many half screens W/S scroll on looking for the next row before giving up. */
private const val LOOK_AHEAD_STEPS = 4
