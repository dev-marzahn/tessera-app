package dev.marzahn.sankaku.features.books

import androidx.compose.animation.AnimatedVisibility
import dev.marzahn.sankaku.ui.components.ButtonHint
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.Disposable
import com.github.panpf.zoomimage.compose.zoom.ZoomableState
import com.github.panpf.zoomimage.compose.zoom.rememberZoomableState
import com.github.panpf.zoomimage.compose.zoom.zoom
import com.github.panpf.zoomimage.zoom.ScalesCalculator
import com.google.fonts.icons.close
import com.google.fonts.icons.info
import com.google.fonts.icons.more_vert
import com.google.fonts.icons.open_in_new
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.PlatformBackHandler
import dev.marzahn.sankaku.PlatformSystemBars
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.PostTitle
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.data.BookFavourite
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.favourite
import dev.marzahn.sankaku.data.BookPlace
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.post.AnimatedSample
import dev.marzahn.sankaku.features.post.PostVideo
import dev.marzahn.sankaku.features.post.VideoPlayback
import dev.marzahn.sankaku.features.post.rememberVideoPlayback
import dev.marzahn.sankaku.features.post.aspectRatio
import dev.marzahn.sankaku.features.post.isAnimated
import dev.marzahn.sankaku.features.post.isFlash
import dev.marzahn.sankaku.features.post.isVideo
import dev.marzahn.sankaku.features.post.platformPlaysVideo
import dev.marzahn.sankaku.images.ImageFailure
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.failure
import dev.marzahn.sankaku.images.fittedTo
import dev.marzahn.sankaku.images.postImageRequest
import dev.marzahn.sankaku.platformBackKeys
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.shell.RetryWhenBack
import dev.marzahn.sankaku.features.tag.TagPages
import dev.marzahn.sankaku.features.tag.rememberTagPageShown
import dev.marzahn.sankaku.tabs.LocalOpenTabList
import dev.marzahn.sankaku.tabs.QueueBar
import dev.marzahn.sankaku.tabs.TabId
import dev.marzahn.sankaku.tabs.queueSwipe
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import dev.marzahn.sankaku.ui.components.ApiErrorState
import dev.marzahn.sankaku.ui.components.FavouriteBursts
import dev.marzahn.sankaku.ui.components.PictureBurstSize
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.HeartToggle
import dev.marzahn.sankaku.ui.components.ImageFailed
import dev.marzahn.sankaku.ui.components.KEY_SCROLL_SCREEN
import dev.marzahn.sankaku.ui.components.KeyScroll
import dev.marzahn.sankaku.ui.components.KeyScrollLine
import dev.marzahn.sankaku.ui.components.LargeIndicatorSize
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.OncePerPress
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.isPlain
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.sqrt

/**
 * A book's pages as one vertical strip (#377 decisions 2, 3, 8, 9, 14): pages fill the width with no gaps, in a column
 * up to [MAX_STRIP_WIDTH] wide, zoomed as a whole by one zoomimage zoom. A tap shows or hides the chrome (the top row
 * with the page count and ⓘ for the book sheet, #484, and on touch the queue's count pill, #483), which opens shown
 * and hides once the strip scrolls; while it's hidden the phone hides its system bars too. ✕, Esc, Back and a drag down that starts at the strip's top close it. F toggles the book's ♡, as the top
 * row's button does (#421), and either plays the heart burst over the strip (#438). [videoPlayback] makes each video
 * page's player (a test's fake).
 *
 * Reading progress (#384, #503): the book opens at its saved page ([ReadingProgress.savedOf]; page 1 with none, or once
 * read), with a "Page N · Start over" chip. Once the strip has left the page it opened at, or Start over was chosen,
 * every page that comes to take most of the screen goes to [onPlace]; the reader sends the last one when it leaves
 * the book.
 *
 * Between books (#382) the reader's pager shows two books mid-swipe: only the [active] one holds the keys, Back and
 * the system bars. The end card follows the last page (#503): "Finished", then what [end] offers next, and [onClose]
 * under the name [BookEnd.back] gives it. [series] is the series
 * the book sheet shows (#383), when the reader knows it.
 *
 * In a kept book's [tab] (#385) it's the tab's content, as a lone post is: ✕ and the drag down close the item
 * ([onClose]), Back and Esc are the shell's (the previous item), a sideways swipe moves between queue items, and it
 * takes the keys once, as it's shown, not back from whatever takes them after. A book is seen once it was the active
 * one with its pages shown, and is left ([RecordBookSeen]).
 */
