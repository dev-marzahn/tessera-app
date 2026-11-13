package dev.marzahn.sankaku.features.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.Feed
import kotlinx.coroutines.flow.drop
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.FeedMarks
import dev.marzahn.sankaku.queue.FeedMarksStore
import dev.marzahn.sankaku.queue.FeedPost
import dev.marzahn.sankaku.queue.FeedReading
import dev.marzahn.sankaku.queue.FeedVisit
import kotlin.time.Clock
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.GridStart
import dev.marzahn.sankaku.data.PageCursor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import dev.marzahn.sankaku.data.PostOverride
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.postImageRequest
import dev.marzahn.sankaku.features.post.keptSampleRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Signed out the rating is locked to Safe (decision 2026-10-30): the search panel shows Rating fixed to Safe and every
 * search is sent with `rating:s` in place of any other rating. [safeOnly], the G only switch (`PrivacySettings.gOnly`),
 * locks it signed in too.
 */
fun ratingLocked(signedIn: Boolean, safeOnly: Boolean): Boolean = !signedIn || safeOnly

/**
 * [tags] as searched, filter tokens first ([SearchTags.filtersFirst]): with a locked rating, any rating tag
 * (`rating:q`, `-rating:s`) is replaced by `rating:s`.
 */
fun searchedTags(tags: List<String>, signedIn: Boolean, safeOnly: Boolean): List<String> =
  SearchTags.filtersFirst(if (ratingLocked(signedIn, safeOnly)) withoutRating(tags) + Rating.G.tag else tags)

fun withoutRating(tags: List<String>): List<String> = tags.filterNot(Rating::isRatingTag)

/**
 * Finding a restored grid's place (#162). A network failure never costs the place (#492): until it's [Found] and shown,
 * or [GivenUp], the grid saves no place over it.
 */
sealed interface PlaceLookup {
  /** "Loading where you were…". */
  data object Looking : PlaceLookup

  /** Sankaku couldn't be reached ([error]: offline, 429, 401): the place stays pending, and Retry looks again. */
  data class Unreachable(val error: ApiError) : PlaceLookup

  /**
   * Found: the grid scrolls to [post] once it has loaded, still covered by "Loading where you were…". [jump] for a
   * feed's Continue (#314) rather than the saved place. [firstPost] is set once the place's own pager has loaded a
   * first page holding [post]: the grid waits for a list that starts with it, not the one shown before (#500 review).
   */
  data class Found(val post: String, val jump: Boolean = false, val firstPost: String? = null) : PlaceLookup

  /** Not found (the post is gone, or the filter changed): the grid starts at the top, and says so with Try again. */
  data object GivenUp : PlaceLookup
}

/** How long opening Following waits for the followed tags to see late posts on page 1 (#539); then it goes on without. */
private val CONTEXT_WAIT = 3.seconds

/** Failures that say nothing about the place, only that Sankaku can't answer now: the place waits for a retry. */
private val ApiError.keepsPlacePending: Boolean
  get() = this is ApiError.Network || this is ApiError.RateLimited || this is ApiError.Unauthorized

