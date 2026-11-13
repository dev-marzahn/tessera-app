package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.ui.components.CountPillText

/**
 * Where a queue item stands in its block (#501): what the phone's count pill and a swipe's peek say while going
 * through the queue, so the user knows which block they're in, how much of it is left and when they leave it. The
 * desktop queue view can say "3 / 8 · from Landscape" from it too.
 */
sealed interface BlockPlace {
  /** What the block is named by: its source's title ("Landscape", "Following"), a closed source's too. */
  val source: String

  /**
   * A kept post or book, the [position]th (from 1) of the [count] kept items of its block, which a source, a feed or
   * a closed source ([closed]) heads.
   */
  data class Kept(override val source: String, val position: Int, val count: Int, val kind: QueueKind, val closed: Boolean = false) : BlockPlace {
    val last: Boolean get() = position == count

    /** "kept post 3 of 8" (or "kept book …"), as TalkBack says it. */
    val spoken: String get() = "${if (kind == QueueKind.KeptBook) "kept book" else "kept post"} $position of $count"

    /** ", closed" after the source's name, for TalkBack: the pill shows the name alone. */
    val closedSuffix: String get() = if (closed) ", closed" else ""
  }

  /** A source or a feed with [kept] kept items following it in its block. */
  data class Head(override val source: String, val kept: Int) : BlockPlace {
    /** "8 kept items follow", as TalkBack says it. */
    val follow: String get() = "$kept kept ${if (kept == 1) "item follows" else "items follow"}"
  }
}

/**
 * The [BlockPlace] of the item at [index] of [tabs], or null when it isn't in a block with kept items: a source or
 * feed with none, a post on its own (or in a block a post heads), anything else. A block's kept items are its tabs
 * after the head, as [TabsController.keptPostsOf] counts them.
 */
internal fun blockPlace(tabs: List<TabState>, index: Int): BlockPlace? {
  val tab = tabs.getOrNull(index) ?: return null
  if (tab.kind.isKept) {
    val head = tab.block ?: return null
    val members = tabs.filter { it.block == head && it.kind.isKept }
    val position = members.indexOf(tab) + 1
    val source = tabs.firstOrNull { it.id == head }
    val closed = tab.closedSource
    return when {
      source != null && headsKeptPosts(source.kind) -> BlockPlace.Kept(source.title, position, members.size, tab.kind)
      source == null && closed != null ->
        BlockPlace.Kept(tabTitle(searchSource(closed), names = tab.tagNames), position, members.size, tab.kind, closed = true)
      else -> null
    }
  }
  if (!headsKeptPosts(tab.kind)) return null
  val kept = tabs.count { it.block == tab.id && it.kind.isKept }
  return if (kept > 0) BlockPlace.Head(tab.title, kept) else null
}

/**
 * Where moving on from the last item of a block goes, for the line over the pill: "Last from Landscape · next: Cat".
 * Null unless [place] is a block's last kept item and moving on goes somewhere ([next]: the item's title, "Up next" or
 * "End of the queue").
 */
internal fun blockEndLine(place: BlockPlace?, next: String?): String? {
  if (place !is BlockPlace.Kept || !place.last || next == null) return null
  return "Last from ${place.source} · next: $next"
}

/** TalkBack's ", last, next Cat" on a block's last kept item ([blockEndLine]), else nothing. */
internal fun blockEndSpoken(place: BlockPlace?, next: String?): String =
  if (place is BlockPlace.Kept && place.last && next != null) ", last, next $next" else ""

/** What moving on brings, by name ([Ahead]): the next item's title, "Up next" or "End of the queue"; null for nothing. */
internal fun aheadTitle(ahead: Ahead, next: TabState?): String? = when (ahead) {
  Ahead.Item -> next?.title
  Ahead.UpNext -> UP_NEXT_PANE
  Ahead.End -> END_PANE
  Ahead.Nothing -> null
}

/** What a swipe's peek of another block says over it: "Leaving Landscape", when the shown item is in a [BlockPlace]. */
internal fun leavingLabel(place: BlockPlace?): String? = place?.let { "Leaving ${it.source}" }

/**
 * The count pill's words (#501):
 * - a kept item in a block: "Landscape · 3 / 8", read "Queue, Landscape, kept post 3 of 8, item 5 of 11";
 * - a source or feed with kept items: "Landscape · 8 kept";
 * - on a [card]: "Up next · 11" or "End · 11";
 * - anything else as before: "2 / 11", or a feed's name ("Following"), which isn't counted (#229).
 * [position] is the item's counted place and [count] the counted items ([countedPosition], [countedItems]); [next] is
 * where moving on from a block's last item goes ([blockEndSpoken]), null where the pill can't move (open-only).
 */
internal fun pillText(place: BlockPlace?, position: Int, count: Int, feed: String?, card: UpNextCard?, next: String? = null): CountPillText {
  if (card != null) {
    val name = if (card == UpNextCard.End) "End" else "Up next"
    return CountPillText(name, "$count", "Queue, ${name.lowercase()}, after $count ${if (count == 1) "item" else "items"}")
  }
  val where = if (feed != null) "after $position ${if (position == 1) "item" else "items"}" else "item $position of $count"
  return when (place) {
    is BlockPlace.Kept -> CountPillText(
      place.source,
      "${place.position} / ${place.count}",
      "Queue, ${place.source}${place.closedSuffix}, ${place.spoken}, $where${blockEndSpoken(place, next)}",
    )
    is BlockPlace.Head -> CountPillText(place.source, "${place.kept} kept", "Queue, ${place.source}, ${place.follow}, $where")
    null -> if (feed != null) CountPillText(null, feed, "Queue, $feed, $where") else CountPillText(null, "$position / $count", "Queue, $where")
  }
}