@Composable
internal fun BookReaderScreen(
  open: OpenBook,
  onClose: () -> Unit,
  onPlace: (BookPlace) -> Unit = {},
  active: Boolean = true,
  end: BookEnd = BookEnd(),
  series: SheetSeries? = null,
  tab: TabId? = null,
  touch: Boolean = platformIsTouch,
  navigator: Navigator = koinInject(),
  privacy: PrivacySettings = koinInject(),
  actions: BookActions = koinInject(),
  books: BooksRepository = koinInject(),
  progress: ReadingProgress = koinInject(),
  tagPages: TagPages = koinInject(),
  videoPlayback: @Composable () -> VideoPlayback = { rememberVideoPlayback() },
) {
  val viewModel = koinViewModel<BookReaderViewModel> { parametersOf(open.id) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  val overrides by actions.favourites.overrides.collectAsStateWithLifecycle()
  val favouriteRejected = rememberBookRejections(actions, open.id)
  val loaded = state as? BookUiState.Loaded
  val title = loaded?.book?.title?.takeIf { it.isNotBlank() } ?: open.title.orEmpty()
  val pages = loaded?.pages.orEmpty()
  var chromeShown by remember { mutableStateOf(true) }
  var sheetOpen by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val zoom = rememberZoomableState()
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val close by rememberUpdatedState(onClose)
  val dragClose = remember(density) { DragToClose(closeAt = with(density) { DRAG_CLOSE_DISTANCE.toPx() }) { close() } }
  val current by remember(listState) { derivedStateOf { currentPage(listState) } }
  // The page the book opens at (#503): its saved place, read once the pages are in; 0 with none, or once it was read.
  val savedIndex = remember(loaded) { loaded?.let { openingPageIndex(it.pages, progress.savedOf(it.book)) } ?: 0 }
  // Asked before the strip's first layout, so page 1 never shows first. A request, not a scroll: it's made in
  // composition, as the pages come in.
  remember(loaded) { if (savedIndex > 0) listState.requestScrollToItem(savedIndex) }
  // Whether the reader has moved on from the page it opened at, or chose Start over. Until then nothing is reported,
  // so a look keeps the saved place (#490). A book of one page is read once it's open.
  var moved by remember { mutableStateOf(false) }
  val place by rememberUpdatedState(onPlace)
  LaunchedEffect(pages, savedIndex) {
    if (pages.isEmpty()) return@LaunchedEffect
    if (pages.size == 1) moved = true
    // The page it opened at, once the strip is laid out there.
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
    val opened = current
    snapshotFlow { current to moved }.collect { (page, _) ->
      if (page != opened) moved = true
      if (moved) place(bookPlace(pages, page))
    }
  }

  // The book itself, as its pages do (#491).
  RetryWhenBack { if ((viewModel.state.value as? BookUiState.Failed)?.unreachable == true) viewModel.load() }
  PlatformBackHandler(enabled = active && tab == null, onBack = onClose)
  RecordBookSeen(open.id, shown = active && loaded?.pages?.isNotEmpty() == true)
  // Light icons on the black strip; while the chrome is hidden, the bars too.
  if (active) PlatformSystemBars(hidden = !chromeShown, lightIcons = true)
  // Any scroll hides the chrome: the book opens with it shown, and a tap brings it back.
  LaunchedEffect(listState) { snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { chromeShown = false } }
  // The reader keeps the keys while it's open: a tab under it (a restored post) asks for focus as it loads, after the
  // reader has. Only its own ⋮ menu and the book sheet take focus from it, and the book a swipe or ← → moves to.
  val focus = remember { FocusRequester() }
  var menuOpen by remember { mutableStateOf(false) }
  // Nor while a tag's page is over it (#522): taking the focus back from it, the reader got the page's Esc.
  val ownsFocus = active && !menuOpen && !sheetOpen && !rememberTagPageShown(tagPages)
  val keepFocus by rememberUpdatedState(ownsFocus)
  LaunchedEffect(ownsFocus) { if (ownsFocus) focus.requestFocus() }
  // A frame after losing it, so a screen that asks in the same frame as the reader doesn't keep it.
  val favouriteKey = remember { OncePerPress() }
  val takeFocusBack: (FocusState) -> Unit = { state ->
    // F's release may go to what took the focus (sign-in, the sheet): the next F is a new press.
    if (!state.hasFocus) favouriteKey.letGo()
    // In a kept book's tab (#385) it takes the keys once, as it's shown, not back from whatever takes them after.
    if (tab == null && !state.hasFocus && keepFocus) {
      scope.launch {
        withFrameNanos {}
        if (keepFocus) focus.requestFocus()
      }
    }
  }

  val keyScroll = remember(listState) { KeyScroll(listState) }
  val onKey: (KeyEvent) -> Boolean = { event ->
    val newPress = favouriteKey.isNewPress(event)
    // F toggles the book's ♡ as the top row's button does (#421), once per press; with Cmd or Ctrl it's the search's,
    // as in the viewer.
    if (event.type == KeyEventType.KeyDown && !event.isCommand && event.key == Shortcuts.Favourite.key) {
      if (newPress) loaded?.book?.let(actions::toggleFavourite)
      true
    } else if (event.type == KeyEventType.KeyDown && event.isPlain && Shortcuts.Menu.matches(event.key)) {
      // R opens the top row's ⋮ (#443), once per press, showing the row if a scroll hid it; the menu takes the focus as
      // it opens.
      if (newPress) {
        chromeShown = true
        menuOpen = true
      }
      true
    } else {
      val step = stripKeyStep(event, listState, zoom, with(density) { KeyScrollLine.toPx() })
      if (step != null) keyScroll.by(step, scope)
      step != null
    }
  }
  // The app's theme, read before the reader goes dark, for the book sheet.
  val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  SankakuTheme(isDark = true) {
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer { translationY = dragClose.offset }
        .background(Color.Black)
        .semantics { paneTitle = title.ifEmpty { "Book" } }
        .onFocusChanged(takeFocusBack)
        .focusRequester(focus)
        // A book either side of the current one (#382) can't take focus: an arrow key moving focus to it would page.
        .focusable(enabled = active)
        .then(if (tab == null) Modifier.platformBackKeys(onClose) else Modifier)
        .onKeyEvent(onKey)
        // In a kept book's tab, a sideways swipe moves between queue items (#385), at 1× only, as on a lone post.
        .queueSwipe(enabled = !zoomedIn(zoom), from = tab, claimSideways = tab != null),
    ) {
      when (val s = state) {
        BookUiState.Loading -> AppLoadingIndicator(Modifier.align(Alignment.Center), size = LargeIndicatorSize)
        is BookUiState.Failed -> ApiErrorState(
          s.error,
          onRetry = viewModel::load,
          modifier = Modifier.align(Alignment.Center),
          onSignIn = koinInject<SignInController>()::open,
        )
        // A pending book can come with no pages (`docs/api.md` → Books): say so, under the top row's ✕.
        is BookUiState.Loaded -> if (s.pages.isEmpty()) {
          Text(
            NO_PAGES,
            Modifier.align(Alignment.Center).padding(24.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Strip(
            pages = s.pages,
            gOnly = gOnly,
            listState = listState,
            zoom = zoom,
            dragClose = dragClose,
            current = current,
            onTap = { chromeShown = !chromeShown },
            videoPlayback = videoPlayback,
            end = { EndCard(title, s.pages.size, end, onBack = onClose) },
            active = active,
          )
        }
      }
      // F's or the ♡'s heart, over the strip in the window's centre, as the viewer's over its picture (#438).
      val hearts = remember(actions, open.id) { actions.hearts.filter { it.bookId == open.id }.map { it.favourited } }
      FavouriteBursts(hearts, Modifier.align(Alignment.Center), size = PictureBurstSize)
      CompositionLocalProvider(LocalContentColor provides Color.White) {
        AnimatedVisibility(chromeShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
          TopRow(
            title = title,
            pageLabel = pages.takeIf { it.isNotEmpty() }?.let { pageLabel(current, it.size) },
            favourite = loaded?.book?.favourite(overrides),
            favouriteRejected = favouriteRejected,
            onToggleFavourite = { loaded?.book?.let(actions::toggleFavourite) },
            onClose = onClose,
            onDetails = { sheetOpen = true }.takeIf { loaded != null },
            menuOpen = menuOpen,
            onMenuOpen = { menuOpen = it },
            page = pages.getOrNull(current)?.takeUnless { hiddenByGOnly(it, gOnly) || it.redirectToSignup },
            onOpenPage = { page ->
              // Over the window the reader closes first; a kept book stays in the queue, and the page opens after it.
              if (tab == null) onClose()
              // Not from a search, so it opens without one; named by its tags as a recommended post is.
              navigator.goTo(Route.Post(page.id, page.md5, emptyList(), PostTitle.of(page), page.rating))
            },
          )
        }
      }
      // Touch: the queue's count pill (#483), as on a lone post, while the chrome is shown; a tap opens the queue page.
      // Over the window the reader closes first, as the queue page is under it.
      val openQueuePage = LocalOpenTabList.current
      val pillShown = touch && chromeShown
      Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(savedIndex > 0 && !moved, enter = fadeIn(), exit = fadeOut()) {
          ResumeChip(
            page = savedIndex + 1,
            onStartOver = {
              moved = true
              scope.launch { listState.scrollToItem(0) }
            },
            modifier = Modifier.padding(bottom = 12.dp),
          )
        }
        AnimatedVisibility(pillShown, enter = fadeIn(), exit = fadeOut()) {
          QueueBar(
            onOpenQueue = {
              if (tab == null) onClose()
              openQueuePage()
            },
            bottom = BottomRowLayout.WindowRoom,
            // Over the window the active item is the one under the reader: ✕, ‹ › and Undo would act on it.
            openOnly = tab == null,
          )
        }
        if (!pillShown) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
      }
      // The series, asked for once the sheet opens; until it answers (or if it fails) the sheet shows none.
      val shownSeries by produceState<InSeries?>(null, series, open.id, sheetOpen) {
        value = null
        if (series == null || !sheetOpen) return@produceState
        value = try {
          inSeries(books.series(series.id, ratingLocked = false), open.id)
        } catch (_: ApiError) {
          null
        }
      }
      // In the app's own theme, light in light mode, as the post viewer's details are (#484); the strip stays black.
      if (sheetOpen && loaded != null) SankakuTheme(isDark = appDark) {
        val book = loaded.book
        BookSheet(
          book = book,
          pages = pages.size,
          favourite = book.favourite(overrides),
          favouriteRejected = favouriteRejected,
          series = shownSeries,
          onToggleFavourite = { actions.toggleFavourite(book) },
          // In this tab, over which the reader closes; in a new one, behind the reader, which stays. Opened from the
          // series' own list, closing the reader is going back to it.
          // A kept book (#385) stays in the queue: the sheet closes and the search opens "now", as from a post.
          onOpen = { route ->
            if (tab == null) onClose() else sheetOpen = false
            if (tab != null || !(route is Route.BookSeries && series?.listed == true)) navigator.goTo(route)
          },
          onOpenInNewTab = { route -> navigator.openInNewTab(route) },
          // A tag's page (#486) comes over the reader, which stays under it.
          onOpenTag = tagPages::open,
          onDismiss = { sheetOpen = false },
        )
      }
    }
  }
}

/**
 * The pages in their zoom: a tap goes to [onTap] once zoomimage has ruled out a double-tap, which zooms.
 */
@Composable
private fun Strip(
  pages: List<PostDto>,
  gOnly: Boolean,
  listState: LazyListState,
  zoom: ZoomableState,
  dragClose: DragToClose,
  current: Int,
  onTap: () -> Unit,
  videoPlayback: @Composable () -> VideoPlayback,
  end: @Composable () -> Unit,
  active: Boolean,
) {
  LaunchedEffect(zoom) {
    zoom.setScalesCalculator(ScalesCalculator.fixed(DOUBLE_TAP_SCALE))
    zoom.setRubberBandScale(false)
  }
  // Pointers down now. Two or more are a pinch: the strip stops scrolling, so zoomimage gets the whole gesture.
  var pointers by remember { mutableIntStateOf(0) }
  val tap by rememberUpdatedState(onTap)
  val zoomed by remember(zoom) { derivedStateOf { zoomedIn(zoom) } }
  val dragEnabled = platformIsTouch && pointers < 2 && !zoomed
  SideEffect { dragClose.enabled = dragEnabled }
  val visible by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.index } } }
  val loadedPages = remember { mutableStateSetOf<Int>() }
  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .pointerInput(listState, dragClose) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.count { it.pressed }
            // A drag closes only if it started with the strip at its top.
            if (pointers == 0 && down > 0) dragClose.startedAtTop = !listState.canScrollBackward
            pointers = down
          }
        }
      }
      .zoom(zoom, onTap = { _: Offset -> tap() }),
  ) {
    val width = with(LocalDensity.current) { min(maxWidth, MAX_STRIP_WIDTH).roundToPx() }
    // A neighbouring book, ready for a swipe (#382), shows its first pages but loads none ahead and plays nothing.
    if (active) Preload(pages, current, current in loadedPages, width, gOnly)
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().nestedScroll(dragClose),
      horizontalAlignment = Alignment.CenterHorizontally,
      userScrollEnabled = pointers < 2,
    ) {
      itemsIndexed(pages) { index, page ->
        StripPage(
          page = page,
          description = "Page ${index + 1} of ${pages.size}",
          gOnly = gOnly,
          width = width,
          scale = { zoom.transform.scaleX },
          playing = active && index in visible,
          onLoaded = { loadedPages += index },
          videoPlayback = videoPlayback,
          modifier = Modifier.widthIn(max = MAX_STRIP_WIDTH).fillMaxWidth(),
        )
      }
      // Always last: [currentPage] counts on it.
      item(key = END_KEY) { end() }
    }
  }
}

