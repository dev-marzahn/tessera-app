package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.shell.LocalQueuePageVisibility
import dev.marzahn.sankaku.shell.sharedPicture
import dev.marzahn.sankaku.shell.sharedMosaicThumb
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.arrow_back
import com.google.fonts.icons.check
import com.google.fonts.icons.close
import com.google.fonts.icons.drag_handle
import com.google.fonts.icons.more_vert
import com.google.fonts.icons.play_arrow
import com.google.fonts.icons.search
import com.google.fonts.icons.shield
import com.google.fonts.icons.undo
import com.google.fonts.icons.visibility_off
import dev.marzahn.sankaku.PlatformBackHandler
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.settings.ClearEverythingConfirm
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.LiveAnnouncement
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.PrimaryIconButton
import dev.marzahn.sankaku.ui.components.TonalButton
import dev.marzahn.sankaku.ui.components.TonalToggleIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/**
 * The queue page (#177, option Q1 of `.scratch/bar-less/`): a full screen over the app, opened from the count pill (#178)
 * and at each start (`QueueStart`). It replaces the tab list sheet (#132) and keeps its positions, find,
 * scrubber, bulk closes, swipe to close (#120), drag to move and Recently closed.
 *
 * - **Header:** "Queue", the count, 🔍 find (by title and tags), a shield for G only (#164), and ⋮ with the bulk
 *   closes and Clear everything. Under it, "Clear viewed · N" ([ClearViewed], #183).
 * - **Rows ([queueRows]):** in queue order, each block's kept posts indented under its head; a kept post seen since it
 *   was kept is dimmed with "seen" in words; a feed where it stands, its row saying "Feed" (#327). With G only on, R15 and R18 items are left out
 *   and counted ("Hidden by G only · N").
 * - **Blur:** R15 and R18 thumbnails, Recently closed's too, are blurred, with an eye-slash, until the queue's first use
 *   since the app started ([interacted], #264), and always while G only is on. Titles stay. On the page a touch, a
 *   scroll, a TalkBack move or close, or closing the page is that use ([onInteract]).
 * - **Continue · {current item}** at the bottom goes back to the active item; a tap on a row goes to that item.
 *
 * A swiped row, or a bulk close from ⋮, leaves "Closed · Undo" in its place until the next close or the page closes.
 * A swipe also tells [onSwipeClosed], so the count pill can offer Undo for the rest of the window once the page closes
 * (#178). Back closes the page, as Continue does.
 *
 * [onListTop] is told where the list starts, below the header, from the window's top: the app's top line goes there
 * (#309).
 *
 * While [leaving] (it fades out as the item under it comes in, #320) Back is the item's again, and the blur stays as it
 * was: leaving is a use of the queue, and it mustn't unblur a picture on its way out.
 */
