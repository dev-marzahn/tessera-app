package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import dev.marzahn.sankaku.ui.components.ButtonHint
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.check
import com.google.fonts.icons.close
import com.google.fonts.icons.more_vert
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.style.TextAlign
import dev.marzahn.sankaku.features.grid.GridMove
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.grid.gridMoveFor
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.TonalButton
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.openOnClick

/**
 * The switcher's queue list (#502, desktop): the phone queue page's rows ([queueRows]) as a list, each block's kept items
 * indented under its head, a seen kept item dimmed, and on each head with kept items how many of them are left
 * ([blockProgress]). The selection is on an item's row or a closed source's row ([selectableIds]); find (`/`) filters
 * the rows by title and tag. Recently closed follows, for the mouse: Z reopens from the keys.
 */
@Composable
internal fun SwitcherQueueList(
  rows: List<QueueRow>,
  tabs: List<TabState>,
  closed: ClosedRows,
  active: TabId,
  selected: TabId,
  seen: (TabState) -> Boolean,
  rating: (TabState) -> Rating?,
  gOnly: Boolean,
  canClose: (TabState) -> Boolean,
  list: LazyListState,
  onGo: (TabId) -> Unit,
  onClose: (TabId) -> Unit,
  onReopen: (ClosedTab) -> Unit,
  finding: Boolean,
) {
  val twins = remember(tabs) { tabs.map { it.id }.zip(twinSuffixes(tabs.map { it.title })).toMap() }
  val progress = rows.associate { row ->
    val id = when (row) {
      // A head's: find lists a kept item without its head, unindented, and it has none.
      is QueueRow.Item -> row.tab.id.takeIf { !row.indented && !row.tab.kind.isKept }
      is QueueRow.ClosedBlock -> blockIds(tabs, row.head).firstOrNull()
      else -> null
    }
    row to id?.let { blockProgress(tabs, it, seen) }
  }
  LazyColumn(
    state = list,
    contentPadding = PaddingValues(start = RingReach, end = RingReach, top = RingReach, bottom = 20.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier = Modifier.testTag(QUEUE_LIST_TAG),
  ) {
    if (rows.isEmpty() && finding) {
      item { Text("Nothing in the queue matches", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
    }
    items(rows, key = ::rowKey) { row ->
      when (row) {
        is QueueRow.Item -> ItemRow(
          row,
          twin = twins[row.tab.id].orEmpty(),
          current = row.tab.id == active,
          selected = row.tab.id == selected,
          seen = seen(row.tab),
          rating = rating(row.tab),
          gOnly = gOnly,
          progress = progress[row],
          closable = canClose(row.tab),
          onGo = { onGo(row.tab.id) },
          onClose = { onClose(row.tab.id) },
        )
        is QueueRow.ClosedBlock -> ClosedRow(row, selected = row.head == selected, progress = progress[row]) {
          onGo(row.head)
        }
        is QueueRow.HiddenByGOnly -> Note("Hidden by G only · ${row.count}")
        is QueueRow.Inline -> Unit
      }
    }
    if (!finding && (closed.rows.isNotEmpty() || closed.hidden > 0)) {
      item(key = "recently closed") {
        Text(
          "Recently closed",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
      }
      items(closed.rows, key = { "closed ${it.closed.key}" }) { row ->
        RecentlyClosed(row) { onReopen(row.closed) }
      }
      if (closed.hidden > 0) item(key = "closed hidden") { Note("Hidden by G only · ${closed.hidden}") }
    }
  }
}

private fun rowKey(row: QueueRow): Any = when (row) {
  is QueueRow.Item -> row.tab.id.value
  is QueueRow.ClosedBlock -> "closed block ${row.head.value}"
  is QueueRow.HiddenByGOnly -> "hidden"
  is QueueRow.Inline -> "inline ${row.closed.key}"
}

/** The ids the queue list's selection moves over, in order: each item's, and each closed source's row's (its head's). */
internal fun selectableIds(rows: List<QueueRow>): List<TabId> = rows.mapNotNull { row ->
  when (row) {
    is QueueRow.Item -> row.tab.id
    is QueueRow.ClosedBlock -> row.head
    else -> null
  }
}

/** One item's row: number, picture, title, its second line ([queueSubtitle]), and on a block's head what's left of it. */
@Composable
private fun ItemRow(
  row: QueueRow.Item,
  twin: String,
  current: Boolean,
  selected: Boolean,
  seen: Boolean,
  rating: Rating?,
  gOnly: Boolean,
  progress: BlockProgress?,
  closable: Boolean,
  onGo: () -> Unit,
  onClose: () -> Unit,
) {
  val tab = row.tab
  // "Current" is the row's chip, and its state to a screen reader: not in the second line too.
  val subtitle = queueSubtitle(tab, current = false, seen, rating, row.from)
  val number = if (tab.kind == QueueKind.Feed) "" else "${row.position + 1}"
  RowFrame(
    selected = selected,
    indented = row.indented,
    closable = closable,
    onGo = onGo,
    onClose = onClose,
    description = listOfNotNull(
      if (number.isEmpty()) null else "Item $number",
      "${tab.title}$twin",
      subtitle,
      progress?.let { leftOf(it) },
    ).joinToString(", "),
    current = current,
    modifier = Modifier.alpha(if (seen) SEEN_ALPHA else 1f).testTag(queueListRowTag(tab.id)),
  ) {
    Number(number)
    if (row.indented) Spacer(Modifier.width(Indent))
    // With G only on, an R15 or R18 item (or one whose rating isn't known) shows its kind's icon, as its card does.
    Thumbnail(tab, hide = gOnly && row.adult, gOnly = gOnly)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      TwinTitle(tab.title, twin, tab.untitled, maxLines = 1)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (current) CurrentMark()
    progress?.let { Progress(it) }
  }
}

/** "from artist_x (closed)" over a closed source's kept posts, with what's left of them; going there shows the first. */
@Composable
private fun ClosedRow(row: QueueRow.ClosedBlock, selected: Boolean, progress: BlockProgress?, onGo: () -> Unit) {
  RowFrame(
    selected = selected,
    indented = false,
    closable = false,
    onGo = onGo,
    onClose = {},
    description = listOfNotNull(row.title, "${row.count} kept stay", progress?.let { leftOf(it) }).joinToString(", "),
    current = false,
    modifier = Modifier.testTag(queueListRowTag(row.head)),
  ) {
    Number("")
    Spacer(Modifier.width(ThumbSize + 10.dp))
    Column(Modifier.weight(1f)) {
      Text(row.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        if (row.count == 1) "1 kept post stays" else "${row.count} kept posts stay",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    progress?.let { Progress(it) }
  }
}

/** A row's frame: the selection's amber ring, hover's tint, a click to go, a middle click or the hovered ✕ to close. */
@Composable
private fun RowFrame(
  selected: Boolean,
  indented: Boolean,
  closable: Boolean,
  onGo: () -> Unit,
  onClose: () -> Unit,
  description: String,
  current: Boolean,
  modifier: Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val hover = remember { MutableInteractionSource() }
  val hovered by hover.collectIsHoveredAsState()
  val colors = MaterialTheme.colorScheme
  Surface(
    shape = RowShape,
    color = if (hovered) colors.surfaceContainerHighest else Color.Transparent,
    modifier = Modifier
      .fillMaxWidth()
      .cursorRing(selected, RowShape)
      .hoverable(hover)
      .clip(RowShape)
      .openOnClick(onOpen = onGo, onOpenInNewTab = { if (closable) onClose() }, interactionSource = hover)
      .semantics {
        role = Role.Tab
        this.selected = selected
        contentDescription = description
        if (current) stateDescription = CURRENT_TAB
      }
      .then(modifier),
  ) {
    Box {
      if (indented) BlockLine(Modifier.padding(start = NumberWidth + Indent / 2).fillMaxHeight())
      Row(Modifier.heightIn(min = RowHeight).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
          if (closable && (hovered || selected)) {
            ButtonHint("Close (${Shortcuts.CloseTab.label})") {
              IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(close, contentDescription = "Close", modifier = Modifier.size(16.dp))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BlockLine(modifier: Modifier) {
  Box(modifier.width(2.dp).height(RowHeight).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun Number(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    modifier = Modifier.width(NumberWidth).padding(end = 8.dp),
    textAlign = TextAlign.End,
  )
}

/** A row's picture, as its switcher card has it ([switcherPictures]), small; the kind's icon when there's none. */
@Composable
private fun Thumbnail(tab: TabState, hide: Boolean, gOnly: Boolean) {
  val pictures = if (hide) emptyList() else switcherPictures(tab, gOnly)
  Box(
    Modifier.size(ThumbSize).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    when {
      pictures.isEmpty() -> TabIcon(tab.top, size = 18.dp, decorative = true, picture = false)
      pictures.size == 1 -> PreviewImage(pictures.single(), Modifier.fillMaxSize())
      else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        pictures.chunked(2).forEach { pair ->
          Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            pair.forEach { PreviewImage(it, Modifier.weight(1f).fillMaxSize()) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
          }
        }
      }
    }
  }
}

@Composable
private fun CurrentMark() {
  CurrentChip()
  Spacer(Modifier.width(8.dp))
}

/** What's left of a block: a bar of what's been seen and "2 of 3 left" (or "all seen"). */
@Composable
private fun Progress(progress: BlockProgress) {
  val done = progress.kept - progress.left
  LinearProgressIndicator(
    progress = { if (progress.kept == 0) 0f else done.toFloat() / progress.kept },
    modifier = Modifier.width(56.dp),
    drawStopIndicator = {},
  )
  Text(
    leftOf(progress),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.End,
    modifier = Modifier.width(84.dp),
  )
}

/** "2 of 3 left", or "all seen". */
internal fun leftOf(progress: BlockProgress): String =
  if (progress.left == 0) "all seen" else "${progress.left} of ${progress.kept} left"

@Composable
private fun RecentlyClosed(row: ClosedRow, onReopen: () -> Unit) {
  val hover = remember { MutableInteractionSource() }
  val hovered by hover.collectIsHoveredAsState()
  Surface(
    shape = RowShape,
    color = if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
    modifier = Modifier
      .fillMaxWidth()
      .hoverable(hover)
      .clip(RowShape)
      .openOnClick(onOpen = onReopen, onOpenInNewTab = onReopen, interactionSource = hover)
      .semantics {
        role = Role.Button
        contentDescription = "${row.closed.title}, Reopen"
      },
  ) {
    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Spacer(Modifier.width(NumberWidth))
      Box(Modifier.size(ThumbSize), contentAlignment = Alignment.Center) {
        TabIcon(row.shown.top, size = 18.dp, decorative = true, picture = false)
      }
      Spacer(Modifier.width(10.dp))
      Text(row.closed.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Text("${Shortcuts.ReopenTab.label} reopens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun Note(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = NumberWidth + 6.dp, top = 6.dp, bottom = 6.dp),
  )
}

/**
 * The queue list's header: "Queue", "21 items · 14 kept left", Clear viewed while there's something to clear, and ⋮
 * ([menuOpen], also R) with the phone page's bulk closes. Find (`/`) puts a field in the title's place.
 */
@Composable
internal fun QueueListHeader(
  count: Int,
  left: Int,
  viewed: Int,
  query: String?,
  onQuery: (String) -> Unit,
  onClearViewed: () -> Unit,
  menuOpen: Boolean,
  onMenu: (Boolean) -> Unit,
  bulk: List<SwitcherBulkClose>,
  onBulk: (List<TabId>) -> Unit,
  modeToggle: @Composable () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    if (query == null) {
      Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
      Text(
        "${itemsText(count)} · $left kept left",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f).testTag(QUEUE_LIST_COUNT_TAG),
      )
    } else {
      FindField(query, onQuery, Modifier.weight(1f))
    }
    if (viewed > 0) TonalButton("Clear viewed · $viewed", check, onClearViewed)
    modeToggle()
    Box {
      ButtonHint("More (${Shortcuts.Menu.label})") {
        IconButton(onClick = { onMenu(true) }) { Icon(more_vert, contentDescription = "More (${Shortcuts.Menu.label})") }
      }
      GroupedMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
        MenuGroup(0, 1) {
          bulk.forEach { close ->
            MenuAction(
              text = { Text("${close.label} (${close.ids.size})") },
              enabled = close.ids.isNotEmpty(),
              onClick = {
                onMenu(false)
                onBulk(close.ids)
              },
            )
          }
        }
      }
    }
  }
}

private fun itemsText(count: Int) = if (count == 1) "1 item" else "$count items"

/** One of ⋮'s bulk closes: its menu [label] and the items it would close now. */
internal class SwitcherBulkClose(val label: String, val ids: List<TabId>)

@Composable
private fun FindField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  TextField(
    value = query,
    onValueChange = onQuery,
    singleLine = true,
    placeholder = { Text("Find by title or tag") },
    colors = TextFieldDefaults.colors(
      focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      focusedIndicatorColor = Color.Transparent,
      unfocusedIndicatorColor = Color.Transparent,
    ),
    shape = RoundedCornerShape(28.dp),
    modifier = modifier.focusRequester(focus).testTag(QUEUE_LIST_FIND_TAG),
  )
}

/** The queue list's keys, as the sidebar and the cards list theirs. */
@Composable
internal fun QueueListKeyHints(modifier: Modifier = Modifier) {
  FlowRow(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    KeyHint("W S", "move")
    KeyHint("${Shortcuts.OpenHere.label} ${Shortcuts.OpenInTab.label}", "go")
    KeyHint(Shortcuts.CloseTab.label, "close")
    KeyHint(Shortcuts.CloseBlock.label, "close block")
    KeyHint(Shortcuts.Search.label, "find")
    KeyHint(Shortcuts.Menu.label, "more")
    KeyHint(Shortcuts.SwitcherMode.label, "cards")
    KeyHint(Shortcuts.ReopenTab.label, "reopen")
    KeyHint("${Shortcuts.Back.label} ${Shortcuts.TabSwitcher.label}", "close")
  }
}

/** What a key does in the queue list ([queueListKey]). */
internal sealed interface QueueListKey {
  data class Move(val down: Boolean) : QueueListKey
  data object Go : QueueListKey
  data object Close : QueueListKey
  data object CloseBlock : QueueListKey
  data object Find : QueueListKey
  data object LeaveFind : QueueListKey
  data object Menu : QueueListKey
  data object Mode : QueueListKey
  data object Reopen : QueueListKey
  data object Dismiss : QueueListKey
}

/**
 * The queue list's keys, before anything in it sees them (#502). As the cards': W/S (and ↑/↓) move on each press and
 * repeat, X closes once per press, Z reopens on each press and repeat, Space and E go and Esc and C close on a fresh
 * press's release. Its own: Shift+X closes the selected row's block once per press, `/` opens find, R opens ⋮, and Q
 * shows the cards, each on a fresh press. While [finding], the field has the keys but Esc (which leaves find), ↑/↓
 * (which move) and Enter (which goes). Returns what to do (null for nothing) and whether the key is taken; Cmd/Ctrl and
 * Alt chords go on to the window's shortcuts.
 */
internal fun queueListKey(event: KeyEvent, presses: SwitcherPresses, finding: Boolean): Pair<QueueListKey?, Boolean> {
  val press = presses.take(event)
  if (event.isCommand || event.isAltPressed) return null to false
  val key = event.key
  val shift = event.isShiftPressed
  val fresh = press == SwitcherPresses.Press.Fresh
  val down = fresh || press == SwitcherPresses.Press.Repeat
  val release = press == SwitcherPresses.Press.Release
  if (finding) {
    return when (key) {
      Key.Escape -> (if (release) QueueListKey.LeaveFind else null) to true
      Key.DirectionUp -> (if (down) QueueListKey.Move(down = false) else null) to true
      Key.DirectionDown -> (if (down) QueueListKey.Move(down = true) else null) to true
      Key.Enter -> (if (release) QueueListKey.Go else null) to true
      else -> null to false
    }
  }
  val move = gridMoveFor(key)
  val action = when {
    move == GridMove.Up -> QueueListKey.Move(down = false).takeIf { down && !shift }
    move == GridMove.Down -> QueueListKey.Move(down = true).takeIf { down && !shift }
    move != null -> null
    key == Shortcuts.CloseTab.key -> when {
      !fresh -> null
      shift -> QueueListKey.CloseBlock
      else -> QueueListKey.Close
    }
    shift -> null
    key == Shortcuts.ReopenTab.key -> QueueListKey.Reopen.takeIf { down }
    key == Shortcuts.Search.key -> QueueListKey.Find.takeIf { fresh }
    key == Shortcuts.Menu.key -> QueueListKey.Menu.takeIf { fresh }
    key == Shortcuts.SwitcherMode.key -> QueueListKey.Mode.takeIf { fresh }
    key == Shortcuts.OpenHere.key || key == Shortcuts.OpenInTab.key -> QueueListKey.Go.takeIf { release }
    key == Shortcuts.Back.key || key == Shortcuts.TabSwitcher.key -> QueueListKey.Dismiss.takeIf { release }
    else -> null
  }
  return action to true
}

/** The id [down] (or up) from [selected] among [ids], or null at an end: the selection stops there. */
internal fun queueListStep(ids: List<TabId>, selected: TabId, down: Boolean): TabId? {
  val at = ids.indexOf(selected)
  if (at < 0) return ids.firstOrNull()
  return ids.getOrNull(if (down) at + 1 else at - 1)
}

internal const val QUEUE_LIST_TAG = "queue-list"
internal const val QUEUE_LIST_COUNT_TAG = "queue-list-count"
internal const val QUEUE_LIST_FIND_TAG = "queue-list-find"

internal fun queueListRowTag(id: TabId) = "queue-list-row-${id.value}"

private val RowShape = RoundedCornerShape(12.dp)
private val RowHeight = 52.dp
private val ThumbSize = 40.dp
private val NumberWidth = 32.dp
private val Indent = 28.dp
private val RingReach = 6.dp