package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.marzahn.sankaku.queue.SeenStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.close
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.features.grid.GridMove
import dev.marzahn.sankaku.features.grid.gridMoveFor
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.isBooks
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.ui.components.FocusRingGap
import dev.marzahn.sankaku.ui.components.FocusRingWidth
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.openOnClick
import dev.marzahn.sankaku.ui.theme.Amber
import org.koin.compose.koinInject

/**
 * The tab switcher (#461, desktop only): a grid of every open tab over the window, opened with [Shortcuts.TabSwitcher],
 * to jump to a far tab without stepping through (and loading) every tab between.
 *
 * It opens with the selection, the grid cursor's amber ring, on the active tab. W/A/S/D (or the arrows) move it, held
 * keys repeating; while the selection is scrolled out of view, the next move puts it on the first card in view instead.
 * Space or E goes to the selected tab, as a click on its tab does, and closes the switcher. X closes the selected tab
 * and Z reopens the last closed one, as they do outside it, and the switcher stays open. Esc or C closes it. The mouse
 * is apart from the keys, as on the grid: hover only highlights, a click goes to the tab, a middle click or the card's ✕
 * closes it, and a click outside the panel closes the switcher.
 *
 * Space, E, Esc and C act on their release, so the key's repeats and release never reach the screen under it (the grid
 * would take a repeated Space for a press and open the viewer). [keyHeld] says the key that opened it is still down: its
 * repeats are ignored and its release does nothing, so a held C opens it once and the next press closes it.
 *
 * Nothing in it makes a request: titles and favourites come from what the app holds ([TabPosts]), pictures from the
 * image cache only ([PreviewImage]), and only the chosen tab loads, once it's shown. With G only on, an R15 or R18 tab
 * ([isAdult]) shows its kind's icon instead of its picture.
 */