@Composable
fun QueuePage(
  onDismiss: () -> Unit,
  onSwipeClosed: (ClosedTab) -> Unit = {},
  controller: TabsController = koinInject(),
  posts: TabPosts = koinInject(),
  privacy: PrivacySettings = koinInject(),
  seen: SeenStore? = koinInject<SeenStore>(),
  clearViewed: ClearViewed = koinInject(),
  interacted: () -> Boolean = koinInject<QueueStart>()::interacted,
  onInteract: () -> Unit = koinInject<QueueStart>()::interact,
  onListTop: (Dp) -> Unit = {},
  leaving: Boolean = false,
) {
  val interact by rememberUpdatedState(onInteract)
  // Back, Continue, a row or a faded source opened: each leaves the page, and each is a use of the queue.
  val dismiss = {
    interact()
    onDismiss()
  }
  PlatformBackHandler(enabled = !leaving, onBack = dismiss)
  val liveTabs by controller.tabs.collectAsStateWithLifecycle()
  val active by controller.active.collectAsStateWithLifecycle()
  val liveClosed by controller.closed.collectAsStateWithLifecycle()
  // Leaving, the rows as last shown (#322): what the tap changed mustn't move the row a picture leaves from, take it
  // away (a Recently closed row reopened), or add a row for what it reopened.
  val shownLists = remember { arrayOfNulls<Pair<List<TabState>, List<ClosedTab>>>(1) }
  val (tabs, closed) = shownLists[0]?.takeIf { leaving } ?: (liveTabs to liveClosed)
  SideEffect { if (!leaving) shownLists[0] = liveTabs to liveClosed }
  // The Recently closed entry the page is leaving for, by its key: its row's thumbnail moves, not the current item's.
  var reopened by remember { mutableStateOf<Long?>(null) }
  // Opened again before it had gone, the page is over the current item again.
  if (!leaving && reopened != null) SideEffect { reopened = null }
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  // Leaving, the blur last shown: the dismiss that starts it is a use of the queue, which would unblur it.
  val blurNow = gOnly || !interacted()
  val shownBlur = remember { booleanArrayOf(blurNow) }
  val blurAdult = if (leaving) shownBlur[0] else blurNow
  SideEffect { if (!leaving) shownBlur[0] = blurNow }
  // Whether a kept post was seen since it was kept (#182); a new lambda on each change to what's seen, so the rows read
  // it again. Mark unseen changes the tab's kept time, which the rows read as snapshot state.
  val seenRevision = seen?.revision?.collectAsStateWithLifecycle()?.value
  val seenSince: (TabState) -> Boolean = remember(seen, seenRevision) {
    { tab -> seen != null && tab.seenSinceKept(seen) }
  }
  // What's known of the posts the tabs show, for find, ratings and "Close favourited". The tops are snapshot state.
  // Recently closed's posts too, for their ratings (#250).
  val postIds = controller.openPosts(tabs) + closedPosts(closed)
  val known by remember(postIds) { posts.of(postIds) }.collectAsStateWithLifecycle(emptyMap())
  val post: (TabState) -> TabPost? = { tab -> (tab.top as? Route.Post)?.let { known[it.id] } }
  // The last close's tab or batch, where it stood. Gone once it's reopened, from here or from Recently closed.
  var swiped by remember { mutableStateOf<ClosedTab?>(null) }
  // The inline rows that have had their focus: once per close, not each time the row scrolls back in.
  val focused = remember { mutableSetOf<Long>() }
  val inline = swiped?.takeIf { it in closed }
  // Null while find is shut.
  var query by remember { mutableStateOf<String?>(null) }
  val finding = query?.takeIf { it.isNotBlank() }
  val rows = queueRows(tabs, closed, inline, finding, gOnly, post)
  val currentRows by rememberUpdatedState(rows)
  // What the header, the rows' positions and the scrubber count: every item but the feeds (#229).
  val counted = countedItems(tabs)
  // A couple of rows above the current item, so it opens in view with its neighbours.
  val list = rememberLazyListState(
    initialFirstVisibleItemIndex = (rows.indexOfFirst { it.isTab(active) } - ROWS_ABOVE_CURRENT).coerceAtLeast(0),
  )
  val rowHeight = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
  val reorder = remember { Reorder() }
  // Bumped to bring the current item back into view, as when the page opened: find cleared or shut.
  var showCurrent by remember { mutableIntStateOf(0) }
  LaunchedEffect(showCurrent) {
    if (showCurrent == 0) return@LaunchedEffect
    val at = currentRows.indexOfFirst { it.isTab(controller.active.value) }
    list.scrollToItem((at - ROWS_ABOVE_CURRENT).coerceAtLeast(0))
  }
  // A scroll, a TalkBack one too, is a use of the queue (#264).
  LaunchedEffect(list) {
    snapshotFlow { list.isScrollInProgress }.first { it }
    interact()
  }
  // Each change to a find shows its matches from the first.
  LaunchedEffect(query) {
    if (!query.isNullOrBlank()) list.scrollToItem(0)
  }
  // A bulk close's inline row may stand far from what's in view: the list moves to it.
  var reveal by remember { mutableStateOf<Long?>(null) }
  LaunchedEffect(reveal) {
    val key = reveal ?: return@LaunchedEffect
    val at = currentRows.indexOfFirst { it is QueueRow.Inline && it.closed.key == key }
    if (at >= 0) list.scrollToItem((at - 1).coerceAtLeast(0))
  }
  val bulk = listOf(
    // The page runs downwards and the phone has no tabs (#249): what's after the current item is below it.
    BulkClose("Close everything below", controller.tabsToTheRight()),
    BulkClose("Close duplicates", controller.duplicateTabs()),
    BulkClose("Close favourited", controller.favouritedTabs { known[it]?.favourited == true }),
    BulkClose("Close all but current", controller.otherTabs()),
  )
  var clearingEverything by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val activeTab = liveTabs.firstOrNull { it.id == active }
  // The room Continue takes at the bottom, measured: its words wrap at a large font.
  var continueRoom by remember { mutableStateOf(CONTINUE_ROOM) }
  // Fading and deletion are worked out as the page opens (#215), and the Faded section is shut until opened.
  val faded by controller.faded.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { controller.sweep() }
  var fadedOpen by rememberSaveable { mutableStateOf(false) }
  val fadedPanel = fadedPanel(
    faded = faded,
    query = finding,
    gOnly = gOnly,
    expanded = fadedOpen,
    now = controller.now(),
    onToggle = { fadedOpen = !fadedOpen },
    onOpen = { source ->
      controller.restore(source.id, show = true)
      dismiss()
    },
    onRestore = { source -> controller.restore(source.id) },
    onDelete = { source ->
      controller.deleteFaded(source.id)?.let {
        swiped = it
        reveal = it.key
      }
    },
  )

  // What the last move said to TalkBack ("artist_x moved to item 3 of 9"), in a polite live region.
  var moved by remember { mutableStateOf("") }
  // Emptied once said, so the same words (a second "marked unseen") are a change to the live region again.
  LaunchedEffect(moved) {
    if (moved.isNotEmpty()) {
      delay(ANNOUNCEMENT_MILLIS)
      moved = ""
    }
  }

  // One step of a drag on ≡ or a TalkBack move: how many rows the item moved on the page, or null if it couldn't.
  fun step(id: TabId, down: Boolean): Int? {
    interact()
    val before = currentRows.indexOfFirst { it.isTab(id) }
    controller.moveInQueue(id, down) ?: return null
    val rowsAfter = queueRows(controller.tabs.value, closed, inline, finding, gOnly, post)
    // TalkBack's focus stays on the place, now held by another row: say where the item went (#185).
    moved = when (val row = rowsAfter.firstOrNull { it.isTab(id) }) {
      is QueueRow.Item -> "${row.tab.title} moved to item ${row.position + 1} of ${countedItems(controller.tabs.value)}"
      is QueueRow.ClosedBlock -> "${row.title} moved ${if (down) "down" else "up"}"
      else -> moved
    }
    return rowsAfter.indexOfFirst { it.isTab(id) } - before
  }

  Box(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      // Any touch on the page, seen before a row or button takes it, is a use of the queue (#264).
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
          interact()
        }
      }
      // A touch on the page's empty parts never reaches the tab under it.
      .pointerInput(Unit) { detectTapGestures {} }
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    LiveAnnouncement(moved)
    Column(Modifier.fillMaxSize()) {
      val typed = query
      if (typed == null) {
        Header(
          count = counted,
          gOnly = gOnly,
          gOnlyEnabled = !privacy.gOnlyForced,
          onGOnly = privacy::setGOnly,
          bulk = bulk,
          onFind = { query = "" },
          onBulk = { ids ->
            controller.closeAll(ids)?.let {
              swiped = it
              reveal = it.key
            }
          },
          onClearEverything = { clearingEverything = true },
        )
        ClearViewedButton(clearViewed.status(), onClick = { clearViewed() })
      } else {
        FindField(
          query = typed,
          onQuery = { new ->
            if (new.isBlank() && typed.isNotBlank()) showCurrent++
            query = new
          },
          onClose = {
            query = null
            showCurrent++
          },
        )
      }
      // Below the header and Clear viewed, or find. The page fills the window, so the window's top is the page's.
      val density = LocalDensity.current
      Box(Modifier.weight(1f).onPlaced { onListTop(with(density) { it.positionInRoot().y.toDp() }) }) {
        QueueItems(
          rows = rows,
          total = counted,
          active = active,
          closed = closed.takeIf { query == null }.orEmpty(),
          finding = finding,
          gOnly = gOnly,
          list = list,
          reorder = reorder,
          rowHeight = rowHeight,
          bottomRoom = continueRoom,
          onAnnounce = { moved = it },
          controller = controller,
          seenSince = seenSince,
          rating = { tab -> postRating(tab, post(tab)) },
          closedPost = { known[it.id] },
          blurAdult = blurAdult,
          step = ::step,
          // A second call for a tab already closed closes nothing, and keeps the inline row.
          onClose = { id ->
            interact()
            controller.close(id)?.let {
              swiped = it
              onSwipeClosed(it)
            }
          },
          firstShow = focused::add,
          faded = fadedPanel,
          onUndo = {
            interact()
            swiped = null
            // It takes the swipe back, so the current item stays current (#306).
            controller.reopen(it, inPlace = true)
          },
          reopened = reopened,
          onReopen = {
            reopened = it.key
            controller.reopen(it)
          },
          onDismiss = dismiss,
        )
        TabScrubber(
          list = list,
          rowCount = rows.size,
          rowHeight = ROW_HEIGHT,
          // Past the item rows (Recently closed), the last item's position.
          // A feed isn't counted (#229): over one it's the counted item after it, 1 for a feed first (#327).
          label = { item -> "${positionNear(rows, item).coerceAtMost(counted.coerceAtLeast(1))} / $counted" },
          modifier = Modifier.matchParentSize().padding(bottom = continueRoom),
        )
      }
    }
    if (activeTab != null && query == null) {
      val density = LocalDensity.current
      ContinueButton(
        activeTab.title,
        dismiss,
        // It takes what its words need at a large font, and the list leaves that much room.
        Modifier.align(Alignment.BottomCenter).onSizeChanged { continueRoom = with(density) { it.height.toDp() } },
      )
    }
    // Leaving, it takes no more touches: a row tapped as it fades would open another item under the one coming in.
    if (leaving) {
      Box(
        Modifier.matchParentSize().pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
          }
        },
      )
    }
  }
  if (clearingEverything) ClearEverythingConfirm(scope = scope, onDismiss = { clearingEverything = false })
}

