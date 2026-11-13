package dev.marzahn.sankaku.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.dto.Page
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PostsRepository(
  private val api: PostsApi,
  private val store: PostStateStore,
  private val authSession: AuthSession,
  private val scope: CoroutineScope,
  private val clock: Clock = Clock.System,
  /** Where a favourite or vote made while Sankaku can't be reached waits to be sent (#505); none by default. */
  private val outbox: Outbox = Outbox(InMemoryTextFile()) { null },
) {
  private val lock = Mutex()
  private val favouriteLocks = mutableMapOf<String, Mutex>()
  private val voteLocks = mutableMapOf<String, Mutex>()
  private val refetches = mutableMapOf<String, Deferred<PostDto>>()

  // Each map's insertion order is use order: a hit moves its entry to the end, so the first is the least recently used.
  private val fullPosts = LinkedHashMap<String, PostDto>()
  private val fullFetches = mutableMapOf<String, Deferred<PostDto>>()
  private val pages = LinkedHashMap<PageKey, CachedPage>()
  private val pageFetches = mutableMapOf<PageKey, PageFetch>()
  private val latestPosts = LinkedHashMap<String, PostDto>()
  // The details' strips: Recommended, Parent and Children.
  private val strips = LinkedHashMap<StripKey, List<PostDto>>()
  private val stripFetches = mutableMapOf<StripKey, Deferred<List<PostDto>>>()

  // Bumped (under [lock]) whenever the caches are cleared for another account. A fetch remembers it before asking and
  // keeps its result only if it's unchanged, so a response for the previous account never lands after the clear.
  private var account = 0

  private fun userOf(state: AuthState) = (state as? AuthState.SignedIn)?.user?.id

  /** Whose state the caches hold: `user` is null when signed out. */
  private data class CachesOwner(val user: String?)

  // Set when the collector below first sees a user, and after each clear; null until then.
  private val cachesOwner = MutableStateFlow<CachesOwner?>(null)

  init {
    // Favourite state (and so every cached post and page) belongs to one account, so it all goes whenever the
    // signed-in user changes. Signed-out pages also lack the URLs of restricted posts.
    scope.launch {
      authSession.state
        .map(::userOf)
        .distinctUntilChanged()
        .withIndex()
        .collect { (index, user) ->
          if (index > 0) clearCaches() else lock.withLock { restorePending() }
          cachesOwner.value = CachesOwner(user)
        }
    }
  }

  // Under [lock]. What waits in the [outbox] shows as made again, if it's the signed-in account's: at launch, and after
  // each clear (Clear everything keeps the session, and what waits is still sent).
  private fun restorePending() {
    val user = userOf(authSession.state.value) ?: return
    if (outbox.owner == user) store.restorePending(outbox.changes.value)
  }

  /**
   * Suspends until the caches hold the current account's state. A toggle made right after signing in waits for this,
   * so the clear for the new account can't wipe its override after it began.
   */
  suspend fun awaitCurrentAccount() {
    combine(authSession.state, cachesOwner) { state, owner -> owner != null && owner.user == userOf(state) }
      .first { it }
  }

  /**
   * Forgets every page, post, recommendation and per-post override held in memory, and any request in flight. Runs
   * when the signed-in user changes (the caches belong to one account) and for "Clear everything".
   */
  suspend fun clearCaches() {
    lock.withLock {
      account++
      pages.clear()
      latestPosts.clear()
      fullPosts.clear()
      strips.clear()
      // A request still in flight belongs to the previous account: new callers ask again instead of joining it.
      refetches.clear()
      fullFetches.clear()
      stripFetches.clear()
      pageFetches.clear()
      // Under the lock too, so a new account's fetch can't set URLs or a vote that this then wipes.
      store.clear()
      restorePending()
    }
  }

  /**
   * A keyset grid for [tags]. Pages loaded in the last [PAGE_CACHE_TTL] come from memory, so a second grid on the
   * same search (another tab, or the one a post tab returns to) starts with what's loaded. [onPage] sees each page
   * the API returns, once, for preview prefetching, including one a preload put in the cache ([gridStart]); [onLoaded] each page's posts as the grid gets them, with their cursor.
   * Posts hidden from signed-out users (`redirect_to_signup`) are left out, and [onLocked] hears of each page that had
   * any: the grid says so once instead of drawing them (#507).
   *
   * With a [start] ([locate]), the first load is that page of its search; a refresh starts at the top of [tags].
   */
  fun posts(
    tags: List<String>,
    start: GridStart? = null,
    onPage: ((List<PostDto>) -> Unit)? = null,
    onLocked: () -> Unit = {},
    onLoaded: (PageCursor, List<PostDto>) -> Unit = { _, _ -> },
  ): Flow<PagingData<PostDto>> {
    // Taken by the first source only; Paging makes a new source for each refresh.
    var from = start
    return Pager(
      PagingConfig(
        pageSize = PostsApi.PAGE_SIZE,
        initialLoadSize = PostsApi.PAGE_SIZE,
        prefetchDistance = PREFETCH_DISTANCE,
        enablePlaceholders = false,
      ),
    ) {
      val searched = from?.tags ?: tags
      val cursor = from?.cursor ?: PageCursor()
      from = null
      KeysetPagingSource(PostDto::id, cursor, onLoaded, hide = PostDto::redirectToSignup, onHidden = onLocked) {
        page(searched, it, onPage)
      }
    }.flow
  }

  /**
   * Where the grid for [tags] opens to show [place], trying in turn (#162):
   * 1. The saved cursor's page, if it still holds the post (one request, the usual case).
   * 2. For a search ordered by upload time, the search narrowed by `date:` to the post's hour and before, whose first
   *    [PLACE_DATED_PAGES] pages are searched for it: a rejected or expired cursor, or new posts that moved it.
   * 3. Otherwise, other than For you, the search's first [PLACE_SCAN_PAGES] pages, from the top.
   *
   * Null when it isn't found (deleted, or the filter changed): the grid starts at the top. Each page it loads is
   * cached, so the grid loads none of them again. A lost connection is thrown; any other failure ends that way.
   */
  suspend fun locate(tags: List<String>, place: GridPlace): GridStart? {
    val saved = place.pageCursor
    if (pageOrNull(tags, saved)?.holds(place.post) == true) return GridStart(tags, saved)
    val time = place.time
    return when {
      timeOrdered(tags) && time != null -> find(datedSearch(tags, time), place.post, PLACE_DATED_PAGES)
      !isForYou(tags) -> find(tags, place.post, PLACE_SCAN_PAGES)
      else -> null
    }
  }

  // The start of the page among the first [pages] of [tags] that holds [post], or null.
  private suspend fun find(tags: List<String>, post: String, pages: Int): GridStart? {
    var cursor = PageCursor()
    repeat(pages) {
      val page = pageOrNull(tags, cursor) ?: return null
      if (page.holds(post)) return GridStart(tags, cursor)
      cursor = cursor.after(page) ?: return null
    }
    return null
  }

  // [page], or null when Sankaku answers with an error (a rejected cursor is a 400, a slow search a 408).
  private suspend fun pageOrNull(tags: List<String>, cursor: PageCursor): Page<PostDto>? = try {
    page(tags, cursor)
  } catch (e: ApiError) {
    if (e is ApiError.Network || e is ApiError.RateLimited || e is ApiError.Unauthorized) throw e
    null
  }

  private fun Page<PostDto>.holds(post: String) = data.any { it.id == post }

  /** Drops [tags]' cached pages, so the grid's next load (a refresh) asks the API for what's new. */
  suspend fun forgetPages(tags: List<String>) = lock.withLock {
    pages.keys.removeAll { it.tags == tags }
    // A request on the way is from before the refresh: the refresh asks again, and its answer isn't cached.
    pageFetches.keys.removeAll { it.tags == tags }
  }

  /**
   * The posts of [tags]' first page if it's cached, fresh and not empty, without waiting: null while the cache is
   * busy. For a new grid's first frame, which can't wait for the pager.
   */
  fun cachedFirstPage(tags: List<String>): List<PostDto>? {
    if (!lock.tryLock()) return null
    try {
      val cached = pages[PageKey(tags, PageCursor())]?.takeIf { clock.now() - it.loadedAt < PAGE_CACHE_TTL } ?: return null
      return cached.page.data.takeIf { it.isNotEmpty() }
    } finally {
      lock.unlock()
    }
  }

  /**
   * The page a grid for [tags] opens on, loaded into the page cache ahead of the grid (#317, the queue's preload): the
   * first, or with a [place] the saved cursor's page, which [locate] tries first. The grid then takes it from the
   * cache, or joins its request if it's still on the way, so it makes no request of its own.
   */
  suspend fun gridStart(tags: List<String>, place: GridPlace? = null): List<PostDto> =
    page(tags, place?.pageCursor ?: PageCursor()).data

  /**
   * One keyset page: from the page cache while it's fresh, else from the API, which then replaces it. Callers asking
   * for the same page at once share one request, which is cancelled once none of them waits for it any more.
   *
   * [onPage] sees each page once, the first time a caller with one gets it: a page loaded without one (a preload,
   * [locate]) is seen by the grid that later takes it from the cache.
   */
  internal suspend fun page(
    tags: List<String>,
    cursor: PageCursor = PageCursor(),
    onPage: ((List<PostDto>) -> Unit)? = null,
  ): Page<PostDto> {
    val key = PageKey(tags, cursor)
    // The cached page, or the request to wait for.
    val (cached, fetch) = lock.withLock {
      val fresh = pages.remove(key)?.takeIf { clock.now() - it.loadedAt < PAGE_CACHE_TTL }
      if (fresh != null) {
        pages[key] = fresh
        fresh.page to null
      } else {
        null to pageFetches.getOrPut(key) { PageFetch(fetchPage(key, account)) }.also { it.waiters++ }
      }
    }
    val page = cached ?: awaitPage(key, checkNotNull(fetch))
    // A page for the previous account was never cached, so it's seen by no one: its pager is replaced on the change.
    if (onPage != null && lock.withLock { deliver(key, page) }) onPage(page.data)
    return page
  }

  private suspend fun awaitPage(key: PageKey, fetch: PageFetch): Page<PostDto> = try {
    fetch.deferred.start()
    fetch.deferred.await().getOrThrow()
  } finally {
    withContext(NonCancellable) {
      lock.withLock {
        fetch.waiters--
        // The last caller gone (a preload moved on): the request has no one left to answer, so it stops, also when a
        // refresh or an account change has dropped it already and no one can join it.
        if (fetch.waiters == 0) {
          if (pageFetches[key] === fetch) pageFetches.remove(key)
          fetch.deferred.cancel()
        }
      }
    }
  }

  // Under [lock]: whether [page], cached under [key], is seen for the first time, which it then no longer is.
  private fun deliver(key: PageKey, page: Page<PostDto>): Boolean {
    val cached = pages[key]?.takeIf { it.page === page && !it.delivered } ?: return false
    cached.delivered = true
    return true
  }

  /**
   * Under [lock]: the request for [key], cached if it's still the one to join and the account still [asked]'s when it
   * answers. A failure is its
   * result, for each caller to throw, rather than the shared scope's. Answered, it's no longer the one to join, so a
   * caller after a failure asks again.
   */
  private fun fetchPage(key: PageKey, asked: Int): Deferred<Result<Page<PostDto>>> = scope.async(start = CoroutineStart.LAZY) {
    val self = coroutineContext[Job]
    val overridesWhenAsked = store.overrides.value
    val page = try {
      api.keyset(key.tags, key.cursor.next, key.cursor.number)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      lock.withLock { if (pageFetches[key]?.deferred === self) pageFetches.remove(key) }
      return@async Result.failure(e)
    }
    lock.withLock {
      // Still the request to join: not dropped since by a refresh ([forgetPages]) or an account change.
      val current = pageFetches[key]?.deferred === self
      if (current) pageFetches.remove(key)
      if (current && account == asked) {
        store.takeListed(page.data, overridesWhenAsked)
        pages.putBounded(key, CachedPage(page, clock.now()), PAGE_CACHE_SIZE)
        page.data.forEach { latestPosts.putBounded(it.id, it, POST_CACHE_SIZE) }
      }
    }
    Result.success(page)
  }

  /**
   * Where [postId] is in the grid for [tags], counting only fresh cached pages from the first: those are the pages a
   * new grid on that search loads without the API. Like the grid, it counts a post a later page repeats only once, and
   * no post hidden from signed-out users. Null if it isn't among them.
   */
  suspend fun cachedIndexOf(tags: List<String>, postId: String): Int? = lock.withLock {
    var cursor = PageCursor()
    val seen = mutableSetOf<String>()
    var index = 0
    repeat(PAGE_CACHE_SIZE) {
      val cached = pages[PageKey(tags, cursor)]?.takeIf { clock.now() - it.loadedAt < PAGE_CACHE_TTL } ?: return null
      for (post in cached.page.data) {
        if (!seen.add(post.id) || post.redirectToSignup) continue
        if (post.id == postId) return index
        index++
      }
      cursor = cursor.after(cached.page) ?: return null
    }
    null
  }

  /** The latest copy of [postId] the app holds (the full post if it was fetched), or null. */
  suspend fun cachedPost(postId: String): PostDto? = lock.withLock { held(postId) }

  /**
   * [cachedPost] without waiting: null while the cache is busy. For a lone post's first frame, which can't wait a
   * coroutine's hop (#277).
   */
  fun heldPost(postId: String): PostDto? {
    if (!lock.tryLock()) return null
    try {
      return held(postId)
    } finally {
      lock.unlock()
    }
  }

  /**
   * Holds [post], a list's copy from outside a page (a recommendation), as a page's posts are held, unless the app
   * holds it already. So the lone post it opens is loaded from its first frame, with its picture to grow into (#321).
   */
  suspend fun hold(post: PostDto) {
    lock.withLock { if (held(post.id) == null) latestPosts.putBounded(post.id, post, POST_CACHE_SIZE) }
  }

  /** [cachedPost] for each of [postIds] the app holds, under one lock: the touch tab list asks for hundreds. */
  suspend fun cachedPosts(postIds: Collection<String>): Map<String, PostDto> = lock.withLock {
    postIds.mapNotNull { id -> held(id)?.let { id to it } }.toMap()
  }

  // Under [lock].
  private fun held(postId: String): PostDto? = fullPosts[postId] ?: latestPosts[postId]

  /**
   * Flips [post]'s favourite at once, then calls the API. If Sankaku can't be reached, the favourite stays and waits in
   * the [Outbox] (#505); on any other failure the previous state comes back, unless a newer toggle owns the post by
   * then. Only the API calls for one post are serialized.
   */
  suspend fun toggleFavourite(post: PostDto): Result<Unit> {
    val current = post.merge(store.overrides.value[post.id])
    val target = !current.isFavorited
    val favCount = (current.favCount + if (target) 1 else -1).coerceAtLeast(0)
    val generation = store.beginToggle(post.id, target, favCount)

    // The outbox changes only under the post's lock, in the order the calls ran, so a toggle that got through after this
    // one can't be undone by this one's change waiting after it.
    val outcome = favouriteLock(post.id).withLock {
      try {
        if (target) api.favourite(post.id) else api.unfavourite(post.id)
        // Sankaku now has what the user last asked for: a favourite still waiting from earlier is settled.
        outbox.settled(Outbox.Kind.Favourite, post.id)
        Outcome.Sent
      } catch (e: ApiError) {
        val waits = e is ApiError.Network &&
          outbox.add(PendingChange.Favourite(post.id, target, favCount, current.isFavorited, current.favCount))
        if (waits) Outcome.Waits else Outcome.Failed(e, pending = outbox.holds(Outbox.Kind.Favourite, post.id))
      }
    }
    when (outcome) {
      Outcome.Sent -> store.setFavourite(post.id, target, favCount, inFlight = false, generation = generation)
      Outcome.Waits -> {}
      // Still running if an earlier favourite waits to be sent.
      is Outcome.Failed ->
        store.setFavourite(post.id, current.isFavorited, current.favCount, inFlight = outcome.pending, generation = generation)
    }
    return outcome.result
  }

  /** How a toggle's call went: [Waits] when it waits in the [Outbox]; [Failed] with whether an earlier one waits. */
  private sealed interface Outcome {
    data object Sent : Outcome

    data object Waits : Outcome

    data class Failed(val error: ApiError, val pending: Boolean) : Outcome

    val result: Result<Unit> get() = if (this is Failed) Result.failure(error) else Result.success(Unit)
  }

  /**
   * Votes [score] (1–5) on [post], or takes the account's vote back with null. The vote and the post's totals change
   * at once. If Sankaku can't be reached, the vote stays and waits in the [Outbox] (#505); on any other failure the
   * previous ones come back, unless a newer vote owns the post by then. A vote the response counts differently takes
   * the response's count.
   */
  suspend fun vote(post: PostDto, score: Int?): Result<Unit> {
    val current = post.merge(store.overrides.value[post.id])
    val target = votedAs(current.voteState(), score)
    val generation = store.beginVote(post.id, target)
    var counted: Int? = null
    // As for a favourite: the outbox changes only under the post's lock.
    val outcome = voteLock(post.id).withLock {
      try {
        counted = (if (score != null) api.vote(post.id, score) else api.unvote(post.id)).voteCount
        outbox.settled(Outbox.Kind.Vote, post.id)
        Outcome.Sent
      } catch (e: ApiError) {
        val waits = e is ApiError.Network && outbox.add(PendingChange.Vote(post.id, target, current.voteState()))
        if (waits) Outcome.Waits else Outcome.Failed(e, pending = outbox.holds(Outbox.Kind.Vote, post.id))
      }
    }
    when (outcome) {
      Outcome.Sent -> store.setVote(post.id, target.copy(voteCount = counted ?: target.voteCount), generation)
      Outcome.Waits -> {}
      is Outcome.Failed -> store.setVote(post.id, current.voteState(), generation, inFlight = outcome.pending)
    }
    return outcome.result
  }

  /**
   * Sends [change], which waited in the [Outbox]. Sent, it leaves the outbox and the post settles on it; refused by
   * Sankaku, it leaves the outbox and the post goes back to how it was before it. Either way a newer toggle of the
   * post made meanwhile keeps its own state, and a change that no longer waits by the time the post's lock is taken (a
   * toggle that got through since settled or replaced it) isn't sent. Returns null once sent or no longer waiting, or
   * the error: [waits] says whether the change stays for the next try.
   */
  suspend fun send(change: PendingChange.Favourite): ApiError? {
    // Before the lock: a toggle that starts while this waits for it owns the post's state.
    val generation = store.overrides.value[change.postId]?.generation ?: 0
    return favouriteLock(change.postId).withLock { sendLocked(change, generation) }
  }

  private suspend fun sendLocked(change: PendingChange.Favourite, generation: Long): ApiError? {
    if (change !in outbox.changes.value) return null
    val error = try {
      if (change.favourited) api.favourite(change.postId) else api.unfavourite(change.postId)
      null
    } catch (e: ApiError) {
      e
    }
    if (error?.waits() == true) return error
    outbox.remove(change)
    if (error == null) {
      store.setFavourite(change.postId, change.favourited, change.favCount, inFlight = false, generation = generation)
    } else {
      store.setFavourite(change.postId, change.wasFavourited, change.wasFavCount, inFlight = false, generation = generation)
    }
    return error
  }

  /** As [send] for a favourite, for a vote that waited in the [Outbox]. */
  suspend fun send(change: PendingChange.Vote): ApiError? {
    val generation = store.overrides.value[change.postId]?.voteGeneration ?: 0
    return voteLock(change.postId).withLock { sendLocked(change, generation) }
  }

  private suspend fun sendLocked(change: PendingChange.Vote, generation: Long): ApiError? {
    if (change !in outbox.changes.value) return null
    val score = change.vote.userVote
    return try {
      val result = if (score != null) api.vote(change.postId, score) else api.unvote(change.postId)
      outbox.remove(change)
      store.setVote(change.postId, change.vote.copy(voteCount = result.voteCount ?: change.vote.voteCount), generation)
      null
    } catch (e: ApiError) {
      if (!e.waits()) {
        outbox.remove(change)
        store.setVote(change.postId, change.was, generation)
      }
      e
    }
  }

  /** Fetches [postId] again for fresh URLs. Concurrent callers for one post share a single request. */
  suspend fun refetch(postId: String): PostDto =
    singleFlight(refetches, postId) {
      val asked = lock.withLock { account }
      api.byId(postId).also { post ->
        lock.withLock {
          if (account != asked) return@withLock
          store.setUrls(post)
          latestPosts.putBounded(postId, post, POST_CACHE_SIZE)
        }
      }
    }

  /**
   * [postId] with every tag (the keyset list carries 5). The last [FULL_POST_CACHE_SIZE] are kept in memory, so
   * stepping back and forth through posts doesn't refetch them. Concurrent callers for one post share a single
   * request, whose fresh URLs go to the [PostStateStore] like a [refetch]'s.
   */
  suspend fun fullPost(postId: String): PostDto {
    lock.withLock {
      fullPosts.remove(postId)?.let { cached ->
        fullPosts[postId] = cached
        return cached
      }
    }
    return singleFlight(fullFetches, postId) {
      val asked = lock.withLock { account }
      api.full(postId).also { post ->
        lock.withLock {
          if (account != asked) return@withLock
          store.setUrls(post)
          // Only a full post carries the account's own vote.
          store.setServerVote(post)
          fullPosts.putBounded(postId, post, FULL_POST_CACHE_SIZE)
        }
      }
    }
  }

  /**
   * The posts Sankaku recommends for [postId], narrowed by [tags] (the locked `rating:s`), without the post itself and
   * without the posts hidden from signed-out users (no image to show). The last [STRIP_CACHE_SIZE] strips are kept
   * in memory, so stepping back through posts doesn't ask again. Opening one fetches it, unless a phone held it first
   * ([hold]).
   * Concurrent callers for one list share a single request.
   */
  suspend fun recommended(postId: String, tags: List<String> = emptyList()): List<PostDto> =
    strip(StripKey(Strip.Recommended, postId, tags)) {
      api.recommended(postId, tags).filter { it.id != postId && it.showable }
    }

  /**
   * The first [PostsApi.CHILDREN_LIMIT] children of [postId] (`parent:<id>`), narrowed by [tags] (the locked
   * `rating:s`), without the posts hidden from signed-out users. One request, kept as [recommended]'s lists are.
   */
  suspend fun children(postId: String, tags: List<String> = emptyList()): Children {
    // Kept as the API sent it, so whether there may be more counts the posts left out here too.
    val page = strip(StripKey(Strip.Children, postId, tags)) { api.children(postId, tags) }
    return Children(page.filter { it.showable }, mayHaveMore = page.size >= PostsApi.CHILDREN_LIMIT)
  }

  /** [children]' posts, and whether the post may have more than the first page holds. */
  data class Children(val posts: List<PostDto>, val mayHaveMore: Boolean)

  /**
   * The parent [parentId], or null when [tags] (the locked `rating:s`) leave it out, it's hidden from signed-out users,
   * or it's gone. The copy the app holds if it has one it can show and [tags] are only ratings, so a parent seen in a
   * grid costs no request; else one (`id:`), kept as [recommended]'s lists are.
   */
  suspend fun parent(parentId: String, tags: List<String> = emptyList()): PostDto? {
    val held = lock.withLock { held(parentId) }
    if (held != null && held.showable && tags.all { it == "rating:${held.rating}" }) return held
    return strip(StripKey(Strip.Parent, parentId, tags)) {
      listOfNotNull(api.byIdOrNull(parentId, tags)?.takeIf { it.showable })
    }.firstOrNull()
  }

  /** One of the details' strips: from memory if it's among the last [STRIP_CACHE_SIZE], else [fetch]ed once. */
  private suspend fun strip(key: StripKey, fetch: suspend () -> List<PostDto>): List<PostDto> {
    lock.withLock {
      strips.remove(key)?.let { cached ->
        strips[key] = cached
        return cached
      }
    }
    return singleFlight(stripFetches, key) {
      val asked = lock.withLock { account }
      val posts = fetch()
      lock.withLock { if (account == asked) strips.putBounded(key, posts, STRIP_CACHE_SIZE) }
      posts
    }
  }

  /** Runs [fetch] once per [key] at a time: concurrent callers share the request in flight in [inFlight]. */
  private suspend fun <K, V> singleFlight(
    inFlight: MutableMap<K, Deferred<V>>,
    key: K,
    fetch: suspend () -> V,
  ): V {
    val deferred = lock.withLock {
      inFlight.getOrPut(key) {
        scope.async(start = CoroutineStart.LAZY) {
          try {
            fetch()
          } finally {
            // Only its own entry: after an account change the key may hold a newer request.
            val self = coroutineContext[Job]
            lock.withLock { if (inFlight[key] === self) inFlight.remove(key) }
          }
        }
      }
    }
    deferred.start()
    return deferred.await()
  }

  private suspend fun favouriteLock(id: String): Mutex = lock.withLock { favouriteLocks.getOrPut(id) { Mutex() } }

  private suspend fun voteLock(id: String): Mutex = lock.withLock { voteLocks.getOrPut(id) { Mutex() } }

  private data class PageKey(val tags: List<String>, val cursor: PageCursor)

  private enum class Strip { Recommended, Parent, Children }

  private data class StripKey(val strip: Strip, val postId: String, val tags: List<String>)

  // [delivered] once a caller with an `onPage` has seen it ([page]).
  private class CachedPage(val page: Page<PostDto>, val loadedAt: Instant, var delivered: Boolean = false)

  // A page request and how many callers wait for it, under [lock].
  private class PageFetch(val deferred: Deferred<Result<Page<PostDto>>>, var waiters: Int = 0)

  companion object {
    /** Full posts kept in memory. One has up to a few hundred tags, so this stays well under a few MB. */
    const val FULL_POST_CACHE_SIZE = 200

    /** Keyset pages kept in memory, across all searches: 1,800 posts of a few KB each. */
    const val PAGE_CACHE_SIZE = 60

    /** List posts kept by id, from pages and refetches, for a post tab to show at once. */
    const val POST_CACHE_SIZE = 1_000

    /**
     * The details' strips kept in memory (Recommended, Parent and Children, each by post), of up to
     * [PostsApi.RECOMMENDED_LIMIT] or [PostsApi.CHILDREN_LIMIT] list posts each.
     */
    const val STRIP_CACHE_SIZE = 64

    /**
     * How long a cached page stands in for the API. Short, so a search opened again later shows new posts; long
     * enough to cover going back to a grid. Expired image URLs in it are refreshed as usual when an image fails.
     */
    val PAGE_CACHE_TTL = 10.minutes

    /**
     * How many pages of the `date:` search [locate] looks through: a busy search holds about a page an hour, and the
     * post is within its first hour's posts.
     */
    const val PLACE_DATED_PAGES = 5

    /** How many pages from the top [locate] looks through in a search not ordered by time: 600 posts. */
    const val PLACE_SCAN_PAGES = 20
  }
}

/**
 * [current] after the account votes [score], or takes its vote back with null: a first vote adds one to the count,
 * a changed vote only moves the total, and the score comes off the total when taken back.
 */
internal fun votedAs(current: VoteState, score: Int?): VoteState {
  val previous = current.userVote
  val count = current.voteCount + when {
    previous == null && score != null -> 1
    previous != null && score == null -> -1
    else -> 0
  }
  val total = current.totalScore - (previous ?: 0) + (score ?: 0)
  return VoteState(score, count.coerceAtLeast(0), total.coerceAtLeast(0))
}

/** Puts [value] last (most recently used) and drops the least recently used entries above [max]. */
internal fun <K, V> LinkedHashMap<K, V>.putBounded(key: K, value: V, max: Int) {
  remove(key)
  put(key, value)
  while (size > max) remove(keys.first())
}

/**
 * The next page loads once the user is within this many items of the end. A full page (30) made a large window load
 * two pages at start, since a first page barely fills it.
 */
private const val PREFETCH_DISTANCE = 12

/** A post a strip can show: not hidden from signed-out users, with a preview to draw. */
private val PostDto.showable: Boolean get() = !redirectToSignup && previewUrl != null