@Composable
fun TabSwitcher(
  open: Boolean,
  onClose: () -> Unit,
  keyHeld: Boolean = false,
  list: Boolean = false,
  onList: (Boolean) -> Unit = {},
  controller: TabsController = koinInject(),
  tabPosts: TabPosts = koinInject(),
  privacy: PrivacySettings = koinInject(),
  // The queue list's seen marks and Clear viewed (#502); without them (a test of the cards) nothing is seen.
  seen: SeenStore? = null,
  clearViewed: ClearViewed? = null,
) {
  if (!open) return
  val tabs by controller.tabs.collectAsStateWithLifecycle()
  val active by controller.active.collectAsStateWithLifecycle()
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  val postIds = remember(tabs) { tabs.mapNotNullTo(mutableSetOf()) { (it.top as? Route.Post)?.id } }
  val posts by remember(tabPosts, postIds) { tabPosts.of(postIds) }.collectAsStateWithLifecycle(emptyMap())
  val twins by remember(tabs) { derivedStateOf { twinSuffixes(tabs.map { it.title }) } }
  var selected by remember { mutableStateOf(active) }
  // A closed selection moves to the card that took its place (the next, else the previous), as closing does.
  val ids = remember(tabs) { tabs.map { it.id } }
  var lastIds by remember { mutableStateOf(ids) }
  LaunchedEffect(ids) {
    selected = selectionAfter(lastIds, ids, selected)
    lastIds = ids
  }
  // A reopen (Z, here or on the + badge) makes the tab it brought back active: the selection follows it.
  LaunchedEffect(controller) { controller.reopened.collect { selected = controller.active.value } }
  // The queue list (#502): the phone page's rows, find, ⋮ and Recently closed.
  val closed by controller.closed.collectAsStateWithLifecycle()
  val seenRevision = seen?.revision?.collectAsStateWithLifecycle()?.value
  val seenSince: (TabState) -> Boolean = remember(seen, seenRevision) { { tab -> seen != null && tab.seenSinceKept(seen) } }
  var query by remember { mutableStateOf<String?>(null) }
  var menuOpen by remember { mutableStateOf(false) }
  val known: (TabState) -> TabPost? = { tab -> (tab.top as? Route.Post)?.let { posts[it.id] } }
  val rows = if (list) queueRows(tabs, closed, inline = null, query?.takeIf { it.isNotBlank() }, gOnly, known) else emptyList()
  val selectable = selectableIds(rows)
  var lastSelectable by remember { mutableStateOf(selectable) }
  LaunchedEffect(selectable) {
    if (list && selected !in selectable && selectable.isNotEmpty()) {
      selected = if (lastSelectable.isEmpty()) selectable.first() else selectionAfter(lastSelectable, selectable, selected)
    }
    lastSelectable = selectable
  }
  val selectedRow = rows.indexOfFirst { selectableIds(listOf(it)).firstOrNull() == selected }
  // A couple of rows above the current item, as the phone page opens.
  val queueList = rememberLazyListState(initialFirstVisibleItemIndex = (selectedRow - 2).coerceAtLeast(0))
  LaunchedEffect(list, selectedRow) { if (list && selectedRow >= 0) queueList.bringIntoView(selectedRow) }
  val presses = remember { SwitcherPresses(heldAtOpen = Shortcuts.TabSwitcher.key.takeIf { keyHeld }) }
  // The opening C let go before the switcher had the focus (a quick tap): the shell heard its release.
  LaunchedEffect(keyHeld) { if (!keyHeld) presses.letGo(Shortcuts.TabSwitcher.key) }
  // Releases while another app has the focus go there: no key is held any more.
  val windowFocused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(windowFocused) { if (!windowFocused) presses.forget() }
  val grid = rememberLazyGridState(initialFirstVisibleItemIndex = tabs.indexOfFirst { it.id == active }.coerceAtLeast(0))
  val focus = remember { FocusRequester() }
  // The grid's columns as last laid out, for the moves; set as the frame applies, not a frame later.
  val columns = remember { intArrayOf(1) }

  fun go(id: TabId) {
    // A closed source's row stands for its block: going there shows its first kept item.
    val target = if (tabs.any { it.id == id }) id else blockIds(tabs, id).firstOrNull() ?: return
    controller.activate(target)
    onClose()
  }

  // The list's X: the item, or a closed source's row's kept items; Shift+X: the whole block of the selected row.
  fun closeRow(id: TabId) {
    val tab = tabs.firstOrNull { it.id == id }
    if (tab != null) {
      if (controller.canClose(tab)) controller.close(id)
    } else {
      controller.closeAll(blockIds(tabs, id))
    }
  }

  val focusRoot = { focus.requestFocus() }

  fun listKey(action: QueueListKey) {
    when (action) {
      is QueueListKey.Move -> queueListStep(selectable, selected, action.down)?.let { selected = it }
      QueueListKey.Go -> if (selected in selectable) go(selected)
      QueueListKey.Close -> if (selected in selectable) closeRow(selected)
      QueueListKey.CloseBlock -> if (selected in selectable) controller.closeBlock(selected)
      QueueListKey.Find -> query = ""
      QueueListKey.LeaveFind -> {
        query = null
        focusRoot()
      }
      QueueListKey.Menu -> menuOpen = true
      QueueListKey.Mode -> onList(false)
      QueueListKey.Reopen -> controller.reopenClosed()
      QueueListKey.Dismiss -> onClose()
    }
  }

  // Back on the cards, a closed source's row's selection (no tab) goes to the current tab.
  LaunchedEffect(list) { if (!list && tabs.none { it.id == selected }) selected = active }
  val selectedIndex = tabs.indexOfFirst { it.id == selected }
  LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) grid.bringIntoView(selectedIndex) }

  // Esc is the switcher's own key, on its release: the dialog's own Esc would close it on the press, and its release
  // and repeats would then reach the screen under it.
  Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnBackPress = false, usePlatformDefaultWidth = false)) {
    BoxWithConstraints(
      Modifier
        .fillMaxSize()
        // A click outside the panel closes the switcher.
        .clickable(interactionSource = null, indication = null, onClick = onClose)
        .onPreviewKeyEvent { event ->
          if (list) {
            val (action, taken) = queueListKey(event, presses, finding = query != null)
            action?.let(::listKey)
            return@onPreviewKeyEvent taken
          }
          switcherKey(
            event,
            presses,
            onMove = { move ->
              val index = tabs.indexOfFirst { it.id == selected }
              // Scrolled out of view (the wheel), the selection comes back on the first card in view. Partly in view,
              // as while a move's scroll runs, it moves on from where it is.
              val from = index.takeIf { it >= 0 && grid.layoutInfo.visibleItemsInfo.any { item -> item.index == it } }
              val next = if (from == null) grid.firstFullyShown() else switcherStep(from, tabs.size, columns[0], move)
              next?.let { tabs.getOrNull(it) }?.let { selected = it.id }
            },
            onGo = { tabs.firstOrNull { it.id == selected }?.let { go(it.id) } },
            onCloseTab = { tabs.firstOrNull { it.id == selected }?.takeIf(controller::canClose)?.let { controller.close(it.id) } },
            onReopen = controller::reopenClosed,
            onDismiss = onClose,
            onMode = { onList(true) },
          )
        }
        .focusRequester(focus)
        .focusable(),
      contentAlignment = Alignment.TopCenter,
    ) {
      val panelWidth = (maxWidth - 64.dp).coerceIn(240.dp, PanelMaxWidth)
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = Modifier
          .padding(top = 48.dp, bottom = 48.dp)
          .width(panelWidth)
          .heightIn(max = maxHeight - 96.dp)
          // Clicks on the panel itself don't reach the scrim's close.
          .clickable(interactionSource = null, indication = null, onClick = {})
          .testTag(TAB_SWITCHER_TAG),
      ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
          if (list) {
            val viewed = clearViewed?.status()?.count ?: 0
            QueueListHeader(
              count = countedItems(tabs),
              left = keptLeft(tabs, seenSince),
              viewed = viewed,
              query = query,
              onQuery = { query = it },
              onClearViewed = { clearViewed?.invoke() },
              menuOpen = menuOpen,
              onMenu = { open ->
                menuOpen = open
                // The menu's popup had the keys: the releases it took (R's, a held key's) never reached the list.
                if (!open) {
                  presses.forget()
                  focusRoot()
                }
              },
              bulk = listOf(
                SwitcherBulkClose("Close everything after current", controller.tabsToTheRight()),
                SwitcherBulkClose("Close duplicates", controller.duplicateTabs()),
                SwitcherBulkClose("Close favourited", controller.favouritedTabs { posts[it]?.favourited == true }),
                SwitcherBulkClose("Close all but current", controller.otherTabs()),
              ),
              onBulk = { ids -> controller.closeAll(ids) },
              modeToggle = { ModeToggle(list, onList) },
            )
            QueueListKeyHints(Modifier.padding(top = 8.dp, bottom = 4.dp))
            SwitcherQueueList(
              rows = rows,
              tabs = tabs,
              closed = closedRows(closed, gOnly) { posts[it.id] },
              active = active,
              selected = selected,
              seen = seenSince,
              rating = { tab -> postRating(tab, known(tab)) },
              gOnly = gOnly,
              canClose = controller::canClose,
              list = queueList,
              onGo = ::go,
              onClose = ::closeRow,
              onReopen = { controller.reopen(it) },
              finding = query != null,
            )
            return@Column
          }
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tabs", style = MaterialTheme.typography.titleLarge)
            Text(
              "${tabs.size} open",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
            ModeToggle(list, onList)
          }
          SwitcherKeyHints(Modifier.padding(top = 8.dp, bottom = 4.dp))
          val gap = 12.dp
          BoxWithConstraints {
            // Cards at least [CardMinWidth] wide, as many as fit; the ring's reach is the grid's padding.
            val room = maxWidth - CardRingReach * 2
            val fit = ((room + gap) / (CardMinWidth + gap)).toInt().coerceIn(1, MaxColumns)
            SideEffect { columns[0] = fit }
            LazyVerticalGrid(
              columns = GridCells.Fixed(fit),
              state = grid,
              contentPadding = PaddingValues(start = CardRingReach, end = CardRingReach, top = CardRingReach, bottom = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(gap),
              verticalArrangement = Arrangement.spacedBy(gap),
            ) {
              itemsIndexed(tabs, key = { _, tab -> tab.id.value }) { index, tab ->
                val post = (tab.top as? Route.Post)?.let { posts[it.id] }
                TabCard(
                  tab,
                  number = index + 1,
                  twin = twins.getOrElse(index) { "" },
                  current = tab.id == active,
                  selected = tab.id == selected,
                  favourited = post?.favourited == true,
                  gOnly = gOnly,
                  hidePicture = gOnly && isAdult(tab, post),
                  closable = controller.canClose(tab),
                  onGo = { go(tab.id) },
                  onCloseTab = { controller.close(tab.id) },
                )
              }
            }
          }
        }
      }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
  }
}