// Whether this row is the item [id], or a closed source's row, which stands for the id of the head it lost (#184).
private fun QueueRow.isTab(id: TabId) = (this is QueueRow.Item && tab.id == id) || (this is QueueRow.ClosedBlock && head == id)

/** One of ⋮'s bulk closes: its menu [label] and the tabs it would close now. */
private class BulkClose(val label: String, val ids: List<TabId>)

/** The 1-based position of the item at [row], or of the nearest item before it (after it, at the top). */
private fun positionNear(rows: List<QueueRow>, row: Int): Int {
  if (rows.isEmpty()) return 1
  val at = row.coerceIn(rows.indices)
  val item = (at downTo 0).firstNotNullOfOrNull { rows[it] as? QueueRow.Item }
    ?: (at..rows.lastIndex).firstNotNullOfOrNull { rows[it] as? QueueRow.Item }
  return (item?.position ?: 0) + 1
}

/**
 * "Queue" and the count, 🔍 (find), the shield (G only, pressed while it's on) and ⋮ with the bulk closes, each with how
 * many it would close and disabled at none, then Clear everything.
 */
@Composable
private fun Header(
  count: Int,
  gOnly: Boolean,
  gOnlyEnabled: Boolean,
  onGOnly: (Boolean) -> Unit,
  bulk: List<BulkClose>,
  onFind: () -> Unit,
  onBulk: (List<TabId>) -> Unit,
  onClearEverything: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.width(8.dp))
    Text(
      "$count",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f).semantics { contentDescription = if (count == 1) "1 item" else "$count items" },
    )
    IconButton(onClick = onFind) { Icon(search, contentDescription = "Find in queue") }
    // A debug run launched with `safeOnly` holds the lock on: the shield shows it and can't change it.
    TonalToggleIconButton(checked = gOnly, onCheckedChange = onGOnly, enabled = gOnlyEnabled) {
      Icon(shield, contentDescription = "G only")
    }
    Box {
      IconButton(onClick = { menu = true }) { Icon(more_vert, contentDescription = "More") }
      GroupedMenu(expanded = menu, onDismissRequest = { menu = false }) {
        MenuGroup(0, 2) {
          bulk.forEach { close ->
            MenuAction(
              text = { Text("${close.label} (${close.ids.size})") },
              enabled = close.ids.isNotEmpty(),
              onClick = {
                menu = false
                onBulk(close.ids)
              },
            )
          }
        }
        MenuGroup(1, 2) {
          MenuAction(
            text = { Text("Clear everything…") },
            onClick = {
              menu = false
              onClearEverything()
            },
          )
        }
      }
    }
  }
}

/**
 * "Clear viewed · N" under the header; only while there's something to clear (N > 0). Tonal, and primary once every kept
 * post has been seen ([ClearViewed.Status.suggested]): that is the whole suggestion, nothing happens by itself. Its
 * accessible name is "Clear viewed, N kept posts".
 */