/**
 * Loads the [PRELOAD_PAGES] pages after [current] through `ImageLoader.enqueue`, once [currentLoaded], with the
 * requests the pages make themselves ([pageRequest]). A page that falls out of the 3 after [current] has its load
 * cancelled; one still in them keeps loading while the new current page loads. A page on screen makes its own request.
 */
@Composable
private fun Preload(pages: List<PostDto>, current: Int, currentLoaded: Boolean, width: Int, gOnly: Boolean) {
  val context = LocalPlatformContext.current
  val imageLoader = koinInject<ImageLoader>()
  val inFlight = remember { HashMap<Int, Disposable>() }
  LaunchedEffect(width, gOnly) {
    inFlight.values.forEach { it.dispose() }
    inFlight.clear()
  }
  LaunchedEffect(current, currentLoaded, width, gOnly) {
    val ahead = preloadedPages(current, pages.size)
    inFlight.keys.filter { it !in ahead }.forEach { inFlight.remove(it)?.dispose() }
    if (!currentLoaded) return@LaunchedEffect
    for (index in ahead) {
      if (index in inFlight) continue
      pageRequest(context, pages[index], width, gOnly)?.let { inFlight[index] = imageLoader.enqueue(it) }
    }
  }
  DisposableEffect(Unit) {
    onDispose {
      inFlight.values.forEach { it.dispose() }
      inFlight.clear()
    }
  }
}

