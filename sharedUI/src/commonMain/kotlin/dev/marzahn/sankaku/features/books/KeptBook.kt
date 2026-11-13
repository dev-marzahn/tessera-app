package dev.marzahn.sankaku.features.books

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.ui.components.rememberAlreadyInTab
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.ClosedTabUndo
import dev.marzahn.sankaku.tabs.LocalTabEntry
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.UpNextState
import dev.marzahn.sankaku.tabs.bookSeenId
import org.koin.compose.koinInject

/** The route that keeps [this] book (#385): named, with its cover for the queue row and its rating for the blur. */
internal fun BookDto.keptRoute(): Route.Book = Route.Book(id, title.ifBlank { null }, coverPost?.md5, rating)

/**
 * A kept book's tab (#385): the reader as the tab's content, as a lone post's is. ✕ and the drag down close the item,
 * on touch with the pill's Undo and moving on as a lone post's drag down does ([ClosedTabUndo.dragClose]), on desktop
 * as the tab's ✕ does (Z reopens it).
 */
@Composable
fun KeptBookScreen(
  route: Route.Book,
  tabs: TabsController = koinInject(),
  undo: ClosedTabUndo = koinInject(),
  upNext: UpNextState = koinInject(),
  reader: BookReader = koinInject(),
) {
  val tab = LocalTabEntry.current?.tab
  // Reading progress (#384) as over the window: the strip reports its place, and it's sent when the book is left (the
  // tab closed or another shown) and when the app goes to the background.
  DisposableEffect(route.id) { onDispose { reader.sendProgress(route.id) } }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reader.sendProgress(route.id) }
  BookReaderScreen(
    OpenBook(route.id, route.title),
    onClose = {
      if (tab != null) {
        if (platformIsTouch) undo.dragClose(tab.id, upNext) else tabs.close(tab.id)
      }
    },
    onPlace = { reader.report(route.id, it) },
    tab = tab?.id,
  )
}

/**
 * Keeping books from a Books screen (#385), as a grid keeps posts: [keep] opens the book in the background ("Keep", a
 * long-press; a middle or Cmd/Ctrl-click), at the end of this Books item's block, or, kept already, keeps it again and
 * has its chip say "Already item N" (desktop: "Already tab N") or "Kept again". [marks] are a row's.
 */
@Stable
internal class BookKeeping(
  private val keepIn: (BookDto) -> Unit,
  private val marksOf: (BookDto) -> BookMarks,
) {
  fun keep(book: BookDto) = keepIn(book)

  fun marks(book: BookDto): BookMarks = marksOf(book)
}

/** The [BookKeeping] of the Books screen in this tab entry. */
@Composable
internal fun rememberBookKeeping(
  navigator: Navigator = koinInject(),
  tabs: TabsController = koinInject(),
  seen: SeenStore = koinInject(),
): BookKeeping {
  val source = LocalTabEntry.current?.tab
  val all by tabs.tabs.collectAsStateWithLifecycle()
  val revision by seen.revision.collectAsStateWithLifecycle()
  // The back stacks are snapshot state: a book kept, or its tab moved on from, shows at once.
  val kept by remember(all) { derivedStateOf { tabs.keptBooks(all) } }
  val already = rememberAlreadyInTab()
  return BookKeeping(
    keepIn = { book ->
      // Asked first: keeping it again makes it unseen (#182).
      val again = tabs.keptBookSeen(book.id, seen)
      navigator.openInNewTab(book.keptRoute())?.let { position -> already.show(book.id, position, again) }
    },
    marksOf = { book ->
      revision
      BookMarks(
        kept = book.id in kept,
        already = already.of(book.id),
        seen = source != null && tabs.seenFromBooks(source, book.id, seen),
      )
    },
  )
}

/**
 * Records the book [bookId] seen once it has been [shown] and is left (#385): closed, moved on from, or its tab
 * closed, as a post is seen on moving on after it was fully shown. The seen store keeps it apart from posts
 * ([bookSeenId]).
 */
@Composable
internal fun RecordBookSeen(bookId: String, shown: Boolean, seen: SeenStore = koinInject()) {
  // Once shown it stays so: a book swiped past (#382) is no longer the active one by the time it leaves.
  val wasShown = remember(bookId) { booleanArrayOf(false) }
  if (shown) wasShown[0] = true
  DisposableEffect(bookId) {
    onDispose { if (wasShown[0]) seen.record(bookSeenId(bookId), null) }
  }
}
