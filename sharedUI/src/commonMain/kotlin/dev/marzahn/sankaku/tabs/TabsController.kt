package dev.marzahn.sankaku.tabs

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.compose.runtime.snapshotFlow
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.CLOSE_LABEL
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.data.PostTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import dev.marzahn.sankaku.queue.Fading
import dev.marzahn.sankaku.queue.FeedItems
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.queue.SessionClock
import dev.marzahn.sankaku.queue.SessionId
import dev.marzahn.sankaku.queue.SourceKey
import dev.marzahn.sankaku.queue.UpNext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class TabId(val value: String)

/**
 * A tab's back stack and the view models of its entries, which live until the entry is popped or the tab closes:
 * one store per entry ([entryOwner]), kept here rather than in the composition, so switching tabs keeps them.
 */
class TabState(
  val id: TabId,
  routes: List<Route>,
  block: TabId? = null,
  place: GridPlace? = null,
  addedIn: SessionId = SessionId.EARLIER,
  touched: Instant = Instant.fromEpochSeconds(0),
  keptAt: Instant? = null,
  closedSource: List<String>? = null,
  /** The loaded title of a post by its id ([TabsController.loadedTitle]); a post route with no label is named by it. */
  private val loadedTitles: (String) -> String? = { null },
  mosaic: QueueMosaic? = null,
  /** Whether the top grid was touched ([topIsTouchedGrid]), as saved. */
  gridTouched: Boolean = false,
  /** The tag chips' names a grid's title is made of ([TabsController.tagNames], #246). */
  internal val tagNames: TagNameLookup = NoTagNames,
  openedFrom: TabId? = null,
) : ViewModelStoreOwner {
  val backStack = NavBackStack<NavKey>(*routes.toTypedArray())

  /**
   * For a grid a search opened "now" from another item (#349): that item, which Back on the grid returns to, closing
   * the grid ([TabsController.openerOf]). Null for any other tab, and for tabs saved before #349. Snapshot state, so
   * it's saved like a navigation.
   */
  var openedFrom: TabId? by mutableStateOf(openedFrom)
    internal set

  /**
   * For a kept post left behind when its source closed (#184): the closed source's search. Its block is then
   * "from artist_x (closed)", and a source opened on the same search ([SourceKey]) takes the block back. Null for any
   * other tab. Snapshot state, so it's saved like a navigation.
   */
  var closedSource: List<String>? by mutableStateOf(closedSource)
    internal set

  /** What this tab is as a queue item, from its top route; it follows navigation. */
  val kind: QueueKind get() = queueKind(top)

  /** For a source, the key that makes it one source per search ([SourceKey]); null for any other kind. */
  val sourceKey: SourceKey? get() = (top as? Route.Grid)?.takeIf { kind == QueueKind.Source }?.let { SourceKey.of(it.tags) }

  /** For a feed, which one (Following, For you): one item per feed, whatever its rating filter; null for any other kind. */
  val feed: Feed? get() = (top as? Route.Grid)?.takeIf { kind == QueueKind.Feed }?.let { QueueKind.feedOf(it.tags) }

  /** The session this item joined the queue in ([SessionClock]). */
  var addedIn: SessionId = addedIn
    internal set

  /**
   * When the tab was last opened, scrolled or acted on ([TabsController.touch]). Snapshot state, so a touch is saved
   * like a navigation.
   */
  var touched: Instant by mutableStateOf(touched)
    private set

  // When the post on top was kept, and the post route it's for: a post opened over it in the same tab is kept anew.
  private var kept by mutableStateOf((routes.lastOrNull() as? Route.Post)?.let { post -> keptAt?.let { post to it } })

  /** For a kept post, when it was kept or last kept again; null for any other kind. */
  val keptAt: Instant? get() = (top as? Route.Post)?.let { post -> kept?.takeIf { it.first == post }?.second }

  /** The tab was opened, scrolled or acted on at [now]. A post newly on top is kept from now. */
  internal fun touch(now: Instant) {
    touched = now
    val post = top as? Route.Post ?: return
    if (kept?.first != post) kept = post to now
  }

  /** "Kept again": the post on top is kept anew at [now], so it's unseen since it was kept. */
  internal fun keepAgain(now: Instant) {
    touched = now
    (top as? Route.Post)?.let { kept = it to now }
  }

  /**
   * For a kept post, whether it was seen (or an exact copy of it) after it was kept or last kept again
   * ([SeenStore.seenSince]); false for any other kind. Clear viewed removes these; Mark unseen and "Kept again" end it.
   */
  fun seenSinceKept(seen: SeenStore): Boolean {
    val post = top as? Route.Post ?: return false
    val at = keptAt ?: return false
    return seen.seenSince(post.id, post.md5, at)
  }

  // The place and the grid route it was reported for. Snapshot state, so a new place is saved like a navigation.
  private var placed by mutableStateOf((routes.lastOrNull() as? Route.Grid)?.let { grid -> place?.let { grid to it } })

  /**
   * Where the top grid was scrolled to (#162): saved with the tab, and where its grid opens when it's shown with no
   * view model (a restored or reopened tab). Null at the top, and while the top entry isn't the grid it was for.
   */
  val place: GridPlace? get() = (top as? Route.Grid)?.let(::placeFor)

  /** Called by the grid [route] as its place changes; null at the top. */
  fun reportPlace(route: Route.Grid, place: GridPlace?) {
    placed = place?.let { route to it }
  }

  /** The saved place for the grid [route], if it's the one the place is for. */
  fun placeFor(route: Route.Grid): GridPlace? = placed?.takeIf { it.first == route }?.second

  // The mosaic and the grid route it was reported for. Snapshot state, so a new one is saved like a navigation.
  private var mosaicFor by mutableStateOf((routes.lastOrNull() as? Route.Grid)?.let { grid -> mosaic?.let { grid to it } })

  /**
   * What the queue page shows for the top grid (#232): its thumbnails and loaded count, as its screen last reported
   * them, or as saved for a tab not shown since launch. Null before the grid loaded posts, and while the top entry
   * isn't the grid it was for.
   */
  val mosaic: QueueMosaic? get() = (top as? Route.Grid)?.let { grid -> mosaicFor?.takeIf { it.first == grid }?.second }

  /** Called by the grid [route] as what it shows changes, once it has posts. */
  fun reportMosaic(route: Route.Grid, mosaic: QueueMosaic) {
    mosaicFor = route to mosaic
  }

  /**
   * The tab heading the block this tab was opened into (a post opened in the background from a grid, or from a post
   * of that grid's block), or null when it heads a block of its own. A block's tabs follow their head in the strip.
   * Snapshot state, so a grid's seen mark ([TabsController.seenFromGrid]) follows a post leaving or joining its block.
   */
  var block: TabId? by mutableStateOf(block)
    internal set

  /** The tab heading the block this tab is in: [block], or this tab itself. */
  val head: TabId get() = block ?: id
  override val viewModelStore = ViewModelStore()
  private val entryStores = mutableMapOf<Any, ViewModelStore>()

  /** The view model store of the back stack entry [contentKey], created on first use. */
  fun entryOwner(contentKey: Any): ViewModelStoreOwner {
    val store = entryStores.getOrPut(contentKey) { ViewModelStore() }
    return object : ViewModelStoreOwner {
      override val viewModelStore = store
    }
  }

  /** Clears the view models of the entry [contentKey], once it's popped. */
  fun clearEntry(contentKey: Any) {
    entryStores.remove(contentKey)?.clear()
    described.remove(contentKey)
  }

  /** Clears every view model of the tab, when it closes. */
  fun clear() {
    entryStores.values.forEach { it.clear() }
    entryStores.clear()
    described.clear()
    viewModelStore.clear()
  }

  // The grids of the back stack that were touched ([markTouched]). Kept here, not in the grid's screen, so a grid out
  // of composition (under the queue page) stays touched (#245). Snapshot state, so a touch is saved like a navigation.
  private var touchedGrids by mutableStateOf(setOfNotNull((routes.lastOrNull() as? Route.Grid)?.takeIf { gridTouched }))

  /**
   * The grid [route] was scrolled, a post was opened from it (the viewer or kept), or it was restored at its place.
   * Scrolling back to the top doesn't undo it.
   */
  fun markTouched(route: Route.Grid) {
    if (route in touchedGrids) return
    touchedGrids = touchedGrids.filterTo(mutableSetOf()) { it in backStack } + route
  }

  /**
   * Whether the grid [route] of this tab was touched ([markTouched]), or has a place: a grid kept at its place
   * (restored, reopened, or found again after a relaunch) counts as touched before it has scrolled there.
   */
  fun isTouched(route: Route.Grid): Boolean = route in touchedGrids || placeFor(route) != null

  /**
   * Whether the top entry is a grid the user has scrolled, opened a post from, or that was restored at its place. A
   * search changed on a touched grid opens a new tab ([TabsController.search]); an untouched one is replaced in place.
   * Saved with the tab, so a relaunch keeps it (#245).
   */
  val topIsTouchedGrid: Boolean
    get() = (top as? Route.Grid)?.let(::isTouched) == true

  /** What `tabs.json` says of [topIsTouchedGrid]: true only where the place doesn't say it already, else left out. */
  internal val savedTouched: Boolean? get() = true.takeIf { topIsTouchedGrid && place == null }

  /** Back within the tab: the top entry goes, and its touched flag with it, so the same grid opened again is untouched. */
  internal fun pop() {
    forget(backStack.removeAt(backStack.lastIndex))
  }

  /** The top entry becomes [route]; the one it replaces is forgotten as [pop] forgets it. */
  internal fun replaceTop(route: Route) {
    forget(backStack.set(backStack.lastIndex, route))
  }

  private fun forget(route: NavKey) {
    if (route in touchedGrids && route !in backStack) touchedGrids = touchedGrids - (route as Route.Grid)
    if (route in coveringRoutes && route !in backStack) coveringRoutes = coveringRoutes - (route as Route)
  }

  // The entries whose viewer covers the shell's chrome ([coverChrome]). Kept here, not in the viewer, so the shell
  // knows it in the first frame of a grid shown again with its viewer open (its tab back in front, the queue page
  // closed), before the viewer composes (#287). Snapshot state, read by the shell in composition.
  private var coveringRoutes by mutableStateOf(emptySet<Route>())

  /**
   * Touch: whether the top entry covers the shell's chrome (the top bar and the count pill). A lone post covers it
   * from its first frame, so the floating pill never flashes up as the post comes forward; a grid's viewer covers it
   * as `CoverChrome` reports it. Another tab, or another entry on top, isn't covered by it.
   */
  val chromeCovered: Boolean get() = top is Route.Post || top in coveringRoutes

  /** Called by the entry [route] as its viewer starts or stops covering the shell's chrome. */
  fun coverChrome(route: Route, covers: Boolean) {
    if (covers != (route in coveringRoutes)) coveringRoutes = if (covers) coveringRoutes + route else coveringRoutes - route
  }

  // What each entry shown so far holds ([DescribeTab]), by content key. Only touched on the UI thread.
  private val described = mutableMapOf<Any, Pair<Route, () -> TabContents>>()

  /** Called by the entry [contentKey] showing [route] each time it composes. */
  fun describe(contentKey: Any, route: Route, contents: () -> TabContents) {
    described[contentKey] = route to contents
  }

  /** What the top entry holds, or null if it was never shown (a background or restored tab). */
  fun topContents(): TabContents? = described.values.lastOrNull { it.first == top }?.second?.invoke()

  /** What every entry shown so far holds. */
  fun allContents(): List<TabContents> = described.values.map { it.second() }

  val top: Route? get() = backStack.lastOrNull() as? Route

  /** The title the top post was named by once it loaded, if its route carries no label; null for anything else. */
  internal val loadedTitle: String? get() = loadedTitleOf(top, loadedTitles)

  val title: String get() = tabTitle(top, loadedTitle, tagNames)

  val untitled: Boolean get() = tabIsUntitled(top, loadedTitle)
}

