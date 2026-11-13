package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.PageCursor
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A post a mark stands on (#314): its [post] id, its upload [time] (UTC seconds), and the page it came in on
 * ([cursor], [page]), so a jump can open there with the cursor or, when that's gone, with `date:` ([place]).
 */
@Serializable
data class FeedMark(val post: String, val time: Long, val cursor: String? = null, val page: Int = 1) {
  val place: GridPlace get() = GridPlace(post, cursor, page, time)
}

/**
 * The unread stretch a visit left: posts after [stop] (the deepest post reached among the posts that were new) down to
 * [until] (the newest post reached before them). [left] is how many unread posts it holds, when that was known. [since]
 * is when it opened (UTC seconds); [offered] once its 7-day offer has been shown. A gap merged with an older one holds
 * a stretch already read, [read] posts ending at [readEnd] (the older gap's stop mark), which [left] leaves out.
 */
@Serializable
data class FeedGap(
  val stop: FeedMark,
  val until: FeedMark,
  val left: Int? = null,
  val since: Long,
  val offered: Boolean = false,
  val read: Int = 0,
  val readEnd: FeedMark? = null,
) {
  /** The unread posts from index [from] of [posts] down to [until] (an index there): the read stretch left out. */
  internal fun unread(posts: List<FeedPost>, from: Int, until: Int): Int {
    val readBelow = readEnd != null && read > 0 && from < posts.size && posts[from].time > readEnd.time
    return (until - from - if (readBelow) read else 0).coerceAtLeast(0)
  }
}

/** One feed's marks: [top], the newest post reached; the [gap], if any; and when they were last set ([at], UTC s). */
@Serializable
data class FeedMarks(val top: FeedMark, val gap: FeedGap? = null, val at: Long, val reached: FeedReached = FeedReached())

/**
 * Upload times [from] to [to] (UTC seconds, inclusive) that were on screen together, one stretch of the feed, read at
 * [read] (UTC seconds) while Following showed [context] ([FeedReading.context]: its search and the followed tags).
 */
@Serializable
data class TimeSpan(val from: Long, val to: Long, val read: Long, val context: String)

/**
 * The Following posts reached (#539): their ids with upload times, and the stretches of the feed ([spans]) that were on
 * screen. A post inside a span that isn't among [posts], uploaded at most [LATE_WITHIN] before the span was read, while
 * Following shows what it showed then (the same search, the same follows), wasn't there then: it turned up late (tagged
 * or approved after it was uploaded), so it's new though it sits below posts already read. A post that appears because
 * Following shows more since (a tag followed, G only turned off) isn't. Keeps the newest [MAX_POSTS] posts; the spans
 * are cut above the newest one dropped, so a post below that isn't taken for a late one.
 */
@Serializable
data class FeedReached(val posts: Map<String, Long> = emptyMap(), val spans: List<TimeSpan> = emptyList()) {
  /**
   * Whether a post uploaded at [time], not among [posts], turned up late in a stretch read in [context] (Following as
   * it is now); none when that isn't known.
   */
  fun lateAt(time: Long, context: String?): Boolean = context != null && spans.any {
    it.context == context && time in it.from..it.to && it.read - time <= LATE_WITHIN.inWholeSeconds
  }

  /**
   * These, with [reached] (posts on screen together) added, read at [now] (UTC seconds) in [context]; with no [context]
   * (not known yet) the posts count as reached but make no stretch.
   */
  fun with(reached: List<FeedPost>, now: Long, context: String?): FeedReached {
    if (reached.isEmpty()) return this
    val added = posts + reached.associate { it.id to it.time }
    val span = context?.let { TimeSpan(reached.minOf { it.time }, reached.maxOf { it.time }, now, it) }
    var kept = added
    var merged = merge(spans + listOfNotNull(span))
    if (added.size > MAX_POSTS) {
      val sorted = added.entries.sortedByDescending { it.value }
      kept = sorted.take(MAX_POSTS).associate { it.key to it.value }
      // Above the newest post dropped: one dropped with the same time as a kept one isn't taken for a late one.
      val floor = sorted[MAX_POSTS].value + 1
      merged = merged.filter { it.to >= floor }.map { if (it.from < floor) it.copy(from = floor) else it }
    }
    return FeedReached(kept, merged.sortedByDescending { it.read }.take(MAX_SPANS))
  }

  companion object {
    /** About 20 KB in `feed-marks.json`: more than a few hours of a busy Following, where late posts turn up. */
    const val MAX_POSTS = 600

    /** The stretches kept, the last read first: a visit makes one or a few. */
    const val MAX_SPANS = 100

    /** How long after upload a post can turn up and count as late. */
    val LATE_WITHIN: Duration = 2.days

    /** Stretches read further apart than this stay apart, so each keeps its own [TimeSpan.read]. */
    val MERGE_WITHIN: Duration = 1.hours

    // Overlapping stretches read in the same context and close together become one, read when the later was.
    private fun merge(spans: List<TimeSpan>): List<TimeSpan> {
      val out = mutableListOf<TimeSpan>()
      for (span in spans.sortedBy { it.from }) {
        val at = out.indexOfLast { it.context == span.context && span.from <= it.to && abs(it.read - span.read) <= MERGE_WITHIN.inWholeSeconds }
        if (at >= 0) {
          val into = out[at]
          out[at] = into.copy(to = maxOf(into.to, span.to), read = maxOf(into.read, span.read))
        } else {
          out += span
        }
      }
      return out
    }
  }
}

/** A post of a feed's grid as the marks see it: its id, upload time (UTC seconds) and the page it came in on. */
data class FeedPost(val id: String, val time: Long, val cursor: PageCursor = PageCursor()) {
  val mark: FeedMark get() = FeedMark(id, time, cursor.next, cursor.number)
}

/** A full-span heading in a feed's grid. */
sealed interface FeedHeading {
  /**
   * "N new" over the posts newer than the newest post reached: [count] of them, or at least that many when [more]
   * (the newest post reached isn't loaded yet). [hasGap] when a gap is kept: the heading offers "Continue · N left",
   * [gapLeft] posts, or with no number when that isn't known. [late] of the [count] turned up below posts already read
   * (#539), the first at [firstLate]: the heading offers "N further down".
   */
  data class New(
    val count: Int,
    val more: Boolean,
    val gapLeft: Int?,
    val hasGap: Boolean = gapLeft != null,
    val late: Int = 0,
    val firstLate: Int? = null,
  ) : FeedHeading

  /** "You're caught up", where the new posts (or a gap) meet what was read before. */
  data object CaughtUp : FeedHeading

  /**
   * "You're caught up" at the top when nothing is new (#539), offering "Continue · N left" when a gap is kept
   * ([gapLeft], [hasGap] as in [New]) and the followed tags otherwise.
   */
  data class UpToDate(val gapLeft: Int?, val hasGap: Boolean = gapLeft != null) : FeedHeading

  /** "Continue · N left from last time" at the stop mark; [left] null when unknown. [offer]: the 7-day offer. */
  data class Continue(val left: Int?, val offer: Boolean) : FeedHeading
}

/** [heading] goes before the post at [before] in the grid's posts. */
data class PlacedHeading(val before: Int, val heading: FeedHeading)

/**
 * Following's marks (#314; spec Tabs → Queue → Feeds): where to put the headings, and when marks expire. Posts are
 * newest first by upload time, nearly (#160), so a mark is found by its id and, if that post is gone, by its time.
 */
object FeedReading {
  /** A For you mark is dropped after this; a Following one never is. */
  val FOR_YOU_KEPT: Duration = 3.days

  /** A Following gap this old is offered once: kept, or let go. */
  val GAP_OFFER_AFTER: Duration = 7.days

  /** How many new posts "N new" counts before it says "N+ new". */
  const val NEW_COUNT_LIMIT = 200

  fun expired(feed: Feed, marks: FeedMarks, now: Instant): Boolean =
    feed == Feed.ForYou && now - Instant.fromEpochSeconds(marks.at) > FOR_YOU_KEPT

  /**
   * The headings for [posts] (the grid's loaded posts) of [feed] given the [marks] a visit started with. [atTop] when
   * the grid starts at the top of the feed, not midway at a place, so the posts above the newest post reached are new.
   * For you isn't in time order, so it has none. [context] is Following's now ([context]), for its late posts.
   */
  fun headings(
    feed: Feed,
    posts: List<FeedPost>,
    atTop: Boolean,
    marks: FeedMarks?,
    now: Instant,
    context: String? = null,
  ): List<PlacedHeading> {
    if (feed != Feed.Following || marks == null || posts.isEmpty()) return emptyList()
    val headings = mutableListOf<PlacedHeading>()
    // A gap with no post left between its ends (found by time, both gone) is read: it shows nothing.
    val gap = marks.gap?.takeUnless { g ->
      val stopAfter = after(posts, g.stop)
      val until = at(posts, g.until)
      stopAfter != null && until != null && until <= stopAfter
    }
    val stopAfter = gap?.let { after(posts, it.stop) }
    val until = gap?.let { at(posts, it.until) }
    val left = if (gap != null && stopAfter != null && until != null) gap.unread(posts, stopAfter, until) else gap?.left
    if (atTop) {
      val top = at(posts, marks.top)
      val late = lateIndices(posts, atTop = true, marks, context)
      when {
        top == null -> headings += PlacedHeading(0, newHeading(posts.size, more = true, gap, left))
        top > 0 -> {
          headings += PlacedHeading(0, newHeading(top, more = false, gap, left, late))
          headings += PlacedHeading(top, FeedHeading.CaughtUp)
        }
        late.isNotEmpty() -> headings += PlacedHeading(0, newHeading(0, more = false, gap, left, late))
        else -> headings += PlacedHeading(0, FeedHeading.UpToDate(left, hasGap = gap != null))
      }
    }
    if (gap != null && stopAfter != null) {
      val offer = !gap.offered && now - Instant.fromEpochSeconds(gap.since) >= GAP_OFFER_AFTER
      headings += PlacedHeading(stopAfter, FeedHeading.Continue(left, offer))
      if (until != null && headings.none { it.before == until }) headings += PlacedHeading(until, FeedHeading.CaughtUp)
    }
    return headings.sortedBy { it.before }
  }

  private fun newHeading(count: Int, more: Boolean, gap: FeedGap?, left: Int?, late: List<Int> = emptyList()) =
    FeedHeading.New(count + late.size, more, left, hasGap = gap != null, late = late.size, firstLate = late.firstOrNull())

  /**
   * The ids of the posts of [posts] that turned up late (#539): below the newest post reached, inside a stretch read
   * before in the same [context] ([FeedReached.lateAt]), and not among the posts reached then. [atTop] as in
   * [headings]; a list opened midway is all below the newest post reached. Marks kept before #539 have no spans, and an
   * unknown [context] matches none, so they find none.
   */
  fun late(feed: Feed, posts: List<FeedPost>, atTop: Boolean, marks: FeedMarks?, context: String?): Set<String> {
    if (feed != Feed.Following || marks == null) return emptySet()
    return lateIndices(posts, atTop, marks, context).mapTo(mutableSetOf()) { posts[it].id }
  }

  /**
   * What Following shows (#539): its [searched] tags (the rating filter with them) and the ids of the [followed] tags.
   * A post appearing in a stretch read in another context may have been hidden then, not late.
   */
  fun context(searched: List<String>, followed: Collection<String>): String =
    searched.sorted().joinToString(" ") + "|" + followed.sorted().joinToString(",").hashCode().toUInt().toString(16)

  private fun lateIndices(posts: List<FeedPost>, atTop: Boolean, marks: FeedMarks, context: String?): List<Int> {
    val reached = marks.reached
    if (reached.spans.isEmpty() || posts.isEmpty()) return emptyList()
    val top = at(posts, marks.top)
    val below = when {
      top != null -> top + 1
      !atTop && posts.first().time <= marks.top.time -> 0
      else -> return emptyList()
    }
    return (below until posts.size).filter { i ->
      val post = posts[i]
      post.id != marks.top.post && post.id !in reached.posts && reached.lateAt(post.time, context)
    }
  }

  /** The index of [mark]'s post in [posts], or if it's gone, of the first post older than it; null if not loaded. */
  internal fun at(posts: List<FeedPost>, mark: FeedMark): Int? {
    val index = posts.indexOfFirst { it.id == mark.post }
    if (index >= 0) return index
    if (posts.isEmpty() || posts.last().time >= mark.time) return null
    return posts.indexOfFirst { it.time < mark.time }
  }

  /** The index of the first post after [mark]'s, or null if not loaded. */
  internal fun after(posts: List<FeedPost>, mark: FeedMark): Int? {
    val index = posts.indexOfFirst { it.id == mark.post }
    if (index >= 0) return (index + 1).takeIf { it < posts.size }
    return at(posts, mark)
  }
}

/**
 * One visit to a feed's grid (#314): from [start], the marks it opened with, it follows the posts the user reaches
 * ([reach]) and gives the [marks] to keep. The newest post reached only moves up, and reading a gap moves its stop mark
 * only down: scrolling back up or refreshing moves neither. Leaving part way through the new posts leaves a gap,
 * merged with one kept from before (its stop mark then the new one, up among the new posts); reading a gap through to
 * its end closes it. For you keeps only when it was read.
 */
class FeedVisit(
  private val feed: Feed,
  private val start: FeedMarks?,
  private val now: Instant,
  /** What Following shows ([FeedReading.context]), once known: the stretches reached are kept with it (#539). */
  var context: String? = null,
) {
  private var newest: FeedPost? = null

  // The deepest post reached among those newer than the start's top, and how many posts were then left above it.
  private var newDeepest: FeedPost? = null
  private var newLeft: Int? = null
  private var caughtUp = false

  // How many posts from the start's top down to its gap's stop mark were read before (a merge leaves them out).
  private var readStretch: Int? = null

  // The kept gap as read in this visit.
  private var gapDeepest: FeedPost? = null
  private var gapLeft: Int? = start?.gap?.left
  private var gapClosed = false

  private var offerShown = false
  private var answered = false
  private var letGo = false

  // The posts reached, with the start's (#539): Following only.
  private var reached = start?.reached ?: FeedReached()

  /**
   * The marks the visit's headings are drawn from: its start, with the 7-day offer's answer. An offer shown but not
   * answered stays on screen for the rest of the visit.
   */
  val shown: FeedMarks?
    get() = start?.let { s ->
      when {
        letGo -> s.copy(gap = null)
        answered -> s.copy(gap = s.gap?.copy(offered = true))
        else -> s
      }
    }

  /** The user reached [posts] from index [first] to [last]; [atTop] when the list starts at the feed's top. */
  fun reach(posts: List<FeedPost>, atTop: Boolean, first: Int, last: Int) {
    if (posts.isEmpty() || first > last) return
    val from = first.coerceIn(posts.indices)
    val to = last.coerceIn(posts.indices)
    // For you isn't in time order: any post reached marks it as read now.
    if (feed == Feed.ForYou && newest == null || atTop && newer(posts[from], newest)) newest = posts[from]
    if (feed == Feed.Following) reached = reached.with(posts.subList(from, to + 1), now.epochSeconds, context)
    if (start == null || feed != Feed.Following) return
    val deepest = posts[to]
    start.gap?.let { gap ->
      val top = FeedReading.at(posts, start.top)
      val stopAfter = FeedReading.after(posts, gap.stop)
      if (readStretch == null && top != null && stopAfter != null) readStretch = stopAfter - top
    }
    if (atTop && !caughtUp) {
      val top = FeedReading.at(posts, start.top)
      if (top != null && to >= top) {
        caughtUp = true
      } else if (newDeepest.let { it == null || older(deepest, it) }) {
        newDeepest = deepest
        newLeft = top?.let { it - to - 1 }
      }
    }
    val gap = start.gap ?: return
    if (gapClosed) return
    val until = FeedReading.at(posts, gap.until)
    // Reaching the gap's end closes it, in a list that starts above the end (one starting below it reached nothing).
    if (until != null && to >= until && posts.first().time > gap.until.time) {
      gapClosed = true
      return
    }
    val inGap = deepest.time < gap.stop.time && deepest.time > gap.until.time
    if (inGap && gapDeepest.let { it == null || older(deepest, it) }) {
      gapDeepest = deepest
      gapLeft = leftIn(posts, gap, to, until)
    }
  }

  /** The 7-day offer was shown: it isn't offered again in a later visit. Not answering it keeps the gap. */
  fun offerShown() {
    offerShown = true
  }

  /** The user kept the gap: the offer goes. */
  fun keep() {
    answered = true
    offerShown = true
  }

  /** The user let the gap go. */
  fun letGo() {
    letGo = true
    answered = true
    offerShown = true
  }

  /** The marks to keep after what was reached so far; null when nothing was reached and none were kept. */
  val marks: FeedMarks?
    get() {
      val top = newest?.takeIf { start == null || newer(it, start.top) }?.mark ?: start?.top ?: return null
      if (feed != Feed.Following) return FeedMarks(top, null, now.epochSeconds)
      if (start == null) return FeedMarks(top, null, now.epochSeconds, reached)
      var gap = start.gap
      if (letGo || gapClosed) gap = null
      gap = gap?.let { g ->
        gapDeepest?.let { g.copy(stop = it.mark, left = gapLeft) } ?: g
      }?.let { if (offerShown) it.copy(offered = true) else it }
      val opened = newest?.takeIf { newer(it, start.top) && !caughtUp }?.let { (newDeepest ?: it) }
      if (opened != null) {
        val left = if (opened == newDeepest) newLeft else null
        gap = if (gap == null) {
          FeedGap(stop = opened.mark, until = start.top, left = left, since = now.epochSeconds)
        } else {
          gap.copy(
            stop = opened.mark,
            left = if (left != null && gap.left != null) left + gap.left else null,
            read = gap.read + (readStretch ?: 0),
            readEnd = gap.readEnd ?: gap.stop,
          )
        }
      }
      return FeedMarks(top, gap, now.epochSeconds, reached)
    }

  private fun leftIn(posts: List<FeedPost>, gap: FeedGap, to: Int, until: Int?): Int? {
    if (until != null) return gap.unread(posts, to + 1, until)
    val stop = posts.indexOfFirst { it.id == gap.stop.post }
    val left = gap.left ?: return null
    return if (stop >= 0) (left - (to - stop)).coerceAtLeast(0) else null
  }

  private fun newer(post: FeedPost, than: FeedPost?): Boolean = than == null || post.time > than.time
  private fun newer(post: FeedPost, than: FeedMark): Boolean = post.id != than.post && post.time > than.time
  private fun older(post: FeedPost, than: FeedPost): Boolean = post.time < than.time
}
