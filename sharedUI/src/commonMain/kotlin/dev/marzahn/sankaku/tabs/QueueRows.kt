package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.isBooks
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.queue.QueueKind

/** A row of the queue page's list, above Recently closed (#177). */
internal sealed interface QueueRow {
  /**
   * A queue item at its [position] in the queue (from 0), as the pill counts it ([countedPosition]). [indented] is a
   * kept post under the block head above it, and [from] names that head for TalkBack ("artist_x", or "artist_x
   * (closed)"). [adult] is an item rated R15 or R18, or a post whose rating isn't known: its thumbnails blur, and G only
   * hides it. [line] is its part of the line drawn from a head down through its kept posts (#234), null when it has none.
   */
  class Item(
    val tab: TabState,
    val position: Int,
    val indented: Boolean,
    val adult: Boolean,
    val from: String? = null,
    val line: BlockLine? = null,
  ) : QueueRow

  /**
   * "from artist_x (closed)" over the kept posts a closed source left (#184), which are [Item]s indented under it. It
   * stands for the block's old head, [head]: moving it ([TabsController.moveInQueue]) moves the whole block.
   */
  class ClosedBlock(val head: TabId, val title: String, val count: Int) : QueueRow

  /** "Closed … · Undo" where the last close stood. */
  class Inline(val closed: ClosedTab) : QueueRow

  /** "Hidden by G only · N": the items G only leaves out. */
  class HiddenByGOnly(val count: Int) : QueueRow
}

/**
 * A row's part of the thin line from a block's head down through its listed kept posts (#234): it starts under the
 * [Head] (a source's row with kept posts listed under it; a [QueueRow.ClosedBlock] always starts one), runs [Through]
 * each kept post but the last, and stops at the [End].
 */
internal enum class BlockLine { Head, Through, End }

/**
 * The queue page's rows: the items in queue order, each block's kept posts indented under its head (a closed source's,
 * under its "from … (closed)" row). A feed is listed where it stands, with its kept posts under it, as a source is
 * (#327): its row says "Feed" and has no position. While [gOnly], R15 and R18 items ([isAdult]) are left out and
 * counted. With a [query] only the matching items show ([matchesFind]). [inline]'s row goes before the first item at
 * or past where it stood. [post] is what's known of a tab's post.
 */
internal fun queueRows(
  tabs: List<TabState>,
  closed: List<ClosedTab>,
  inline: ClosedTab?,
  query: String?,
  gOnly: Boolean,
  post: (TabState) -> TabPost?,
): List<QueueRow> {
  // [closed] holds its current index: tabs reopened since may have moved it.
  val inlineAt = inline?.let { closed.first { c -> c == it }.index } ?: 0
  val main = mutableListOf<QueueRow>()
  var unplaced = inline
  var hidden = 0
  // How many counted items stand before the one walked ([countedPosition]): a feed isn't one.
  var counted = 0
  // Whether the head of the block being walked was listed (else its posts aren't indented). A feed's block is its kept
  // posts (#265).
  var headListed = false
  // The kept posts a closed source left (#184) are a block under a row of its own, listed with its first listed post.
  var closedRow: QueueRow.ClosedBlock? = null
  var closedRowListed = false
  // What the listed head of the block being walked is called, for its kept posts' "Kept from …" (#234).
  var headName: String? = null
  for ((index, tab) in tabs.withIndex()) {
    val position = counted
    if (tab.kind != QueueKind.Feed) counted++
    val closedSource = tab.closedSource
    val member: Boolean
    if (closedSource != null) {
      member = true
      val previous = tabs.getOrNull(index - 1)
      if (previous?.closedSource == null || previous.block != tab.block) {
        val count = tabs.drop(index).takeWhile { it.closedSource != null && it.block == tab.block }.size
        closedRow = QueueRow.ClosedBlock(tab.head, closedBlockTitle(closedSource, tab.tagNames), count)
        closedRowListed = false
      }
    } else {
      member = tab.block != null && index > 0 && tabs[index - 1].head == tab.block
      if (!member) headListed = false
    }
    val known = post(tab)
    val adult = isAdult(tab, known)
    if (gOnly && adult) {
      hidden++
      continue
    }
    if (query != null && !matchesFind(query, tab, known)) continue
    val indented = closedSource != null || (member && headListed)
    val from = when {
      closedSource != null -> closedSourceName(closedSource, tab.tagNames)
      indented -> headName
      else -> null
    }
    val item = QueueRow.Item(tab, position, indented = indented, adult = adult, from = from)
    if (!member) {
      headListed = true
      headName = tab.title
    }
    if (unplaced != null && index >= inlineAt) {
      main += QueueRow.Inline(unplaced)
      unplaced = null
    }
    if (closedSource != null && !closedRowListed) {
      closedRow?.let { main += it }
      closedRowListed = true
    }
    main += item
  }
  if (unplaced != null) main += QueueRow.Inline(unplaced)
  if (hidden > 0) main += QueueRow.HiddenByGOnly(hidden)
  return withBlockLines(main)
}

/**
 * Each item's part of its block's line ([BlockLine]): a head with a kept post listed next starts it, and a kept post
 * carries it on while the next listed row is one too. The inline Undo row draws none and doesn't end a block.
 */
private fun withBlockLines(rows: List<QueueRow>): List<QueueRow> {
  val kept = rows.map { it is QueueRow.Item && it.indented }
  val listed = rows.indices.filter { rows[it] !is QueueRow.Inline }
  val nextKept = BooleanArray(rows.size)
  listed.zipWithNext { at, next -> nextKept[at] = kept[next] }
  return rows.mapIndexed { index, row ->
    if (row !is QueueRow.Item) return@mapIndexed row
    val line = when {
      row.indented -> if (nextKept[index]) BlockLine.Through else BlockLine.End
      nextKept[index] -> BlockLine.Head
      else -> null
    }
    if (line == null) row else QueueRow.Item(row.tab, row.position, row.indented, row.adult, row.from, line)
  }
}

