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
import dev.marzahn.sankaku.ui.components.PillWithLine
import dev.marzahn.sankaku.ui.components.TipAnchor
import dev.marzahn.sankaku.ui.components.TipAction
import dev.marzahn.sankaku.ui.keptWaitsText
import dev.marzahn.sankaku.ui.openedInFrontText
import dev.marzahn.sankaku.ui.components.UNDO_CLOSE_LABEL
import dev.marzahn.sankaku.ui.components.UndoChip
import dev.marzahn.sankaku.ui.components.hidesOnScroll
import kotlin.math.roundToInt
import org.koin.compose.koinInject

/**
 * Touch: the queue's bar since the phone's tab bar was retired (#178, option A′ of `.scratch/bar-less/`), the same
 * on a grid and on a post: the count pill "▤ 2 / 9" ([CountPill]) centred, which opens the queue page ([onOpenQueue]);
 * ↶ (Undo) growing out of its left side while a close can be undone ([ClosedTabUndo]); and with "Show queue buttons"
 * ([AccessibilitySettings]), ✕ in the left corner and ‹ › in the pill. With one item it still reads "1 / 1", so the
 * queue page stays one tap away.
 *
 * TalkBack gets "Previous queue item", "Next queue item", "Close" and, while it can be undone, "Undo close" as the
 * pill's custom actions, with or without the buttons, and hears "Closed “title”. Undo available" as a close happens.
 *
 * On a grid it floats on the bottom row's line ([BottomRowLayout]), [bottom] being the room under it, and with
 * [hideOnScroll] the pill slides out on scrolling down, the rest with it; ↑ Top is the grid's, in the right corner. On
 * a post the viewer places it over the picture, above the sheet.
 *
 * [openOnly]: the pill only counts and opens the queue page, with no ✕, ‹ ›, Undo, hold to reopen or custom actions.
 * The book reader over the window uses it (#483): what's shown isn't the active item, so those would act on the item
 * under the reader while the reader stayed.
 *
 * [swipesQueue]: a sideways swipe on the screen under the pill moves between queue items, so the swipe tip
 * ([Tip.QueueSwipe]) may show. The viewer over a grid passes false: its swipe steps the grid's posts (#538).
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
  openOnly: Boolean = false,
  swipesQueue: Boolean = true,
) {
  val tabs by controller.tabs.collectAsStateWithLifecycle()
  val active by controller.active.collectAsStateWithLifecycle()
  val closed by controller.closed.collectAsStateWithLifecycle()
  val faded by controller.faded.collectAsStateWithLifecycle()
  val gOnly by upNext.gOnly.collectAsStateWithLifecycle()
  val pending by undo.pending.collectAsStateWithLifecycle()
  val showButtons by settings.showQueueButtons.collectAsStateWithLifecycle()
  val buttons = showButtons && !openOnly
  val index = tabs.indexOfFirst { it.id == active }.coerceAtLeast(0)
  val previous = controller.neighbour(forward = false)
  val next = controller.neighbour(forward = true)
  // Past the last item › brings the Up next card (#216, #230, #327), else End of the queue; on a card ‹ goes
  // back to the item it stands past, and on Up next › goes on to End of the queue.
  val cardUp = upNext.card != null
  val ahead = upNext.ahead(gOnly, faded)
  val goPrevious = { if (cardUp) upNext.dismiss() else controller.previous() }
  // Moving on with ›, as with a swipe, is what the swipe tip teaches (#501): it's done then.
  val goNext = {
    tips.used(Tip.QueueSwipe)
    upNext.forward()
  }
  val close = { undo.close(active) }
  val current = tabs.getOrNull(index)
  // A feed item (#229) isn't counted, so the pill names it, and it can't be closed: no ✕ and no Close action.
  val feed = current?.takeIf { it.kind == QueueKind.Feed }?.title
  val closable = current == null || controller.canClose(current)
  // "Close artist_x · 30 kept stay" for a source whose kept posts stay (#184); the tabs and their tops are snapshot state.
  val closeName = current?.let { queueCloseLabel(it, controller.keptPostsOf(it)) } ?: CLOSE_LABEL
  val actions = if (openOnly) emptyList() else listOfNotNull(
    if (cardUp) CustomAccessibilityAction(PREVIOUS_ITEM) { upNext.dismiss(); true } else previous?.let { CustomAccessibilityAction(PREVIOUS_ITEM) { controller.previous() } },
    aheadAction(ahead, upNext),
    CustomAccessibilityAction(closeName) { close(); true }.takeIf { closable },
    pending?.let { CustomAccessibilityAction(UNDO_CLOSE_LABEL) { undo.undo(); true } },
  )
  // Read at placement, so the slide recomposes nothing: ✕ and Undo follow the pill as it hides.
  val slide = Modifier.offset { IntOffset(0, hideOnScroll?.hiddenBy?.roundToInt() ?: 0) }
  // The block shown (#501): its place on the pill, and on its last kept item where moving on goes, over it. On a card the
  // pill says which card, with the count.
  val place = blockPlace(tabs, index)
  // Open-only, the pill doesn't move on, so it doesn't say where moving on goes.
  val aheadName = if (cardUp || openOnly) null else aheadTitle(ahead, next)
  val text = pillText(place, countedPosition(tabs, index), countedItems(tabs), feed, upNext.card, aheadName)
  val endLine = blockEndLine(place, aheadName)
  // The pill of an item, not a card's, nor the reader's open-only one: where the queue's tips belong.
  val fullPill = !cardUp && !openOnly
  // A source with kept items: where they went (#559). Only where a swipe here goes to them, as for the swipe tip.
  val keptAfter = (place as? BlockPlace.Head)?.source?.takeIf { fullPill && swipesQueue }
  val pillTip = rememberPillTip(
    tips,
    // A next item to swipe to, a swipe here that goes there (#538), and the pill not open-only: the swipe tip (#501).
    swipeDue = ahead == Ahead.Item && fullPill && swipesQueue,
    twoTabs = tabs.size >= 2,
    // Open-only, the hold doesn't reopen, so its tip isn't offered (#485 review).
    reopenable = closed.isNotEmpty() && pending == null && !openOnly,
    barOut = { (hideOnScroll?.hiddenBy ?: 0f) == 0f },
    // A search opened in front of an item that Back returns to (#349): what the tag tap did (#559).
    openedInFront = current?.takeIf { fullPill && controller.openerOf(it) != null }?.title,
    keptAfter = keptAfter,
    keptBooks = keptAfter != null && current != null && keptBooks(tabs, current),
    onOpenQueue = onOpenQueue,
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
    undo = undo@{
      if (openOnly) return@undo
      UndoChip(
        visibleState = rememberUndoShown(pending, undo),
        onUndo = undo::undo,
        left = { undo.left },
        interactionSource = undoPresses,
        modifier = slide,
      )
      // A morphing control is silent to TalkBack: the close is said here, politely (feedback C).
      // "Closed “artist_x” · 30 kept stay. Undo available" when a source's kept posts stay (#184).
      LiveAnnouncement(pending?.let { "${it.closed.closedText}. Undo available" } ?: "")
    },
    pill = {
      // A tip over the pill has the line's place: it waits.
      PillWithLine(line = endLine.takeIf { pillTip == null }, modifier = Modifier.hidesOnScroll(hideOnScroll)) {
        CountPill(
          text = text,
          onOpen = onOpenQueue,
          canReopen = closed.isNotEmpty() && !openOnly,
          onReopen = undo::reopenLatest,
          actions = actions,
          arrows = buttons,
          onPrevious = { goPrevious() },
          onNext = { goNext() },
          previousLabel = if (cardUp) backToLabel(current?.title ?: PREVIOUS_ITEM) else previous?.let { "$PREVIOUS_ITEM: ${it.title}" } ?: PREVIOUS_ITEM,
          nextLabel = aheadLabel(ahead, next),
          previousEnabled = cardUp || previous != null,
          nextEnabled = ahead != Ahead.Nothing,
          modifier = Modifier.testTag(COUNT_PILL),
          tip = { pillTip?.let { TipAnchor(it.tip, tips, it.shown, Modifier.matchParentSize(), text = it.text, action = it.action) } },
        )
      }
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

/** A tip on the pill's count ([Tip.QueueSwipe], [Tip.TabList] or [Tip.ReopenHold]): [shown] while it's on screen. */
private class PillTip(val tip: Tip, val shown: Boolean, val text: String = tip.text, val action: TipAction? = null)