/**
 * One page, [width] px wide: its preview until its sample shows ([pagePreviewRequest]), its sample, and its full
 * file over it once zoomed past the sample's resolution ([needsFullFile]); a video or GIF plays in place, muted, while [playing] (on screen). With G only on, a page of
 * another rating is a band instead. [onLoaded] runs once the page shows its sample or band (not its
 * preview), for the preload.
 */
@Composable
private fun StripPage(
  page: PostDto,
  description: String,
  gOnly: Boolean,
  width: Int,
  scale: () -> Float,
  playing: Boolean,
  onLoaded: () -> Unit,
  modifier: Modifier,
  videoPlayback: @Composable () -> VideoPlayback,
) {
  val loaded by rememberUpdatedState(onLoaded)
  val band = when {
    hiddenByGOnly(page, gOnly) -> "Hidden by G only"
    page.redirectToSignup -> "Sign in to view this page"
    else -> null
  }
  if (band != null) {
    LaunchedEffect(Unit) { loaded() }
    Band(band, modifier.semantics { contentDescription = "$description, ${band.lowercase()}" })
    return
  }
  val context = LocalPlatformContext.current
  var attempt by remember(page.id) { mutableIntStateOf(0) }
  var failure by remember(page.id, attempt) { mutableStateOf<ImageFailure?>(null) }
  var shown by remember(page.id, attempt) { mutableStateOf(false) }
  LaunchedEffect(shown) { if (shown) loaded() }
  RetryWhenBack { if (failure == ImageFailure.Unreachable) attempt++ }
  Box(
    modifier
      .aspectRatio(aspectRatio(page))
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .semantics { contentDescription = description },
  ) {
    if (!shown) {
      val preview = remember(page.id, width) { pagePreviewRequest(context, page, width) }
      if (preview != null) {
        AsyncImage(
          model = preview,
          contentDescription = null,
          imageLoader = koinInject(),
          contentScale = ContentScale.FillBounds,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    key(attempt) {
      val request = remember(page.id, width) { pageRequest(context, page, width, gOnly = false) }
      AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = koinInject(),
        contentScale = ContentScale.FillBounds,
        onState = {
          if (it is AsyncImagePainter.State.Success) shown = true
          failure = it.failure()
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    when {
      isFlash(page) || (isVideo(page) && !platformPlaysVideo(page)) -> Unplayable(Modifier.align(Alignment.Center))
      isVideo(page) -> PostVideo(page, Modifier.fillMaxSize(), play = playing, shown = true, bookPage = true, player = videoPlayback())
      isAnimated(page) -> if (playing) AnimatedSample(page, Modifier.fillMaxSize(), onShown = {})
      else -> FullFile(page, width, scale)
    }
    val failed = failure
    if (failed != null && !shown) {
      ImageFailed(noImage = failed == ImageFailure.NoImage, onRetry = { attempt++ }, modifier = Modifier.fillMaxSize())
    }
  }
}

/**
 * [page]'s full file over its sample, from the moment the strip's zoom ([scale]) shows it wider than the sample
 * ([needsFullFile]), kept after zooming out. Decoded only as wide as the zoom shows it, by zoom steps
 * ([fullFileWidth]), so a page at 1× costs what its sample would, and only zooming decodes more.
 */
@Composable
private fun FullFile(page: PostDto, width: Int, scale: () -> Float) {
  // The widest the page has been shown so far, by zoom step; 0 until it's past the sample.
  var decodeWidth by remember(page.id) { mutableIntStateOf(0) }
  LaunchedEffect(page.id, width) {
    snapshotFlow { if (needsFullFile(page, width * scale())) fullFileWidth(page, width, scale()) else 0 }
      .collect { if (it > decodeWidth) decodeWidth = it }
  }
  if (decodeWidth == 0) return
  val context = LocalPlatformContext.current
  val request = remember(page.id, decodeWidth) {
    postImageRequest(context, PostImage.of(page, Variant.File)) {
      // The sample, or the last decode of the file, until this one is ready.
      placeholderMemoryCacheKey(PostImage.cacheKey(page.md5, Variant.Sample))
      fittedTo(pageSize(page, decodeWidth))
    }
  }
  var shown by remember(page.id) { mutableStateOf(false) }
  AsyncImage(
    model = request,
    contentDescription = null,
    imageLoader = koinInject(),
    contentScale = ContentScale.FillBounds,
    alpha = if (shown) 1f else 0f,
    onState = { shown = it is AsyncImagePainter.State.Success },
    modifier = Modifier.fillMaxSize(),
  )
}

/**
 * The width [FullFile] decodes [page]'s file at, shown [width] px wide at [scale]: the zoom rounded up to a step (1×,
 * 2.5×, 6.25×, the double-tap and pinch stops), so a pinch decodes a few times rather than on every frame; never past
 * the file's own width or [MAX_FULL_PIXELS].
 */
internal fun fullFileWidth(page: PostDto, width: Int, scale: Float): Int {
  val fileWidth = page.fileWidth ?: return 0
  val step = ZOOM_STEPS.firstOrNull { it >= scale } ?: ZOOM_STEPS.last()
  val capped = sqrt(MAX_FULL_PIXELS * aspectRatio(page)).toInt()
  return minOf(fileWidth, capped, (width * step).toInt()).coerceAtLeast(1)
}

private val ZOOM_STEPS = listOf(1f, DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE * DOUBLE_TAP_SCALE)

@Composable
private fun Band(text: String, modifier: Modifier) {
  Box(
    modifier.height(BAND_HEIGHT).background(MaterialTheme.colorScheme.surfaceContainer),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun Unplayable(modifier: Modifier) {
  Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), modifier = modifier) {
    Text("This page can't play here", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
  }
}

/**
 * ✕, the title over where the reader is ([pageLabel], "page 4 / 27", once the pages are in), the book's ♡
 * ([favourite], null until the book has loaded), ⓘ for the book sheet ([onDetails], null until the book has loaded,
 * #484) and ⋮ with "Open this page as a post" for [page], the current one.
 */
@Composable
private fun TopRow(
  title: String,
  pageLabel: String?,
  favourite: BookFavourite?,
  favouriteRejected: Int,
  onToggleFavourite: () -> Unit,
  onClose: () -> Unit,
  onDetails: (() -> Unit)?,
  menuOpen: Boolean,
  onMenuOpen: (Boolean) -> Unit,
  page: PostDto?,
  onOpenPage: (PostDto) -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
      .windowInsetsPadding(WindowInsets.statusBars)
      .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ButtonHint("Close (${Shortcuts.Back.label})") { IconButton(onClick = onClose) { Icon(close, contentDescription = "Close") } }
    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (pageLabel != null) {
        Text(pageLabel, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.75f), maxLines = 1)
      }
    }
    HeartToggle(
      checked = favourite?.on == true,
      onCheckedChange = { onToggleFavourite() },
      rejected = favouriteRejected,
      enabled = favourite != null,
    )
    ButtonHint(BOOK_SHEET) {
      IconButton(onClick = { onDetails?.invoke() }, enabled = onDetails != null) { Icon(info, contentDescription = BOOK_SHEET) }
    }
    Box {
      ButtonHint("More (${Shortcuts.Menu.label})") {
        IconButton(onClick = { onMenuOpen(true) }) { Icon(more_vert, contentDescription = "More") }
      }
      GroupedMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
        MenuGroup(0, 1) {
          MenuAction(
            text = { Text("Open this page as a post") },
            onClick = {
              onMenuOpen(false)
              page?.let(onOpenPage)
            },
            leadingIcon = { Icon(open_in_new, contentDescription = null) },
            enabled = page != null,
          )
        }
      }
    }
  }
}

/**
 * "Page 12 · Start over" (#384, flipped by #503): the book opened at its saved [page] (from 1); [onStartOver] goes to
 * page 1, and the place follows from there. Either it or leaving the page hides it.
 */
@Composable
private fun ResumeChip(page: Int, onStartOver: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(50),
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
    shadowElevation = 3.dp,
    modifier = modifier,
  ) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        resumeLabel(page),
        Modifier.padding(start = 12.dp, end = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
      TextButton(onClick = onStartOver) { Text(START_OVER) }
    }
  }
}

