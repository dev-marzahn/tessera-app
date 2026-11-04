package dev.marzahn.sankaku.features.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import dev.marzahn.sankaku.api.ApiError
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
 * One grid's results. A new pager is built when the user signs in or out (which also replaces a failed one) or turns
 * G only on or off, never on a token refresh. Favourite changes come through [overrides], so they don't resubmit the paged list.
 *
 * With a [place] (a restored or reopened grid tab, #162) it first finds that place ([PostsRepository.locate]) and
 * starts the pager there; [placePost] is then the post for the grid to scroll to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GridViewModel(
  val tags: List<String>,
  place: GridPlace?,
  private val repository: PostsRepository,
  private val session: AuthSession,
  store: PostStateStore,
  private val imageLoader: ImageLoader,
  private val context: PlatformContext,
  private val privacy: PrivacySettings,
  private val knownTags: TagsRepository,
) : ViewModel() {
  private val shared = MutableSharedFlow<PagingData<PostDto>>(replay = 1)

  /**
   * A shared flow with a replay, so a grid shown again draws its posts in its first frame (paging-compose reads the
   * replay synchronously). A new grid whose first page is cached starts with that page as static data for the same
   * reason; the pager's data replaces it once it has loaded. Without it, a new tab showed an empty grid for a frame.
   */
  val posts: SharedFlow<PagingData<PostDto>> = shared.asSharedFlow()

  private val _restoring = MutableStateFlow(place != null)

  /** True while the grid looks for its place: it shows "Loading where you were…". */
  val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

  private val _placePost = MutableStateFlow<String?>(null)

  /** The post the grid scrolls to once its first page is in, set when its place is found; [placeShown] clears it. */
  val placePost: StateFlow<String?> = _placePost.asStateFlow()

  private val _midway = MutableStateFlow(false)

  /**
   * True while the grid starts at its place rather than the top, so the newer posts above it aren't loaded. Going to
   * the top ([toTop]) ends it.
   */
  val midway: StateFlow<Boolean> = _midway.asStateFlow()

  // The cursor of the page each post came in on, for the place the grid saves. Written by the pager's thread.
  private val cursors = MutableStateFlow<Map<String, PageCursor>>(emptyMap())

  init {
    // A grid opening at its place doesn't show the top first.
    if (place == null) repository.cachedFirstPage(searchTags())?.let { shared.tryEmit(PagingData.from(it)) }
    // Dispatched, not immediate: started right here, the pager's first (still empty) data replaced the seed before
    // the grid's first frame read it.
    viewModelScope.launch(Dispatchers.Main) {
      var start: GridStart? = null
      var startFor: List<String>? = null
      if (place != null) {
        // The search depends on the session, so the stored one must be loaded first.
        session.state.first { it !is AuthState.Restoring }
        startFor = searchTags()
        // Not found, or not reachable now: the grid starts at the top. The tab keeps its place until it's scrolled.
        start = try {
          repository.locate(startFor, place)
        } catch (e: ApiError) {
          null
        }
        _midway.value = start?.midway == true
        _placePost.value = start?.let { place.post }
        _restoring.value = false
      }
      // Signing in or out rebuilds even when the search is the same (it replaces a failed pager); G only does only
      // when it changes what is asked (a search that already has `rating:s` keeps its place).
      combine(session.isSignedIn, privacy.gOnly) { signedIn, gOnly -> signedIn to searchedTags(tags, signedIn, gOnly) }
        .distinctUntilChanged()
        .flatMapLatest { (_, searched) ->
          // The place only for the search it was found in; a rebuild for another starts at the top.
          val from = start?.takeIf { searched == startFor }
          if (start != null && from == null) _midway.value = false
          start = null
          repository.posts(searched, from, onPage = ::onPage, onLoaded = ::onLoaded)
        }
        .cachedIn(viewModelScope)
        .collect { shared.emit(it) }
    }
  }

  /** The cursor of the page [postId] came in on, or null if it isn't known (the first page, or not loaded here). */
  fun cursorOf(postId: String): PageCursor? = cursors.value[postId]

  /** The grid has scrolled to [placePost], or given up on it. */
  fun placeShown() {
    _placePost.value = null
  }

  /** Refresh and the Top button: the grid reloads from the top, so it no longer starts midway. */
  fun toTop() {
    _midway.value = false
    cursors.value = emptyMap()
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