private fun loadedTitleOf(top: Route?, loaded: (String) -> String?): String? =
  (top as? Route.Post)?.takeIf { it.label == null }?.let { loaded(it.id) }

/**
 * The queue kind of a tab showing [top] (#174): a grid is a source or a feed, a post is a kept post, and Settings
 * (desktop, #205) is other.
 */
internal fun queueKind(top: Route?): QueueKind = when (top) {
  is Route.Grid -> QueueKind.ofGrid(top.tags)
  is Route.Post -> QueueKind.KeptPost
  Route.Settings, null -> QueueKind.Other
}

/**
 * Every post is a lone post from #181, with no grid in its back stack. Tabs saved before it with routes under a post
 * keep only the post. What's under it is dropped when it's a copy (a post opened in the background was put over a
 * copy of its grid, marked by [Route.Grid.at]) or a search another tab shows already. The grid a post was tapped open
 * from in place (a recommended post, a parent), which no other tab holds, would be lost, so it's split out as a tab of
 * its own, just before the post, which joins its block.
 */
internal fun loneAtLaunch(saved: List<SavedTab>, newId: () -> String = { Uuid.random().toString() }): List<SavedTab> {
  fun searchOf(route: Route?) = (route as? Route.Grid)?.let { SourceKey.of(it.tags) ?: it.tags }
  val searches = saved.filter { it.routes.last() is Route.Grid }.mapTo(mutableSetOf()) { searchOf(it.routes.last()) }
  // A post split from its grid no longer heads its block: the tabs in that block follow the post into the grid's.
  val heads = mutableMapOf<String, String>()
  val lone = saved.flatMap { tab ->
    val post = tab.routes.last() as? Route.Post
    if (post == null || tab.routes.size == 1) return@flatMap listOf(tab)
    val under = tab.routes[tab.routes.lastIndex - 1] as? Route.Grid
    val keep = under != null && under.at == null && searches.add(searchOf(under))
    if (!keep) return@flatMap listOf(tab.copy(routes = listOf(post)))
    val grid = tab.copy(id = newId(), routes = tab.routes.dropLast(1), kind = queueKind(under), keptAt = null)
    val block = tab.block ?: grid.id
    heads[tab.id] = block
    listOf(grid, tab.copy(routes = listOf(post), block = block))
  }
  return lone.map { tab -> tab.block?.let(heads::get)?.let { tab.copy(block = it) } ?: tab }
}

/** A tab's label by the route it shows. [loaded] names a post route with no label once the post has loaded. */
internal fun tabTitle(
  top: Route?,
  loaded: String? = null,
  names: TagNameLookup = NoTagNames,
  touch: Boolean = platformIsTouch,
): String = when (top) {
  // The content tags by their chips' names, never the filter tokens: a tag opened by popularity is named by the tag
  // (#233, #246, [sourceTitle]).
  is Route.Grid -> sourceTitle(top.tags, names)
  // [PostTitle] when it was opened; a post opened without its tags (a parent link) has it once it loads (#156).
  is Route.Post -> top.label ?: loaded ?: PostTitle.UNTITLED
  Route.Settings -> SETTINGS_TITLE
  // The phone has no tabs (#249).
  null -> if (touch) "Item" else "Tab"
}

/** Settings' title: its tab's, its page's and its screen's heading (#205). */
internal const val SETTINGS_TITLE = "Settings"

/** A batch of [size] closed together: "12 items" on a phone, which has no tabs (#249), "12 tabs" on desktop. */
internal fun batchTitle(size: Int, touch: Boolean = platformIsTouch): String = if (touch) "$size items" else "$size tabs"

/**
 * How many of [tabs] the count pill's "n / N" and the queue page's header count (#229): every item but the feeds
 * ([TabsController.pinFeeds]); a counted item's position is [countedPosition].
 */
internal fun countedItems(tabs: List<TabState>): Int = tabs.count { it.kind != QueueKind.Feed }

/**
 * The position (from 1) the count pill and the queue page give the item at [index] of [tabs]: how many counted items
 * ([countedItems]) stand up to it, so an item after a feed, which isn't counted, is one place lower than its index. A
 * feed's own is the counted item it stands after, and is never shown.
 */
internal fun countedPosition(tabs: List<TabState>, index: Int): Int =
  tabs.subList(0, (index + 1).coerceIn(0, tabs.size)).count { it.kind != QueueKind.Feed }

/** Whether an item of [kind] is what its kept posts were kept from: a source, or a feed (#265). */
internal fun headsKeptPosts(kind: QueueKind): Boolean = kind == QueueKind.Source || kind == QueueKind.Feed

/** " · 30 kept stay" when closing a source leaves [kept] kept posts (#184), else nothing. */
internal fun keptStaySuffix(kept: Int): String = if (kept > 0) " · $kept kept stay" else ""

/**
 * The queue page's words on closing [tab]: "Close artist_x · 30 kept stay", or "Close" for anything but a source with
 * kept posts (the phone has no tabs, #249).
 */
internal fun closeLabel(tab: TabState, keptStay: Int): String =
  if (keptStay > 0 && headsKeptPosts(tab.kind)) "Close ${tab.title}${keptStaySuffix(keptStay)}" else CLOSE_LABEL

/** A closed source's block: "from artist_x (closed)", named by its search ([TabState.closedSource]). */
internal fun closedBlockTitle(source: List<String>, names: TagNameLookup = NoTagNames): String =
  "from ${closedSourceName(source, names)}"

/** A closed source as its kept posts name it: "artist_x (closed)". */
internal fun closedSourceName(source: List<String>, names: TagNameLookup = NoTagNames): String =
  "${tabTitle(Route.Grid(source), names = names)} (closed)"

/** Whether [tabTitle] is the placeholder [PostTitle.UNTITLED], which is drawn in the variant colour. */
internal fun tabIsUntitled(top: Route?, loaded: String? = null): Boolean = top is Route.Post && top.label == null && loaded == null

/**
 * A closed tab as [TabsController.reopenClosed] brings it back: its back stack and where it stood in the strip. A bulk
 * close ([TabsController.closeAll]) makes one of these for the whole batch: its [members], each with its own place,
 * come back together. [TabsController.clearViewed] makes one too, [cleared]: "N posts · cleared".
 */
class ClosedTab internal constructor(
  members: List<Member>,
  /**
   * The member that was the active tab, which becomes active again on reopening. A single closed tab records it too,
   * for an Undo that reopens in place ([TabsController.reopen], #306).
   */
  internal val activeMember: Int? = null,
  /** Unique for the session, for list keys: two closed tabs may hold the same routes. */
  val key: Long = nextKey++,
  /** Whether Clear viewed made this entry, of any size (even 1): it's "N posts" and none of them becomes active. */
  val cleared: Boolean = false,
  /** How many kept posts a closed source left behind, as a block "from … (closed)" (#184); 0 for anything else. */
  val keptStay: Int = 0,
  /** The tag chips' names a closed grid's title is made of ([TabsController.tagNames], #246). */
  private val tagNames: TagNameLookup = NoTagNames,
) {
  /**
   * A closed tab's back stack and where it stood in the strip, counting the batch's other members still in it. [id],
   * [block] and [addedIn] put it back in its block and its session ([TabsController.reopen]), [place] at its place,
   * and [keptAt] keeps a kept post's time, so reopening isn't keeping it again.
   */
  internal data class Member(
    val routes: List<Route>,
    val index: Int,
    val id: TabId? = null,
    val block: TabId? = null,
    val addedIn: SessionId = SessionId.EARLIER,
    val place: GridPlace? = null,
    val keptAt: Instant? = null,
    val closedSource: List<String>? = null,
    /** The title its post was named by once it loaded ([TabState.loadedTitle]), for a post route with no label. */
    val title: String? = null,
    /** Whether its top grid was touched beyond what its place says ([TabState.savedTouched]), #245. */
    val gridTouched: Boolean = false,
    /** Its top grid's mosaic ([TabState.mosaic], #232): Recently closed shows it (#250), and it comes back with the tab. */
    val mosaic: QueueMosaic? = null,
    /** The item it was opened from ([TabState.openedFrom], #349), so Back on it after Undo returns there again. */
    val openedFrom: TabId? = null,
  ) {
    /** The route on top of its back stack. */
    val top: Route? get() = routes.lastOrNull()
  }

  /**
   * The tabs it brings back. Only [adopt] changes them, in place: a closed tab equals another by [key], so a list of
   * changed copies would be equal to the old one and [TabsController.closed] wouldn't take it.
   */
  internal var members: List<Member> = members
    private set

  /** How many tabs this brings back: 1, or a batch's size. */
  val size: Int get() = members.size

  /** The back stack of the (first) tab. */
  val routes: List<Route> get() = members.first().routes

  /** Where the (first) tab stood. */
  internal val index: Int get() = members.first().index

  val top: Route? get() = routes.lastOrNull()

  /** The tab's title, [batchTitle] for a batch ("12 tabs"), or "12 posts" for what Clear viewed [cleared]. */
  val title: String
    get() = when {
      cleared -> if (size == 1) "1 post" else "$size posts"
      size == 1 -> tabTitle(top, members.first().title, tagNames)
      else -> batchTitle(size)
    }

  /** Whether [title] is the placeholder "Untitled post"; a batch's never is. */
  val untitled: Boolean get() = size == 1 && !cleared && tabIsUntitled(top, members.first().title)

  /** The same closed tab with each member's place mapped by [place]. */
  internal fun moved(place: (Int) -> Int) =
    ClosedTab(members.map { it.copy(index = place(it.index)) }, activeMember, key, cleared, keptStay, tagNames)

  /**
   * The same closed tab with each member's block head mapped by [ids] (a head reopened under a new id). A member whose
   * head came back is in a live block, so it's no longer "from … (closed)" (#184).
   */
  internal fun renamed(ids: Map<TabId, TabId>) = ClosedTab(
    members.map { member ->
      // The item it was opened from came back under a new id (#349).
      val opener = member.openedFrom?.let(ids::get)?.let { member.copy(openedFrom = it) } ?: member
      val head = opener.block?.let(ids::get) ?: return@map opener
      opener.copy(block = head, closedSource = null)
    },
    activeMember, key, cleared, keptStay, tagNames,
  )

  /** The kept posts of the closed source [from] are now in the block of [to] (#184): reopening this brings them back so. */
  internal fun adopt(from: TabId, to: TabId) {
    members = members.map { if (it.block == from && it.closedSource != null) it.copy(block = to, closedSource = null) else it }
  }

  /** "Closed “artist_x”": what the close names. */
  val closedLabel: String get() = if (size == 1) "Closed “$title”" else "Closed $size"

  /** What the close says: "Closed “artist_x” · 30 kept stay" for a source that left kept posts, else the title alone. */
  val closedText: String get() = closedLabel + keptStaySuffix(keptStay)

  // Equal by key, so a copy with a moved index is still the same closed tab.
  override fun equals(other: Any?) = other is ClosedTab && other.key == key

  override fun hashCode() = key.hashCode()

  private companion object {
    // Closed tabs are only made on the UI thread.
    var nextKey = 0L
  }
}

