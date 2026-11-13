package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.queue.QueueKind

/**
 * One block of the queue (#502), by the queue page's rule ([queueRows]): an item that isn't a kept item of the block
 * before it starts one, and the kept items right after it with its id as their `block` are its members. A closed
 * source's kept posts (#184) are a block with no head ([headIndex] null) under the head id they lost. [head] is the
 * block's id either way; [kept] are its members' indices in the queue. An item with nothing kept after it is a block
 * of its own with no members.
 */
internal class QueueBlock(val head: TabId, val headIndex: Int?, val kept: List<Int>, val closedSource: List<String>?) {
  /** Every index of the block, its head's first. */
  val indices: List<Int> get() = listOfNotNull(headIndex) + kept
}

/** The blocks of [tabs], in queue order ([QueueBlock]). */
internal fun queueBlocks(tabs: List<TabState>): List<QueueBlock> {
  val blocks = mutableListOf<QueueBlock>()
  var head: TabId? = null
  var headIndex: Int? = null
  var closedSource: List<String>? = null
  val kept = mutableListOf<Int>()
  fun flush() {
    head?.let { blocks += QueueBlock(it, headIndex, kept.toList(), closedSource) }
    kept.clear()
  }
  for ((index, tab) in tabs.withIndex()) {
    val previous = tabs.getOrNull(index - 1)
    val closed = tab.closedSource
    val member = if (closed != null) {
      previous?.closedSource != null && previous.block == tab.block
    } else {
      previous != null && tab.block != null && previous.head == tab.block
    }
    if (member) {
      kept += index
      continue
    }
    flush()
    if (closed != null) {
      head = tab.head
      headIndex = null
      closedSource = closed
      kept += index
    } else {
      head = tab.id
      headIndex = index
      closedSource = null
    }
  }
  flush()
  return blocks
}

/**
 * Where an item stands in its block (#502), what the desktop's chip by the search line says, and what the phone's
 * pill can say (#501): the block's [title] (its head's, or "artist_x (closed)"), the item's [position] among the
 * block's kept items (from 1; null on the head), how many items are [kept] in it and how many of those are [left]
 * (not seen since they were kept). [closed] is a closed source's block, [feed] a feed's.
 */
internal data class BlockProgress(
  val title: String,
  val position: Int?,
  val kept: Int,
  val left: Int,
  val closed: Boolean,
  val feed: Boolean,
)

/**
 * [BlockProgress] for the item [id] of [tabs], or null when it's in no block with kept items (a source nothing was kept
 * from, a post on its own). [seen] says whether a kept item was seen since it was kept ([TabState.seenSinceKept]).
 */
internal fun blockProgress(tabs: List<TabState>, id: TabId, seen: (TabState) -> Boolean): BlockProgress? {
  val index = tabs.indexOfFirst { it.id == id }
  if (index < 0) return null
  val block = queueBlocks(tabs).firstOrNull { index in it.indices } ?: return null
  val kept = block.kept.filter { tabs[it].kind.isKept }
  if (kept.isEmpty()) return null
  val head = block.headIndex?.let(tabs::get)
  val first = tabs[block.kept.first()]
  val title = block.closedSource?.let { closedSourceName(it, first.tagNames) } ?: head?.title ?: return null
  return BlockProgress(
    title = title,
    position = kept.indexOf(index).takeIf { it >= 0 }?.plus(1),
    kept = kept.size,
    left = kept.count { !seen(tabs[it]) },
    closed = block.closedSource != null,
    feed = head?.kind == QueueKind.Feed,
  )
}

/** The chip's words: "3 / 8 · from Landscape" on a kept item, "Landscape · 5 left" (or "· all seen") on its head. */
internal fun blockChipText(progress: BlockProgress): String = when (val position = progress.position) {
  null -> "${progress.title} · ${leftText(progress.left)}"
  else -> "$position / ${progress.kept} · from ${progress.title}"
}

/** What a screen reader says for the chip: "Kept item 3 of 8 from Landscape, 5 left". */
internal fun blockChipSpoken(progress: BlockProgress): String = when (val position = progress.position) {
  null -> "${progress.title}, ${progress.kept} kept, ${leftText(progress.left)}"
  else -> "Kept item $position of ${progress.kept} from ${progress.title}, ${leftText(progress.left)}"
}

/** "5 left", or "all seen" at 0. */
internal fun leftText(left: Int): String = if (left == 0) "all seen" else "$left left"

/** How many kept items of the whole queue are left: kept, and not [seen] since they were kept. */
internal fun keptLeft(tabs: List<TabState>, seen: (TabState) -> Boolean): Int = tabs.count { it.kind.isKept && !seen(it) }