/**
 * The pill's tip, or null where none belongs, shown while the pill is fully out ([barOut]):
 * - the opened-in-front tip ([Tip.OpenedInFront], #559), first, while the item shown is a search opened in front of an
 *   item that Back returns to ([openedInFront], its name), with Show queue ([onOpenQueue]);
 * - the kept tip ([Tip.KeptWaits], #559), while the item shown is a source with kept items ([keptAfter], its name;
 *   [keptBooks] when they're books);
 * - the swipe tip ([Tip.QueueSwipe], #501), while there's a next item to swipe to ([swipeDue]), restored at
 *   launch or not: a queue brought back full is where the swipe is most needed and least found;
 * - the queue tip ([Tip.TabList]), offered once a second item has appeared since launch ([Tips.secondTabAppeared])
 *   while there are [twoTabs] (or once another tip has gone);
 * - the reopen tip ([Tip.ReopenHold], #344), offered while an item is closed and Undo isn't offered ([reopenable]):
 *   the hold is the way back once Undo has gone. Never beside Undo: a tip takes the first tap anywhere to dismiss
 *   itself, which would cost a tap on Undo, and with queue buttons on Undo can sit above ✕, under the tip. On the pill
 *   with queue buttons on too, as the pill's hold works then and the tip names it.
 */
