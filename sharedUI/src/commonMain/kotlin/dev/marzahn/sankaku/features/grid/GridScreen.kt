package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import dev.marzahn.sankaku.shell.SharedTransitions
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.marzahn.sankaku.GridScrollbar
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.merge
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.post.PostActions
import dev.marzahn.sankaku.features.settings.FollowedTagsPage
import dev.marzahn.sankaku.queue.FeedHeading
import dev.marzahn.sankaku.queue.FeedReading
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SeenStore
import kotlinx.coroutines.flow.filter
import kotlin.time.Clock
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.PageCursor
import dev.marzahn.sankaku.tabs.ClosedTabUndo
import dev.marzahn.sankaku.tabs.DescribeTab
import dev.marzahn.sankaku.tabs.LocalTabEntry
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.flow.Flow
import dev.marzahn.sankaku.ui.Message
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import dev.marzahn.sankaku.tabs.QueueMosaic
import dev.marzahn.sankaku.tabs.TabContents
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import androidx.compose.foundation.layout.windowInsetsPadding
import dev.marzahn.sankaku.shell.LocalOpenSearch
import dev.marzahn.sankaku.ui.components.ApiErrorState
import dev.marzahn.sankaku.ui.components.GridOfflineState
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.advice
import dev.marzahn.sankaku.shell.RetryWhenBack
import dev.marzahn.sankaku.ui.components.rememberHeldKeys
import dev.marzahn.sankaku.ui.components.KeyFocusHolder
import dev.marzahn.sankaku.ui.components.LocalKeyFocus
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.tabStep
import kotlinx.coroutines.Job
import dev.marzahn.sankaku.ui.components.LargeIndicatorSize
import dev.marzahn.sankaku.ui.components.ScrollToTopButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import dev.marzahn.sankaku.shell.GridRefresh
import dev.marzahn.sankaku.shell.LocalChromePadding
import dev.marzahn.sankaku.shell.LocalCoverChrome
import dev.marzahn.sankaku.shell.LocalTabBarSlide
import dev.marzahn.sankaku.shell.LocalUnderQueuePage
import dev.marzahn.sankaku.shell.underQueuePageMoving
import dev.marzahn.sankaku.ui.components.spatialSpec
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.PullToRefresh
import dev.marzahn.sankaku.ui.components.rememberAlreadyInTab
import dev.marzahn.sankaku.tabs.queueSwipe
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A grid and its post viewer. The viewer is an overlay owned by this screen, not a back-stack entry, so the grid
 * keeps its scroll position and the thumbnail can grow into the viewer.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GridScreen(
  route: Route.Grid,
  navigator: Navigator = koinInject(),
  signIn: SignInController = koinInject(),
  refresh: GridRefresh = koinInject(),
  actions: PostActions = koinInject(),
  tabs: TabsController = koinInject(),
  cursorRequest: GridCursorRequest = koinInject(),
  tips: Tips = koinInject(),
  seenStore: SeenStore = koinInject(),
  closeUndo: ClosedTabUndo = koinInject(),
  messages: Messages = koinInject(),
) {
  val tabEntry = LocalTabEntry.current
  val viewModel = koinViewModel<GridViewModel>(key = "grid-${route.tags}") {
    parametersOf(route.tags, tabEntry?.tab?.placeFor(route))
  }
  val items = viewModel.posts.collectAsLazyPagingItems()
  // Back online, the pages that failed for want of a connection load again; a background tab's, when it's shown. A
  // grid shown again gets its load states a moment after its first frame, so the retry waits for them, briefly.
  var retryPending by remember { mutableStateOf(false) }
  RetryWhenBack {
    // A place that couldn't be looked up for want of a connection is looked up again (#492); the grid has no pager
    // until then. A 429 or 401 waits for Retry, as a page that failed with one does.
    val pending = viewModel.lookup.value
    if (pending is PlaceLookup.Unreachable) {
      if (pending.error is ApiError.Network) viewModel.retryPlace()
    } else {
      retryPending = true
    }
  }
  if (retryPending) {
    LaunchedEffect(Unit) {
      withTimeoutOrNull(RETRY_WAIT_MS) { snapshotFlow { items.loadState.failedUnreachable() }.first { it } }
        ?.let { items.retry() }
      retryPending = false
    }
  }
  val overrides by viewModel.overrides.collectAsStateWithLifecycle()
  val gridState = rememberLazyStaggeredGridState()
  val scrollUp = rememberScrollUpWatch(gridState)
  val scope = rememberCoroutineScope()
  // Edit search, for a search Sankaku couldn't run as it stands: a timeout or the tag limit (#520).
  val openSearch = LocalOpenSearch.current
  val midway by viewModel.midway.collectAsStateWithLifecycle()
  val signedOutNotice by viewModel.signedOutNotice.collectAsStateWithLifecycle()
  // A feed's headings (#314), from the marks its visit started with, as full-span items among the posts.
  val marks by viewModel.marks.collectAsStateWithLifecycle()
  // What Following shows now, for its late posts (#539).
  val feedContext by viewModel.feedContext.collectAsStateWithLifecycle()
  val feedPosts by remember(viewModel) {
    derivedStateOf { if (viewModel.feed == null) emptyList() else feedPostsOf(items.itemSnapshotList.items, viewModel::cursorOf) }
  }
  val gridItems by remember(viewModel) {
    derivedStateOf {
      val feed = viewModel.feed ?: return@derivedStateOf GridItems.None
      GridItems(FeedReading.headings(feed, feedPosts, atTop = !midway, marks, Clock.System.now(), feedContext))
    }
  }
  // Following's posts that turned up late, below posts already read (#539): they carry "New".
  val latePosts by remember(viewModel) {
    derivedStateOf { viewModel.feed?.let { FeedReading.late(it, feedPosts, atTop = !midway, marks, feedContext) }.orEmpty() }
  }
  var showingFollowed by rememberSaveable { mutableStateOf(false) }
  if (showingFollowed) FollowedTagsPage(onDismiss = { showingFollowed = false }, onOpenTag = { showingFollowed = false })
  FeedHeadingEffects(viewModel, items, gridState, { gridItems }, { feedPosts })
  DescribeTab(route) { TabContents.grid(items.itemSnapshotList.items, gridState.firstPostIndex(gridItems)) }
  // What the queue page shows for this grid, for its tab to save (#232): once it has posts, so a grid that can't load
  // (offline) keeps the mosaic saved at the last launch.
  LaunchedEffect(gridState, items, route) {
    val tab = tabEntry?.tab ?: return@LaunchedEffect
    snapshotFlow { QueueMosaic.of(items.itemSnapshotList.items, gridState.firstPostIndex(gridItems)) }
      .filterNotNull()
      .distinctUntilChanged()
      .collect { tab.reportMosaic(route, it) }
  }
  // Scrolled, a post opened from it (the viewer or a background tab), or restored at its place: a search changed here
  // then opens a new tab, so what's left of this grid isn't lost (#161). Scrolling back to the top doesn't untouch it.
  // Kept on the tab, which saves it, not in this screen's saved state, so it outlasts the screen (#245).
  fun markTouched() {
    tabEntry?.tab?.markTouched(route)
  }
  if (tabEntry?.tab?.isTouched(route) == false) {
    LaunchedEffect(gridState) {
      snapshotFlow { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }.first { it }
      markTouched()
    }
  }
  val lookup by viewModel.lookup.collectAsStateWithLifecycle()
  // A grid opened at its place (#162) scrolls to its post once it's in the list. A first page without it (the view
  // model sees that in the pager's own first load) gives the place up, which ends this wait. A first page that fails
  // leaves the place pending (#492): the grid's own error shows, and its retry (or the connection back) loads the
  // place's page. Left unfinished (the tab switched away), it runs again when shown. A feed's Continue (#314) is
  // found the same way, so this follows each one.
  LaunchedEffect(viewModel) {
    viewModel.lookup.collectLatest { found ->
      if (found !is PlaceLookup.Found || found.firstPost == null) return@collectLatest
      // The place's own list, which starts with its pager's first post: a Continue's jump replaces a list already
      // shown, which may hold the post too.
      val at = snapshotFlow {
        val list = items.itemSnapshotList
        if (list.firstOrNull()?.id != found.firstPost) -1 else list.indexOfFirst { it?.id == found.post }
      }.first { it >= 0 }
      gridState.scrollToItem(gridItems.itemOf(at))
      markTouched()
      viewModel.placeShown()
    }
  }
  // A place given up says so, once, with Try again (#492), which looks it up again.
  LaunchedEffect(viewModel) {
    viewModel.lookup.collect {
      if (it != PlaceLookup.GivenUp || !viewModel.sayGivenUp()) return@collect
      messages.show(Message(PLACE_GIVEN_UP, actionLabel = "Try again", isError = true, onAction = viewModel::retryPlace))
    }
  }
  // Where the grid is, for its tab to save: each change once the place is shown or given up, not the grid as it first
  // appears, and nothing while it's pending, so a grid that couldn't reach its place (offline) keeps it (#492).
  LaunchedEffect(viewModel, gridState) {
    val tab = tabEntry?.tab ?: return@LaunchedEffect
    viewModel.lookup.map { it == null || it == PlaceLookup.GivenUp }.distinctUntilChanged().collectLatest { settled ->
      if (!settled) return@collectLatest
      gridState.placeReports(items, midway, viewModel::cursorOf, postOf = { gridItems.postOf(it) })
        .drop(1)
        .collect {
          tab.reportPlace(route, it)
          // A scroll touches the queue item (#174); the place a restore lands on, dropped above, doesn't.
          tabs.touch(tab.id)
        }
    }
  }
  // The back stacks are snapshot state, so this follows navigation within a tab as well as tabs opening and closing.
  val tabList by tabs.tabs.collectAsStateWithLifecycle()
  val openInTabs by remember(tabList) { derivedStateOf { tabs.openPosts(tabList) } }
  // "Done with …?" at the end of a source's finite grid closes it as ✕ does (#184): with Undo on the top line on touch.
  val endOfSource = tabEntry?.tab?.takeIf { it.top == route && it.kind == QueueKind.Source }?.let { tab ->
    val keptStay = tabs.keptPostsOf(tab)
    remember(tab, tab.title, keptStay) {
      EndOfSource(tab.title, keptStay) { if (platformIsTouch) closeUndo.close(tab.id) else tabs.close(tab.id) }
    }
  }

  var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
  // The post whose cell is hidden while the viewer is open: the one it grew from, then the current one after a step.
  // Saved with the viewer, so a grid shown again (its tab back in front) closes the viewer into the right cell.
  var sharedId by rememberSaveable { mutableStateOf<String?>(null) }

  // Refresh (the top bar's button, Cmd/Ctrl-R, pulling down): reload past the page cache and go to the top, which the
  // tab saves as its place, so the next launch doesn't go back down. Not while a post is open: the list would change
  // under it.
  LaunchedEffect(viewModel) {
    refresh.requests.collect {
      if (viewerIndex != null) return@collect
      refresh.setRunning(true)
      try {
        viewModel.toTop()
        tabEntry?.tab?.reportPlace(route, null)
        viewModel.forgetPages()
        items.refresh()
        // The reload starts a moment later: wait for it to start, then to end.
        withTimeoutOrNull(REFRESH_START_TIMEOUT_MS) { snapshotFlow { items.loadState.refresh }.first { it is LoadState.Loading } }
        snapshotFlow { items.loadState.refresh }.first { it !is LoadState.Loading }
        gridState.scrollToItem(0)
      } finally {
        refresh.setRunning(false)
      }
    }
  }
  // Signing in or out reloads the list from its first page, so the open post may have moved or gone. The viewer
  // follows it by id (sharedId is the post on screen while it's open), and closes if the new list doesn't hold it.
  val followed = viewerIndex?.let { index -> followedIndex(index, sharedId, items.itemCount) { items.peekOrNull(it)?.id } }
  val listShown = items.itemCount > 0 || items.loadState.refresh !is LoadState.Loading
  SideEffect {
    val index = viewerIndex
    if (index != null && followed != index && listShown) {
      viewerIndex = followed
      if (followed == null) sharedId = null
    }
  }
  // The viewer steps in reading order, not list order (#110; see ReadingOrder.kt), from where the grid drew its cells.
  // The order is worked out only when a viewer reads it, and again only when the list or the drawn cells change.
  // Frozen while a viewer is on screen, opening, open or closing: the close's scroll to the post can jump the grid,
  // and the order (so the pager's positions) mustn't change under it.
  val viewerTransition = remember { mutableStateOf<MutableTransitionState<Boolean>?>(null) }
  // A grid shown again with its viewer open (its tab back in front) starts it open, instead of replaying the opening.
  val viewerOnScreen = remember { ViewerOnScreen(startOpen = viewerIndex != null) }
  // No viewer on screen, opening, open or closing: the gestures tip waits for the card to settle into its cell.
  val viewerSettled = viewerTransition.value?.let { it.isIdle && !it.currentState } ?: true
  val laidOut by rememberLaidOutCells(gridState) {
    viewerIndex != null || viewerTransition.value?.let { it.currentState || it.targetState } == true
  }
  val order by remember {
    derivedStateOf {
      val list = items.itemSnapshotList
      readingOrder(list.size, { list[it]?.id }, laidOut)
    }
  }
  // Kept while the viewer animates out, after viewerIndex is cleared.
  var shownIndex by remember { mutableStateOf(0) }
  followed?.let { shownIndex = it }
  // Counts openings, so a close that finishes after the viewer was reopened leaves the new opening alone.
  var opening by remember { mutableStateOf(0) }

  // The keyboard cursor (W/A/S/D or the arrows): an item index, null until the first press. A grid a search opened
  // starts with it on the first cell it can open.
  var cursor by rememberSaveable { mutableStateOf<Int?>(null) }
  // The cell that takes focus next, once, so focus and the cursor stay on one cell (#418): Space and E act on the
  // ring's cell, and Tab goes on from it.
  var pendingFocus by remember { mutableStateOf<Int?>(null) }

  /** Puts the cursor on [index] (null hides it), and keyboard focus with it. */
  fun putCursor(index: Int?) {
    cursor = index
    pendingFocus = index
  }
  // Keys handed back to the grid (the search panel closing, Esc in the tag list) go to the cursor's cell, not the
  // screen's KeyFocusHolder, or the next Tab would start from the grid's top and drag the ring there.
  val keyReturns = LocalKeyFocus.current.returns
  // Only to a cell on screen: one scrolled away would take the focus whenever it came back into view, from whatever
  // has it then.
  LaunchedEffect(keyReturns) {
    val at = cursor ?: return@LaunchedEffect
    if (viewerIndex == null && gridState.layoutInfo.visibleItemsInfo.any { it.index == gridItems.itemOf(at) }) pendingFocus = at
  }
  LaunchedEffect(Unit) {
    if (cursorRequest.take(route.tags)) putCursor(0)
  }
  // A grid that reloaded with fewer posts, or none, drops a cursor past its end once all of it is in (#488), so the
  // ring doesn't come back on whatever post a later reload puts at that index. Not while it loads: a saved cursor
  // waits there for its posts.
  LaunchedEffect(Unit) {
    snapshotFlow {
      val load = items.loadState
      cursor?.takeIf { load.refresh is LoadState.NotLoading && load.append.endOfPaginationReached && it >= items.itemCount }
    }.filterNotNull().collect { putCursor(null) }
  }
  // Held keys repeat faster than a step that scrolls; a press during one is dropped.
  var cursorStep by remember { mutableStateOf<Job?>(null) }

  fun openViewer(index: Int) {
    markTouched()
    opening++
    val id = items.peekOrNull(index)?.id
    viewerOnScreen.open(id, closingId = sharedId)
    sharedId = id
    viewerIndex = index
  }

  // A post already in a tab opens nothing; its chip says which tab instead (#130).
  val alreadyInTab = rememberAlreadyInTab()

  fun keep(post: PostDto): Kept {
    markTouched()
    val kept = keepPost(post, route.tags, navigator, tabs, seenStore)
    // Kept now, not already: its sample goes to disk for its turn in the queue (#263). The desktop has no queue swipe.
    if (!kept.already && platformIsTouch) viewModel.prefetchKept(post)
    return kept
  }

  fun openInTab(post: PostDto) {
    // A long-press (touch), or a middle-click, E or the cell menu (desktop): the keep tip has nothing left to teach.
    tips.used(keepTip())
    val kept = keep(post)
    if (kept.already) alreadyInTab.show(post.id, kept.position, again = kept.again)
  }

  // The viewer's Keep (#504): the cell's chip is under the viewer, so the answer is in words. Desktop's E there is
  // what its keep tip teaches; the phone's ⋮ isn't the long-press its tip teaches.
  fun keepFromViewer(post: PostDto) {
    if (!platformIsTouch) tips.used(Tip.KeepLoop)
    messages.show(keptMessage(keep(post), platformIsTouch))
  }

  // A post kept from this grid and seen since it was kept is dimmed and badged (#182, #262); any other post isn't.
  val isSeen = seenMark(route, tabEntry?.tab, tabs, seenStore)

  // The post the gestures tip points at while it's the current tip, from a close back to the grid (#140).
  var keepTipId by remember { mutableStateOf<String?>(null) }

  fun closeViewer() {
    val index = viewerIndex ?: return
    val closing = opening
    scope.launch {
      try {
        // Ours, not the user's: it never shows ↑ Top (#299).
        gridState.scrollBackForClose(gridItems.itemOf(index), scrollUp)
      } finally {
        // A user scroll (a trackpad's momentum, say) outranks ours and cancels it; the viewer closes regardless,
        // or it would stay half-closed with nothing left to finish it. Unless it was reopened meanwhile.
        if (opening == closing) {
          sharedId = items.peekOrNull(index)?.id
          viewerIndex = null
          // A keyboard user stays on the post the viewer closed on.
          if (cursor != null) putCursor(index)
          // The first close back to a grid brings the keep tip, on the cell it closed into, once settled (desktop's
          // too, #504). A tip offered before and not yet shown (the viewer reopened, its cell scrolled away) moves here.
          if (sharedId != null && (tips.offer(keepTip()) || tips.current == keepTip())) keepTipId = sharedId
        }
      }
    }
  }

  // Space toggles the viewer (#424): its auto-repeat, held, neither closes the viewer it opened nor reopens the one it
  // closed. Seen here, above the viewer, so the press that opened it counts.
  val heldKeys = rememberHeldKeys()

  // The grid's keys, while no viewer covers it: the cursor, E (a background tab) and Space (the viewer). Letter keys
  // with Cmd, Ctrl or Alt are app shortcuts.
  fun gridKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || viewerIndex != null) return false
    if (event.isCommand || event.isAltPressed || event.tabStep != null) return false
    val move = gridMoveFor(event.key)
    if (move != null) {
      if (cursorStep?.isActive != true) {
        val from = cursor
        val page = event.isShiftPressed && (move == GridMove.Up || move == GridMove.Down)
        cursorStep = scope.launch { putCursor(gridState.moveCursor(from, move, page, items.itemCount, gridItems)) }
      }
      return true
    }
    // Past the end while a reload is still coming in (#488): no post to act on yet, so the key does its own job.
    val at = cursor ?: return false
    val post = items.peekOrNull(at)?.takeUnless { it.redirectToSignup } ?: return false
    return when (event.key) {
      Shortcuts.OpenInTab.key -> true.also { openInTab(post) }
      Shortcuts.OpenHere.key -> true.also { if (!heldKeys.repeating(event.key)) openViewer(at) }
      else -> false
    }
  }

  // Desktop's tip doesn't take the keys (#504): the next key the grid acts on sends it away, as a click elsewhere does.
  // A modifier alone (the start of Cmd-Tab) or a shortcut the grid leaves alone doesn't.
  fun onGridKey(event: KeyEvent): Boolean = gridKey(event).also { if (it) keepTipId = null }

  // Touch, under the queue page (#322): while the page closes over this grid, or opens over it, the cells of its mosaic
  // are one element with the thumbnails on its row. Not with a viewer on screen, which covers its cells. Closing, only
  // if the grid had its cells from the frame the page started to: one still loading keeps the page's plain fade, rather
  // than have thumbnails fly into an empty grid as its cells come.
  val underPage = LocalUnderQueuePage.current
  val pageMoving = underQueuePageMoving()
  val coming = underPage?.transition?.let { it.targetState == EnterExitState.Visible && it.currentState != EnterExitState.Visible } == true
  val cellsWhenItCame = remember(coming) { items.itemCount > 0 }
  val cellsShown = if (coming) cellsWhenItCame else items.itemCount > 0
  val mosaicThumbs = underPage?.let { tabEntry?.tab?.takeIf { it.top == route }?.mosaic?.thumbs?.map { it.md5 } }.orEmpty()
  val mosaicMoves = pageMoving && cellsShown && viewerIndex == null && viewerSettled
  // On the content's visibility, as long as the move, while there's a mosaic to move: with nothing of its own on it, the
  // grid's visibility ended in the frame the page opened and took its cells with it before they had moved into the row.
  // Otherwise it goes at once, as before: drawn under the page as it fades in, it costs every frame of the fade.
  val holdForMove = mosaicMoves && mosaicThumbs.isNotEmpty()
  underPage?.transition?.animateFloat({ spatialSpec() }, label = "grid under the queue page") {
    if (it == EnterExitState.Visible || !holdForMove) 1f else 0f
  }

  // The shell's scope on touch (#320), so the grid's pictures can meet their counterparts on other screens.
  SharedTransitions(Modifier.fillMaxSize()) {
    // A sideways swipe moves to the next or previous queue item (Android, #181): on the grid and on whatever stands in
    // for it (loading, "Loading where you were…", an error), never while the viewer is over it, whose pager steps
    // through the posts.
    Box(Modifier.fillMaxSize().then(heldKeys.modifier).onKeyEvent(::onGridKey).queueSwipe(enabled = viewerIndex == null, from = tabEntry?.tab?.id)) {
      // The viewer holds the keys while it's open; closed, the grid takes them back.
      if (viewerIndex == null) KeyFocusHolder()
      val load = items.loadState.refresh
      // Android: pulling the grid down (or the message in its place) does what the top bar's Refresh does on desktop.
      // The signed-out notice above it stays put and isn't part of the pull.
      val refreshing by refresh.running.collectAsStateWithLifecycle()
      // Touch: the screen fills the window so the viewer can cover the top bar; the grid keeps below it.
      Column(Modifier.fillMaxSize().padding(LocalChromePadding.current)) {
        // Signed out, once, in place of the posts the pager leaves out (#507).
        if (signedOutNotice) SignedOutNotice(onSignIn = signIn::open)
        PullToRefresh(
          refreshing,
          onRefresh = refresh::request,
          enabled = platformIsTouch,
          modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
          when {
            load is LoadState.Loading && items.itemCount == 0 ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Held back a moment: a new tab's first page usually comes from the cache within a frame or two, and
                // the indicator flashing up for that long read as the grid blinking.
                val shown by produceState(false) {
                  delay(LOADING_INDICATOR_DELAY_MS)
                  value = true
                }
                if (shown) AppLoadingIndicator(size = LargeIndicatorSize)
              }
            load is LoadState.Error && items.itemCount == 0 ->
              GridMessage {
                // By the error, not `offline`: the network coming back clears that a moment before the retry starts,
                // which would flash the error in between.
                when (val error = load.error) {
                  is ApiError.Network -> GridOfflineState(onRetry = items::retry)
                  // Over the tag limit: the search's tokens as removable chips, as an empty search has them (#520).
                  is ApiError.TagLimit -> EmptySearch(route, title = error.advice().sentence)
                  else -> ApiErrorState(error, onRetry = items::retry, onEditSearch = openSearch, onSignIn = signIn::open)
                }
              }
            load is LoadState.NotLoading && items.itemCount == 0 ->
              GridMessage { EmptySearch(route) }
            else -> {
              PostGrid(
                items = items,
                overrides = overrides,
                state = gridState,
                hiddenId = if (viewerIndex != null) sharedId else null,
                sharedId = sharedId,
                viewerClosing = viewerIndex == null,
                cursor = cursor,
                onOpen = ::openViewer,
                onOpenInNewTab = ::openInTab,
                // From the grid, so the post isn't seen by it (#174).
                onToggleFavourite = { post, viaDoubleTap ->
                  // Acting on the grid touches its queue item all the same.
                  tabs.touchActive()
                  actions.toggleFavourite(post, viaDoubleTap, fromGrid = true)
                },
                dimUnfavourited = isFavouritesSearch(route.tags),
                openInTabs = openInTabs,
                alreadyInTab = alreadyInTab,
                isSeen = isSeen,
                isLate = { it in latePosts },
                // "Done with …?" at the end of a source's finite grid closes it as ✕ does (#184): with Undo on touch.
                endOfSource = endOfSource,
                mosaicThumbs = mosaicThumbs,
                underPage = underPage?.takeIf { mosaicMoves },
                keepTipId = keepTipId.takeIf { viewerIndex == null && tips.current == keepTip() },
                keepTipShown = viewerSettled,
                pendingFocus = pendingFocus,
                onFocusTaken = { pendingFocus = null },
                // Not while the viewer is open: the cell its post closes into is the cursor's then.
                onCellFocused = { if (viewerIndex == null) cursor = it },
                // The focused cell's Space, before its click sees it: the cursor is on that cell.
                onCellKey = ::onGridKey,
                gridItems = gridItems,
                heading = { placed ->
                  FeedHeadingRow(
                    placed,
                    onContinue = {
                      // Loaded: scroll to the stop mark's heading. Else open the grid there (#314).
                      val at = gridItems.itemOfHeading { it is FeedHeading.Continue }
                      if (at != null) scope.launch { gridState.animateScrollToItem(at) } else viewModel.continueFromGap()
                    },
                    onOfferShown = viewModel::offerShown,
                    onKeep = viewModel::keepGap,
                    onLetGo = viewModel::letGapGo,
                    onFurtherDown = { post -> scope.launch { gridState.animateScrollToItem(gridItems.itemOf(post)) } },
                    onFollowedTags = { showingFollowed = true },
                  )
                },
                // While the viewer is open the grid is out of the accessibility tree: screen readers and automation
                // would otherwise find grid cells under the viewer's buttons (Compose hit-tests in traversal order).
                modifier = Modifier
                  .fillMaxSize()
                  .then(if (viewerIndex != null) Modifier.semantics { hideFromAccessibility() } else Modifier),
              )
              GridScrollbar(gridState, gridItems.count(items.itemCount), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp))
              // Touch: icon-only in the bottom row's right corner, on the count pill's line, sliding with the pill as
              // it hides (R2, #178). Slid at placement, so the pill's motion recomposes nothing. A post opening fades
              // it out.
              val barSlide = LocalTabBarSlide.current
              ScrollToTopButton(
                visible = scrollUp.backUp || (midway && gridState.firstVisibleItemIndex == 0),
                replaced = platformIsTouch && viewerIndex != null,
                fade = platformIsTouch && (viewerIndex != null || viewerTransition.value?.let { it.currentState || it.targetState } == true),
                // A grid that started at its place has newer posts above what's loaded: Top reloads from the top.
                onClick = { if (midway) refresh.request() else scope.launch { gridState.animateScrollToItem(0) } },
                compact = platformIsTouch,
                modifier = Modifier
                  .align(Alignment.BottomEnd)
                  .offset { IntOffset(0, barSlide().roundToInt()) }
                  .then(
                    if (platformIsTouch) {
                      // On the row's line, above the row's room; the grid's box already ends at the navigation bar.
                      Modifier.windowInsetsPadding(BottomRowLayout.SideInsets).padding(
                        BottomRowLayout.topButtonPadding(
                          room = (BottomRowLayout.WindowRoom - LocalChromePadding.current.calculateBottomPadding()).coerceAtLeast(0.dp),
                        ),
                      )
                    } else {
                      Modifier.padding(end = 28.dp, bottom = TOP_BOTTOM)
                    },
                  ),
              )
            }
          }
        }
      }

      val viewerShown = viewerOnScreen.state
      SideEffect { viewerTransition.value = viewerShown }
      // Touch: the viewer says on the tab whether it covers the shell's chrome, so the shell knows from this grid's
      // first frame (#287). Once it's gone (closed, or never there), nothing covers it.
      val tab = tabEntry?.tab
      val coverChrome: (Boolean) -> Unit = remember(tab, route) { { covers -> tab?.coverChrome(route, covers) } }
      SideEffect { if (viewerShown.isIdle && !viewerShown.currentState) coverChrome(false) }
      ViewerVisibility(viewerOnScreen, visible = viewerIndex != null) {
        // Open on a post the list no longer holds: it closes after this frame, so show nothing rather than whatever
        // post is now at its old place.
        if (viewerIndex != null && followed == null) return@ViewerVisibility
        val index = shownIndex.coerceAtMost(items.itemCount - 1)
        if (index < 0) return@ViewerVisibility
        if (platformIsTouch) {
          // The pager's pages are positions in reading order, mapped to list indices here.
          CompositionLocalProvider(LocalCoverChrome provides coverChrome) {
            PhoneViewer(
              index = order.positionOf(index),
              // getOrNull (not peek) so paging near the end loads the next page. Bounded, as the pager can ask for its
              // old page count's pages once the list has reloaded shorter (#472).
              postAt = { page -> items.getOrNull(order.indexAt(page))?.let { it.merge(overrides[it.id]) } },
              pageKey = { page -> items.viewerPageKey(page, order) },
              loadedCount = items.itemCount,
              hasMore = items.loadState.append.endOfPaginationReached.not(),
              end = viewerEnd(items.loadState.append),
              onRetryEnd = items::retry,
              sharedId = sharedId,
              visibilityScope = this,
              onShow = { page ->
                val shown = order.indexAt(page)
                viewerIndex = shown
                // The hidden cell follows the viewer, so closing shrinks into the post on screen.
                sharedId = items.peekOrNull(shown)?.id ?: sharedId
              },
              onClose = ::closeViewer,
              opening = opening,
              searchTags = route.tags,
              onKeep = ::keepFromViewer,
            )
          }
          return@ViewerVisibility
        }
        PostViewer(
          index = index,
          // getOrNull (not peek) so stepping near the end loads the next page.
          postAt = { i -> items.getOrNull(i)?.let { it.merge(overrides[it.id]) } },
          loadedCount = items.itemCount,
          hasMore = items.loadState.append.endOfPaginationReached.not(),
          end = viewerEnd(items.loadState.append),
          onRetryEnd = items::retry,
          sharedId = sharedId,
          visibilityScope = this,
          positionOf = { i -> order.positionOf(i) },
          onStep = { forward ->
            val next = order.indexAt(order.positionOf(index) + if (forward) 1 else -1)
            viewerIndex = next
            // The hidden cell follows the viewer, so closing shrinks into the post on screen.
            sharedId = items.peekOrNull(next)?.id ?: sharedId
          },
          onClose = ::closeViewer,
          opening = opening,
          searchTags = route.tags,
          isRepeat = heldKeys::repeating,
          onKeep = ::keepFromViewer,
        )
      }

      // Opening at its place, the grid is covered until it's there, so the top doesn't show first. A place that can't be
      // looked up now stays pending (#492), with the grid's own offline or error message and Retry; so does one whose
      // first page failed, where the grid's own error shows uncovered.
      val cover = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(LocalChromePadding.current)
      when (val pending = lookup) {
        PlaceLookup.Looking, is PlaceLookup.Found -> if (load !is LoadState.Error) {
          Column(
            cover,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            AppLoadingIndicator(size = LargeIndicatorSize)
            Text("Loading where you were…", style = MaterialTheme.typography.bodyMedium)
          }
        }
        is PlaceLookup.Unreachable -> Box(cover, contentAlignment = Alignment.Center) {
          if (pending.error is ApiError.Network) {
            GridOfflineState(onRetry = viewModel::retryPlace)
          } else {
            ApiErrorState(pending.error, onRetry = viewModel::retryPlace, onEditSearch = openSearch, onSignIn = signIn::open)
          }
        }
        PlaceLookup.GivenUp, null -> Unit
      }
    }
  }
}