/** What the keys do in the switcher, as the sidebar lists its own. */
@Composable
private fun SwitcherKeyHints(modifier: Modifier = Modifier) {
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    KeyHint("W A S D", "move")
    KeyHint("${Shortcuts.OpenHere.label} ${Shortcuts.OpenInTab.label}", "go")
    KeyHint(Shortcuts.CloseTab.label, "close tab")
    KeyHint(Shortcuts.ReopenTab.label, "reopen")
    KeyHint(Shortcuts.SwitcherMode.label, "queue")
    KeyHint("${Shortcuts.Back.label} ${Shortcuts.TabSwitcher.label}", "close")
  }
}

/** Cards or Queue (#502): the switcher's two views, which Q changes too. */
@Composable
private fun ModeToggle(list: Boolean, onList: (Boolean) -> Unit) {
  Row(
    Modifier.clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
  ) {
    ModeChoice("Cards", chosen = !list) { onList(false) }
    ModeChoice("Queue", chosen = list) { onList(true) }
  }
}

@Composable
private fun ModeChoice(text: String, chosen: Boolean, onClick: () -> Unit) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
      .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
      .clickable(onClick = onClick)
      .semantics {
        role = Role.Tab
        selected = chosen
      }
      .padding(horizontal = 12.dp, vertical = 6.dp)
      .testTag(modeTag(text)),
  )
}