@Composable
private fun ClearViewedButton(status: ClearViewed.Status, onClick: () -> Unit) {
  if (status.count <= 0) return
  val text = "Clear viewed · ${status.count}"
  // One name, "Clear viewed, 118 kept posts": a description beside the visible words made TalkBack read both (#185).
  val modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).clearAndSetSemantics {
    contentDescription = clearViewedLabel(status.count)
    role = Role.Button
    onClick { onClick(); true }
  }
  if (status.suggested) PrimaryIconButton(text, check, onClick, modifier) else TonalButton(text, check, onClick, modifier)
}

/** Find, in the header's place: ← shuts it, ✕ clears it. It takes the focus as it opens. */
@Composable
private fun FindField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Row(
    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onClose) { Icon(arrow_back, contentDescription = "Close find") }
    TextField(
      value = query,
      onValueChange = onQuery,
      singleLine = true,
      placeholder = { Text("Find by title or tag") },
      trailingIcon = if (query.isEmpty()) {
        null
      } else {
        { IconButton(onClick = { onQuery("") }) { Icon(close, contentDescription = "Clear") } }
      },
      colors = TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
      ),
      shape = RoundedCornerShape(28.dp),
      modifier = Modifier.weight(1f).focusRequester(focus),
    )
  }
}

/** "Continue · {title}": a filled 52 dp button at the thumb that goes back to the current item. */
@Composable
private fun ContinueButton(title: String, onClick: () -> Unit, modifier: Modifier) {
  Button(
    onClick = onClick,
    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    modifier = modifier.padding(16.dp).fillMaxWidth().heightIn(min = CONTINUE_HEIGHT),
  ) {
    Icon(play_arrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text("Continue · $title", maxLines = rowTextLines(), overflow = TextOverflow.Ellipsis)
  }
}

/**
 * The list itself: [rows], then Recently closed. While [finding], rows don't move (their places among the hidden items
 * aren't shown), and an empty result says so.
 */
@Composable
private fun QueueItems(
  rows: List<QueueRow>,
  total: Int,
  active: TabId,
  closed: List<ClosedTab>,
  finding: String?,
  gOnly: Boolean,
  list: LazyListState,
  reorder: Reorder,
  rowHeight: Float,
  bottomRoom: Dp,
  onAnnounce: (String) -> Unit,
  controller: TabsController,
  seenSince: (TabState) -> Boolean,
  rating: (TabState) -> Rating?,
  closedPost: (Route.Post) -> TabPost?,
  blurAdult: Boolean,
  step: (TabId, down: Boolean) -> Int?,
  onClose: (TabId) -> Unit,
  firstShow: (Long) -> Boolean,
  faded: FadedPanel?,
  onUndo: (ClosedTab) -> Unit,
  reopened: Long?,
  onReopen: (ClosedTab) -> Unit,
  onDismiss: () -> Unit,
) {
  // ≡ takes only a vertical drag, past the platform's touch slop: a sideways swipe that starts on it is left to the row,
  // so it closes the tab however long the finger rested first (#120).
  val handleFor: (TabId) -> Modifier = { id ->
    Modifier.pointerInput(id) {
      detectVerticalDragGestures(
        onDragStart = { reorder.start(id) },
        onDragEnd = reorder::stop,
        onDragCancel = reorder::stop,
        onVerticalDrag = { change, dy ->
          change.consume()
          // A row is taller at a large font: the dragged row's own height, the normal one when it's scrolled out.
          val height = list.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == id.value || it.key == "$BLOCK_PREFIX${id.value}" }?.size?.toFloat() ?: rowHeight
          reorder.drag(dy, height, neighbour = { down -> neighbourRows(rows, id, down) }) { down -> step(id, down) }
        },
      )
    }
  }
  LazyColumn(state = list, contentPadding = PaddingValues(bottom = bottomRoom), modifier = Modifier.fillMaxWidth()) {
    if (finding != null && rows.none { it is QueueRow.Item } && faded == null) {
      item(key = NO_MATCH) {
        Text(
          "Nothing in the queue matches “$finding”",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(16.dp),
        )
      }
    }
    // Every row moves while not finding, a feed's with its block too: a feed is a source that persists (#327).
    rows.forEach { row ->
      when (row) {
        is QueueRow.Inline -> inlineUndoRow(row.closed, firstShow, onUndo)
        is QueueRow.ClosedBlock -> item(key = "$BLOCK_PREFIX${row.head.value}") {
          val movable = finding == null
          ClosedBlockRow(
            row = row,
            // TalkBack's way to move the block, as on a head's row.
            onMoveUp = if (movable && controller.canMoveInQueue(row.head, down = false)) ({ step(row.head, false) }) else null,
            onMoveDown = if (movable && controller.canMoveInQueue(row.head, down = true)) ({ step(row.head, true) }) else null,
            handle = if (movable) handleFor(row.head) else null,
            modifier = if (reorder.dragging == row.head) {
              Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offset }
            } else {
              Modifier.animateItem()
            },
          )
        }
        is QueueRow.HiddenByGOnly -> item(key = HIDDEN_BY_G_ONLY) {
          SectionHeading("Hidden by G only · ${row.count}", Modifier.animateItem())
        }
        is QueueRow.Item -> item(key = row.tab.id.value) {
          val tab = row.tab
          val movable = finding == null
          // A dragged head carries its kept posts with it.
          val dragged = reorder.dragging?.let { it == tab.id || (row.indented && it == tab.head) } == true
          QueueItemRow(
            row = row,
            total = total,
            selected = tab.id == active,
            // The current item's: the lone post opening as the page closes, or the one it opened over (#320), or the
            // grid (#322). Not when what the page closes to was reopened from Recently closed: that row's moves.
            sharing = tab.id == active && reopened == null,
            seen = tab.kind == QueueKind.KeptPost && seenSince(tab),
            onMarkUnseen = {
              controller.keepAgain(tab.id)
              onAnnounce("${tab.title} marked unseen")
            },
            rating = rating(tab),
            blurAdult = blurAdult,
            onOpen = {
              controller.activate(tab.id)
              onDismiss()
            },
            onClose = { onClose(tab.id) },
            // A feed item can't be closed (#229): no swipe and no Close action.
            closable = controller.canClose(tab),
            // TalkBack's way to move a row, one place at a time; a head moves its block.
            onMoveUp = if (movable && controller.canMoveInQueue(tab.id, down = false)) ({ step(tab.id, false) }) else null,
            onMoveDown = if (movable && controller.canMoveInQueue(tab.id, down = true)) ({ step(tab.id, true) }) else null,
            handle = if (movable) handleFor(tab.id) else null,
            closeLabel = closeLabel(tab, controller.keptPostsOf(tab)),
            modifier = if (dragged) {
              Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offset }
            } else {
              Modifier.animateItem()
            },
          )
        }
      }
    }
    // Last of the queue, before Recently closed (#215).
    fadedSection(faded)
    if (closed.isNotEmpty()) {
      item(key = CLOSED_HEADER) { SectionHeading("Recently closed", Modifier.animateItem()) }
      // The most recent first; G only leaves out R15 and R18 entries and counts them, as the queue page does (#250).
      val listed = closedRows(closed, gOnly, closedPost)
      items(listed.rows, key = { "$CLOSED_PREFIX${it.closed.key}" }) { row ->
        RecentlyClosedRow(
          row,
          blurAdult = blurAdult,
          sharing = row.closed.key == reopened,
          onReopen = {
            onReopen(row.closed)
            onDismiss()
          },
          modifier = Modifier.animateItem(),
        )
      }
      if (listed.hidden > 0) {
        item(key = CLOSED_HIDDEN) { SectionHeading("Hidden by G only · ${listed.hidden}", Modifier.animateItem()) }
      }
    }
  }
}