/**
 * Whether the viewer over a grid is on screen: its [state], which goes on to the next opening unless that opening has
 * to start afresh (#323).
 *
 * A cell tapped while the viewer is still closing reopens it. On the post it's closing into, the transition turns
 * round (#105). On a different post, [open] gives the viewer a new state, and [ViewerVisibility] a new composition with
 * it: the old card goes at once (it was shrinking into a cell the grid now draws), and the new one grows from the cell
 * tapped, its pager on its own page, as from rest. Turned round instead, the pager showed the old post's page and then
 * slid sideways to the new one, and the card grew from where the old one was.
 */
@Stable
internal class ViewerOnScreen(startOpen: Boolean) {
  var state by mutableStateOf(MutableTransitionState(startOpen))
    private set

  /** The viewer opens on the post [id]; [closingId] is the post a viewer still on screen is closing into. */
  fun open(id: String?, closingId: String?) {
    val closing = state.currentState && !state.targetState
    if (closing && id != closingId) state = MutableTransitionState(false)
  }
}

/**
 * The viewer over a grid, shown while [visible], with no enter or exit effects of its own: the card moves through its
 * shared elements, the scrim fades itself. Each of [onScreen]'s states is a composition of its own, so an opening that
 * starts afresh remembers nothing of the last one.
 */
