package dev.marzahn.sankaku.features.books

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.features.post.aspectRatio
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.tabStep
import dev.marzahn.sankaku.ui.components.spatialSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The book reader over the whole window while [BookReader] has a book open (#381), above the tabs, the sidebar and the
 * phone's pages. Each opening is a composition of its own ([ReaderSession.opening]); moving between results isn't.
 *
 * A debug run's `openBook` flag ([DebugFlags.openBook]) opens that book once at launch, to try the reader without the
 * Books list.
 */
@Composable
fun BookReaderPage(reader: BookReader = koinInject(), flags: DebugFlags = koinInject()) {
  var debugOpened by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(flags.openBook) {
    if (debugOpened) return@LaunchedEffect
    debugOpened = true
    flags.openBook?.let { reader.open(it) }
  }
  // Going to the background sends the open book's place (#384); closing or moving to another book sends it through
  // [BookReader] itself.
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reader.sendInBackground() }
  val session by reader.session.collectAsStateWithLifecycle()
  val shown = session ?: return
  key(shown.opening) { BookReaderFrame(shown, reader) }
}

/**
 * One opening of the reader (#382). Opened from a row ([ReaderSession.results]), it grows out of the row's cover as a
 * grid cell grows into the post viewer: the black frame grows from the cover to the window on the slow spatial spring,
 * while the cover flies to page 1's place at the strip's top and the strip fades in under it. Closing runs it
 * backwards into the row of the book shown then, scrolled whole into view first; with no row on screen (or opened on
 * its own) the reader fades in and out instead.
 *
 * The results are a pager: ← → (A/D) and sideways swipes go to the previous or next book, loading more results near the
 * end as the post viewer does. A swipe starts only once it's clearly sideways, so the strip's vertical scroll keeps
 * its drags; a zoomed strip pans first and lets the pager have the swipe at its edge (zoomimage's own rule).
 */