/**
 * How many rows a step of the item [id] would pass on the page, or null if none: a kept post passes one row, a head the
 * whole neighbouring block. Stops at the G only count.
 */
internal fun neighbourRows(rows: List<QueueRow>, id: TabId, down: Boolean): Int? {
  // The inline Undo row isn't part of any block.
  val items = rows.filter { it !is QueueRow.Inline }
  val at = items.indexOfFirst { it.isTab(id) }
  if (at < 0) return null
  // A closed source's row is a head like any other (#184); its kept posts are the indented rows after it.
  if ((items[at] as? QueueRow.Item)?.indented == true) return 1
  fun isKept(i: Int) = (items.getOrNull(i) as? QueueRow.Item)?.indented == true
  fun isHead(i: Int) = items.getOrNull(i).let { it is QueueRow.ClosedBlock || (it is QueueRow.Item && !it.indented) }
  if (down) {
    // Past this block's own kept posts, the next head and its kept posts.
    var i = at + 1
    while (isKept(i)) i++
    if (!isHead(i)) return null
    var count = 1
    while (isKept(++i)) count++
    return count
  }
  // The previous block's kept posts, then its head.
  var i = at - 1
  var count = 0
  while (isKept(i)) {
    count++
    i--
  }
  return if (isHead(i)) count + 1 else null
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
  )
}

/**
 * "from artist_x (closed)" over the kept posts a closed source left (#184), with how many there are, and the ≡
 * [handle] that moves the block whole. A heading to TalkBack, with "Move up" and "Move down" where [onMoveUp] and
 * [onMoveDown] are set, as on a source's row. Opening a source on that search puts it back in front of the block.
 */
