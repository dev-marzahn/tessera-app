package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import dev.marzahn.sankaku.ui.components.CLOSE_LABEL
import dev.marzahn.sankaku.ui.components.CloseCorner
import dev.marzahn.sankaku.ui.components.CountPill
import dev.marzahn.sankaku.ui.components.HideOnScroll
import dev.marzahn.sankaku.ui.components.LiveAnnouncement
import dev.marzahn.sankaku.ui.components.PillRow
import dev.marzahn.sankaku.ui.components.TipAnchor
import dev.marzahn.sankaku.ui.components.UNDO_CLOSE_LABEL
import dev.marzahn.sankaku.ui.components.UndoChip
import dev.marzahn.sankaku.ui.components.hidesOnScroll
import kotlin.math.roundToInt
import org.koin.compose.koinInject

/**
 * Touch: the queue's bar since the phone's tab bar was retired (#178, option A′ of `.scratch/bar-less/`), the same
 * on a grid and on a post: the count pill "▤ 2 / 9" ([CountPill]) centred, which opens the queue page ([onOpenQueue]);
 * "↶ Undo" growing out of its left side while a close can be undone ([ClosedTabUndo]); and with "Show queue buttons"
 * ([AccessibilitySettings]), ✕ in the left corner and ‹ › in the pill. With one item it still reads "1 / 1", so the
 * queue page stays one tap away.
 *
 * TalkBack gets "Previous queue item", "Next queue item", "Close" and, while it can be undone, "Undo close" as the
 * pill's custom actions, with or without the buttons, and hears "Closed “title”. Undo available" as a close happens.
 *
 * On a grid it floats on the bottom row's line ([BottomRowLayout]), [bottom] being the room under it, and with
 * [hideOnScroll] the pill slides out on scrolling down, the rest with it; ↑ Top is the grid's, in the right corner. On
 * a post the viewer places it over the picture, above the sheet.
 */
@Composable
fun QueueBar(
  onOpenQueue: () -> Unit,
  modifier: Modifier = Modifier,
  hideOnScroll: HideOnScroll? = null,
  bottom: Dp = 0.dp,
  controller: TabsController = koinInject(),
  undo: ClosedTabUndo = koinInject(),
  settings: AccessibilitySettings = koinInject(),
  tips: Tips = koinInject(),
  upNext: UpNextState = koinInject(),
) {
  val tabs by controller.tabs.collectAsStateWithLifecycle()
  val active by controller.active.collectAsStateWithLifecycle()
  val closed by controller.closed.collectAsStateWithLifecycle()
  val faded by controller.faded.collectAsStateWithLifecycle()
  val gOnly by upNext.gOnly.collectAsStateWithLifecycle()
  val pending by undo.pending.collectAsStateWithLifecycle()
  val buttons by settings.showQueueButtons.collectAsStateWithLifecycle()
  val index = tabs.indexOfFirst { it.id == active }.coerceAtLeast(0)
  val previous = controller.neighbour(forward = false)
  val next = controller.neighbour(forward = true)
  // Past the last item › brings the Up next card (#216, #230, #327), else End of the queue; on a card ‹ goes
  // back to the item it stands past, and on Up next › goes on to End of the queue.
  val cardUp = upNext.card != null
  val ahead = upNext.ahead(gOnly, faded)
  val goPrevious = { if (cardUp) upNext.dismiss() else controller.previous() }
  val goNext = { upNext.forward() }
  val close = { undo.close(active) }
  val current = tabs.getOrNull(index)
  // A feed item (#229) isn't counted, so the pill names it, and it can't be closed: no ✕ and no Close action.
  val feed = current?.takeIf { it.kind == QueueKind.Feed }?.title
  val closable = current == null || controller.canClose(current)
  // "Close artist_x · 30 kept stay" for a source whose kept posts stay (#184); the tabs and their tops are snapshot state.
  val closeName = current?.let { queueCloseLabel(it, controller.keptPostsOf(it)) } ?: CLOSE_LABEL
  val actions = listOfNotNull(
    if (cardUp) CustomAccessibilityAction(PREVIOUS_ITEM) { upNext.dismiss(); true } else previous?.let { CustomAccessibilityAction(PREVIOUS_ITEM) { controller.previous() } },
    aheadAction(ahead, upNext),
    CustomAccessibilityAction(closeName) { close(); true }.takeIf { closable },
    pending?.let { CustomAccessibilityAction(UNDO_CLOSE_LABEL) { undo.undo(); true } },
  )
  // Read at placement, so the slide recomposes nothing: ✕ and Undo follow the pill as it hides.
  val slide = Modifier.offset { IntOffset(0, hideOnScroll?.hiddenBy?.roundToInt() ?: 0) }
  val pillTip = rememberPillTip(
    tips,
    twoTabs = tabs.size >= 2,
    reopenable = closed.isNotEmpty() && pending == null,
    barOut = { (hideOnScroll?.hiddenBy ?: 0f) == 0f },
  )
  val undoPresses = remember { MutableInteractionSource() }
  val undoPressed by undoPresses.collectIsPressedAsState()
  val undoFocused by undoPresses.collectIsFocusedAsState()
  PauseUndoWhile(undoPressed || undoFocused, undo)
  PillRow(
    modifier.fillMaxWidth().height(BottomRowLayout.Height + bottom),
    bottom = bottom,
    start = {
      if (buttons && closable) {
        CloseCorner(canReopen = closed.isNotEmpty(), onClose = close, onReopen = undo::reopenLatest, modifier = slide, closeLabel = closeName)
      }
    },
    undo = {
      UndoChip(
        visibleState = rememberUndoShown(pending, undo),
        onUndo = undo::undo,
        left = { undo.left },
        interactionSource = undoPresses,
        compact = buttons,
        modifier = slide,
      )
      // A morphing control is silent to TalkBack: the close is said here, politely (feedback C).
      // "Closed “artist_x” · 30 kept stay. Undo available" when a source's kept posts stay (#184).
      LiveAnnouncement(pending?.let { "${it.closed.closedText}. Undo available" } ?: "")
    },
    pill = {
      CountPill(
        position = countedPosition(tabs, index),
        count = countedItems(tabs),
        feed = feed,
        onOpen = onOpenQueue,
        canReopen = closed.isNotEmpty(),
        onReopen = undo::reopenLatest,
        actions = actions,
        arrows = buttons,
        onPrevious = { goPrevious() },
        onNext = { goNext() },
        previousLabel = if (cardUp) backToLabel(current?.title ?: PREVIOUS_ITEM) else previous?.let { "$PREVIOUS_ITEM: ${it.title}" } ?: PREVIOUS_ITEM,
        nextLabel = aheadLabel(ahead, next),
        previousEnabled = cardUp || previous != null,
        nextEnabled = ahead != Ahead.Nothing,
        modifier = Modifier.hidesOnScroll(hideOnScroll).testTag(COUNT_PILL),
        tip = { pillTip?.let { TipAnchor(it.tip, tips, it.shown, Modifier.matchParentSize()) } },
      )
    },
  )
}