@Composable
internal fun ViewerVisibility(onScreen: ViewerOnScreen, visible: Boolean, content: @Composable AnimatedVisibilityScope.() -> Unit) {
  val state = onScreen.state
  state.targetState = visible
  key(state) {
    AnimatedVisibility(visibleState = state, enter = EnterTransition.None, exit = ExitTransition.None, content = content)
  }
}

/**
 * Where the post [id] is in a list of [count] posts, [idAt] giving each one's id: [index] if it's still there,
 * otherwise wherever it moved to, or null if the list doesn't hold it. Without an [id] the index stands.
 */
internal fun followedIndex(index: Int, id: String?, count: Int, idAt: (Int) -> String?): Int? = when {
  id == null -> index
  index < count && idAt(index) == id -> index
  else -> (0 until count).firstOrNull { idAt(it) == id }
}

/** A place the grid knows, null at the top; wrapped so "not known" (null in [placeReports]) is told apart from it. */
private class KnownPlace(val place: GridPlace?)

/**
 * The grid's place ([placeOf]) each time it changes. Nothing while its first post isn't in the list (#415): a reload,
 * such as signing in's, empties the list for a moment while the grid still stands where it was, and that's no place
 * to save over the one the tab has.
 */
internal fun LazyStaggeredGridState.placeReports(
  items: LazyPagingItems<PostDto>,
  midway: Boolean,
  cursorOf: (String) -> PageCursor?,
  postOf: (item: Int) -> Int? = { it },
): Flow<GridPlace?> =
  snapshotFlow {
    // The first post whose top shows, else the first visible post, which may be past an emptied list's end. A feed's
    // headings (#314) are items too, but no place.
    val visible = layoutInfo.visibleItemsInfo.mapNotNull { info -> postOf(info.index)?.let { it to info } }
    val first = visible.filter { (post, info) -> post < items.itemCount && info.offset.y >= 0 }.minOfOrNull { it.first }
      ?: visible.minOfOrNull { it.first }
      ?: postOf(firstVisibleItemIndex)
      ?: return@snapshotFlow null
    if (first < items.itemCount) KnownPlace(placeOf(first, items, midway, cursorOf)) else null
  }
    .filterNotNull()
    .map { it.place }
    .distinctUntilChanged()