/**
 * One grid's results. A new pager is built when the user signs in or out (which also replaces a failed one) or turns
 * G only on or off, never on a token refresh. Favourite changes come through [overrides], so they don't resubmit the paged list.
 *
 * With a [place] (a restored or reopened grid tab, #162) it first finds that place ([PostsRepository.locate]) and
 * starts the pager there; [lookup] is then [PlaceLookup.Found], with the post for the grid to scroll to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GridViewModel(
  val tags: List<String>,
  savedPlace: GridPlace?,
  private val repository: PostsRepository,
  private val session: AuthSession,
  store: PostStateStore,
  private val imageLoader: ImageLoader,
  private val context: PlatformContext,
  private val privacy: PrivacySettings,
  private val knownTags: TagsRepository,
  private val marksStore: FeedMarksStore = FeedMarksStore(InMemoryTextFile()),
  private val clock: Clock = Clock.System,
  /** The ids of the followed tags, or null when they can't be read: Following's context (#539). */
  private val followedTagIds: suspend () -> List<String>? = { null },
) : ViewModel() {
  /** The feed this grid is, unnarrowed (Following or For you, any rating): it keeps marks (#314). Else null. */
  val feed: Feed? = withoutRating(tags).singleOrNull()?.let(Feed::of)

  // For you's place goes with its marks after 3 days: it then opens at the top (#314).
  private val place = savedPlace?.takeUnless { feed != null && marksStore.expired(feed) }

  private var visit: FeedVisit? = feed?.let { FeedVisit(it, marksStore.get(it), clock.now()) }

  private val _marks = MutableStateFlow(visit?.shown)

  /** The marks this visit's headings are drawn from: those it started with, and the 7-day offer's answer. */
  val marks: StateFlow<FeedMarks?> = _marks.asStateFlow()

  private val _context = MutableStateFlow<String?>(null)

  /**
   * What Following shows now ([FeedReading.context]: its search and the followed tags), null until it's read or when it
   * can't be: only a stretch read in the same context marks late posts (#539).
   */
  val feedContext: StateFlow<String?> = _context.asStateFlow()

  // The first context read (or null if it couldn't be): opening waits for it to see late posts on page 1.
  private val firstContext = CompletableDeferred<String?>()

  // Goes up on Refresh and Top, so the follows are read again for the next visit.
  private val contextReads = MutableStateFlow(0)

  // A jump to a place (Continue, #314), counted so the same place can be jumped to twice.
  private val jump = MutableStateFlow<Pair<GridPlace, Int>?>(null)
  private val shared = MutableSharedFlow<PagingData<PostDto>>(replay = 1)

  /**
   * A shared flow with a replay, so a grid shown again draws its posts in its first frame (paging-compose reads the
   * replay synchronously). A new grid whose first page is cached starts with that page as static data for the same
   * reason; the pager's data replaces it once it has loaded. Without it, a new tab showed an empty grid for a frame.
   */
  val posts: SharedFlow<PagingData<PostDto>> = shared.asSharedFlow()

  // Whether a page of this grid had posts hidden from signed-out users. Written by the pager's thread.
  private val locked = MutableStateFlow(false)

  /**
   * Signed out, once a page has left out posts hidden from signed-out users: the grid says "Some posts need an
   * account · Sign in" once instead of drawing them (#507). Signed in, never.
   */
  val signedOutNotice: StateFlow<Boolean> = combine(session.isSignedIn, locked) { signedIn, hidden -> !signedIn && hidden }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  private val _lookup = MutableStateFlow<PlaceLookup?>(if (place != null) PlaceLookup.Looking else null)

  /** Where finding the grid's place is (#162, #492); null with no place, or once it's shown. */
  val lookup: StateFlow<PlaceLookup?> = _lookup.asStateFlow()

  // A lookup that couldn't reach Sankaku waits here for Retry. Refresh and ↑ Top cancel [lookupJob] instead.
  private val retries = Channel<Unit>(Channel.CONFLATED)
  private var lookupJob: Job? = null

  // The pager's run: null while Try again looks the place up again (the grid is emptied, so the scroll to the place
  // can't find its post in the old list), then the next run, which starts at the place if it was found.
  private val pagerRun = MutableStateFlow<Int?>(0)

  // Where the next pager starts, and the search it was found in. Read and written on the main thread.
  private var start: GridStart? = null
  private var startFor: List<String>? = null

  private val _midway = MutableStateFlow(false)

  /**
   * True while the grid starts at its place rather than the top, so the newer posts above it aren't loaded. Going to
   * the top ([toTop]) ends it.
   */
  val midway: StateFlow<Boolean> = _midway.asStateFlow()

  // The cursor of the page each post came in on, for the place the grid saves. Written by the pager's thread.
  private val cursors = MutableStateFlow<Map<String, PageCursor>>(emptyMap())

  init {
    restartOnClear()
    readContext()
    // Signing in or out rebuilds the pager: its pages say again whether any post was left out, so the old answer
    // doesn't bring the notice back before they come.
    viewModelScope.launch { session.isSignedIn.drop(1).collect { locked.value = false } }
    // A grid opening at its place doesn't show the top first.
    if (place == null) {
      repository.cachedFirstPage(searchTags())?.let { page ->
        // Without the posts the pager leaves out, as it will (#507).
        val shown = page.filterNot { it.redirectToSignup }
        if (shown.size < page.size) locked.value = true
        if (shown.isNotEmpty()) shared.tryEmit(PagingData.from(shown))
      }
    }
    // Dispatched, not immediate: started right here, the pager's first (still empty) data replaced the seed before
    // the grid's first frame read it.
    viewModelScope.launch(Dispatchers.Main) {
      if (place != null) {
        // The search depends on the session, so the stored one must be loaded first.
        session.state.first { it !is AuthState.Restoring }
        // Until it's found or given up, no pager runs: the grid shows no top for a first scroll to save over the place.
        startLookup(place).join()
      }
      // Signing in or out rebuilds even when the search is the same (it replaces a failed pager); G only does only
      // when it changes what is asked (a search that already has `rating:s` keeps its place).
      combine(session.isSignedIn, privacy.gOnly, pagerRun) { signedIn, gOnly, run ->
        Triple(signedIn, searchedTags(tags, signedIn, gOnly), run)
      }
        .distinctUntilChanged()
        .combine(jump) { search, to -> search to to }
        .flatMapLatest { (search, to) ->
          val (_, searched, run) = search
          if (run == null) return@flatMapLatest flowOf(PagingData.empty())
          if (to != null) return@flatMapLatest postsFrom(searched, jumpStart(searched, to.first))
          // The place only for the search it was found in; a rebuild for another starts at the top.
          val from = start?.takeIf { searched == startFor }
          // A pager that doesn't start at a place still to be shown (another search, or rebuilt before the place's
          // first page came in, which took [start]) can't show it: the place is given up (#500 review).
          if (from == null && (start != null || _lookup.value is PlaceLookup.Found)) {
            _midway.value = false
            placeMissed()
          }
          start = null
          postsFrom(searched, from)
        }
        .cachedIn(viewModelScope)
        .collect { shared.emit(it) }
    }
  }

  /**
   * The pager for [searched] from [from]. A place found is shown or missed by this pager's own first page (#492
   * review): the grid's list may still be the last pager's, and a first page can come back empty.
   */
  private fun postsFrom(searched: List<String>, from: GridStart?): Flow<PagingData<PostDto>> {
    var found = (_lookup.value as? PlaceLookup.Found)?.takeIf { from != null }
    return repository.posts(searched, from, onPage = ::onPage, onLocked = { locked.value = true }) { cursor, posts ->
      onLoaded(cursor, posts)
      found?.let { place ->
        found = null
        viewModelScope.launch(Dispatchers.Main) {
          when {
            // Refresh or another jump may have replaced this place's lookup meanwhile.
            _lookup.value != place -> Unit
            posts.none { it.id == place.post } -> placeMissed()
            else -> _lookup.value = place.copy(firstPost = posts.first().id)
          }
        }
      }
    }
  }

  /** The cursor of the page [postId] came in on, or null if it isn't known (the first page, or not loaded here). */
  fun cursorOf(postId: String): PageCursor? = cursors.value[postId]

  /**
   * Finds [place] ([PostsRepository.locate]). Sankaku not reachable (offline, 429, 401), the place stays pending
   * ([PlaceLookup.Unreachable]) and is looked up again on [retryPlace]; [toTop] gives it up. Not found, or any other
   * failure, it's [PlaceLookup.GivenUp] and the grid starts at the top.
   */
  private fun startLookup(place: GridPlace, pastCache: Boolean = false): Job {
    _lookup.value = PlaceLookup.Looking
    return viewModelScope.launch(Dispatchers.Main) {
      if (pastCache) repository.forgetPages(searchTags())
      while (true) {
        _lookup.value = PlaceLookup.Looking
        val searched = searchTags()
        val found = try {
          // Following with posts newer than the newest one reached opens at the top, at "N new" (#314): nothing to say.
          if (newPostsAbove(searched)) {
            _lookup.value = null
            return@launch
          }
          repository.locate(searched, place)
        } catch (e: ApiError) {
          if (!e.keepsPlacePending) null else {
            // A Retry from before this failure doesn't count for it.
            retries.tryReceive()
            _lookup.value = PlaceLookup.Unreachable(e)
            retries.receive()
            continue
          }
        }
        start = found
        startFor = searched
        _midway.value = found?.midway == true
        if (found != null) _lookup.value = PlaceLookup.Found(place.post) else setGivenUp()
        return@launch
      }
    }.also { lookupJob = it }
  }

  /**
   * Retry, or the connection back: a place that couldn't be looked up is looked up again. Try again on a place given
   * up looks again past the page cache, and the grid starts again from it if it's found.
   */
  fun retryPlace() {
    val place = place ?: return
    when (_lookup.value) {
      is PlaceLookup.Unreachable -> {
        // Looking at once, so a second Retry before the lookup resumes does nothing.
        _lookup.value = PlaceLookup.Looking
        retries.trySend(Unit)
      }
      PlaceLookup.GivenUp -> {
        val run = pagerRun.value ?: 0
        pagerRun.value = null
        val job = startLookup(place, pastCache = true)
        viewModelScope.launch(Dispatchers.Main) {
          try {
            job.join()
          } finally {
            // Found, the pager starts again there; not (or Refresh gave it up), from the top again.
            pagerRun.value = run + 1
          }
        }
      }
      else -> Unit
    }
  }

  /** The grid has scrolled to its place's post. */
  fun placeShown() {
    _lookup.value = null
  }

  /** The pager's first page came in without the place's post (or empty): the grid stays at the top, and says so. */
  fun placeMissed() {
    val found = _lookup.value as? PlaceLookup.Found ?: return
    // A Continue's jump (#314) that misses stays at the top silently: Try again is for the saved place.
    if (found.jump) _lookup.value = null else setGivenUp()
  }

  private var givenUpSaid = false

  private fun setGivenUp() {
    givenUpSaid = false
    _lookup.value = PlaceLookup.GivenUp
  }

  /** True once for each place given up: the grid says so then, not again each time its tab is shown. */
  fun sayGivenUp(): Boolean {
    if (givenUpSaid) return false
    givenUpSaid = true
    return true
  }

  /**
   * Refresh and the Top button: the grid reloads from the top, so it no longer starts midway. A feed's visit starts
   * again from the marks it got to, so "N new" counts what came in since.
   */
  fun toTop() {
    _midway.value = false
    cursors.value = emptyMap()
    // A place still being looked for, pending or not yet shown is given up: the user asked for the top.
    lookupJob?.cancel()
    start = null
    _lookup.value = null
    jump.value = null
    val feed = feed ?: return
    visit = FeedVisit(feed, visit?.marks ?: marksStore.get(feed), clock.now(), _context.value)
    _marks.value = visit?.shown
    contextReads.update { it + 1 }
  }

  /**
   * Following's context (#539): its search, as signing in and G only make it, and the followed tags, read again when
   * either changes and on each Refresh or Top. Unknown (null) while a changed search's is read, so nothing reached then
   * makes a stretch; on Refresh or Top the previous one holds until the read ends, so the New pills don't flicker.
   */
  private fun readContext() {
    if (feed != Feed.Following) {
      firstContext.complete(null)
      return
    }
    viewModelScope.launch {
      session.state.first { it !is AuthState.Restoring }
      var last: List<String>? = null
      combine(session.isSignedIn, privacy.gOnly, contextReads) { signedIn, gOnly, _ -> searchedTags(tags, signedIn, gOnly) }
        .collectLatest { searched ->
          if (searched != last) {
            _context.value = null
            visit?.context = null
          }
          last = searched
          val context = followedTagIds()?.let { FeedReading.context(searched, it) }
          visit?.context = context
          _context.value = context
          firstContext.complete(context)
        }
    }
  }

  // Whether Following's first page starts with a post newer than the newest one reached, or has one that turned up late
  // below it (#539): then it opens at the top.
  private suspend fun newPostsAbove(searched: List<String>): Boolean {
    if (feed != Feed.Following) return false
    val marks = _marks.value ?: return false
    val page = repository.gridStart(searched)
    val first = page.firstOrNull() ?: return false
    if (first.id != marks.top.post && (first.createdAt?.epochSeconds ?: 0) > marks.top.time) return true
    return FeedReading.late(Feed.Following, feedPostsOf(page) { null }, atTop = true, marks, withTimeoutOrNull(CONTEXT_WAIT) { firstContext.await() }).isNotEmpty()
  }

  // Where a jump opens: the page holding the place, the grid covered meanwhile ([lookup] Looking, then Found). A jump
  // that misses, or can't reach Sankaku, opens at the top and says nothing: the saved place isn't the jump's.
  private suspend fun jumpStart(searched: List<String>, to: GridPlace): GridStart? {
    _lookup.value = PlaceLookup.Looking
    val located = try {
      repository.locate(searched, to)
    } catch (e: ApiError) {
      null
    }
    _midway.value = located?.midway == true
    _lookup.value = located?.let { PlaceLookup.Found(to.post, jump = true) }
    return located
  }

  /**
   * The user reached the posts from [first] to [last] of [posts], the grid's loaded posts (#314): a feed's marks move
   * on, and are saved.
   */
  fun reach(posts: List<FeedPost>, first: Int, last: Int) = save { it.reach(posts, atTop = !_midway.value, first, last) }

  /** Continue (#314): opens the grid at the gap's stop mark, by its cursor or, when that's gone, by `date:`. */
  fun continueFromGap() {
    val stop = _marks.value?.gap?.stop ?: return
    jump.value = stop.place to ((jump.value?.second ?: 0) + 1)
  }

  /** The 7-day offer showed (#314): it isn't offered again. */
  fun offerShown() = save { it.offerShown() }

  /** The 7-day offer's Keep. */
  fun keepGap() = save { it.keep() }

  /** The 7-day offer's Let go: the gap goes. */
  fun letGapGo() = save { it.letGo() }

  // [change]s the visit, then shows and saves the marks it comes to.
  private fun save(change: (FeedVisit) -> Unit) {
    val feed = feed ?: return
    val visit = visit ?: return
    change(visit)
    _marks.value = visit.shown
    visit.marks?.let { marksStore.set(feed, it) }
  }

  // Sign-out and Clear everything clear the marks: the visit starts again from none, so it can't write the old back.
  private fun restartOnClear() {
    val feed = feed ?: return
    viewModelScope.launch {
      marksStore.clears.drop(1).collect {
        visit = FeedVisit(feed, null, clock.now(), _context.value)
        _marks.value = null
      }
    }
  }

  private fun onLoaded(cursor: PageCursor, posts: List<PostDto>) {
    cursors.update { known -> known + posts.associate { it.id to cursor } }
  }

  val overrides: StateFlow<Map<String, PostOverride>> = store.overrides

  /** Drops this grid's cached pages, so the next load (a refresh) asks the API for what's new. */
  suspend fun forgetPages() = repository.forgetPages(searchTags())

  private fun searchTags() = searchedTags(tags, session.isSignedIn.value, privacy.gOnly.value)

  /** Each page teaches the app its posts' tags, so search chips can show their names and colours. */
  private fun onPage(posts: List<PostDto>) {
    viewModelScope.launch { knownTags.learnFromPosts(posts.flatMap { it.tags }) }
    prefetchPreviews(posts)
  }

  /**
   * Touch: [post], just kept, puts its sample on disk (#263, [keptSampleRequest]): one request for each keep, so its
   * turn in the queue starts from the disk, not the network.
   */
  fun prefetchKept(post: PostDto) {
    keptSampleRequest(context, post)?.let(imageLoader::enqueue)
  }

  /** Fills the disk cache only, so every loaded preview is cached before its URL expires. */
  private fun prefetchPreviews(posts: List<PostDto>) {
    posts.filterNot { it.redirectToSignup }.forEach { post ->
      imageLoader.enqueue(
        postImageRequest(context, PostImage.of(post, Variant.Preview)) { memoryCachePolicy(CachePolicy.DISABLED) },
      )
    }
  }
}