@Composable
private fun ClosedBlockRow(
  row: QueueRow.ClosedBlock,
  onMoveUp: (() -> Unit)?,
  onMoveDown: (() -> Unit)?,
  handle: Modifier?,
  modifier: Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val lineColor = colors.outlineVariant
  Row(
    modifier
      .fillMaxWidth()
      .background(colors.surfaceContainerLow)
      // Its kept posts always follow: the line starts under its words (#234).
      .drawBehind { blockLine(BlockLine.Head, lineColor, headBottom = size.height - CLOSED_ROW_TEXT_BOTTOM.toPx()) }
      .heightIn(min = ROW_HEIGHT)
      .semantics(mergeDescendants = true) {
        heading()
        customActions = listOfNotNull(
          onMoveUp?.let { move -> CustomAccessibilityAction("Move up") { move(); true } },
          onMoveDown?.let { move -> CustomAccessibilityAction("Move down") { move(); true } },
        )
      }
      .padding(start = 8.dp, end = SCRUBBER_WIDTH),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Under the position numbers, level with the titles of the rows above.
    Spacer(Modifier.width(POSITION_WIDTH + 10.dp))
    Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(row.title, style = MaterialTheme.typography.titleSmall)
      Text(
        if (row.count == 1) "1 kept post stays" else "${row.count} kept posts stay",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
    if (handle != null) {
      Box(handle.size(48.dp).semantics { contentDescription = "Move" }, contentAlignment = Alignment.Center) {
        Icon(drag_handle, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
      }
    } else {
      Spacer(Modifier.width(12.dp))
    }
  }
}

/**
 * "Closed “title” · ↶ Undo" in a swiped row's place, or "Closed 12 · ↶ Undo" for a bulk close, on the inverse surface
 * as a snackbar is. It covers nothing, so it has no timer. TalkBack hears what closed and its focus moves to Undo.
 */
private fun LazyListScope.inlineUndoRow(closedTab: ClosedTab, firstShow: (Long) -> Boolean, onUndo: (ClosedTab) -> Unit) {
  item(key = "$CLOSED_PREFIX$INLINE_PREFIX${closedTab.key}") {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (firstShow(closedTab.key)) focus.requestFocus() }
    Row(
      Modifier
        .animateItem()
        .padding(horizontal = 12.dp, vertical = 4.dp)
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(colors.inverseSurface)
        .padding(start = 16.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // "30 kept stay" (#184) is a line of its own, so a long title can't cut it off.
      Column(
        Modifier.weight(1f).padding(vertical = 8.dp).semantics(mergeDescendants = true) {
          contentDescription = if (closedTab.size > 1) "Closed ${closedTab.size} items" else closedTab.closedText
          liveRegion = LiveRegionMode.Polite
        },
      ) {
        Text(
          closedTab.closedLabel,
          style = MaterialTheme.typography.bodyMedium,
          color = colors.inverseOnSurface,
          maxLines = rowTextLines(),
          overflow = TextOverflow.Ellipsis,
        )
        if (closedTab.keptStay > 0) {
          Text(
            "${closedTab.keptStay} kept stay",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inverseOnSurface.copy(alpha = 0.8f),
          )
        }
      }
      TextButton(
        onClick = { onUndo(closedTab) },
        colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
        modifier = Modifier.focusRequester(focus),
      ) {
        Icon(undo, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Undo")
      }
    }
  }
}

/**
 * A queue item's row: its position (as the pill counts), a thumbnail (a grid's first four, or a post's preview, from
 * the image cache only), title, [queueSubtitle] and the ≡ [handle] (none while finding). A kept post
 * under its block's head is indented by 28 dp, with a thin line from the head down through the block
 * ([QueueRow.Item.line]), TalkBack hears "Kept from artist_x" (#234), and it's dimmed once [seen]. The current item
 * is in the secondary container with a primary bar. Swiped to the left it closes. TalkBack hears "Item 90 of 123" and offers [closeLabel] ("Close", or
 * "Close artist_x · 30 kept stay" for a source with kept posts), and "Move up" and "Move down" where [onMoveUp] and [onMoveDown] are set. A [seen] kept post offers "Mark unseen" ([onMarkUnseen]) in a
 * long-press menu and as a TalkBack action (#182).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueItemRow(
  row: QueueRow.Item,
  total: Int,
  selected: Boolean,
  sharing: Boolean,
  seen: Boolean,
  onMarkUnseen: () -> Unit,
  rating: Rating?,
  blurAdult: Boolean,
  onOpen: () -> Unit,
  onClose: () -> Unit,
  closable: Boolean,
  onMoveUp: (() -> Unit)?,
  onMoveDown: (() -> Unit)?,
  handle: Modifier?,
  closeLabel: String,
  modifier: Modifier,
) {
  val tab = row.tab
  val position = row.position + 1
  val closeTab by rememberUpdatedState(onClose)
  val swipe = rememberSwipeToDismissBoxState(
    confirmValueChange = { value -> (value == SwipeToDismissBoxValue.EndToStart).also { if (it) closeTab() } },
  )
  val colors = MaterialTheme.colorScheme
  val primary = colors.primary
  val lineColor = colors.outlineVariant
  var menu by remember { mutableStateOf(false) }
  SwipeToDismissBox(
    state = swipe,
    modifier = modifier,
    enableDismissFromStartToEnd = false,
    enableDismissFromEndToStart = closable,
    // A red square with ✕ comes in from the end as the row slides away.
    backgroundContent = {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.fillMaxHeight().width(ROW_HEIGHT).background(colors.error), contentAlignment = Alignment.Center) {
          Icon(close, contentDescription = null, tint = colors.onError)
        }
      }
    },
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        // 72 dp at the normal font; at a large one the title and the line under it wrap and the row grows (#185).
        .heightIn(min = ROW_HEIGHT)
        .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
        // The current item also has a primary bar at its start edge.
        .drawBehind { if (selected) drawRect(primary, size = Size(4.dp.toPx(), size.height)) }
        // A head's thumbnail is centred, so its line starts under it however tall the row grows.
        .drawBehind { blockLine(row.line, lineColor, headBottom = size.height / 2 + (THUMBNAIL_SIZE / 2).toPx()) }
        .combinedClickable(
          onClickLabel = "Show",
          onLongClickLabel = if (seen) MARK_UNSEEN else null,
          onLongClick = if (seen) ({ menu = true }) else null,
          onClick = onOpen,
        )
        .semantics {
          customActions = listOfNotNull(
            CustomAccessibilityAction(closeLabel) { closeTab(); true }.takeIf { closable },
            if (seen) CustomAccessibilityAction(MARK_UNSEEN) { onMarkUnseen(); true } else null,
            onMoveUp?.let { move -> CustomAccessibilityAction("Move up") { move(); true } },
            onMoveDown?.let { move -> CustomAccessibilityAction("Move down") { move(); true } },
          )
        }
        // ≡ ends clear of the scrubber's strip, so the thumb never covers it.
        .padding(start = 8.dp, end = SCRUBBER_WIDTH, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // A feed isn't counted (#229): its row has no position, and says "Feed".
      val counted = tab.kind != QueueKind.Feed
      Text(
        if (counted) "$position" else "",
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.widthIn(min = POSITION_WIDTH).then(
          if (counted) Modifier.semantics { contentDescription = "Item $position of $total" } else Modifier,
        ),
      )
      Row(
        Modifier.weight(1f).padding(start = if (row.indented) INDENT else 0.dp).alpha(if (seen) SEEN_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // As the grid last reported it, or as saved for one not shown since launch (#232); cache-only either way.
        QueueThumbnail(
          top = tab.top,
          mosaic = tab.mosaic?.thumbs,
          blurred = { thumb -> blurAdult && (thumb?.isAdult ?: row.adult) },
          size = if (row.indented) KEPT_THUMBNAIL_SIZE else THUMBNAIL_SIZE,
          sharing = sharing,
        )
        Box(Modifier.weight(1f)) {
          RowText(
            tab.title,
            tab.untitled,
            queueSubtitle(tab, selected, seen, rating),
            Modifier,
            // TalkBack hears which source a kept post is under: "Kept from artist_x" (#234).
            spokenKind = row.from?.let { queueSubtitle(tab, selected, seen, rating, from = it) },
          )
          GroupedMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuGroup(0, 1) {
              MenuAction(
                text = { Text(MARK_UNSEEN) },
                onClick = {
                  menu = false
                  onMarkUnseen()
                },
              )
            }
          }
        }
      }
      if (handle != null) {
        Box(handle.size(48.dp).semantics { contentDescription = "Move" }, contentAlignment = Alignment.Center) {
          Icon(drag_handle, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
      } else {
        Spacer(Modifier.width(12.dp))
      }
    }
  }
}

/**
 * A grid's [mosaic] in a 2 × 2 square once it has loaded posts (saved with the tab, so also after a relaunch); else the
 * icon of [top] (a post's cached preview, filling the square). Every picture is cache-only: a miss leaves a blank
 * tile. Each one [blurred] says so (null for the post's own preview) is blurred, and an eye-slash sits over the
 * square, so an R15 or R18 picture never shows by accident.
 *
 * While [sharing] (the row of the item the page opens over or closes to), a kept post's preview is one element with
 * the lone post's picture (#320), and a mosaic's thumbnails each with its cell in the grid (#322): they move between
 * them as the queue page opens or closes. Never while any of it is blurred: a blurred row opens and closes with the
 * plain transition.
 */
@Composable
private fun QueueThumbnail(
  top: Route?,
  mosaic: List<QueueThumb>?,
  blurred: (QueueThumb?) -> Boolean,
  size: Dp,
  sharing: Boolean,
) {
  var anyBlurred = false
  Box(
    Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    val rowVisibility = LocalQueuePageVisibility.current
    // Made anew as the item under the page leaves for it (#320): the shared transition's scope only looks again at
    // what's moving when an element comes or goes, and with the same one kept, the picture stood still under the page.
    // Only then: made anew as the page starts to close too, the element lost the bounds the move leaves from, and the
    // item's picture showed at its own place at once (#322).
    val target = rowVisibility?.transition?.targetState
    val arrivals = remember { intArrayOf(0) }
    val lastTarget = remember { arrayOfNulls<EnterExitState>(1) }
    if (target != lastTarget[0]) {
      if (target == EnterExitState.Visible) arrivals[0]++
      lastTarget[0] = target
    }
    key(arrivals[0]) {
      if (mosaic.isNullOrEmpty()) {
        val blur = top is Route.Post && blurred(null)
        anyBlurred = blur
        val kept = (top as? Route.Post)?.takeIf { it.md5 != null }
        Box(
          // First in the chain, so the blur coming or going doesn't make the element anew.
          (if (kept == null) Modifier else Modifier.sharedPicture(kept.id, rowVisibility, enabled = sharing && !blur, slow = false, clip = RoundedCornerShape(12.dp)))
            .then(if (blur) Modifier.blur(BLUR).testTag(BLURRED_TAG) else Modifier),
        ) {
          if (kept != null) KeptPicture(kept, size) else TabIcon(top, size = if (top is Route.Post) size else 24.dp, decorative = true)
        }
      } else {
        val cell = (size - 2.dp) / 2
        // A mosaic with any thumbnail blurred is a blurred one: none of it moves (#322).
        val mosaicBlurred = mosaic.any(blurred)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          mosaic.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
              row.forEachIndexed { c, thumb ->
                val blur = blurred(thumb)
                if (blur) anyBlurred = true
                val tag = if (blur) BLURRED_TAG else if (sharing) mosaicThumbTag(r * 2 + c) else null
                PreviewImage(
                  thumb.image,
                  Modifier
                    .sharedMosaicThumb(thumb.md5, rowVisibility, enabled = sharing && !mosaicBlurred, clip = RoundedCornerShape(6.dp), cell = false)
                    .size(cell)
                    .then(if (blur) Modifier.blur(BLUR) else Modifier)
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier),
                )
              }
            }
          }
        }
      }
    }
    if (anyBlurred) {
      Box(
        Modifier.size(24.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(visibility_off, contentDescription = "Blurred", tint = Color.White, modifier = Modifier.size(16.dp))
      }
    }
  }
}