/** The page taking most of the strip's viewport ([mostShownPage]); the end card (#503) isn't a page. */
private fun currentPage(state: LazyListState): Int {
  val info = state.layoutInfo
  val pages = info.visibleItemsInfo.filter { it.key != END_KEY }
  // Only the end card in sight: the last page is where the book was left.
  if (pages.isEmpty() && info.visibleItemsInfo.isNotEmpty()) return (info.totalItemsCount - 2).coerceAtLeast(0)
  return mostShownPage(pages.map { PageSpan(it.index, it.offset, it.size) }, info.viewportStartOffset, info.viewportEndOffset)
}

/**
 * How far a key scrolls the strip, in its own px (divided by the zoom, so a step is the same on screen): Space and
 * Page Down a screen down (Shift-Space and Page Up up), S and ↓ a line down, W and ↑ up. Null for any other key.
 */
private fun stripKeyStep(event: KeyEvent, state: LazyListState, zoom: ZoomableState, line: Float): Float? {
  if (event.type != KeyEventType.KeyDown) return null
  val screen = state.layoutInfo.viewportSize.height * KEY_SCROLL_SCREEN
  val step = when (event.key) {
    Key.Spacebar -> if (event.isShiftPressed) -screen else screen
    Key.PageDown -> screen
    Key.PageUp -> -screen
    Key.S, Key.DirectionDown -> line
    Key.W, Key.DirectionUp -> -line
    else -> return null
  }
  return step / zoom.transform.scaleX.coerceAtLeast(1f)
}