/**
 * The open tabs, in order. They start as [store] saved them (one Browse tab if nothing usable is saved), and every
 * change to the tabs, their back stacks or the active tab is saved [SAVE_DELAY] after the last one, in [scope]. A
 * restored tab holds only its routes until it's shown, like a background tab.
 *
 * **Where a new tab goes (#161): a tap means "now", a new tab means "later".** A *block* is a tab and the run of tabs
 * opened into it ([TabState.block]), which follow it.
 * - A post opened in the background goes at the end of the active tab's block ([openInBlock]).
 * - A search opened in place goes in front of the active tab's block and becomes active ([search], [openInFront]),
 *   unless the active tab shows an untouched grid, which it replaces. Moving on past its block lands back on the tab
 *   that was active in the block it was opened from ([step]).
 * - Anything else opened as a new tab goes at the end of this session's tabs, before the tabs restored at launch
 *   ([open]).
 * - **One source per search (#174):** a search whose source is already open ([sourceFor]) goes to it instead.
 * - **A closed source keeps its kept posts (#184):** they stay as a block ([TabState.closedSource]), and a source opened
 *   on the same search goes in front of them and heads them again ([closedBlock]).
 * - **A feed is a source that persists (#229, #327):** signed in, Following (and For you, if the setting is on) is
 *   always there as a feed item: not counted, never closed, never faded ([pinFeeds]). It goes at the end when it's
 *   added and is an ordinary item from then on, with the posts kept from it in its block (#265): placement around it
 *   follows the rules above, and nothing keeps it last.
 *
 * Each tab is a queue item ([TabState.kind]) that records when it was touched, the session it joined in ([sessions])
 * and, for a kept post, when it was kept; all of it is saved with the tabs (`tabs.json` v2, [FileTabStateStore]).
 *
 * **Fading (#215):** [sweep] moves the sources untouched for 3 days, with their unseen kept posts, out of the tabs into
 * [faded], and deletes the faded ones 30 days after they faded ([Fading]). It runs as the app launches and when the
 * queue page opens, never while the app is closed. [seen] says which kept posts are unseen.
 */