/**
 * A kept post's preview, from the first frame when it's decoded in memory, under the key every request for it uses
 * ([PostImage.cacheKey]): as the page opens it's what flies back from the lone post, and loaded it left a frame with
 * no picture (#320). Otherwise, the cache-only [TabIcon].
 */
@Composable
private fun KeptPicture(post: Route.Post, size: Dp) {
  val context = LocalPlatformContext.current
  val md5 = post.md5
  val preview = remember(md5) {
    md5?.let { SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(PostImage.cacheKey(it, Variant.Preview)))?.image }
  }
  if (preview == null) return TabIcon(post, size = size, decorative = true)
  Image(
    preview.asPainter(context),
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)),
  )
}

/**
 * A closed tab, or a bulk close's batch ("12 items · Closed together", reopened whole), or what Clear viewed cleared
 * ("12 posts · Cleared"), with the thumbnail its item had on the queue page (#250): [ClosedRow.shown]'s preview
 * or mosaic, cache-only, else its kind's icon. An R15 or R18 picture blurs while [blurAdult], as a queue row's does.
 * TalkBack hears the title, the kind and "Blurred" when it is, and "Reopen".
 *
 * Reopened ([sharing]), its thumbnail moves into what it reopens as the page closes (#322): a kept post's into its
 * picture, a grid's mosaic into its cells, as a queue row's does.
 */
@Composable
private fun RecentlyClosedRow(row: ClosedRow, blurAdult: Boolean, sharing: Boolean, onReopen: () -> Unit, modifier: Modifier) {
  val closedTab = row.closed
  Row(
    modifier
      .fillMaxWidth()
      .heightIn(min = ROW_HEIGHT)
      .clickable(onClickLabel = "Reopen", onClick = onReopen)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    QueueThumbnail(
      top = row.shown.top,
      mosaic = row.shown.mosaic?.thumbs,
      blurred = { thumb -> blurAdult && (thumb?.isAdult ?: row.adult) },
      size = THUMBNAIL_SIZE,
      sharing = sharing,
    )
    val kind = when {
      closedTab.cleared -> "Cleared"
      closedTab.size == 1 -> kindOf(closedTab.top)
      else -> "Closed together"
    }
    RowText(closedTab.title, closedTab.untitled, kind, Modifier.weight(1f))
  }
}

