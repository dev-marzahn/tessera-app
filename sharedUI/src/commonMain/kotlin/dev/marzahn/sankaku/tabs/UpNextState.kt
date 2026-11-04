package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.UpNext
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * What the card past the queue's items shows (#216, #230), text only: never a picture, for privacy (the stress test).
 * [search] is the search its page shows on the search line: the faded source's, or none.
 */
sealed interface UpNextCard {
  val search: Route.Grid?

  /** An offer of a faded source: its [id] (the faded source's, which stays its id while faded) and [title]. */
  sealed interface Offer : UpNextCard {
    val id: TabId
    val title: String
  }

  /** "Last chance: artist_x", a faded source in its last week; [detail] says when it is deleted and what it holds. */
  data class LastChance(override val id: TabId, override val title: String, val detail: String, override val search: Route.Grid?) : Offer

  /** "Pick up artist_x again?", a faded source; [detail] as on its row in the Faded section. */
  data class Pickup(override val id: TabId, override val title: String, val detail: String, override val search: Route.Grid?) : Offer

  /** "End of the queue": nothing is left to offer. Back to the start, or Browse. */
  data object End : UpNextCard {
    override val search: Route.Grid? = null
  }
}

/** What moving on from the item shown (or the card) brings. */
enum class Ahead {
  /** The next queue item. */
  Item,

  /** The Up next card, with something to offer. */
  UpNext,

  /** The End of the queue card. */
  End,

  /** Nothing: the End of the queue card is up. */
  Nothing,
}

/**
 * Touch: the Up next and End of the queue cards (#216, #230; spec: Tabs → Queue → Up next). Up next offers only what
 * is out of view, in order ([UpNext.offers]): a Last chance, then the other faded sources. It stands past the last
 * item, whatever it is (#327: a feed is a source that persists, so nothing stands after the feeds by rule), and shows
 * when there is something to offer; past it comes End of the queue. Both are memory only, so neither is there at a
 * launch, and a start ([QueueStart]) or any change of the item shown ([itemShown]) takes them away.
 *
 * Open restores the faded source with its place and shows it ([TabsController.restore]). A Last chance is marked
 * offered as it appears ([TabsController.offered]), so it is offered once however it is answered.
 */