/**
 * Where the grid is, as its tab saves it (#162), given its [first] post whose top shows, which must be in [items]: that
 * post, with the cursor of the page it came in on ([cursorOf], unknown counting as the first page) and its upload time.
 * Null at the top, unless the grid opened [midway], where its first post isn't the top of the search.
 */
private fun placeOf(
  first: Int,
  items: LazyPagingItems<PostDto>,
  midway: Boolean,
  cursorOf: (String) -> PageCursor?,
): GridPlace? {
  if (first == 0 && !midway) return null
  val post = items.peekOrNull(first) ?: return null
  val cursor = cursorOf(post.id) ?: PageCursor()
  return GridPlace(post.id, cursor.next, cursor.number, post.createdAt?.epochSeconds)
}

/**
 * Scrolls the least distance that shows the whole cell at [index], so the closing viewer shrinks into a cell on
 * screen rather than one cut off at an edge. Instant: the viewer still covers the grid. Returns whether it scrolled.
 */
internal suspend fun LazyStaggeredGridState.scrollFullyIntoView(index: Int): Boolean {
  val offScreen = layoutInfo.visibleItemsInfo.none { it.index == index }
  if (offScreen) scrollToItem(index)
  val info = layoutInfo
  val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return offScreen
  val top = item.offset.y
  val bottom = top + item.size.height
  // The range [fullyShows] allows, which leaves room for the cursor's ring at both edges.
  val start = 0
  val end = shownBottom()
  val delta = when {
    top < start -> top - start
    bottom > end -> minOf(bottom - end, top - start)
    else -> 0
  }
  if (delta != 0) scrollBy(delta.toFloat())
  return offScreen || delta != 0
}