@Composable
private fun rememberPillTip(
  tips: Tips,
  swipeDue: Boolean,
  twoTabs: Boolean,
  reopenable: Boolean,
  barOut: () -> Boolean,
  openedInFront: String? = null,
  keptAfter: String? = null,
  keptBooks: Boolean = false,
  onOpenQueue: () -> Unit = {},
): PillTip? {
  val currentTip = tips.current
  val listDue = twoTabs && tips.secondTabAppeared
  val openedDue = openedInFront != null
  val keptDue = keptAfter != null
  // Offered again once a tip that just left stops holding the next one up (#538).
  LaunchedEffect(openedDue, keptDue, swipeDue, listDue, twoTabs, reopenable, currentTip, tips.resting) {
    // A pill tip offered whose moment passed before its anchor came (a close brought Undo back, the second item went):
    // no anchor will free it, so it's freed here, to be offered again later, rather than holding every other tip's place.
    if (currentTip == Tip.OpenedInFront && !openedDue) tips.dismissed(Tip.OpenedInFront)
    if (currentTip == Tip.KeptWaits && !keptDue) tips.dismissed(Tip.KeptWaits)
    if (currentTip == Tip.QueueSwipe && !swipeDue) tips.dismissed(Tip.QueueSwipe)
    if (currentTip == Tip.TabList && !twoTabs) tips.dismissed(Tip.TabList)
    if (currentTip == Tip.ReopenHold && !reopenable) tips.dismissed(Tip.ReopenHold)
    // Not straight after another tip left the screen: it waits for the user's next touch to end (#538).
    if (currentTip != null || tips.resting) return@LaunchedEffect
    if (openedDue && tips.offer(Tip.OpenedInFront)) return@LaunchedEffect
    if (keptDue && tips.offer(Tip.KeptWaits)) return@LaunchedEffect
    if (swipeDue && tips.offer(Tip.QueueSwipe)) return@LaunchedEffect
    if (listDue && tips.offer(Tip.TabList)) return@LaunchedEffect
    if (reopenable) tips.offer(Tip.ReopenHold)
  }
  val currentBarOut by rememberUpdatedState(barOut)
  val out by remember { derivedStateOf { currentBarOut() } }
  return when {
    currentTip == Tip.OpenedInFront && openedInFront != null ->
      PillTip(Tip.OpenedInFront, out, openedInFrontText(openedInFront), TipAction(SHOW_QUEUE, onOpenQueue))
    currentTip == Tip.KeptWaits && keptAfter != null -> PillTip(Tip.KeptWaits, out, keptWaitsText(keptAfter, keptBooks))
    currentTip == Tip.QueueSwipe && swipeDue -> PillTip(Tip.QueueSwipe, out)
    currentTip == Tip.TabList && twoTabs -> PillTip(Tip.TabList, out)
    currentTip == Tip.ReopenHold && reopenable -> PillTip(Tip.ReopenHold, out)
    else -> null
  }
}

/** Opens the shell's [QueuePage], for the [QueueBar] a post draws itself. */
val LocalOpenTabList = staticCompositionLocalOf<() -> Unit> { {} }

/** The count pill, for tests. */
internal const val COUNT_PILL = "Count pill"

/** Whether the kept items of the source [head] in [tabs] are books, for the kept tip's words (#559). */
private fun keptBooks(tabs: List<TabState>, head: TabState): Boolean =
  tabs.firstOrNull { it.block == head.id && it.kind.isKept }?.kind == QueueKind.KeptBook

/** The opened-in-front tip's button that opens the queue page (#559), as the pill's tap is named. */
internal const val SHOW_QUEUE = "Show queue"