/**
 * The ids of the block [head] heads, or of the closed block that lost it, head first: what Shift+X closes (#502), in
 * one batch, so the kept posts close with their head rather than stay as a closed block. Empty when no such block is
 * in [tabs].
 */
internal fun blockIds(tabs: List<TabState>, head: TabId): List<TabId> {
  val block = queueBlocks(tabs).firstOrNull { it.head == head } ?: return emptyList()
  return block.indices.map { tabs[it].id }
}

/**
 * Closes the block of the item [id] (its head and kept items, or a closed source's kept posts) as one Recently closed
 * entry, which Z reopens: the list's Shift+X and the "Done with …?" offer's (#502). A feed's head stays; its kept
 * items close. Returns the entry and the block's title, or null when nothing closed.
 */
internal fun TabsController.closeBlock(id: TabId): Pair<ClosedTab, String>? {
  val all = tabs.value
  val head = blockHeadOf(all, id) ?: id
  val ids = blockIds(all, head)
  val title = all.firstOrNull { it.id == head }?.title
    ?: all.firstOrNull { it.id in ids }?.let { kept -> kept.closedSource?.let { closedSourceName(it, kept.tagNames) } }
    ?: return null
  return closeAll(ids)?.let { it to title }
}

/**
 * How the strip draws one tab's block (#502): [underlined] for a tab of a block with kept items, in the [block]th
 * such block's tone; [startsBlock] for the first tab of a block next to an underlined one, which stands a little apart;
 * [joinsNext] when the next tab is of the same underlined block, so the line runs on under the gap.
 */
internal data class StripBlock(val block: Int, val underlined: Boolean, val startsBlock: Boolean, val joinsNext: Boolean)

/** [StripBlock] for each of [tabs], in order. */
internal fun stripBlocks(tabs: List<TabState>): List<StripBlock> {
  val out = arrayOfNulls<StripBlock>(tabs.size)
  var ordinal = 0
  var previousUnderlined = false
  for (block in queueBlocks(tabs)) {
    val underlined = block.kept.isNotEmpty()
    val indices = block.indices
    indices.forEachIndexed { at, index ->
      out[index] = StripBlock(
        block = ordinal,
        underlined = underlined,
        startsBlock = at == 0 && (underlined || previousUnderlined),
        joinsNext = underlined && at < indices.lastIndex,
      )
    }
    if (underlined) ordinal++
    previousUnderlined = underlined
  }
  return out.map { it ?: StripBlock(0, underlined = false, startsBlock = false, joinsNext = false) }
}

/** The head id of the block the item [id] is in, or null when it isn't in [tabs]. */
internal fun blockHeadOf(tabs: List<TabState>, id: TabId): TabId? {
  val index = tabs.indexOfFirst { it.id == id }
  if (index < 0) return null
  return queueBlocks(tabs).firstOrNull { index in it.indices }?.head
}

/**
 * What moving from one block to another says (#502), worked out once the item shown changed: [Done] offers to close a
 * source whose kept items were all seen or closed, [QueueDone] says nothing kept is left anywhere, [None] says nothing.
 */
internal sealed interface LeavingBlock {
  data class Done(val head: TabId, val title: String, val seen: Int) : LeavingBlock
  data object QueueDone : LeavingBlock
  data object None : LeavingBlock
}

/**
 * What leaving the block headed [left] for the item [now] in [tabs] says: [left] had [hadKept] kept items while it was
 * shown and [leftBefore] kept items were left in the whole queue then. The queue being done ([keptLeft] now 0, more
 * than 0 before) wins; else a source block with kept items before and none left now, never [offered] before and not
 * a feed, is [LeavingBlock.Done]. Staying in the block says nothing.
 */
internal fun leavingBlock(
  tabs: List<TabState>,
  left: TabId?,
  hadKept: Boolean,
  leftBefore: Int,
  now: TabId,
  offered: Set<TabId>,
  seen: (TabState) -> Boolean,
): LeavingBlock {
  // Done wherever the move or close lands, its own block's head included (closing the queue's last item lands left).
  if (leftBefore > 0 && keptLeft(tabs, seen) == 0) return LeavingBlock.QueueDone
  if (left == null || blockHeadOf(tabs, now) == left) return LeavingBlock.None
  if (!hadKept || left in offered) return LeavingBlock.None
  val block = queueBlocks(tabs).firstOrNull { it.head == left && it.headIndex != null } ?: return LeavingBlock.None
  val head = tabs[block.headIndex!!]
  if (head.kind != QueueKind.Source) return LeavingBlock.None
  val kept = block.kept.map(tabs::get).filter { it.kind.isKept }
  if (kept.any { !seen(it) }) return LeavingBlock.None
  return LeavingBlock.Done(head.id, head.title, kept.size)
}
