package dev.marzahn.sankaku.tabs

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.rememberActionWindowMillis
import dev.marzahn.sankaku.ui.runWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Touch: closing a queue item and bringing items back. A close (a drag down on a lone post, ✕ with queue buttons on,
 * the pill's "Close" action, a swipe on the queue page) opens a window in which "↶ Undo" grows out of the count pill's
 * left side (#178; `.scratch/android-feedback/`, C), with its countdown; each close restarts it and points it at that
 * item. Holding the pill (or ✕) reopens the most recently closed item still closed, at any time ([reopenLatest]), as
 * desktop's Cmd/Ctrl-Shift-T does; a one-time tip on the pill teaches that ([Tip.ReopenHold], shown by [QueueBar]),
 * and a hold that reopens an item means it's known ([tips]). One holder for every screen, so the window carries on
 * when the pill moves between a grid and a post.
 */
class ClosedTabUndo(
  private val controller: TabsController,
  private val tips: Tips? = null,
) {
  /** The item Undo would reopen, with a [serial] new for each close; null when no window is open. */
  data class Pending(val closed: ClosedTab, val serial: Int)

  private val _pending = MutableStateFlow<Pending?>(null)
  val pending: StateFlow<Pending?> = _pending.asStateFlow()

  private var serial = 0

  /** The window's share left, 1 → 0: Undo's countdown. A snapshot read. */
  var left by mutableFloatStateOf(1f)
    internal set

  // What pauses the window now: Undo pressed or focused, a post's chrome hidden with the pill. Keyed by who asked, so
  // several bars (the shell's and a post's, overlapping as a viewer closes) never undo each other's pause.
  private val pausedBy = mutableStateMapOf<Any, Unit>()

  /** Whether something on screen holds the window still ([PauseUndoWhile]). A snapshot read. */
  val paused: Boolean get() = pausedBy.isNotEmpty()

  /** Holds the window still for [by] while [paused], until it says otherwise. */
  fun pause(by: Any, paused: Boolean) {
    if (paused) pausedBy[by] = Unit else pausedBy.remove(by)
  }

  /** Closes [id] (as [TabsController.close]) and opens the window for it. An item already gone does nothing. */
  fun close(id: TabId) {
    opened(controller.close(id) ?: return)
  }

  /**
   * Back on a grid opened from an item (#349, [TabsController.closeBack]): closes it, shows that item and opens the
   * window for it. False, changing nothing, when Back doesn't close [id].
   */
  fun closeBack(id: TabId): Boolean {
    opened(controller.closeBack(id) ?: return false)
    return true
  }

  /**
   * A drag down on a lone post (#326): closes [id] as [close] does and moves on, to the item that takes its place, as ✕
   * does. Where moving on would bring the Up next or End of the queue card ([UpNextState.ahead]), it lands on the item
   * before and shows that card past it; with no item before, on whatever is left. Undo brings it back and shows it.
   */
  fun dragClose(id: TabId, upNext: UpNextState) {
    val ahead = upNext.ahead()
    val toCard = controller.activeTab.id == id && controller.neighbour(forward = false) != null &&
      (ahead == Ahead.UpNext || ahead == Ahead.End)
    opened(controller.close(id, toPrevious = toCard) ?: return)
    if (toCard) upNext.showAhead(ahead)
  }

  /**
   * Opens the window for [closed], an item closed elsewhere (a swipe on the queue page, which has its own inline Undo
   * while it's open): the pill offers Undo for what's left of the window once the page closes.
   */
  fun opened(closed: ClosedTab) {
    left = 1f
    _pending.value = Pending(closed, ++serial)
  }

  /** Reopens the pending item where it was, and ends the window. */
  fun undo() {
    val pending = _pending.value ?: return
    end(pending)
    controller.reopen(pending.closed)
  }

  /**
   * The hold on the pill or ✕: reopens the most recently closed item that's still closed, where it was. False when
   * there's none (the hold then says so with the "reject" haptic).
   */
  fun reopenLatest(): Boolean {
    if (controller.closed.value.isEmpty()) return false
    controller.reopenClosed()
    tips?.used(Tip.ReopenHold)
    return true
  }

  // The last close whose Undo has finished growing out of a pill, in whichever bar showed it first (#285). Not snapshot
  // state: a bar reads it only once, as it's composed.
  private var grownSerial = 0

  /** Whether [pending]'s Undo has grown out of a pill already, so a bar composed after that shows it at once. */
  internal fun hasGrown(pending: Pending): Boolean = grownSerial == pending.serial

  /** Says [pending]'s Undo has grown out of a pill. */
  internal fun grown(pending: Pending) {
    grownSerial = pending.serial
  }

  /** Ends [pending]'s window, if it's still the open one. */
  internal fun end(pending: Pending) {
    _pending.update { if (it == pending) null else it }
  }
}

/**
 * Runs [undo]'s window (the snackbar's Long time, lengthened by the accessibility settings, as the top line's is),
 * paused while something holds it ([ClosedTabUndo.paused]) or [paused] (the queue page covers the pill), and ends it when its item
 * came back another way (a hold, the queue page's Undo or Recently closed, Z).
 */
@Composable
fun ClosedTabUndoWindow(undo: ClosedTabUndo = koinInject(), controller: TabsController = koinInject(), paused: Boolean = false) {
  val pending by undo.pending.collectAsStateWithLifecycle()
  val window = rememberActionWindowMillis()
  val hidden by rememberUpdatedState(paused)
  val current = pending ?: return
  LaunchedEffect(current) {
    // Read from the flow itself: a list collected for composition can be a frame behind the close that opened this.
    val cameBack = launch { controller.closed.first { current.closed !in it }.let { undo.end(current) } }
    runWindow(window, isPaused = { undo.paused || hidden }) { undo.left = it }
    cameBack.cancel()
    undo.end(current)
  }
}

/**
 * Whether a bar shows Undo for [pending], for its `AnimatedVisibility`. Each close's Undo grows out of the pill once, in
 * whichever bar first shows it: a bar composed after a close that changed the screen (a drag down onto the item
 * before, the queue page's swipe-close) starts hidden and grows it (#285), and a bar composed once it has grown (the
 * user moves on) starts shown. Only a finished grow counts, so a bar that leaves mid-grow (the one the close took
 * away) leaves it to the next.
 */
@Composable
internal fun rememberUndoShown(pending: ClosedTabUndo.Pending?, undo: ClosedTabUndo): MutableTransitionState<Boolean> {
  val shown = remember { MutableTransitionState(pending != null && undo.hasGrown(pending)) }
  shown.targetState = pending != null
  LaunchedEffect(pending) {
    val current = pending ?: return@LaunchedEffect
    // Already shown: this bar didn't grow this close's Undo (a second close while it showed, the bar the close takes
    // away), so the next bar to show it still grows it.
    if (shown.currentState) return@LaunchedEffect
    // A transition state's current state turns true only once the enter has finished (its fade too).
    snapshotFlow { shown.currentState }.first { it }
    undo.grown(current)
  }
  return shown
}

/** Holds [undo]'s window still while [paused] and this is composed; leaving lets it go. */
@Composable
fun PauseUndoWhile(paused: Boolean, undo: ClosedTabUndo = koinInject()) {
  val key = remember { Any() }
  SideEffect { undo.pause(key, paused) }
  DisposableEffect(undo, key) { onDispose { undo.pause(key, false) } }
}