internal fun modeTag(text: String) = "switcher-mode-$text"

/** Scrolls the least distance that shows the row at [index] whole, or to it when it's out of view. */
private suspend fun LazyListState.bringIntoView(index: Int) {
  val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
  if (item == null) {
    scrollToItem(index)
    return
  }
  val top = item.offset - layoutInfo.beforeContentPadding
  val bottom = item.offset + item.size + layoutInfo.beforeContentPadding
  when {
    top < layoutInfo.viewportStartOffset -> animateScrollBy((top - layoutInfo.viewportStartOffset).toFloat())
    bottom > layoutInfo.viewportEndOffset -> animateScrollBy((bottom - layoutInfo.viewportEndOffset).toFloat())
  }
}

/**
 * One tab's card: its picture (a post's or book's preview, a grid's 2×2 from where it was last scrolled to, else its
 * kind's icon), its number, title and kind, the ♥ of a favourited post and "Current" on the active tab. The selection
 * has the grid cursor's ring; hover only lightens the card. A click goes to the tab; a middle click or the ✕ (on the
 * hovered or selected card, not a feed's) closes it.
 */
@Composable
private fun TabCard(
  tab: TabState,
  number: Int,
  twin: String,
  current: Boolean,
  selected: Boolean,
  favourited: Boolean,
  gOnly: Boolean,
  hidePicture: Boolean,
  closable: Boolean,
  onGo: () -> Unit,
  onCloseTab: () -> Unit,
) {
  val hover = remember { MutableInteractionSource() }
  val hovered by hover.collectIsHoveredAsState()
  val colors = MaterialTheme.colorScheme
  val kind = switcherKindLabel(tab)
  Surface(
    shape = CardShape,
    color = if (hovered) colors.surfaceContainerHighest else colors.surfaceContainer,
    modifier = Modifier
      .cursorRing(selected, CardShape)
      .hoverable(hover)
      .clip(CardShape)
      .openOnClick(onOpen = onGo, onOpenInNewTab = { if (closable) onCloseTab() }, interactionSource = hover)
      .semantics {
        role = Role.Tab
        this.selected = selected
        contentDescription = "Tab $number: ${tab.title}$twin, $kind"
        if (current) stateDescription = CURRENT_TAB
      }
      .testTag(switcherCardTag(number - 1)),
  ) {
    Column(Modifier.padding(6.dp)) {
      Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant)) {
        CardPicture(tab, gOnly, hidePicture)
        // "Current" beside the number, so the active card keeps the ✕'s corner.
        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          NumberBadge(number)
          if (current) CurrentChip()
        }
        if (closable && (hovered || selected)) CloseButton(onCloseTab, Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (favourited) FavouritedMark(Modifier.align(Alignment.BottomEnd).padding(6.dp))
      }
      Row(Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // The kind's icon: the picture is just above.
        TabIcon(tab.top, size = 16.dp, decorative = true, picture = false)
        Spacer(Modifier.width(6.dp))
        TwinTitle(tab.title, twin, tab.untitled, maxLines = 2, Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text(kind, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
      }
    }
  }
}