@Composable
private fun RowText(title: String, untitled: Boolean, kind: String, modifier: Modifier, spokenKind: String? = null) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      title,
      color = if (untitled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = rowTextLines(),
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      kind,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = rowTextLines(),
      overflow = TextOverflow.Ellipsis,
      modifier = if (spokenKind != null) Modifier.semantics { contentDescription = spokenKind } else Modifier,
    )
  }
}

private fun kindOf(route: Route?): String = when (route) {
  is Route.Post -> "Post"
  is Route.Grid -> "Grid"
  else -> "Item"
}

private val ROW_HEIGHT = 72.dp

/**
 * How many lines a row's title, the line under it, Continue and the inline Undo's words take before "…": one at the
 * normal font, as before; as many as they need at a large one, so nothing is cut short (#185).
 */
@Composable
internal fun rowTextLines(): Int = if (LocalDensity.current.fontScale > LARGE_FONT) Int.MAX_VALUE else 1

/** Past this font scale the queue page wraps its text instead of ellipsizing it. */
private const val LARGE_FONT = 1.3f
private val THUMBNAIL_SIZE = 48.dp
private val KEPT_THUMBNAIL_SIZE = 40.dp

/** How far a kept post sits in from its block's head (#234). */
private val INDENT = 28.dp

/**
 * Where a block's line runs: in the middle of the indent, past a row's start padding (8 dp), the position numbers
 * and the gap after them (10 dp), so it's under the head's thumbnail and clear of its kept posts'.
 */
private val BLOCK_LINE_X get() = 8.dp + POSITION_WIDTH + 10.dp + INDENT / 2
private val BLOCK_LINE_WIDTH = 2.dp

/** How far above a closed source's row's bottom its line starts: just under its words, which sit 8 dp up. */
private val CLOSED_ROW_TEXT_BOTTOM = 6.dp

/**
 * This row's part of its block's [line] ([BlockLine]), in [color]: from [headBottom] down on a head, through a kept post
 * but the last, and down to the middle of the last (level with its thumbnail). Rows touch, so the parts join up.
 */
private fun DrawScope.blockLine(line: BlockLine?, color: Color, headBottom: Float) {
  val (top, bottom) = when (line ?: return) {
    BlockLine.Head -> headBottom to size.height
    BlockLine.Through -> 0f to size.height
    BlockLine.End -> 0f to size.height / 2
  }
  val width = BLOCK_LINE_WIDTH.toPx()
  val start = BLOCK_LINE_X.toPx() - width / 2
  val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - width else start
  drawRect(color, topLeft = Offset(x, top), size = Size(width, bottom - top))
}

/** A seen kept post's opacity. */
private const val SEEN_ALPHA = 0.6f

private const val MARK_UNSEEN = "Mark unseen"

/** Enough to leave no detail in a 20 dp cell. */
private val BLUR = 10.dp

/** On every blurred image, for tests. */
internal const val BLURRED_TAG = "blurred"

/** For tests: the [index]th thumbnail of the mosaic on the row that shares it (#322). */
internal fun mosaicThumbTag(index: Int) = "mosaic thumb $index"

private val CONTINUE_HEIGHT = 52.dp

/** The room Continue takes at the bottom, so the last row scrolls clear of it. */
private val CONTINUE_ROOM = CONTINUE_HEIGHT + 32.dp

/** Room for "400" at the label size, so the thumbnails line up down the list. */
private val POSITION_WIDTH = 28.dp

/** Rows shown above the current item when the page opens on it. */
private const val ROWS_ABOVE_CURRENT = 2

/** How long a move or Mark unseen stays in the live region before it is emptied again. */
private const val ANNOUNCEMENT_MILLIS = 3_000L
private const val CLOSED_HEADER = "closed-header"
private const val CLOSED_HIDDEN = "closed-hidden-by-g-only"
private const val CLOSED_PREFIX = "closed-"
private const val NO_MATCH = "closed-no-match"
private const val HIDDEN_BY_G_ONLY = "hidden-by-g-only"

/** A closed source's row key, under its own prefix so it never clashes with a tab's. */
private const val BLOCK_PREFIX = "closed-source-"

/** The inline undo row's keys: under [CLOSED_PREFIX], so they never clash with a tab's. */
private const val INLINE_PREFIX = "inline-"

/**
 * Dragging a row by its handle: the row (with its block's kept posts, for a head) follows the finger ([offset]). Once
 * it has gone half the neighbouring block's height, it steps past that block, and the offset is rebased on the row's
 * new slot so it stays under the finger.
 */
private class Reorder {
  var dragging by mutableStateOf<TabId?>(null)
    private set
  var offset by mutableFloatStateOf(0f)
    private set

  fun start(id: TabId) {
    dragging = id
    offset = 0f
  }

  fun stop() {
    dragging = null
    offset = 0f
  }

  /**
   * [neighbour] is how many rows a step down (or up) would pass, null if none; [step] makes it and returns how many rows
   * the row moved. A step that moved it no row (a kept post leaving its block in place) starts the offset again.
   */
  fun drag(dy: Float, rowHeight: Float, neighbour: (down: Boolean) -> Int?, step: (down: Boolean) -> Int?) {
    if (dragging == null) return
    offset += dy
    val down = offset > 0
    val rows = neighbour(down) ?: return
    if (kotlin.math.abs(offset) <= rows * rowHeight / 2) return
    val moved = step(down) ?: return
    offset = if (moved == 0) 0f else offset - moved * rowHeight
  }
}