class UpNextState(
  private val tabs: TabsController,
  private val privacy: PrivacySettings,
  private val messages: Messages,
) {
  /** The card shown, or null. Snapshot state. */
  var card: UpNextCard? by mutableStateOf(null)
    private set

  // The offers already shown and answered or skipped, for as long as the card stays up.
  private val passed = mutableSetOf<String>()

  /** Whether G only is on, which leaves R15 and R18 items out of the card; the bars read it to know when to ask [ahead]. */
  val gOnly: StateFlow<Boolean> get() = privacy.gOnly

  private fun offers() = tabs.upNextOffers(passed, privacy.gOnly.value)

  /** Whether Up next has anything to offer past the item shown. */
  fun available(): Boolean = tabs.upNextAhead() && offers().isNotEmpty()

  /**
   * What moving on brings, from the item shown or from the card up. Read it keyed by the queue, as it reads that.
   */
  fun ahead(): Ahead = when (card) {
    UpNextCard.End -> Ahead.Nothing
    is UpNextCard.Offer -> if (hasNext()) Ahead.Item else Ahead.End
    null -> when {
      available() -> Ahead.UpNext
      hasNext() -> Ahead.Item
      else -> Ahead.End
    }
  }

  private fun hasNext() = tabs.neighbour(forward = true) != null

  /**
   * [ahead] for a composable: it is asked on every composition, not remembered, since the answer also moves with the
   * clock. [gOnly] and [faded] are what the caller collected, so a change in either recomposes it.
   */
  @Suppress("UNUSED_PARAMETER")
  fun ahead(gOnly: Boolean, faded: List<FadedSource>): Ahead = ahead()

  /**
   * Moves on: to the next item, the Up next card or the End of the queue card, as [ahead] says. Returns whether the
   * item shown changed (a card shows over the item shown, which stays).
   */
  fun forward(): Boolean = when (ahead()) {
    Ahead.Item -> {
      dismiss()
      tabs.next()
    }
    Ahead.UpNext -> {
      show()
      false
    }
    Ahead.End -> {
      showEnd()
      false
    }
    Ahead.Nothing -> false
  }

  /** Shows the first offer (TalkBack's "Up next"). Returns false, showing nothing, when there is none. */
  fun show(): Boolean {
    if (card is UpNextCard.Offer) return true
    if (!available()) return false
    passed.clear()
    return advance()
  }

  /** Skip: the next offer; past the last one, End of the queue. */
  fun skip() {
    pass()
    advance()
  }

  /** Open: the faded source is restored, with its place and its kept posts, and shown; the card goes. */
  fun open() {
    val shown = card as? UpNextCard.Offer ?: return
    dismiss()
    tabs.restore(shown.id, show = true)
  }

  /** Last chance's Keep: the source is back in the queue (not shown), then the next offer. */
  fun keep() {
    val shown = card as? UpNextCard.LastChance ?: return
    tabs.keep(shown.id)
    messages.show("Kept “${shown.title}”")
    pass()
    advance()
  }

  /** Last chance's Let go: the source is deleted now, with an Undo on the top line, then the next offer. */
  fun letGo() {
    val shown = card as? UpNextCard.LastChance ?: return
    tabs.letGo(shown.id)?.let { closed ->
      messages.show(Message("Let go of “${shown.title}”", actionLabel = "Undo", short = true) { tabs.reopen(closed) })
    }
    pass()
    advance()
  }

  /** End of the queue's "Back to the start": the first queue item. */
  fun backToStart() {
    dismiss()
    tabs.backToStart(privacy.gOnly.value)
  }

  /** End of the queue's "Browse": a Browse grid, as Start on: Browse opens it. */
  fun browse() {
    dismiss()
    tabs.showBrowse()
  }

  /**
   * A drag down closed the last item before a card (#326): the card [ahead] names, [Ahead.UpNext] or [Ahead.End], comes
   * up past the item it landed on, as moving on would have shown it.
   */
  fun showAhead(ahead: Ahead) {
    when (ahead) {
      Ahead.UpNext -> show()
      Ahead.End -> showEnd()
      Ahead.Item, Ahead.Nothing -> Unit
    }
    // The shell sees the item it landed on come forward only after this; the card stays for that ([itemShown]).
    if (card != null) landedOn = tabs.activeTab.let { it.id to it.top }
  }

  /**
   * The shell's answer to [id] coming forward, or [top] run in it: the card goes, unless a drag down just showed it past
   * this very item as it is now ([showAhead]).
   */
  fun itemShown(id: TabId, top: Route?) {
    if (landedOn != id to top) dismiss()
  }

  /** The card goes, back to the item shown. */
  fun dismiss() {
    card = null
    landedOn = null
    passed.clear()
  }

  private fun showEnd() {
    passed.clear()
    card = UpNextCard.End
  }

  // The item, and its search, a drag down landed on with the card [showAhead] brought up past it.
  private var landedOn: Pair<TabId, Route?>? = null

  private fun pass() {
    (card as? UpNextCard.Offer)?.let { passed += it.id.value }
  }

  // The next offer; with none left, past Up next: End of the queue. If a source kept from a Last chance now follows
  // instead, the card goes, back on the item: Keep doesn't show it.
  private fun advance(): Boolean {
    while (true) {
      val offer = offers().firstOrNull()
      if (offer == null) {
        val hadCard = card != null
        val next = tabs.neighbour(forward = true)
        dismiss()
        if (hadCard && next == null) showEnd()
        return hadCard && next == null
      }
      val next = cardFor(offer)
      if (next != null) {
        card = next
        return true
      }
      passed += offer.id
    }
  }

  private fun cardFor(offer: UpNext.Offer): UpNextCard? {
    val now = tabs.now()
    val id = TabId(offer.id)
    val faded = tabs.faded.value.firstOrNull { it.id == id } ?: return null
    val search = faded.route as? Route.Grid
    return when (offer) {
      is UpNext.Offer.LastChance -> {
        // Shown means offered: it is not offered again, whatever the answer.
        tabs.offered(id)
        UpNextCard.LastChance(id, faded.title, fadedDetail(faded, now), search)
      }
      is UpNext.Offer.Pickup -> UpNextCard.Pickup(id, faded.title, fadedDetail(faded, now), search)
    }
  }
}

/**
 * The route the search line shows (#230): with a [card] up, its own search ([UpNextCard.search]) or an empty one,
 * never the grid's it stands past; otherwise the item's [top].
 */
internal fun shownSearch(card: UpNextCard?, top: Route?): Route? =
  if (card == null) top else card.search ?: Route.Grid(emptyList())

/** "Deleted in 4 days · place kept · 3 kept posts": when a faded source goes, and what goes with it (also its row's). */
internal fun fadedDetail(faded: FadedSource, now: Instant): String {
  val parts = mutableListOf(faded.deletedIn(now))
  if (faded.place != null) parts += "place kept"
  if (faded.keptCount > 0) parts += if (faded.keptCount == 1) "1 kept post" else "${faded.keptCount} kept posts"
  return parts.joinToString(" · ")
}