/**
 * The drag down that closes the reader: what the strip, at its top, leaves of a drag down moves the reader down
 * ([offset]); let go past [closeAt] (or flung down) it closes, else it springs back. Only while [enabled] and when the
 * drag [startedAtTop]; a drag back up takes the offset back first.
 */
@Stable
private class DragToClose(private val closeAt: Float, private val onClose: () -> Unit) : NestedScrollConnection {
  var offset by mutableFloatStateOf(0f)
    private set
  var enabled = false
  var startedAtTop = false

  override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
    if (offset <= 0f || available.y >= 0f) return Offset.Zero
    val used = maxOf(available.y, -offset)
    offset += used
    return Offset(0f, used)
  }

  override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
    if (source != NestedScrollSource.UserInput || available.y <= 0f || !enabled || !startedAtTop) return Offset.Zero
    offset += available.y
    return Offset(0f, available.y)
  }

  override suspend fun onPreFling(available: Velocity): Velocity {
    if (offset <= 0f) return Velocity.Zero
    if (offset > closeAt || available.y > CLOSE_FLING) {
      onClose()
    } else {
      animate(offset, 0f) { value, _ -> offset = value }
    }
    return available
  }
}

/** The strip's widest, on desktop: a centred column (#377 decision 2). */
internal val MAX_STRIP_WIDTH = 900.dp
private val BAND_HEIGHT = 96.dp

/** What a book with no pages to show says. */
internal const val NO_PAGES = "This book has no pages to show"
private val DRAG_CLOSE_DISTANCE = 120.dp

/** px/s down that closes the reader whatever the distance. */
private const val CLOSE_FLING = 2_000f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOMED_FROM = 1.01f

/** Whether the strip is zoomed in past its fitted scale: a snapshot read of [zoom]'s transform. */
private fun zoomedIn(zoom: ZoomableState): Boolean = zoom.transform.scaleX > zoom.minScale * ZOOMED_FROM
private const val MAX_FULL_PIXELS = 12_000_000f
