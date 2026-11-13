package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.Shortcuts
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * The desktop's offer to close a finished block (#502): the block "Done with …?" names while its message shows, which
 * Shift+X ([Shortcuts.CloseBlock]) closes as the message's action does. Each block is offered once per run.
 */
class BlockOffer {
  /** The head of the block on offer, while its message shows. */
  var pending: TabId? by mutableStateOf(null)
    internal set

  internal val offered = mutableSetOf<TabId>()

  /** The offer's own message, which doesn't end it; any other message replacing it does. */
  internal var message: Message? = null

  /**
   * Closes the block on offer, head and kept items, as one Recently closed entry, and says so with Undo in the offer's
   * place; false with none on offer.
   */
  fun take(controller: TabsController, messages: Messages): Boolean {
    val head = pending ?: return false
    pending = null
    val (closed, title) = controller.closeBlock(head) ?: return false
    messages.show(Message("Closed “$title” · ${closed.size}", actionLabel = "Undo", short = true) { controller.reopen(closed) })
    return true
  }
}

/**
 * Says what leaving a block means (#502, desktop), each time another item comes forward ([leavingBlock]): "Queue done ·
 * nothing kept is left", with Clear viewed while there are seen kept posts to clear, when the move or close used up the
 * last kept item left; else "Done with artist_x? · 4 seen" with Close, when it left a source whose kept items were all
 * seen or closed. Worked out a frame after the change, once the item left has recorded what it made seen.
 */
@Composable
fun LeavingBlockMessages(
  offer: BlockOffer,
  controller: TabsController = koinInject(),
  seen: SeenStore = koinInject(),
  clearViewed: ClearViewed = koinInject(),
  messages: Messages = koinInject(),
) {
  // What was shown as of the last change; plain memory, as it's only read by the next change.
  val last = remember { Shown() }
  val active by controller.active.collectAsStateWithLifecycle()
  // The offer lasts as long as its message, a Long snackbar's 10 s, however the user moves meanwhile.
  LaunchedEffect(offer.pending) {
    if (offer.pending == null) return@LaunchedEffect
    delay(OFFER_MILLIS)
    offer.pending = null
  }
  // Any other message takes the offer's place on screen ("Closed · Undo", "Cleared · Undo"): Shift+X has nothing
  // on offer from then on.
  LaunchedEffect(messages) {
    messages.messages.collect { if (it !== offer.message) offer.pending = null }
  }
  LaunchedEffect(active) {
    withFrameNanos {}
    val tabs = controller.tabs.value
    val seenNow = { tab: TabState -> tab.seenSinceKept(seen) }
    val said = leavingBlock(
      tabs,
      left = last.head,
      hadKept = last.hadKept,
      leftBefore = last.keptLeft,
      now = active,
      offered = offer.offered,
      seen = seenNow,
    )
    val head = blockHeadOf(tabs, active)
    val hasKept = blockProgress(tabs, active, seenNow) != null
    // Within one block, it had kept items if it had them at any point: closing the last one lands on its head.
    last.hadKept = hasKept || (head == last.head && last.hadKept)
    last.head = head
    last.keptLeft = keptLeft(tabs, seenNow)
    // Back in the offered block, it isn't done with: the offer ends, though its message may still show.
    if (offer.pending != null && offer.pending == head) offer.pending = null
    when (said) {
      LeavingBlock.None -> Unit
      LeavingBlock.QueueDone -> {
        offer.pending = null
        val viewed = clearViewed.status().count
        messages.show(
          if (viewed > 0) {
            Message("Queue done · nothing kept is left", actionLabel = "Clear viewed · $viewed") { clearViewed() }
          } else {
            Message("Queue done · nothing kept is left")
          },
        )
      }
      is LeavingBlock.Done -> {
        offer.offered += said.head
        val message = Message(
          "Done with ${said.title}?${if (said.seen > 0) " · ${said.seen} seen" else ""}",
          actionLabel = "Close ${Shortcuts.CloseBlock.label}",
        ) { offer.take(controller, messages) }
        offer.message = message
        offer.pending = said.head
        messages.show(message)
      }
    }
  }
}

/** The block shown, whether it had kept items, and how many kept items the whole queue had left. */
private class Shown(var head: TabId? = null, var hadKept: Boolean = false, var keptLeft: Int = 0)

private const val OFFER_MILLIS = 10_000L