/**
 * The card's picture, from the image cache only; nothing while [hide] (an R15 or R18 tab under G only) or when the tab
 * holds none (its icon shows). Under [gOnly] each picture is checked too ([switcherPictures]).
 */
@Composable
private fun CardPicture(tab: TabState, gOnly: Boolean, hide: Boolean) {
  val pictures = if (hide) emptyList() else switcherPictures(tab, gOnly)
  when {
    pictures.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      TabIcon(tab.top, size = 32.dp, decorative = true, picture = false)
    }
    pictures.size == 1 -> PreviewImage(pictures.single(), Modifier.fillMaxSize())
    else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      pictures.chunked(2).forEach { row ->
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          row.forEach { PreviewImage(it, Modifier.weight(1f).fillMaxSize()) }
          if (row.size == 1) Spacer(Modifier.weight(1f))
        }
      }
    }
  }
}

/**
 * What a card shows, all cache-only: a post's or a book's preview (by its route, else what its screen last held), a
 * grid's mosaic (up to four, from where it was last scrolled to, as saved for a tab not shown since launch) or what its
 * screen last held; nothing for a tab that has never loaded.
 *
 * With [gOnly] on, a grid shows only its mosaic's G thumbnails (one saved before G only, or with unrated thumbnails,
 * may hold others), and never what a screen held, which carries no rating. A post's or book's own picture is judged by
 * the caller, by its rating ([isAdult]).
 */
internal fun switcherPictures(tab: TabState, gOnly: Boolean): List<PostImage> {
  val held = { if (gOnly) emptyList() else tab.topContents()?.preview.orEmpty() }
  return when (val top = tab.top) {
    is Route.Post -> listOfNotNull(top.md5?.let { PostImage(top.id, it, Variant.Preview, null) } ?: held().firstOrNull())
    is Route.Book -> listOfNotNull(top.cover?.let { PostImage(top.id, it, Variant.Preview, null) })
    is Route.Grid -> tab.mosaic?.thumbs?.takeIf { it.isNotEmpty() }?.filter { !gOnly || it.isG }?.map { it.image }
      ?: held().take(4)
    null -> emptyList()
    else -> held().take(4)
  }
}