/**
 * Moves the keyboard cursor from [from] and returns where it lands, scrolling so that cell shows whole. With no
 * cursor yet, or one scrolled out of view, it appears on the first wholly visible cell and the grid stays put. Up
 * and down scroll half a screen to find a cell not laid out yet; [page] jumps a screen, to the cell at the cursor's
 * place on screen.
 */
private suspend fun LazyStaggeredGridState.moveCursor(
  from: Int?,
  move: GridMove,
  page: Boolean,
  postCount: Int,
  gridItems: GridItems,
): Int? {
  val cells = cellBounds(postCount, gridItems)
  val at = from?.let(cells::get) ?: return firstShown(cells, ::fullyShows) ?: from
  val height = layoutInfo.viewportSize.height.toFloat()
  val down = if (move == GridMove.Down) 1 else -1
  var scrolled = 0
  // The cells once the grid has scrolled on by [share] of a screen and laid out again, with [from] where it is now
  // (it may have scrolled out of the laid-out cells).
  suspend fun cellsScrolled(share: Float): Map<Int, IntRect> {
    scrolled += scrollBy(down * height * share).roundToInt()
    withFrameNanos {}
    return cellBounds(postCount, gridItems) + (from to at.translate(0, -scrolled))
  }
  val next = when {
    page -> cellNear(cellsScrolled(PAGE_JUMP), at.center.x, at.center.y)
    move == GridMove.Left || move == GridMove.Right -> nextCell(cells, from, move)
    // The next cell may be past what's laid out: look up to a few screens on.
    else -> nextCell(cells, from, move) ?: (1..LOOK_AHEAD_STEPS).firstNotNullOfOrNull {
      nextCell(cellsScrolled(0.5f), from, move)
    }
  }
  // Nothing found (the end of what's loaded): the cursor stays, and the grid comes back to it
  // rather than leave it off screen.
  scrollFullyIntoView(gridItems.itemOf(next ?: from))
  return next ?: from
}