class TabsController(
  private val store: TabStateStore = TabStateStore.None,
  scope: CoroutineScope? = null,
  private val clock: Clock = Clock.System,
  private val seen: SeenStore? = null,
  /** The tags the app has learnt ([dev.marzahn.sankaku.data.TagsRepository.known]), for the names in grid titles (#246). */
  knownTags: StateFlow<Map<String, TagSuggestion>>? = null,
) {
  // The tags known now, as snapshot state, so a title shown while a tag's name is learnt shows the name.
  private var known by mutableStateOf(knownTags?.value.orEmpty())

  /**
   * A search tag's name as its search chip shows it ([TagSuggestion.displayName]), by its search key: every grid title
   * in the queue is made of these (#246, [sourceTitle]).
   */
  internal val tagNames: TagNameLookup = { key -> known[key]?.displayName }

  private val _tabs: MutableStateFlow<List<TabState>>
  private val _active: MutableStateFlow<TabId>
  private val _faded: MutableStateFlow<List<FadedSource>>

  /**
   * The current session ([SessionClock]): each tab records the one it joined in ([TabState.addedIn]), and "later" goes
   * to the end of the current one's tabs. A launch carries on in the saved session; only [clearViewed] starts another.
   */
  val sessions: SessionClock

  // For a block a search was opened in front of, by its head: the tab in it that was active then, which moving back
  // into the block returns to ([step]). Kept for the session only.
  private val returnTo = mutableMapOf<TabId, TabId>()

  // The title each post was named by once it loaded (#156), by post id: a post opened without a label (a parent link, a
  // recommended post) is named by it in the strip, the queue page and the previews. Snapshot state, so a title is saved
  // like a navigation, with the tabs showing the post.
  private val loaded = mutableStateMapOf<String, String>()

  /**
   * The post [route] loaded and [PostTitle] names it [title]; null or blank means it has no name now, so an earlier
   * name is forgotten and the tab is "Untitled post" again. Noted only for a post opened without a label, which is what
   * [tabTitle] reads it for. Kept for the session and saved with the tab ([SavedTab.title]).
   */
  fun loadedTitle(route: Route.Post, title: String?) {
    if (route.label != null) return
    if (title.isNullOrBlank()) loaded.remove(route.id) else if (loaded[route.id] != title) loaded[route.id] = title
  }

  /** Forgets every loaded title ("Clear everything"). */
  fun forgetTitles() {
    loaded.clear()
  }

  // The feeds that are queue items (#229), in the order they're added: [pinFeeds] sets them from [FeedItems.pinned].
  // Snapshot state, so what can close ([canClose]) follows it.
  private var pinned by mutableStateOf(emptyList<Feed>())

  // Every change to the tab list goes through here ([headingBlocks]).
  private var order: List<TabState>
    get() = _tabs.value
    set(value) {
      _tabs.value = headingBlocks(value)
    }

  /**
   * [tabs] unchanged in order: a feed heads a block of the posts kept from it (#265), as a source does, and is in no
   * other block. Nothing keeps a feed last (#327): it stands where it was added or moved to.
   */
  private fun headingBlocks(tabs: List<TabState>): List<TabState> {
    tabs.forEach { if (it.kind == QueueKind.Feed && it.block != null) it.block = null }
    return tabs
  }

  init {
    val saved = store.load()
    sessions = SessionClock.atLaunch(saved?.session?.takeIf { it.isNotBlank() }?.let(::SessionId))
    val restored = loneAtLaunch(saved?.tabs.orEmpty().filter { it.routes.isNotEmpty() }.distinctBy { it.id })
      .map { tab ->
        val post = tab.routes.last() as? Route.Post
        if (post != null && tab.title != null) loadedTitle(post, tab.title)
        TabState(
          TabId(tab.id),
          tab.routes,
          tab.block?.let(::TabId),
          tab.place,
          addedIn = tab.addedIn?.let(::SessionId) ?: SessionId.EARLIER,
          touched = tab.touched?.let(Instant::fromEpochSeconds) ?: clock.now(),
          keptAt = tab.keptAt?.let(Instant::fromEpochSeconds),
          closedSource = tab.closedSource,
          loadedTitles = loaded::get,
          mosaic = QueueMosaic.restored(tab.mosaic, tab.posts),
          gridTouched = tab.gridTouched == true,
          tagNames = tagNames,
          openedFrom = tab.openedFrom?.let(::TabId),
        )
      }
    // A broken file that couldn't be moved aside makes the clear fail, and then it stays where it is.
    if (restored.isEmpty()) runCatching { store.clear() }
    // In the saved order (#327: nothing moves the feeds at launch); nothing is saved for a feed's block cleared alone.
    val tabs = headingBlocks(restored.ifEmpty { listOf(newTab(listOf(Route.Grid(emptyList())))) })
    _tabs = MutableStateFlow(tabs)
    // By id: a grid split out from under a post at launch ([loneAtLaunch]) moves the tabs after it.
    val activeId = saved?.tabs?.getOrNull(saved.activeIndex)?.id
    _active = MutableStateFlow((tabs.firstOrNull { it.id.value == activeId } ?: tabs[(saved?.activeIndex ?: 0).coerceIn(tabs.indices)]).id)
    _faded = MutableStateFlow(saved?.faded.orEmpty().mapNotNull { FadedSource.of(it, tagNames) }.filter { f -> tabs.none { it.id == f.id } })
    val atLaunch = savedState(tabs, _active.value, _faded.value)
    // Before the saver starts, so what fading or deleting changes at launch is saved.
    sweep()
    // A failed save (a full disk, or a backup copy that couldn't be made first) ends neither the saver nor the app: the
    // next change saves again, copy first.
    scope?.launch { changes().dropWhile { it == atLaunch }.collect { runCatching { store.save(it) } } }
    if (knownTags != null) scope?.launch { knownTags.collect { known = it } }
  }

  val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()
  val active: StateFlow<TabId> = _active.asStateFlow()

  val activeTab: TabState get() = _tabs.value.first { it.id == _active.value }

  fun open(route: Route, background: Boolean) = open(listOf(route), background)

  /**
   * "Later": a tab whose back stack is [routes], the last one shown, at the end of this session's tabs. That's after
   * the block of the last tab opened in the current session, and so before the tabs of earlier ones (restored at
   * launch), or first in the strip while none has been opened.
   *
   * A search whose source is already open ([sourceFor]) adds no tab: it's shown instead, unless [background]. Returns
   * that source, or null when a tab was added.
   */
  fun open(routes: List<Route>, background: Boolean): TabState? {
    val all = _tabs.value
    openSource(routes.lastOrNull())?.let { source ->
      if (!background) goToSource(source)
      return source
    }
    val tab = newTab(routes)
    insertSource(tab, routes.lastOrNull(), default = endOfSession(all))
    if (!background) show(tab)
    return null
  }

  // Where a "later" item goes: after the block of the last item added in the current session, else first. A feed counts
  // as any source does (#327): with the feeds and their blocks last, "later" goes after them, at the end of the queue.
  private fun endOfSession(all: List<TabState>): Int {
    val last = all.indexOfLast { it.addedIn == sessions.current }
    return if (last < 0) 0 else blockEnd(all, last) + 1
  }

  /**
   * The posts a closed source left behind that a source on [route]'s search would take back (#184): the first run of
   * kept posts whose [TabState.closedSource] has the same [SourceKey]. For a feed, the posts a feed of the same feed
   * left as it went ([takesBack], #265). Empty for anything else, and when none is left.
   */
  private fun closedBlockFor(route: Route?): List<TabState> {
    val grid = route as? Route.Grid ?: return emptyList()
    val all = _tabs.value
    val first = all.indexOfFirst { tab -> tab.closedSource?.let { takesBack(grid, it) } == true }
    return if (first < 0) emptyList() else all.subList(first, blockEnd(all, first) + 1).toList()
  }

  // Whether a source or feed on [grid] takes back the kept posts left by the closed [search] (#184, #265): a source by
  // its [SourceKey], a feed by its feed. A feed and a source never take each other's (Following and
  // `feed:following order:popularity` share a key), and a search with no key never takes any.
  private fun takesBack(grid: Route.Grid, search: List<String>): Boolean {
    val kind = queueKind(grid)
    if (kind != QueueKind.ofGrid(search)) return false
    return when (kind) {
      QueueKind.Source -> SourceKey.of(grid.tags)?.let { it == SourceKey.of(search) } == true
      QueueKind.Feed -> QueueKind.feedOf(grid.tags) == QueueKind.feedOf(search)
      else -> false
    }
  }

  /** The kept posts a closed source left that a source searching [tags] would take back (#184). */
  internal fun closedBlock(tags: List<String>): List<TabState> = closedBlockFor(Route.Grid(tags))

  // Adds the new [tab] showing [route]. A source takes back the kept posts its closed namesake left (#184): it goes in
  // front of them and heads them again. Anything else goes at [default].
  private fun insertSource(tab: TabState, route: Route?, default: Int) {
    val back = closedBlockFor(route)
    val at = back.firstOrNull()?.let { first -> _tabs.value.indexOf(first) } ?: default
    takeBack(tab, back)
    insert(at, tab)
  }

  // [tab] heads the kept posts [back] a closed namesake left (#184) again.
  private fun takeBack(tab: TabState, back: List<TabState>) {
    val from = back.firstOrNull()?.block ?: return
    back.forEach {
      it.block = tab.id
      it.closedSource = null
    }
    // A post of the block closed since comes back into the block as it is now, not as one of a closed source's.
    _closed.value.forEach { it.adopt(from, tab.id) }
  }

  /**
   * The open source that searches the same as [key] (one source per search): the first in the strip, as tabs saved
   * before this rule may hold two. A faded source is not open: [fadedFor] finds it, and opening its search restores it.
   */
  fun sourceFor(key: SourceKey): TabState? = _tabs.value.firstOrNull { it.sourceKey == key }

  /** The faded source that searches the same as [key]: the first faded. */
  fun fadedFor(key: SourceKey): FadedSource? = _faded.value.firstOrNull { it.sourceKey == key }

  /**
   * The open feed that shows [feed] (#185): Following and For you are each one queue item, whatever rating filter a
   * search adds, so opening one that's open goes to it. The first in the strip, as tabs saved before this may hold two.
   * A source on `feed:following order:popularity` is a search, not the feed ([QueueKind.feedOf]).
   */
  fun feedFor(feed: Feed): TabState? = _tabs.value.firstOrNull { it.feed == feed }

  /**
   * **The feed items (#229):** the feeds that are always in the queue, from [FeedItems.pinned] (signed in: Following,
   * and For you if "Show For you in the queue" is on; signed out: none). The shell calls this as the sign-in and the
   * setting change. Each feed of [feeds] not in the queue is added at the end ([FeedItems.added], #327); one that is
   * (opened from the sidebar, or restored) becomes the item, as it is, where it stands, with its place. A feed no longer
   * pinned (signing out, the setting turned off) leaves the queue, every tab of it, without a Recently closed entry; if
   * it was shown, the item before it is shown.
   *
   * A feed item isn't counted in "n / N" ([countedItems]) and can't be closed ([canClose]); opening its feed anywhere
   * goes to it ([feedFor]). Otherwise it's an ordinary item: nothing keeps it last once it's added (#327).
   */
  fun pinFeeds(feeds: List<Feed>) {
    val gone = FeedItems.leaving(pinned, feeds)
    pinned = feeds
    val all = _tabs.value
    val leaving = all.filter { it.feed in gone }
    leaving.forEach { it.clear() }
    val ids = leaving.mapTo(mutableSetOf()) { it.id }
    returnTo.keys.removeAll(ids)
    returnTo.values.removeAll(ids)
    val rest = all.filter { it.id !in ids }
    // The posts kept from a feed that leaves stay, as a closed source's do (#184, #265): "from Following (closed)",
    // until the feed comes back and heads them again.
    leaving.forEach { leaveKeptPosts(it, rest) }
    // A feed tab saved with routes under its feed (a grid that went to it in place, before #229) becomes the feed
    // alone, so Back can't turn the feed item into something else.
    feeds.forEach { feed -> rest.firstOrNull { it.feed == feed }?.backStack?.let { while (it.size > 1) it.removeAt(0) } }
    val added = FeedItems.added(feeds, rest.mapNotNull { it.feed }).map { newTab(listOf(Route.Grid(listOf(it.tag)))) }
    // A feed that comes back takes back the posts it left (#265), as a source on a closed source's search does, and
    // they follow it to the end as its block.
    val back = added.associateWith { closedBlockFor(it.top) }
    back.forEach { (feed, posts) -> takeBack(feed, posts) }
    val moving = back.values.flatten().toSet()
    order = (rest.filter { it !in moving } + added.flatMap { listOf(it) + back.getValue(it) })
      .ifEmpty { listOf(newTab(listOf(Route.Grid(emptyList())))) }
    if (_active.value in ids) {
      val index = all.indexOfFirst { it.id == _active.value }
      show(all.take(index).lastOrNull { it.id !in ids } ?: _tabs.value.first())
    }
  }

  /** Whether [tab] is a feed item (#229, [pinFeeds]): the first tab on a pinned feed. It can't be closed. */
  fun isFeedItem(tab: TabState): Boolean = tab.feed?.let { it in pinned && feedFor(it) === tab } == true

  /** Whether [tab] can be closed: anything but a feed item (#229). */
  fun canClose(tab: TabState): Boolean = !isFeedItem(tab)

  // The queue item a grid [route] would duplicate, if any: an open feed ([feedFor]), the open source ([sourceFor]), else
  // a faded source, which this restores (#215: a search that matches it brings it back with its place, as if opened).
  // A feed never merges with a source whose key equals its own (Following and `feed:following order:popularity`).
  private fun openSource(route: Route?): TabState? {
    val grid = route as? Route.Grid ?: return null
    if (queueKind(grid) == QueueKind.Feed) return QueueKind.feedOf(grid.tags)?.let(::feedFor)
    val key = SourceKey.of(grid.tags) ?: return null
    return sourceFor(key) ?: fadedFor(key)?.let { restore(it.id) }
  }

  /** How many kept posts follow [tab] in its block (#184): the ones that stay if it closes. 0 for a kept post. */
  fun keptPostsOf(tab: TabState): Int = _tabs.value.count { it.block == tab.id && it !== tab }

  /**
   * A kept post's source: the head of its block, when that's a source or a feed (a post kept from Following, #265).
   * Null for a post on its own (or in a block headed by a post), and for anything not a kept post. The one answer to
   * "where was this kept from" for every reader.
   */
  fun sourceOf(tab: TabState): TabState? {
    if (tab.kind != QueueKind.KeptPost) return null
    val head = tab.block ?: return null
    return _tabs.value.firstOrNull { it.id == head }?.takeIf { headsKeptPosts(it.kind) }
  }

  // Shows the open [source] for a search opened "now": it keeps its place, its block and its order. Moving on past it
  // lands back where the search was opened from, as for a source opened in front ([openInFront]).
  private fun goToSource(source: TabState) {
    val from = activeTab
    // Reached by its search from somewhere else, it's no longer the grid just opened from its item: Back doesn't close
    // it (#349).
    if (source !== from) source.openedFrom = null
    show(source)
    if (from.head != source.head) returnTo[from.head] = from.id
  }

  /**
   * The tab [id] was opened, scrolled or acted on now: what stage 3 fades by. A post newly on top of it is kept from
   * now. Showing a tab ([activate], [step], a new tab shown) touches it already.
   */
  fun touch(id: TabId) {
    _tabs.value.firstOrNull { it.id == id }?.touch(clock.now())
    // A navigation within a tab may make it a feed (a grid that went to For you in place), which then heads its own
    // block and is in no other ([headingBlocks]). It stays where it stands (#327). An unchanged list is no change to the
    // flow.
    order = _tabs.value
  }

  /** Touches the active tab ([touch]): a post acted on in it, or a navigation within it. */
  fun touchActive() = touch(_active.value)

  /** "Kept again" (#130's "Already tab N"): the post tab [id] is kept anew, so it's unseen since it was kept. */
  fun keepAgain(id: TabId) {
    _tabs.value.firstOrNull { it.id == id }?.keepAgain(clock.now())
  }

  /**
   * Whether a tab keeps the post [postId] and it was seen since it was kept (#182). Asked before opening the post in
   * a background tab again: that keeps it anew, and then the chip says "Kept again" rather than "Already tab N".
   */
  fun keptPostSeen(postId: String, seen: SeenStore): Boolean =
    indexOfPost(postId)?.let { _tabs.value[it].seenSinceKept(seen) } == true

  /**
   * Whether the grid of [grid] shows the post [postId] as seen (#262): a tab keeps it with [grid] as its source
   * ([sourceOf]), and it was seen since it was kept ([TabState.seenSinceKept]). A post seen but not kept, or kept from
   * another source, looks like any other post there. The kept post is matched by id: an exact copy under another id
   * isn't kept, though seeing a copy counts as seeing the kept one. Reads snapshot state (the back stacks, kept times
   * and blocks), so a cell that calls it follows them.
   */
  fun seenFromGrid(grid: TabState, postId: String, seen: SeenStore): Boolean =
    _tabs.value.any { tab -> (tab.top as? Route.Post)?.id == postId && sourceOf(tab) === grid && tab.seenSinceKept(seen) }

  /**
   * A post opened in the background (long-press, middle-click, Cmd/Ctrl-click, E): a tab whose back stack is [routes]
   * at the end of the active tab's block, in that block.
   */
  fun openInBlock(routes: List<Route>) {
    val all = _tabs.value
    val index = activeIndex(all)
    // In a closed source's block (#184), the new post is one of its posts, "from … (closed)" too.
    val tab = newTab(routes, block = all[index].head, closedSource = all[index].closedSource)
    insert(blockEnd(all, index) + 1, tab)
  }

  /**
   * "Now", for a post (#181): a lone post, the post [route] alone in a tab with no grid under it, right after the
   * active tab and in its block, made active. So Back on it ([previous]) returns to where it was opened from, and its
   * source ([sourceOf]) is that block's head. A post some tab already shows (one tab per post, #130) is shown there.
   */
  fun openLone(route: Route.Post) {
    indexOfPost(route.id)?.let { index -> return show(_tabs.value[index]) }
    val all = _tabs.value
    val index = activeIndex(all)
    // From a feed too: a feed heads a block (#265), so the post stands right after it and Back returns to it.
    val tab = newTab(listOf(route), block = all[index].head)
    insert(index + 1, tab)
    show(tab)
  }

  /**
   * The item [previous] (or [next] if [forward]) would show from the active one, or null at that end: the neighbour in
   * the strip, or the tab a block remembers ([step]), so a swipe's peek shows where it lands.
   */
  fun neighbour(forward: Boolean): TabState? {
    val all = _tabs.value
    val index = activeIndex(all)
    val target = index + if (forward) 1 else -1
    return if (target in all.indices) landing(all, target, from = all[index].head) else null
  }

  /** Back on a lone post, and a swipe towards the start: the previous queue item ([step] without wrapping). */
  fun previous(): Boolean = step(forward = false, wrap = false)

  /** A swipe towards the end: the next queue item ([step] without wrapping). */
  fun next(): Boolean = step(forward = true, wrap = false)

  /**
   * "Now": a grid tab for [route] in front of the active tab's block, made active. The block remembers which of its
   * tabs was active, so moving back into it ([step]) lands there.
   */
  private fun openInFront(route: Route, fromItem: Boolean = false) {
    val all = _tabs.value
    val index = activeIndex(all)
    val from = all[index]
    // A search opened from an item: Back on the new grid closes it and returns to that item (#349, [openerOf]). Start on:
    // Browse ([showBrowse]) isn't one.
    val tab = newTab(listOf(route), openedFrom = if (fromItem) from.id else null)
    // In front of the kept posts a closed namesake left, if any (#184), else of the current block.
    val takesBack = closedBlockFor(route).isNotEmpty()
    insertSource(tab, route, default = blockStart(all, index))
    show(tab)
    // Moving on past its block lands back here, which only holds when it was put in front of this block.
    if (!takesBack) returnTo[from.head] = from.id
  }

  /**
   * A search opened in place (a tag tapped, Search only this tag, Add, Exclude or Remove, a search submitted, a
   * sidebar entry): on a touched grid ([TabState.topIsTouchedGrid]), a post or Settings it opens in front of the block
   * ([openInFront]), so nothing is lost; anywhere else (an untouched grid) it opens in this tab, which Back returns to.
   *
   * One source per search (#174): when the search's source is already open ([sourceFor]), that tab is shown as it is
   * (its place, block and order) and no tab is added. If it's this tab, the search runs in it (a new order or rating
   * on the same tags), as on an untouched grid.
   */
  fun search(route: Route.Grid) {
    val tab = activeTab
    val source = openSource(route)
    when {
      source == tab -> if (tab.top != route) tab.backStack.add(route)
      source != null -> goToSource(source)
      // A source that takes back the kept posts of its closed namesake (#184) can't replace a grid in place, and a feed
      // item is never replaced (#229): it would stop being the feed. Settings stays alone in its tab (#205).
      tab.top is Route.Post || tab.top is Route.Settings || tab.topIsTouchedGrid || isFeedItem(tab) || closedBlockFor(route).isNotEmpty() -> openInFront(route, fromItem = true)
      else -> tab.backStack.add(route)
    }
    touchActive()
  }

  /**
   * ‹ › and Ctrl-Tab: the next tab, or the previous one if not [forward], wrapping past the ends if [wrap]. Moving into
   * a block a search was opened in front of lands on the tab that was active in it then, once. Returns false at an end
   * without [wrap].
   */
  fun step(forward: Boolean, wrap: Boolean): Boolean {
    val all = _tabs.value
    val index = activeIndex(all)
    val next = index + if (forward) 1 else -1
    if (!wrap && next !in all.indices) return false
    show(landing(all, next.mod(all.size), from = all[index].head))
    return true
  }

  // The tab to show on moving to the tab at [target] from the block headed by [from]: on entering a block a search was
  // opened in front of, the tab that was active in it then, if it's still in the block; else [target].
  private fun landing(all: List<TabState>, target: Int, from: TabId): TabState {
    val tab = all[target]
    if (tab.head == from) return tab
    val remembered = returnTo[tab.head] ?: return tab
    val at = all.indexOfFirst { it.id == remembered }
    return if (at >= 0 && at in blockStart(all, target)..blockEnd(all, target)) all[at] else tab
  }

  private fun activeIndex(all: List<TabState>) = all.indexOfFirst { it.id == _active.value }

  // The first and last index of the block of the tab at [index]: its head, if it's still in the strip just before its
  // run, and the run of tabs opened into it around [index].
  private fun blockStart(all: List<TabState>, index: Int): Int {
    val head = all[index].head
    var start = index
    while (start > 0 && all[start].block == head && all[start - 1].head == head) start--
    return start
  }

  private fun blockEnd(all: List<TabState>, index: Int): Int {
    val head = all[index].head
    var end = index
    while (end < all.lastIndex && all[end + 1].block == head) end++
    return end
  }

  private fun insert(at: Int, tab: TabState) {
    order = _tabs.value.toMutableList().apply { add(at.coerceIn(0, size), tab) }
  }

  // Every change of the active tab: a block moved into by any way has been returned to, and the tab is touched.
  private fun show(tab: TabState) {
    _active.value = tab.id
    returnTo.remove(tab.head)
    tab.touch(clock.now())
  }

  /**
   * The ids of the posts some tab shows (its top route), so the grid can mark them. The back stacks are snapshot
   * state, so reading this in a `derivedStateOf` over [tabs] follows navigation within a tab too.
   */
  fun openPosts(tabs: List<TabState>): Set<String> =
    tabs.mapNotNullTo(mutableSetOf()) { (it.top as? Route.Post)?.id }

  /** The index of the first tab showing the post [postId], as [openPosts] counts it, or null if none does. */
  fun indexOfPost(postId: String): Int? =
    _tabs.value.indexOfFirst { (it.top as? Route.Post)?.id == postId }.takeIf { it >= 0 }

  fun activate(id: TabId) {
    _tabs.value.firstOrNull { it.id == id }?.let(::show)
  }

  // The last [REOPENABLE] closed tabs, the most recent last. Kept for the session only.
  private val _closed = MutableStateFlow<List<ClosedTab>>(emptyList())

  /** The tabs [reopenClosed] can bring back, the most recent last. */
  val closed: StateFlow<List<ClosedTab>> = _closed.asStateFlow()

  /**
   * Forgets the closed tabs, so nothing of them can be reopened ("Clear everything"). A pending Undo ends with them
   * (`ClosedTabUndoWindow`).
   */
  fun forgetClosed() {
    _closed.value = emptyList()
  }

  /**
   * Clears the tab's view models. Closing the last tab leaves an empty grid. [reopenClosed] can bring it back. Returns
   * the closed tab, or null if [id] was no longer open.
   *
   * Closing the active tab brings the one to its right forward, else the one to its left; with [toPrevious] (a lone
   * post dragged down at the end of the queue, before its card, #326) the one to its left, else the one to its right.
   */
  fun close(id: TabId, toPrevious: Boolean = false): ClosedTab? = closeLandingOn(id, toPrevious, landOn = null)

  /** [close], landing on [landOn] when it's the active tab that closes and [landOn] is still open. */
  private fun closeLandingOn(id: TabId, toPrevious: Boolean, landOn: TabId?): ClosedTab? {
    val all = _tabs.value
    val index = all.indexOfFirst { it.id == id }
    // A feed item is never closed (#229).
    if (index < 0 || !canClose(all[index])) return null
    // Whether it was the active tab, so the queue page's Undo makes it active again ([reopen] `inPlace`, #306).
    val closedTab = ClosedTab(
      listOf(member(all[index], index)), activeMember = 0.takeIf { _active.value == id },
      keptStay = leaveKeptPosts(all[index], all), tagNames = tagNames,
    )
    _closed.value = (_closed.value + closedTab).takeLast(REOPENABLE)
    all[index].clear()
    val rest = all - all[index]
    if (rest.isEmpty()) {
      val fresh = newTab(listOf(Route.Grid(emptyList())))
      order = listOf(fresh)
      show(fresh)
    } else {
      order = rest
      if (_active.value == id) {
        show(rest.firstOrNull { it.id == landOn } ?: rest[(if (toPrevious) index - 1 else index).coerceIn(0, rest.lastIndex)])
      }
    }
    return closedTab
  }

  /**
   * The item Back on [tab] closes it and returns to (#349): the item a search opened it "now" from
   * ([TabState.openedFrom]), while [tab] shows that grid at the root of its back stack, was added in the current session
   * and can be closed, and the item is still in the queue. Null otherwise, and Back does what it did before: a grid
   * saved before #349, a source shown because its search was opened again, one from before the last Clear viewed.
   */
  fun openerOf(tab: TabState): TabState? {
    if (tab.backStack.size != 1 || tab.top !is Route.Grid || tab.addedIn != sessions.current || !canClose(tab)) return null
    val from = tab.openedFrom ?: return null
    return _tabs.value.firstOrNull { it.id == from && it !== tab }
  }

  /**
   * Back on a grid opened from an item (#349, [openerOf]): closes it as [close] does (its kept posts stay as a closed
   * block, #184; Undo and Recently closed reopen it) and shows that item, as it was left. Returns the closed tab, or
   * null, changing nothing, when Back on [id] doesn't close it.
   */
  fun closeBack(id: TabId): ClosedTab? {
    val tab = _tabs.value.firstOrNull { it.id == id } ?: return null
    val opener = openerOf(tab) ?: return null
    return closeLandingOn(id, toPrevious = false, landOn = opener.id)
  }

  /** The tab showing Settings (desktop, #205), if one is open. */
  fun settingsTab(): TabState? = _tabs.value.firstOrNull { it.top is Route.Settings }

  /**
   * Settings as a desktop tab (#205), one at most. Shown (not [background]): the open one is shown, or a new one right
   * after the active tab's block, outside it, so no block is split; either way Back on it ([closeSettings]) returns to
   * the tab it was opened from now ([TabState.openedFrom]). In the [background] (a middle or Cmd/Ctrl-click), a new one
   * goes "later", at the end of the session's tabs, as a sidebar grid does ([open]), and the open one is left as it is.
   * Returns the Settings tab that was open already, or null when one was added.
   */
  fun openSettings(background: Boolean): TabState? {
    val all = _tabs.value
    val from = activeTab
    settingsTab()?.let { open ->
      if (!background && open !== from) {
        open.openedFrom = from.id
        show(open)
      }
      return open
    }
    val tab = newTab(listOf(Route.Settings), openedFrom = if (background) null else from.id)
    insert(if (background) endOfSession(all) else blockEnd(all, activeIndex(all)) + 1, tab)
    if (!background) show(tab)
    return null
  }

  /**
   * Back on the Settings tab [id] (#205): it closes as [close] does (Z and Recently closed reopen it) and the tab it was
   * opened from is shown, if that's still open; else the tab before it. Returns the closed tab, or null when
   * [id] isn't an open Settings tab.
   */
  fun closeSettings(id: TabId): ClosedTab? {
    val tab = _tabs.value.firstOrNull { it.id == id }?.takeIf { it.top is Route.Settings } ?: return null
    return closeLandingOn(id, toPrevious = true, landOn = tab.openedFrom)
  }

  /**
   * Z and Cmd/Ctrl-Shift-T: the most recently closed tab comes back where it was, with its back stack, and becomes
   * active. Its view models were cleared, so it loads again like a restored tab.
   */
  fun reopenClosed() {
    reopen(_closed.value.lastOrNull() ?: return)
  }

  /**
   * One of [closed] (the touch tab list's "Recently closed"), as [reopenClosed] does with the latest. A batch comes
   * back whole, each tab where it stood; its active tab, if it had one, becomes active again, else the active tab
   * stays. A single tab becomes active.
   *
   * [inPlace] (the queue page's Undo of a swipe, #306) only takes the close back: whatever comes back becomes active
   * only if it was active when it closed, so the current item stays the current item.
   */
  fun reopen(closedTab: ClosedTab, inPlace: Boolean = false) {
    val position = _closed.value.indexOf(closedTab)
    if (position < 0) return
    // The copy in [closed], not the caller's: its places may have moved since (a snackbar's Undo holds an old one).
    val current = _closed.value[position]
    // One Settings tab at most (#205): a closed one reopened while another is open shows that one instead.
    val openSettings = settingsTab()
    if (openSettings != null && current.size == 1 && current.top is Route.Settings) {
      _closed.value = _closed.value - current
      if (!inPlace) show(openSettings)
      return
    }
    val strip = _tabs.value.toMutableList()
    // Where each member went, in order: the members are in strip order, so each lands where it stood.
    val placed = mutableListOf<Int>()
    var activate: TabState? = null
    // A reopened tab gets a new id (its saved UI state was for the old one), so the tabs of its block follow it.
    val renamed = mutableMapOf<TabId, TabId>()
    current.members.forEachIndexed { i, member ->
      // In front of its kept posts if they were moved since (#184), else where it stood.
      val leftBehind = member.id?.let { id -> strip.indexOfFirst { it.block == id && it.closedSource != null } } ?: -1
      val at = if (leftBehind >= 0) leftBehind else member.index.coerceAtMost(strip.size)
      noteTitle(member)
      val tab = newTab(
        member.routes, member.block, addedIn = member.addedIn, place = member.place, keptAt = member.keptAt,
        closedSource = member.closedSource, gridTouched = member.gridTouched, mosaic = member.mosaic,
        openedFrom = member.openedFrom,
      )
      member.id?.let { renamed[it] = tab.id }
      strip.add(at, tab)
      placed += at
      if ((current.size == 1 && !current.cleared && !inPlace) || current.activeMember == i) activate = tab
    }
    // A closed source's kept posts are headed by it again (Undo, or a hold on ✕): they're no longer "from … (closed)".
    strip.forEach { tab ->
      tab.block?.let(renamed::get)?.let {
        tab.block = it
        tab.closedSource = null
      }
      // A grid opened from an item that came back returns to it again (#349).
      tab.openedFrom?.let(renamed::get)?.let { tab.openedFrom = it }
    }
    // A remembered tab, or the head it's remembered for, may be one that came back.
    val remembered = returnTo.entries.associate { (head, tab) -> (renamed[head] ?: head) to (renamed[tab] ?: tab) }
    returnTo.clear()
    returnTo.putAll(remembered)
    // Tabs closed after this one were placed in a strip without it: one at or past where a member returns moves up.
    fun shifted(index: Int) = placed.fold(index) { at, returned -> if (returned <= at) at + 1 else at }
    _closed.value = _closed.value.filterIndexed { i, _ -> i != position }.mapIndexed { i, later ->
      (if (i >= position) later.moved(::shifted) else later).renamed(renamed)
    }
    // A closed feed whose feed item is open (a Following closed before signing in) isn't a second one (#229): it goes
    // to the item instead. A Following while it isn't pinned (signed out) doesn't come back at all, as [pinFeeds]
    // would take it away.
    val before = _tabs.value
    val unpinned = FeedItems.leaving(emptyList(), pinned)
    // The feeds that don't come back, and the item each one's kept posts join instead, if any (#265).
    val dropped = mutableMapOf<TabState, TabState?>()
    strip.removeAll { tab ->
      if (before.any { it === tab }) return@removeAll false
      val feed = tab.feed ?: return@removeAll false
      val item = if (feed in pinned) before.firstOrNull { it.feed == feed } ?: return@removeAll false else null
      if (feed !in pinned && feed !in unpinned) return@removeAll false
      if (activate === tab) activate = item
      dropped[tab] = item
      tab.clear()
      true
    }
    // Their kept posts join the feed item, at the end of its block (nothing moves them there any more, #327), or stay
    // as "from Following (closed)" until it's there.
    dropped.forEach { (tab, item) ->
      val joining = strip.filter { it.block == tab.id }
      if (item == null) {
        leaveKeptPosts(tab, strip)
      } else if (joining.isNotEmpty()) {
        joining.forEach { it.block = item.id }
        strip.removeAll(joining)
        strip.addAll(blockEnd(strip, strip.indexOf(item)) + 1, joining)
      }
    }
    order = strip
    activate?.let(::show)
    // A grid opened from an item comes back as it was opened (#349): moving on past its block lands on that item again.
    activate?.let { grid ->
      openerOf(grid)?.let { opener -> if (opener.head != grid.head) returnTo[opener.head] = opener.id }
    }
  }

  private fun member(tab: TabState, index: Int) = ClosedTab.Member(
    tab.backStack.filterIsInstance<Route>(), index, tab.id, tab.block, tab.addedIn, tab.place, tab.keptAt,
    tab.closedSource, tab.loadedTitle, gridTouched = tab.savedTouched == true, mosaic = tab.mosaic,
    openedFrom = tab.openedFrom,
  )

  /** The title a closed or faded post came with, noted again as it comes back. */
  private fun noteTitle(member: ClosedTab.Member) {
    val post = member.routes.lastOrNull() as? Route.Post
    if (post != null && member.title != null) loadedTitle(post, member.title)
  }

  /**
   * A source closing leaves its kept posts in [remaining] where they are (#184): they stay as a block, marked with the
   * source's search ([TabState.closedSource]). Reopening the closed tab, or a source on that search, takes them back.
   * A feed leaving (signing out, For you's setting off, an unpinned For you closed) leaves its kept posts so too (#265),
   * and the feed coming back takes them back. Returns how many stay; 0 for anything but a source or a feed.
   */
  private fun leaveKeptPosts(closing: TabState, remaining: List<TabState>): Int {
    val search = (closing.top as? Route.Grid)?.takeIf { headsKeptPosts(closing.kind) }?.tags ?: return 0
    val kept = remaining.filter { it.block == closing.id && it !== closing }
    kept.forEach { it.closedSource = search }
    return kept.size
  }

  /**
   * Clear everything (#164): every tab closes as in [closeAll], leaving one Browse tab, shown. A feed item (#229), which
   * can't be closed, is made again on its bare feed, so its place, loaded pages and view models go too; nothing of it
   * goes to Recently closed.
   */
  fun clearAll() {
    closeAll(_tabs.value.map { it.id })
    val feeds = _tabs.value.filter(::isFeedItem)
    if (feeds.isEmpty()) return
    feeds.forEach { it.clear() }
    returnTo.clear()
    val browse = newTab(listOf(Route.Grid(emptyList())))
    order = listOf(browse) + feeds.mapNotNull { it.feed }.map { newTab(listOf(Route.Grid(listOf(it.tag)))) }
    show(browse)
  }

  /**
   * Closes every tab of [ids] at once (the touch tab list's bulk closes): one change, and one entry in [closed], so one
   * Undo ([reopen]) brings the whole batch back in place. If the active tab closes, the next open tab to its right
   * comes forward, else the one to its left; closing every tab leaves an empty grid, as [close] does. Returns the
   * batch, or null if none of [ids] was open; a single tab closes as [close] does.
   */
  fun closeAll(ids: Collection<TabId>): ClosedTab? {
    val all = _tabs.value
    // A feed item is never closed (#229).
    val wanted = ids.toSet() - all.filter(::isFeedItem).map { it.id }.toSet()
    val indices = all.indices.filter { all[it].id in wanted }
    when (indices.size) {
      0 -> return null
      1 -> return close(all[indices.single()].id)
    }
    val activeIndex = all.indexOfFirst { it.id == _active.value }
    val batch = ClosedTab(
      members = indices.map { member(all[it], it) },
      activeMember = indices.indexOf(activeIndex).takeIf { it >= 0 },
      tagNames = tagNames,
    )
    _closed.value = (_closed.value + batch).takeLast(REOPENABLE)
    indices.forEach { all[it].clear() }
    val rest = all.filter { it.id !in wanted }
    indices.forEach { leaveKeptPosts(all[it], rest) }
    if (rest.isEmpty()) {
      val fresh = newTab(listOf(Route.Grid(emptyList())))
      order = listOf(fresh)
      show(fresh)
      return batch
    }
    order = rest
    if (batch.activeMember != null) {
      val next = all.drop(activeIndex + 1).firstOrNull { it.id !in wanted }
        ?: all.take(activeIndex).last { it.id !in wanted }
      show(next)
    }
    return batch
  }

  /**
   * The kept posts Clear viewed clears (#183): those seen since they were kept ([TabState.seenSinceKept]), in queue
   * order. Never the current item, and never a source or a feed.
   */
  fun viewedKeptPosts(seen: SeenStore): List<TabId> = _tabs.value
    .filter { it.id != _active.value && it.kind == QueueKind.KeptPost && it.seenSinceKept(seen) }
    .map { it.id }

  /** Whether the queue holds kept posts and every one was seen since it was kept: Clear viewed is then suggested. */
  fun everyKeptPostViewed(seen: SeenStore): Boolean {
    val kept = _tabs.value.filter { it.kind == QueueKind.KeptPost }
    return kept.isNotEmpty() && kept.all { it.seenSinceKept(seen) }
  }

  /**
   * Clear viewed (#183): removes the [viewedKeptPosts] at once, as one entry in [closed] ("N posts · cleared", so
   * [reopen] restores them all at their places, with no timer), and ends the session ([SessionClock.startNew]): "later"
   * then goes at the end of the items added from here on. Returns the entry, or null, changing nothing, when there is
   * nothing to clear.
   */
  fun clearViewed(seen: SeenStore): ClosedTab? {
    val wanted = viewedKeptPosts(seen).toSet()
    if (wanted.isEmpty()) return null
    val all = _tabs.value
    val batch = ClosedTab(
      members = all.indices.filter { all[it].id in wanted }.map { member(all[it], it) },
      cleared = true,
      tagNames = tagNames,
    )
    _closed.value = (_closed.value + batch).takeLast(REOPENABLE)
    all.filter { it.id in wanted }.forEach { it.clear() }
    // The session first: it's a plain var that the save pipeline reads when the tabs change, on another thread. Set
    // after the tabs, a save in between would write the old id, and nothing would save the new one.
    sessions.startNew()
    // The current item stays, so the queue is never left empty.
    order = all.filter { it.id !in wanted }
    return batch
  }

  /** The controller's clock's now: what the queue page counts "Deleted in N days" from. */
  fun now(): Instant = clock.now()

  /** The faded sources, the soonest to be deleted first (#215). Not tabs: stepping, swipes and counts never meet them. */
  val faded: StateFlow<List<FadedSource>> = _faded.asStateFlow()

  /**
   * Works out fading and deletion at the clock's now (#215); the app does it as it launches and when the queue page
   * opens, never in the background. Safe to call again: it changes nothing until time has passed.
   *
   * - **Fading:** a source ([QueueKind.Source]) not touched for 3 days moves to [faded] with its unseen kept posts, as
   *   one block. Seen kept posts stay in the queue. It faded at `touched + 3 days` ([Fading.fadesAt]), whenever the app
   *   noticed.
   * - **The item shown never fades,** and nor does its source when it's a kept post: both start their 3 days again.
   * - **Deletion:** a faded source goes 30 days after it faded, with the kept posts that faded with it. A source found
   *   already past that (the app was closed for a month) is deleted without ever being listed. Nothing else is touched:
   *   kept posts in the queue, feeds and other sources stay.
   */
  fun sweep() {
    val now = clock.now()
    val all = _tabs.value
    val current = all.firstOrNull { it.id == _active.value }
    current?.touch(now)
    all.firstOrNull { it.id == current?.head && it !== current }?.touch(now)
    val leaving = mutableSetOf<TabId>()
    val sources = mutableListOf<TabState>()
    val fading = mutableListOf<FadedSource>()
    for (tab in all) {
      if (tab.id in leaving || tab.kind != QueueKind.Source || !Fading.isFading(tab.touched, now)) continue
      val kept = all.filter { post -> post.block == tab.id && post.kind == QueueKind.KeptPost && (seen == null || !post.seenSinceKept(seen)) }
      leaving += tab.id
      sources += tab
      kept.mapTo(leaving) { it.id }
      val fadedAt = Fading.fadesAt(tab.touched)
      if (Fading.isDeleted(fadedAt, now)) continue
      val members = listOf(member(tab, 0).copy(block = null)) + kept.mapIndexed { i, post -> member(post, i + 1) }
      fading += FadedSource(members, fadedAt, tab.touched, tagNames = tagNames)
    }
    val remaining = _faded.value.filterNot { Fading.isDeleted(it.fadedAt, now) }
    if (leaving.isNotEmpty()) {
      all.filter { it.id in leaving }.forEach { it.clear() }
      returnTo.keys.removeAll(leaving)
      returnTo.values.removeAll(leaving)
      val rest = all.filter { it.id !in leaving }
      // The seen kept posts stay, as a closed source's do (#184): "from artist_x (closed)", taken back by the source.
      sources.forEach { leaveKeptPosts(it, rest) }
      order = rest
    }
    if (fading.isNotEmpty() || remaining.size != _faded.value.size) {
      _faded.value = (remaining + fading).sortedBy { it.fadedAt }
    }
  }

  /**
   * Opens the faded source [id] again: it goes at the end of this session, with its place and its kept posts as one
   * block, and its 3 days start again (#215). Shown too if [show]. Returns the source, or null if [id] isn't faded.
   * What a search matching it does ([search], [open]), and the Faded section's Restore and Keep.
   */
  fun restore(id: TabId, show: Boolean = false): TabState? {
    val faded = _faded.value.firstOrNull { it.id == id } ?: return null
    val all = _tabs.value
    val head = faded.members.first()
    _faded.value = _faded.value - faded
    // One source per search: if the search is open again (a source reopened from Recently closed since it faded), its
    // kept posts join that source's block and nothing is added, so there are never two sources on one search.
    val open = faded.sourceKey?.let(::sourceFor)
    faded.members.forEach(::noteTitle)
    if (open != null) {
      val joined = faded.members.drop(1).map { newTab(it.routes, block = open.id, addedIn = sessions.current, keptAt = it.keptAt) }
      order = all.toMutableList().apply { addAll(blockEnd(all, all.indexOf(open)) + 1, joined) }
      if (show) show(open) else open.touch(clock.now())
      return open
    }
    val source = newTab(
      head.routes, addedIn = sessions.current, place = head.place, gridTouched = head.gridTouched, mosaic = head.mosaic,
    )
    val kept = faded.members.drop(1).map { newTab(it.routes, block = source.id, addedIn = sessions.current, keptAt = it.keptAt) }
    // In front of the seen kept posts it left behind, if they're still there (#184), which it heads again; its own kept
    // posts follow it.
    insertSource(source, source.top, default = endOfSession(all))
    val at = _tabs.value.indexOf(source) + 1
    order = _tabs.value.toMutableList().apply { addAll(at, kept) }
    if (show) show(source)
    return source
  }

  /**
   * Deletes the faded source [id] and its kept posts now ("Delete now", and Last chance's "Let go"). It's one entry in
   * [closed], so Undo (or Recently closed) brings the block back into the queue, for the session only. Returns it, or
   * null if [id] isn't faded.
   */
  fun deleteFaded(id: TabId): ClosedTab? {
    val faded = _faded.value.firstOrNull { it.id == id } ?: return null
    _faded.value = _faded.value - faded
    // They come back at the end of the queue, in order.
    val end = _tabs.value.size
    val closedTab = ClosedTab(faded.members.mapIndexed { i, member -> member.copy(index = end + i) }, tagNames = tagNames)
    _closed.value = (_closed.value + closedTab).takeLast(REOPENABLE)
    return closedTab
  }

  /**
   * The faded source Up next should offer as "Last chance: artist_x · Keep / Let go" (#216): the soonest to be deleted
   * that's in its last week ([Fading.LAST_CHANCE]) and not offered before. Null when none. Offer it, then call
   * [offered]; [keep] and [letGo] answer it. If it's never shown or not answered it is deleted on day 30 all the same.
   */
  fun lastChance(): FadedSource? = lastChance { true }

  // The same, among the faded sources [visible] lets through (Up next leaves R15 and R18 out while G only is on).
  private fun lastChance(visible: (FadedSource) -> Boolean): FadedSource? {
    val now = clock.now()
    return _faded.value.firstOrNull { it.isLastChance(now) && visible(it) }
  }

  /**
   * What the Up next card offers (#216, #230), in order ([UpNext.offers]): the [lastChance], then the other faded
   * sources, the one that faded first first. Only what is out of view: nothing in the queue is offered, so moving past
   * the end never loops back to an item. [passed] are the ids already shown and answered or skipped. With [gOnly] the
   * R15 and R18 ones are left out, as the queue page lists none.
   */
  fun upNextOffers(passed: Set<String>, gOnly: Boolean): List<UpNext.Offer> {
    // A search with no key (Browse, Popular, Top) isn't something to pick up again; it still gets its Last chance.
    val pickups = _faded.value.filter { it.sourceKey != null && !(gOnly && it.adult) }
    val chance = lastChance { !(gOnly && it.adult) }?.id?.value
    return UpNext.offers(chance, pickups.map { UpNext.Faded(it.id.value, it.fadedAt) }, passed)
  }

  /**
   * Whether the Up next card stands just past the active item (#230): the active item is the last item, whatever it is
   * (#327: a feed is a source that persists, so the card no longer stands before the feeds), and moving on reaches
   * nothing.
   */
  fun upNextAhead(): Boolean = neighbour(forward = true) == null

  /**
   * End of the queue's "Back to the start" (#230): the first queue item, or with [gOnly] the first that isn't R15 or
   * R18, as the queue page lists them. Shown as any move is; with G only hiding every item, nothing moves.
   */
  fun backToStart(gOnly: Boolean) {
    _tabs.value.firstOrNull { !(gOnly && isAdult(it, null)) }?.let(::show)
  }

  /** Up next showed the last chance of the faded source [id]: it is not offered again. */
  fun offered(id: TabId) {
    _faded.value = _faded.value.map { if (it.id == id) it.asOffered() else it }
  }

  /** Last chance's "Keep": back in the queue as if opened ([restore]), without showing it. */
  fun keep(id: TabId): TabState? = restore(id)

  /** Last chance's "Let go": deleted now ([deleteFaded]). */
  fun letGo(id: TabId): ClosedTab? = deleteFaded(id)

  /** Forgets every faded source, so nothing of them is left ("Clear everything"). */
  fun forgetFaded() {
    _faded.value = emptyList()
  }

  /** The tabs after the active one: the tab list's "Close tabs to the right". Never a feed item (#229). */
  fun tabsToTheRight(): List<TabId> {
    val all = _tabs.value
    return all.drop(all.indexOfFirst { it.id == _active.value } + 1).filter(::canClose).map { it.id }
  }

  /** Every tab but the active one: "Close all but current". Never a feed item (#229). */
  fun otherTabs(): List<TabId> = _tabs.value.filter { it.id != _active.value && canClose(it) }.map { it.id }

  /**
   * Post tabs showing a post an earlier tab shows too: "Close duplicates", which keeps the first tab of each post. If
   * the active tab is one of a post's copies, it's the one kept instead, so the list doesn't move away from it.
   */
  fun duplicateTabs(): List<TabId> {
    val all = _tabs.value
    val byPost = all.filter { it.top is Route.Post }.groupBy { (it.top as Route.Post).id }.values
    val closing = byPost.flatMapTo(mutableSetOf()) { copies ->
      val kept = copies.firstOrNull { it.id == _active.value } ?: copies.first()
      copies.filter { it !== kept }.map { it.id }
    }
    return all.map { it.id }.filter { it in closing }
  }

  /** Post tabs whose post [isFavourited] says is favourited: "Close favourited". */
  fun favouritedTabs(isFavourited: (postId: String) -> Boolean): List<TabId> =
    _tabs.value.filter { tab -> (tab.top as? Route.Post)?.let { isFavourited(it.id) } == true }.map { it.id }

  /** Moves the tab at [from] to [to]; the active tab stays active. The queue page moves blocks ([moveInQueue]). */
  fun move(from: Int, to: Int) {
    val all = _tabs.value
    if (from !in all.indices || to !in all.indices || from == to) return
    order = all.toMutableList().apply { add(to, removeAt(from)) }
  }

  /**
   * The queue page's reorder (a drag on ≡, or TalkBack's Move up and Move down): one step up, or down if [down]. Returns
   * how many places the item [id] moved (negative for up; 0 for a kept post dragged out of its block in place; more than
   * the whole neighbouring block for a head), or null if it couldn't move.
   *
   * - **A block's head moves the whole block** past the neighbouring block (spec, Queue → Items). A feed is a source
   *   that persists (#327): it moves with its block like any other.
   * - **A kept post moves within its block.** Past its block's first or last place it's dragged out: it leaves the block
   *   and stands on its own just before or after it, a feed's too.
   */
  fun moveInQueue(id: TabId, down: Boolean): Int? {
    val step = queueStep(id, down) ?: return null
    // A kept post dragged out of its block has no source any more (#184), not even a closed one.
    step.detach?.let {
      it.block = null
      it.closedSource = null
    }
    val before = _tabs.value.indexOf(step.moved)
    order = step.tabs
    return _tabs.value.indexOf(step.moved) - before
  }

  /** Whether [moveInQueue] would move the item [id]: TalkBack offers only the moves that fit. */
  fun canMoveInQueue(id: TabId, down: Boolean): Boolean = queueStep(id, down) != null

  // A step of [moveInQueue]: the new order, the tab whose move it counts, and the kept post that leaves its block, if any.
  private class QueueStep(val tabs: List<TabState>, val moved: TabState, val detach: TabState?)

  private fun queueStep(id: TabId, down: Boolean): QueueStep? {
    val all = _tabs.value.toMutableList()
    var index = all.indexOfFirst { it.id == id }
    // The id of a closed source (#184) is the head of the block it left: the queue page's "from … (closed)" row.
    val closedHead = index < 0
    if (closedHead) index = all.indexOfFirst { it.block == id && it.closedSource != null }
    if (index < 0) return null
    val tab = all[index]
    val start = blockStart(all, index)
    val end = blockEnd(all, index)
    // A closed source's posts all follow its row, so they're all kept posts; a live block's head is the first tab.
    val orphan = tab.closedSource != null
    if (!closedHead && (index != start || orphan)) {
      // A kept post: a step inside its block, or out of it to just before or after it.
      val firstPost = if (orphan) start else start + 1
      val within = if (down) index < end else index > firstPost
      val to = when {
        within -> index + if (down) 1 else -1
        down -> end
        else -> start
      }
      all.add(to, all.removeAt(index))
      return QueueStep(all, tab, detach = tab.takeUnless { within })
    }
    val block = all.subList(start, end + 1).toList()
    val to = if (down) {
      val next = end + 1
      if (next > all.lastIndex) return null
      blockEnd(all, next) - block.size + 1
    } else {
      val previous = start - 1
      if (previous < 0) return null
      blockStart(all, previous)
    }
    all.subList(start, end + 1).clear()
    all.addAll(to, block)
    return QueueStep(all, tab, detach = null)
  }

  /**
   * "Start on: Browse" (#177): a Browse grid opened "now", in front of the current block, and shown. If the active tab
   * already shows Browse, it's shown as it is, so starts don't pile up Browse tabs.
   */
  fun showBrowse() {
    val browse = Route.Grid(emptyList())
    if (activeTab.top == browse) return
    openInFront(browse)
  }

  /** Ctrl-Tab and Ctrl-Shift-Tab, which wrap around ([step]). */
  fun cycle(forward: Boolean) {
    step(forward, wrap = true)
  }

  /**
   * The state to save after each change, once changes stop for [SAVE_DELAY]. The tab list and active tab are flows;
   * the back stacks are snapshot state, so a navigation within a tab counts too.
   */
  @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
  private fun changes(): Flow<SavedTabs> = combine(_tabs, _active, _faded, ::Triple)
    .flatMapLatest { (tabs, active, faded) -> snapshotFlow { savedState(tabs, active, faded) } }
    .distinctUntilChanged()
    .debounce(SAVE_DELAY)

  /** What would be saved now. */
  internal fun saved(): SavedTabs = savedState(_tabs.value, _active.value, _faded.value)

  private fun savedState(all: List<TabState>, active: TabId, faded: List<FadedSource>): SavedTabs {
    // A Settings tab (#205) holds nothing to restore and isn't saved, so `tabs.json` has no route an older app can't
    // read; nor is any tab with Settings anywhere in its back stack. While one is active, the tab it was opened from is
    // saved as active, else the one before it.
    fun holdsSettings(tab: TabState) = tab.backStack.any { it is Route.Settings }
    val tabs = all.filterNot(::holdsSettings)
    val activeTab = all.firstOrNull { it.id == active }
    val shown = if (activeTab == null || !holdsSettings(activeTab)) {
      active
    } else {
      val opener = tabs.firstOrNull { it.id == activeTab.openedFrom }
      (opener ?: all.take(all.indexOf(activeTab)).lastOrNull { it in tabs })?.id ?: active
    }
    return savedState(tabs, faded, tabs.indexOfFirst { it.id == shown }.coerceAtLeast(0))
  }

  private fun savedState(tabs: List<TabState>, faded: List<FadedSource>, activeIndex: Int) = SavedTabs(
    activeIndex = activeIndex,
    tabs = tabs.map { tab ->
      SavedTab(
        tab.id.value,
        tab.backStack.filterIsInstance<Route>(),
        tab.block?.value,
        tab.place,
        kind = tab.kind,
        touched = tab.touched.epochSeconds,
        addedIn = tab.addedIn.value,
        keptAt = tab.keptAt?.epochSeconds,
        closedSource = tab.closedSource,
        title = tab.loadedTitle,
        mosaic = tab.mosaic?.thumbs?.takeIf { it.isNotEmpty() },
        posts = tab.mosaic?.posts,
        gridTouched = tab.savedTouched,
        openedFrom = tab.openedFrom?.value,
      )
    },
    version = SavedTabs.VERSION,
    session = sessions.current.value,
    faded = faded.map(FadedSource::saved),
  )

  // Every tab made here, rather than restored, joins the current session unless it's a reopened older one, and is
  // touched now; a post on top is kept now, unless it's a reopened one kept before.
  private fun newTab(
    routes: List<Route>,
    block: TabId? = null,
    addedIn: SessionId = sessions.current,
    place: GridPlace? = null,
    keptAt: Instant? = null,
    closedSource: List<String>? = null,
    gridTouched: Boolean = false,
    mosaic: QueueMosaic? = null,
    openedFrom: TabId? = null,
  ): TabState {
    val now = clock.now()
    val kept = keptAt ?: now.takeIf { routes.lastOrNull() is Route.Post }
    return TabState(
      TabId(Uuid.random().toString()), routes, block, place, addedIn, touched = now, keptAt = kept,
      closedSource = closedSource, loadedTitles = loaded::get, mosaic = mosaic, gridTouched = gridTouched,
      tagNames = tagNames, openedFrom = openedFrom,
    )
  }

  companion object {
    val SAVE_DELAY = 1.seconds

    /** How many closed tabs [reopenClosed] can bring back. */
    const val REOPENABLE = 20
  }
}