/** A card's kind, as its row on the queue page names it. */
internal fun switcherKindLabel(tab: TabState): String = when (tab.kind) {
  QueueKind.Source -> if (tab.top?.isBooks == true) BOOKS_TITLE else "Grid"
  QueueKind.Feed -> "Feed"
  QueueKind.KeptPost -> "Post"
  QueueKind.KeptBook -> "Book"
  QueueKind.Other -> "Item"
}

@Composable
private fun NumberBadge(number: Int, modifier: Modifier = Modifier) {
  Surface(shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, modifier = modifier) {
    Text("$number", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp))
  }
}

@Composable
internal fun CurrentChip(modifier: Modifier = Modifier) {
  Surface(shape = RoundedCornerShape(6.dp), color = Amber, contentColor = Color(0xFF2A1A00), modifier = modifier) {
    Text(CURRENT_TAB, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
  }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier) {
  Box(
    modifier
      .size(22.dp)
      .clip(CircleShape)
      .background(Color.Black.copy(alpha = 0.6f))
      .openOnClick(onClick, onClick)
      .semantics { role = Role.Button },
    contentAlignment = Alignment.Center,
  ) {
    Icon(close, contentDescription = "Close tab", tint = Color.White, modifier = Modifier.size(14.dp))
  }
}

/** The grid cursor's ring ([FocusRingWidth] of amber, [FocusRingGap] outside) while [shown]. */
internal fun Modifier.cursorRing(shown: Boolean, shape: RoundedCornerShape): Modifier = drawWithContent {
  drawContent()
  if (!shown) return@drawWithContent
  val ring = FocusRingWidth.toPx()
  val out = FocusRingGap.toPx() + ring / 2
  val outline = shape.createOutline(Size(size.width + 2 * out, size.height + 2 * out), layoutDirection, this)
  translate(-out, -out) { drawOutline(outline, Amber, style = Stroke(ring)) }
}

/** Whether the card at [index] is wholly in view, its ring's reach (the top content padding) included. */
private fun LazyGridState.fullyShows(index: Int): Boolean {
  val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
  val reach = layoutInfo.beforeContentPadding
  return item.offset.y - reach >= layoutInfo.viewportStartOffset &&
    item.offset.y + item.size.height + reach <= layoutInfo.viewportEndOffset
}

/** The first card wholly in view, else the first in view at all. */
private fun LazyGridState.firstFullyShown(): Int? =
  layoutInfo.visibleItemsInfo.map { it.index }.let { shown -> shown.firstOrNull(::fullyShows) ?: shown.firstOrNull() }

/** Scrolls the least distance that shows the card at [index] whole. */
private suspend fun LazyGridState.bringIntoView(index: Int) {
  val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
  if (item == null) {
    scrollToItem(index)
    return
  }
  val reach = layoutInfo.beforeContentPadding
  val top = item.offset.y - reach
  val bottom = item.offset.y + item.size.height + reach
  when {
    top < layoutInfo.viewportStartOffset -> animateScrollBy((top - layoutInfo.viewportStartOffset).toFloat())
    bottom > layoutInfo.viewportEndOffset -> animateScrollBy((bottom - layoutInfo.viewportEndOffset).toFloat())
  }
}

/**
 * Where a [move] from the card at [index] lands among [count] cards in rows of [columns], or null at an edge: the
 * selection stops there, as the grid cursor does, and a short last row has nothing below the cards past its end.
 */
internal fun switcherStep(index: Int, count: Int, columns: Int, move: GridMove): Int? {
  val next = when (move) {
    GridMove.Left -> if (index % columns == 0) return null else index - 1
    GridMove.Right -> if (index % columns == columns - 1) return null else index + 1
    GridMove.Up -> index - columns
    GridMove.Down -> index + columns
  }
  return next.takeIf { it in 0 until count }
}

/**
 * The selection once the tabs went from [before] to [after]: [selected] while it's still open, else the tab that took
 * its place (the next, else the previous, as closing the active tab does), else the first.
 */
internal fun selectionAfter(before: List<TabId>, after: List<TabId>, selected: TabId): TabId {
  if (selected in after || after.isEmpty()) return selected
  val at = before.indexOf(selected)
  val next = before.drop(at + 1).firstOrNull { it in after }
  val previous = before.take(at.coerceAtLeast(0)).lastOrNull { it in after }
  return next ?: previous ?: after.first()
}

/**
 * Tells the switcher's key presses from their repeats and releases: desktop key events carry no repeat flag. A key's
 * first down is a fresh press; more downs before its release are repeats. The key that opened the switcher
 * ([heldAtOpen]) is down already, so its repeats and release are not fresh.
 */
internal class SwitcherPresses(heldAtOpen: Key? = null) {
  private val down = mutableSetOf<Key>().apply { heldAtOpen?.let(::add) }
  private val fresh = mutableSetOf<Key>()

  enum class Press { Fresh, Repeat, Release, StaleRelease, Other }

  /** [key] isn't down any more: its release went elsewhere (the shell heard the opening C's, on a quick tap). */
  fun letGo(key: Key) {
    down.remove(key)
    fresh.remove(key)
  }

  /** No key is down any more: the window lost the focus, and their releases go to another app. */
  fun forget() {
    down.clear()
    fresh.clear()
  }

  fun take(event: KeyEvent): Press = when (event.type) {
    KeyEventType.KeyDown -> if (down.add(event.key)) Press.Fresh.also { fresh += event.key } else Press.Repeat
    KeyEventType.KeyUp -> {
      down.remove(event.key)
      if (fresh.remove(event.key)) Press.Release else Press.StaleRelease
    }
    else -> Press.Other
  }
}

/**
 * The switcher's keys, before anything in it sees them; every key is taken, so none reaches the screen under it, but
 * Cmd/Ctrl and Alt chords, which go on to the window's shortcuts (Cmd/Ctrl-W, Ctrl-Tab). W/A/S/D and the arrows move on each press and repeat; X closes the selected tab once per press, Z reopens on each
 * press and repeat (as outside it); Space and E go, Esc and C close, each on a fresh press's release.
 */
internal fun switcherKey(
  event: KeyEvent,
  presses: SwitcherPresses,
  onMove: (GridMove) -> Unit,
  onGo: () -> Unit,
  onCloseTab: () -> Unit,
  onReopen: () -> Unit,
  onDismiss: () -> Unit,
  onMode: () -> Unit = {},
): Boolean {
  val press = presses.take(event)
  if (event.isCommand || event.isAltPressed) return false
  val key = event.key
  val plain = !event.isShiftPressed
  val down = press == SwitcherPresses.Press.Fresh || press == SwitcherPresses.Press.Repeat
  val move = gridMoveFor(key)
  when {
    move != null -> if (down && plain) onMove(move)
    key == Shortcuts.CloseTab.key -> if (press == SwitcherPresses.Press.Fresh && plain) onCloseTab()
    key == Shortcuts.ReopenTab.key -> if (down && plain) onReopen()
    key == Shortcuts.SwitcherMode.key -> if (press == SwitcherPresses.Press.Fresh && plain) onMode()
    key == Shortcuts.OpenHere.key || key == Shortcuts.OpenInTab.key ->
      if (press == SwitcherPresses.Press.Release && plain) onGo()
    key == Shortcuts.Back.key -> if (press == SwitcherPresses.Press.Release) onDismiss()
    key == Shortcuts.TabSwitcher.key -> if (press == SwitcherPresses.Press.Release && plain) onDismiss()
  }
  return true
}

/** What the active tab's card says, on its chip and to a screen reader. */
internal const val CURRENT_TAB = "Current"

internal const val TAB_SWITCHER_TAG = "tab-switcher"

internal fun switcherCardTag(index: Int) = "tab-switcher-card-$index"

private val CardShape = RoundedCornerShape(16.dp)
private val CardMinWidth: Dp = 200.dp
private val CardRingReach: Dp = FocusRingGap + FocusRingWidth
private val PanelMaxWidth: Dp = 1080.dp
private const val MaxColumns = 6
