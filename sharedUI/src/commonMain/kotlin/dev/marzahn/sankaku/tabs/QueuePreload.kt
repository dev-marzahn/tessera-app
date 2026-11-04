package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.features.grid.cellRatio
import dev.marzahn.sankaku.features.grid.gridCellRequest
import dev.marzahn.sankaku.features.grid.searchedTags
import dev.marzahn.sankaku.features.post.decodedInMemory
import dev.marzahn.sankaku.features.post.firstShownRequests
import dev.marzahn.sankaku.platformMinCellWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Touch: the items beside the one shown in the queue ([neighbours], the items a swipe or ‹ and › land on) load what
 * they show first as soon as they're neighbours, so a move lands on it instead of starting its load.
 * - A lone post loads its post and the images the viewer shows first ([firstShownRequests]), so it lands on a decoded
 *   picture (#263). The requests are the viewer's own, cache first, so a cached image costs no request.
 * - A grid or feed loads the page it opens on into the page cache ([PostsRepository.gridStart]) and decodes the
 *   thumbnails of its first screen ([firstScreen]) with the cells' own requests (#317), so the grid starts with them in
 *   its first frame and asks for nothing more. It's the search the grid would make ([searchedTags]), once the stored
 *   session is known.
 *
 * Only what can still be landed on loads: an item that stops being a neighbour, and isn't [shown], has its load
 * cancelled, so swiping through the queue never has more than three items' loads in flight. The one a move lands on
 * keeps going, as its screen then makes the same requests, and joins the page request if it's still on the way.
 *
 * Returns the posts whose picture has loaded, by id, for the swipe's peek to draw them ([QueuePeek]).
 */
@Composable
internal fun PreloadNeighbours(
  shown: TabState,
  neighbours: List<TabState>,
  repository: PostsRepository = koinInject(),
  imageLoader: ImageLoader = koinInject(),
  session: AuthSession = koinInject(),
  privacy: PrivacySettings = koinInject(),
): Map<String, LoadedNeighbour> {
  val context = LocalPlatformContext.current
  val window = LocalWindowInfo.current.containerSize
  val minCellWidth = with(LocalDensity.current) { platformMinCellWidth.roundToPx() }
  val auth by session.state.collectAsStateWithLifecycle()
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  // The host's scope: it stays across moves, so a load outlives the move that made its item the one shown.
  val scope = rememberCoroutineScope()
  val loads = remember { mutableMapOf<Preload, Job>() }
  val ready = remember { mutableStateMapOf<String, LoadedNeighbour>() }
  // Before the stored session is known the search isn't: a grid waits for it, as the grid itself does.
  fun preloadOf(tab: TabState): Preload? = when (val top = tab.top) {
    is Route.Post -> Preload.Post(top.id)
    is Route.Grid -> if (auth is AuthState.Restoring) {
      null
    } else {
      Preload.Grid(searchedTags(top.tags, auth is AuthState.SignedIn, gOnly), tab.placeFor(top))
    }
    Route.Settings, null -> null
  }
  val near = neighbours.mapNotNull(::preloadOf).distinct()
  val keep = near + listOfNotNull(preloadOf(shown))
  LaunchedEffect(near, keep, window, minCellWidth) {
    loads.keys.filter { it !in keep }.forEach { loads.remove(it)?.cancel() }
    ready.keys.filter { Preload.Post(it) !in keep }.forEach { ready.remove(it) }
    // Unmeasured, the requests would be sized by nothing and decode at another size under the same key.
    if (window.width <= 0 || window.height <= 0) return@LaunchedEffect
    for (preload in near) {
      // Loading or loaded already; a load that failed is the item's own screen's to retry once it's shown.
      if (preload in loads) continue
      loads[preload] = when (preload) {
        is Preload.Post -> scope.preloadPost(preload.id, repository, imageLoader, context, window.width, window.height) {
          ready[preload.id] = it
        }
        is Preload.Grid -> scope.launch {
          val posts = try {
            repository.gridStart(preload.tags, preload.place)
          } catch (_: ApiError) {
            return@launch
          }
          // A grid restored at its place scrolls to its post, so its first screen starts there.
          val shown = preload.place?.let { place -> posts.dropWhile { it.id != place.post }.ifEmpty { null } } ?: posts
          firstScreen(shown, window.width, window.height, minCellWidth)
            .map { launch { imageLoader.execute(gridCellRequest(context, it)) } }
            .joinAll()
        }
      }
    }
  }
  return ready
}

/** What a neighbour loads: a lone post's, or a grid's search (as it's asked) and the [Grid.place] it opens at. */
private sealed interface Preload {
  data class Post(val id: String) : Preload

  data class Grid(val tags: List<String>, val place: GridPlace?) : Preload
}

private fun CoroutineScope.preloadPost(
  id: String,
  repository: PostsRepository,
  imageLoader: ImageLoader,
  context: PlatformContext,
  width: Int,
  height: Int,
  onReady: (LoadedNeighbour) -> Unit,
): Job = launch {
  // The post as the lone post's view model loads it: held, or fetched once (the repository shares the fetch).
  val post = repository.cachedPost(id) ?: try {
    repository.fullPost(id)
  } catch (_: ApiError) {
    return@launch
  }
  // Ready with the first picture decoded, the preview (on disk from the grid) while the sample still downloads, and
  // again with the sharper one.
  firstShownRequests(context, post, width, height).map { request ->
    launch {
      imageLoader.execute(request)
      decodedInMemory(imageLoader, post.md5)?.let { onReady(LoadedNeighbour(post, it)) }
    }
  }.joinAll()
}

/**
 * The posts of [posts] (a grid's first page) a [windowWidth] × [windowHeight] px window shows before it's scrolled,
 * without those that need signing in (no image). It lays them out as the grid does, each in the shortest of the
 * columns of at least [minCellWidth] px, but in the whole window: the grid's edges and gaps only make its cells
 * narrower and fewer, so this never counts one short.
 */
internal fun firstScreen(posts: List<PostDto>, windowWidth: Int, windowHeight: Int, minCellWidth: Int): List<PostDto> {
  if (windowWidth <= 0 || windowHeight <= 0) return emptyList()
  val lanes = (windowWidth / minCellWidth.coerceAtLeast(1)).coerceAtLeast(1)
  val laneWidth = windowWidth.toFloat() / lanes
  val heights = FloatArray(lanes)
  val shown = mutableListOf<PostDto>()
  for (post in posts) {
    val lane = heights.indices.minBy { heights[it] }
    if (heights[lane] >= windowHeight) break
    // A cell that needs signing in is square, with no image.
    heights[lane] += laneWidth / if (post.redirectToSignup) 1f else cellRatio(post)
    if (!post.redirectToSignup) shown += post
  }
  return shown
}

/**
 * A neighbour [PreloadNeighbours] has loaded: its [post] and the best [picture] decoded for it. The map holds the
 * pictures of at most three posts (the neighbours and the one shown), which the memory cache can't evict meanwhile.
 */
class LoadedNeighbour(val post: PostDto, val picture: Image)