@Composable
private fun BookReaderFrame(session: ReaderSession, reader: BookReader) {
  val results = session.results
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  // The reader's own bounds in the window, which the rows' covers are measured in too.
  var frame by remember { mutableStateOf(Rect.Zero) }
  // The cover the reader grows from or shrinks into, in window coordinates, and its picture.
  var cover by remember { mutableStateOf(results?.peek(session.index)?.let { results.coverOf(it.book.id) }) }
  var coverPost by remember { mutableStateOf(results?.peek(session.index)?.book?.coverPost) }
  val progress = remember { Animatable(0f) }
  var closing by remember { mutableStateOf(false) }
  val openSpec = spatialSpec<Float>(slow = true)
  val closeSpec = spatialSpec<Float>()
  LaunchedEffect(Unit) { progress.animateTo(1f, openSpec) }
  // Opening, the cover stays over page 1's place until the book's pages are there, then hands over to page 1, which
  // starts as the same preview: without waiting, the cover gave way to a loading indicator.
  var bookShown by remember { mutableStateOf(false) }
  val coverFade = remember { Animatable(1f) }
  val fadeSpec = effectsSpec<Float>()
  LaunchedEffect(bookShown) {
    if (!bookShown) return@LaunchedEffect
    snapshotFlow { progress.value >= LANDED }.first { it }
    coverFade.animateTo(0f, fadeSpec)
  }

  val coverGone by remember { derivedStateOf { coverFade.value == 0f } }

  val pager = rememberPagerState(initialPage = session.index) { results?.count ?: 1 }
  // A result whose book has loaded (or failed) while the pager was settled on it. The books either side are composed,
  // and so load, only once the first one has: book preloads start once the current page has loaded. From then on they
  // stay, or a swipe dropped the book it came from and swiping back asked for it again.
  var loadedPage by remember { mutableIntStateOf(-1) }
  if (results != null) {
    // The row the reader stands in for keeps its cover hidden, so it isn't drawn twice while the reader moves.
    SideEffect { results.hiddenCover = results.peek(pager.currentPage)?.book?.id }
    DisposableEffect(results) { onDispose { results.hiddenCover = null } }
  }

  fun close() {
    if (closing) return
    closing = true
    scope.launch {
      val index = pager.currentPage
      val result = results?.peek(index)
      // Continued into the next book of its series in place, the reader shows a book the row doesn't: it fades.
      val rowShown = result != null && reader.session.value?.book?.id == result.book.id
      if (results != null && result != null && rowShown) {
        results.hiddenCover = result.book.id
        results.reveal(index)
        // The row's new place is laid out a frame or two after the scroll.
        repeat(2) { withFrameNanos {} }
        cover = results.coverOf(result.book.id)?.takeIf { it.overlaps(frame) }
        coverPost = result.book.coverPost
      } else {
        cover = null
      }
      progress.animateTo(0f, closeSpec)
      reader.close()
    }
  }

  fun step(forward: Boolean): Boolean {
    val target = pager.currentPage + if (forward) 1 else -1
    if (results == null || target !in 0 until pager.pageCount) return false
    scope.launch { pager.animateScrollToPage(target) }
    return true
  }

  val maxStrip = with(density) { MAX_STRIP_WIDTH.toPx() }
  val cornerPx = with(density) { COVER_CORNER.toPx() }
  val grows = cover != null && coverPost != null
  // Where the frame is at [p]: the cover's bounds (from the frame's own corner) grown to the frame's.
  fun frameAt(p: Float): Rect {
    val whole = Rect(Offset.Zero, frame.size)
    val from = cover?.translate(-frame.topLeft) ?: return whole
    return if (grows) lerp(from, whole, p) else whole
  }

  Box(
    Modifier
      .fillMaxSize()
      .onGloballyPositioned { frame = it.boundsInWindow() }
      .onKeyEvent { event ->
        if (closing || results == null || event.isCommand || event.tabStep != null) return@onKeyEvent false
        val forward = when {
          Shortcuts.Previous.matches(event.key) -> false
          Shortcuts.Next.matches(event.key) -> true
          else -> return@onKeyEvent false
        }
        // The release is the reader's too: left to the window, it moved focus sideways.
        if (event.type == KeyEventType.KeyDown) step(forward)
        true
      },
  ) {
    // The reader, laid out whole throughout (a strip remeasured on every frame would ask for its pages again at each
    // width), shown through the frame as it grows.
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          val p = progress.value
          if (grows) {
            val rect = frameAt(p)
            clip = true
            shape = RectAt(rect, lerp(cornerPx, 0f, p.coerceIn(0f, 1f)))
            // The black comes and goes near the cover, so the cover alone lands in its row.
            alpha = frameAlpha(p)
          } else {
            alpha = p.coerceIn(0f, 1f)
          }
        }
        .background(Color.Black),
    ) {
      Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (grows) stripAlpha(progress.value) else 1f }) {
        ResultsPager(
          pager = pager,
          session = session,
          results = results,
          closing = closing,
          neighbours = loadedPage >= 0,
          onClose = ::close,
          onShowing = reader::showing,
          onLoaded = { page ->
            loadedPage = page
            if (page == session.index) bookShown = true
          },
        )
      }
    }
    val flying = coverPost
    val from = cover
    if (grows && flying != null && from != null && (closing || !coverGone)) {
      // The cover, flying between its row and page 1's place at the strip's top: what page 1 shows at first, its
      // preview, so it hands over to the strip as that fades in.
      val locked = results?.locked == true
      FlyingCover(
        post = flying,
        locked = locked,
        rect = {
          val p = progress.value
          val whole = frame.size
          val width = minOf(whole.width, maxStrip)
          val slot = Rect(Offset((whole.width - width) / 2f, 0f), Size(width, width / aspectRatio(flying)))
          lerp(from.translate(-frame.topLeft), slot, p)
        },
        alpha = { if (closing) 1f - stripAlpha(progress.value) else coverFade.value },
        corner = { lerp(cornerPx, 0f, progress.value.coerceIn(0f, 1f)) },
      )
    }
  }
}

/** The pager over the results, or the one book opened on its own. */
@Composable
private fun ResultsPager(
  pager: PagerState,
  session: ReaderSession,
  results: BookResults?,
  closing: Boolean,
  neighbours: Boolean,
  onClose: () -> Unit,
  onShowing: (index: Int, book: OpenBook) -> Unit,
  onLoaded: (index: Int) -> Unit,
) {
  val scope = rememberCoroutineScope()
  HorizontalPager(
    state = pager,
    userScrollEnabled = results != null && !closing,
    // Once the current book has loaded, the books either side are ready for a swipe, and swiping back finds the last
    // one where it was.
    beyondViewportPageCount = if (neighbours) 1 else 0,
    modifier = Modifier.fillMaxSize(),
  ) { page ->
    val result = if (results == null) null else results.bookAt(page) ?: return@HorizontalPager
    // A series' list's results are its books: the next one is the next result, and the series isn't asked for.
    val seriesList = results?.seriesId != null
    ResultPage(
      start = result?.open ?: session.book,
      seriesId = result?.seriesId?.takeUnless { seriesList },
      sheetSeries = result?.seriesId?.let { SheetSeries(it, listed = seriesList) },
      nextResult = results?.peek(page + 1)?.book,
      seriesList = seriesList,
      listed = results != null,
      // Only once the pager has settled on it: taking focus mid-swipe brought the book into view on top of the
      // swipe's own scroll, which then went a book too far.
      current = page == pager.settledPage && !closing,
      locked = results?.locked == true,
      onClose = onClose,
      onNextResult = { scope.launch { pager.animateScrollToPage(page + 1) } },
      onShowing = { onShowing(page, it) },
      onLoaded = { onLoaded(page) },
    )
  }
}