/**
 * What closing [tab] is called on ✕ and as the pill's action: [closeLabel]'s "Close artist_x · 30 kept stay" when a
 * source leaves [keptStay] kept posts (#184), else [CLOSE_LABEL], as on the queue page's rows.
 */
internal fun queueCloseLabel(tab: TabState, keptStay: Int): String = closeLabel(tab, keptStay)

/**
 * TalkBack's action for moving on, named for what it brings ([Ahead]): "Next queue item", "Up next" or "End of the
 * queue". None on the End of the queue card.
 */
internal fun aheadAction(ahead: Ahead, upNext: UpNextState): CustomAccessibilityAction? = when (ahead) {
  Ahead.Item -> CustomAccessibilityAction(NEXT_ITEM) { upNext.forward() }
  Ahead.UpNext -> CustomAccessibilityAction(UP_NEXT_ACTION) { upNext.forward(); true }
  Ahead.End -> CustomAccessibilityAction(END_PANE) { upNext.forward(); true }
  Ahead.Nothing -> null
}

/** ›'s name: "Next queue item: Following", "Up next", "End of the queue", or plain "Next queue item" when it's off. */
internal fun aheadLabel(ahead: Ahead, next: TabState?): String = when (ahead) {
  Ahead.Item -> next?.let { "$NEXT_ITEM: ${it.title}" } ?: NEXT_ITEM
  Ahead.UpNext -> UP_NEXT_ACTION
  Ahead.End -> END_PANE
  Ahead.Nothing -> NEXT_ITEM
}

/** A tip on the pill's count ([Tip.TabList] or [Tip.ReopenHold]): [shown] while it's on screen. */
private class PillTip(val tip: Tip, val shown: Boolean)

/**
 * The pill's tip, or null where none belongs, shown while the pill is fully out ([barOut]):
 * - the queue tip ([Tip.TabList]), offered once a second item has appeared since launch ([Tips.secondTabAppeared])
 *   while there are [twoTabs] (or once another tip has gone);
 * - the reopen tip ([Tip.ReopenHold], #344), offered while an item is closed and Undo isn't offered ([reopenable]):
 *   the hold is the way back once Undo has gone. Never beside Undo: a tip takes the first tap anywhere to dismiss
 *   itself, which would cost a tap on Undo, and with queue buttons on Undo can sit above ✕, under the tip. On the pill
 *   with queue buttons on too, as the pill's hold works then and the tip names it.
 */
@Composable
private fun rememberPillTip(tips: Tips, twoTabs: Boolean, reopenable: Boolean, barOut: () -> Boolean): PillTip? {
  val currentTip = tips.current
  val listDue = twoTabs && tips.secondTabAppeared
  LaunchedEffect(listDue, twoTabs, reopenable, currentTip) {
    // A pill tip offered whose moment passed before its anchor came (a close brought Undo back, the second item went):
    // no anchor will free it, so it's freed here, to be offered again later, rather than holding every other tip's place.
    if (currentTip == Tip.TabList && !twoTabs) tips.dismissed(Tip.TabList)
    if (currentTip == Tip.ReopenHold && !reopenable) tips.dismissed(Tip.ReopenHold)
    if (currentTip != null) return@LaunchedEffect
    if (listDue && tips.offer(Tip.TabList)) return@LaunchedEffect
    if (reopenable) tips.offer(Tip.ReopenHold)
  }
  val currentBarOut by rememberUpdatedState(barOut)
  val out by remember { derivedStateOf { currentBarOut() } }
  return when {
    currentTip == Tip.TabList && twoTabs -> PillTip(Tip.TabList, out)
    currentTip == Tip.ReopenHold && reopenable -> PillTip(Tip.ReopenHold, out)
    else -> null
  }
}

/** Opens the shell's [QueuePage], for the [QueueBar] a post draws itself. */
val LocalOpenTabList = staticCompositionLocalOf<() -> Unit> { {} }

/** The count pill, for tests. */
internal const val COUNT_PILL = "Count pill"