/**
 * A Recently closed row (#250): the entry, and the member whose thumbnail it shows, [shown] (a post's preview, a grid's
 * mosaic). [adult] is whether that member is R15 or R18 ([isAdult]), so its picture blurs as a queue row's does.
 */
internal class ClosedRow(val closed: ClosedTab, val shown: ClosedTab.Member, val adult: Boolean)

/** Recently closed as listed ([rows], the most recent first) and how many entries G only leaves out ([hidden]). */
internal data class ClosedRows(val rows: List<ClosedRow>, val hidden: Int)

/**
 * Recently closed's [ClosedRows]. An entry shows its first member's thumbnail (a batch's, or the first of what Clear
 * viewed cleared). While [gOnly], an R15 or R18 entry is left out as on the queue page, and a batch shows its first G
 * member, or is left out if it has none. [post] is what's known of a post route's post.
 */
internal fun closedRows(closed: List<ClosedTab>, gOnly: Boolean, post: (Route.Post) -> TabPost?): ClosedRows {
  var hidden = 0
  val rows = closed.asReversed().mapNotNull { entry ->
    val adult = { member: ClosedTab.Member -> isAdult(member.top, post) }
    val shown = if (gOnly) entry.members.firstOrNull { !adult(it) } else entry.members.first()
    if (shown == null) {
      hidden++
      null
    } else {
      ClosedRow(entry, shown, adult(shown))
    }
  }
  return ClosedRows(rows, hidden)
}

/** The post ids [closed] shows, for what's known of them ([TabPosts]). */
internal fun closedPosts(closed: List<ClosedTab>): Set<String> =
  closed.flatMapTo(mutableSetOf()) { entry -> entry.members.mapNotNull { (it.top as? Route.Post)?.id } }

/**
 * Whether [tab] is an R15 or R18 item: a post rated so (by its route, else by [post]), or whose rating isn't known, so
 * a post opened from a parent link or saved before #177 is blurred and hidden until it's known; a grid whose search
 * lets no G post through (`rating:e`). Any other grid is G: with G only on, it searches G only (#164).
 */
internal fun isAdult(tab: TabState, post: TabPost?): Boolean = isAdult(tab.top) { post }

/** [isAdult] for a [top] route, open or closed. */
internal fun isAdult(top: Route?, post: (Route.Post) -> TabPost?): Boolean = when (top) {
  is Route.Post -> postRating(top, post(top)) != Rating.G
  is Route.Grid -> Rating.G !in searchRatings(top.tags)
  is Route.Books -> Rating.G !in searchRatings(top.tags)
  is Route.BookSeries -> Rating.G !in searchRatings(top.tags)
  // A kept book by the rating its row had (#385); one not known counts as R15 or R18, as a post's does.
  is Route.Book -> Rating.ofCode(top.rating) != Rating.G
  null -> false
}

/** A post tab's rating: its route's, else what [post] (a loaded copy) says; null when neither knows, or not a post. */
internal fun postRating(tab: TabState, post: TabPost?): Rating? = (tab.top as? Route.Post)?.let { postRating(it, post) }

private fun postRating(top: Route.Post, post: TabPost?): Rating? = Rating.ofCode(top.rating ?: post?.rating)

/** The ratings a search lets through: every rating tag narrows them (`rating:q`, `-rating:s`). */
internal fun searchRatings(tags: List<String>): Set<Rating> =
  tags.fold(Rating.entries.toSet()) { ratings, tag -> Rating.of(tag)?.let(ratings::intersect) ?: ratings }

/**
 * A row's second line: the item's kind, then "place kept" for a source scrolled into its grid (else how many posts it
 * has loaded), "from Landscape" for a search changed on that grid and opened as a new item ([TabState.refinedFrom]), "seen" for a kept post [seen] since it was kept, its [rating] if it's R15 or R18, and "current". Given
 * the source a kept post is listed under ([QueueRow.Item.from]), it says "Kept from artist_x": what TalkBack reads,
 * since the indent and the line show it on screen (#234).
 */
internal fun queueSubtitle(tab: TabState, current: Boolean, seen: Boolean, rating: Rating?, from: String? = null): String {
  val parts = mutableListOf<String>()
  when (tab.kind) {
    QueueKind.Source -> {
      // A Books search (#385) or a grid.
      parts += if (tab.top?.isBooks == true) BOOKS_TITLE else "Grid"
      // The filters the title leaves out (#233): "Grid · popular".
      (tab.top as? Route.Grid)?.let { parts += filterNotes(it.tags) }
      // As saved for a source not shown since launch (#232), so it reads as it did before the relaunch.
      val loaded = tab.mosaic?.posts?.takeIf { it > 0 }
      if (tab.place != null) parts += "place kept" else if (loaded != null) parts += "$loaded posts"
      // A search changed on a grid, opened as a new item (#509): "from Landscape".
      tab.refinedFrom?.let { parts += "from $it" }
    }
    QueueKind.Feed -> parts += "Feed"
    QueueKind.KeptPost -> {
      parts += if (from != null) "Kept from $from" else "Kept"
      if (seen) parts += "seen"
    }
    QueueKind.KeptBook -> {
      parts += if (from != null) "Kept book from $from" else "Kept book"
      if (seen) parts += "seen"
    }
    QueueKind.Other -> parts += "Item"
  }
  if (rating != null && rating != Rating.G) parts += rating.badge
  if (current) parts += "current"
  return parts.joinToString(" · ")
}