/**
 * One result: its book in the strip, ending with the end card (#503): the next volume when the reader knows the
 * series (#377 decision 13), the [nextResult] of the results it's from, and the way back. In a [seriesList] the next
 * result is the next volume, so the card pages to it; from a series' row in the Books list, the next volume continues in
 * place. While [current], it reports the book it shows ([onShowing]) and, once that has loaded or failed, [onLoaded].
 */
@Composable
private fun ResultPage(
  start: OpenBook,
  seriesId: String?,
  sheetSeries: SheetSeries?,
  nextResult: BookDto?,
  seriesList: Boolean,
  listed: Boolean,
  current: Boolean,
  locked: Boolean,
  onClose: () -> Unit,
  onNextResult: () -> Unit,
  onShowing: (OpenBook) -> Unit,
  onLoaded: () -> Unit,
  books: BooksRepository = koinInject(),
  reader: BookReader = koinInject(),
) {
  var book by remember(start.id) { mutableStateOf(start) }
  val showing by rememberUpdatedState(onShowing)
  LaunchedEffect(current, book) { if (current) showing(book) }
  // A series' row's series, asked for once the pager has settled on it; a series' list has its books already.
  val series by produceState<List<BookDto>?>(null, seriesId, locked, current) {
    if (seriesId == null || value != null || !current) return@produceState
    value = try {
      books.series(seriesId, locked).books
    } catch (_: ApiError) {
      null
    }
  }
  // On the result's own book, a series' list pages on to its next result, its next volume; a series' row continues
  // into its next volume in place.
  val pagesOn = seriesList && nextResult != null && book.id == start.id
  val nextVolume = if (pagesOn) nextResult else series?.let { nextInSeries(it, book.id) }
  val end = BookEnd(
    nextVolume = nextVolume,
    onNextVolume = { nextVolume?.let { if (pagesOn) onNextResult() else book = OpenBook(it.id, it.title) } },
    nextResult = nextResult?.takeUnless { seriesList || it.id == nextVolume?.id },
    onNextResult = onNextResult,
    back = if (listed) BACK_TO_BOOKS else CLOSE_BOOK,
    locked = locked,
  )
  key(book.id) {
    OwnViewModels {
      // The screen's own view model (the same owner and class): loaded or failed, the book shows something.
      val state by koinViewModel<BookReaderViewModel> { parametersOf(book.id) }.state.collectAsStateWithLifecycle()
      val loaded by rememberUpdatedState(onLoaded)
      val settled = state !is BookUiState.Loading
      LaunchedEffect(settled, current) { if (settled && current) loaded() }
      BookReaderScreen(
        book,
        onClose = onClose,
        // Where the strip is, held by the reader until it sends it (#384).
        onPlace = { reader.report(book.id, it) },
        active = current,
        series = sheetSeries,
        end = end,
      )
    }
  }
}

@Composable
private fun OwnViewModels(content: @Composable () -> Unit) {
  val owner = remember {
    object : ViewModelStoreOwner {
      override val viewModelStore = ViewModelStore()
    }
  }
  DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
  CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}


/**
 * The cover between its row and page 1's place: laid out at [rect] (window-relative to the reader), cropped as the
 * row crops it, rounding off at its corners. Grey, as on its row, when the rating keeps it hidden.
 */
@Composable
private fun FlyingCover(post: PostDto, locked: Boolean, rect: () -> Rect, alpha: () -> Float, corner: () -> Float) {
  BookPicture(
    post,
    locked,
    Modifier
      .layout { measurable, _ ->
        val r = rect()
        val width = r.width.toInt().coerceAtLeast(1)
        val height = r.height.toInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(r.left.toInt(), r.top.toInt()) }
      }
      .graphicsLayer {
        this.alpha = alpha()
        clip = true
        shape = RoundedCornerShape(corner())
      },
  )
}

/** The strip's opacity at the transition's [progress]: in over its second half, so the cover lands first. */
internal fun stripAlpha(progress: Float): Float = ((progress - STRIP_FADE_FROM) / (1f - STRIP_FADE_FROM)).coerceIn(0f, 1f)

/** The black frame's opacity at [progress]: it comes in over the first stretch from the cover and goes over the last. */
internal fun frameAlpha(progress: Float): Float = (progress / FRAME_FADE_TO).coerceIn(0f, 1f)

private const val STRIP_FADE_FROM = 0.5f
private const val FRAME_FADE_TO = 0.25f

/** Near enough 1 that the opening spring has landed, to the eye. */
private const val LANDED = 0.99f

/** A row's cover's corners, which the frame and the flying cover start and end with. */
private val COVER_CORNER = 8.dp

/** A rounded rectangle at [rect] within the layer: the frame while it grows from the cover. */
private class RectAt(private val rect: Rect, private val corner: Float) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
    Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
}