/** A Shift+W/S page jump, as a share of the viewport, so a row stays in sight across it. */
private const val PAGE_JUMP = 0.85f

/** How many half screens W/S scroll on looking for the next cell before giving up. */
private const val LOOK_AHEAD_STEPS = 4

/** Desktop: the Top button's gap to the grid's bottom edge. */
private val TOP_BOTTOM = 20.dp

private const val LOADING_INDICATOR_DELAY_MS = 300L

private const val REFRESH_START_TIMEOUT_MS = 2_000L

private const val RETRY_WAIT_MS = 2_000L

/** A restored grid's place given up (#492): the post is gone, or the filter changed. */
internal const val PLACE_GIVEN_UP = "Couldn't get back to where you were"

/** A page failed because Sankaku couldn't be reached: the only failure the network coming back retries. */
internal fun CombinedLoadStates.failedUnreachable(): Boolean =
  listOf(refresh, prepend, append).any { (it as? LoadState.Error)?.error is ApiError.Network }

/**
 * A message in the grid's place, centred. On Android it scrolls (by nothing: it's exactly as tall as the view), so a
 * drag on it reaches the pull-to-refresh around it, as a drag on the grid does. Desktop has no pull: a plain box.
 */
@Composable
private fun GridMessage(content: @Composable BoxScope.() -> Unit) {
  if (!platformIsTouch) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
    return
  }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Box(
      Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().height(maxHeight),
      contentAlignment = Alignment.Center,
      content = content,
    )
  }
}
