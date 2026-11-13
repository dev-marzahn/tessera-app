package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.PostOverride
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostTitle
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.grid.ratingLocked
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.postImageRequest
import dev.marzahn.sankaku.shell.LocalItemVisibility
import dev.marzahn.sankaku.shell.LocalPostHandoff
import dev.marzahn.sankaku.shell.PostHandoff
import dev.marzahn.sankaku.shell.sharedPicture
import dev.marzahn.sankaku.tabs.LocalTabEntry
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.components.AlreadyInTab
import dev.marzahn.sankaku.ui.components.FavouriteBadgeButton
import dev.marzahn.sankaku.ui.components.InTabChip
import dev.marzahn.sankaku.ui.components.InTabWash
import dev.marzahn.sankaku.ui.components.OpenInTabFilter
import dev.marzahn.sankaku.ui.components.rememberAlreadyInTab
import dev.marzahn.sankaku.ui.openOnClick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * The post details' "Recommended" strip (`.scratch/android-viewer/` A, row 3; `.scratch/android-shell/` option 3,
 * row 7): the posts Sankaku recommends for [postId], a [PostStrip].
 */
@Composable
fun RecommendedPosts(
  postId: String,
  modifier: Modifier = Modifier,
  navigator: Navigator = koinInject(),
  repository: PostsRepository = koinInject(),
  tabs: TabsController = koinInject(),
) {
  PostStrip(SheetStrip.Recommended, postId, modifier, navigator = navigator, repository = repository, tabs = tabs) {
    StripPosts(repository.recommended(postId, it))
  }
}

/** A strip's loaded [posts]; with [more], the route its last tile opens for the rest (the Children's grid). */
data class StripPosts(val posts: List<PostDto>, val more: Route? = null)

/** The details' strips of small previews, each with its title, its words when it fails, and its empty cells. */
enum class SheetStrip(val title: String, val failed: String, val label: String, val placeholders: Int) {
  Parent("Parent", "Couldn't load the parent post", "Parent post", 1),
  Children("Children", "Couldn't load the child posts", "Child post", 4),
  Recommended("Recommended", "Couldn't load recommendations", "Recommended post", 4),
}

/**
 * A strip of [strip]'s posts for the post [postId], as 72 × 96 dp previews that scroll sideways. Self-contained, so any
 * details container can host it: the desktop info panel or the phone's sheet.
 *
 * It [load]s only once some of it is on screen (not clipped away by a scroll, a collapsed panel or a sheet below the
 * fold), so details that are never shown or read that far cost no request. [load] gets the tags that narrow it:
 * signed out (or with `safeOnly`) the locked `rating:s`, as every search does. A tap opens the post here, a middle- or
 * Cmd/Ctrl-click or a long-press in a background tab ([openOnClick]); a post open in a tab is nearly grey with the
 * grid's "In tab" chip, and opening it in a background tab opens nothing: the chip says "Already tab N". A favourited
 * post has the grid's heart in its top-right corner, from the [PostStateStore] the grid reads, so it follows a
 * favourite made anywhere. While loading it shows empty cells at the strip's size; with no posts it leaves nothing
 * behind. A strip whose [StripPosts.more] is set ends in a tile that opens the rest.
 */
@Composable
internal fun PostStrip(
  strip: SheetStrip,
  postId: String,
  modifier: Modifier = Modifier,
  navigator: Navigator = koinInject(),
  repository: PostsRepository = koinInject(),
  session: AuthSession = koinInject(),
  privacy: PrivacySettings = koinInject(),
  tabs: TabsController = koinInject(),
  store: PostStateStore = koinInject(),
  load: suspend (narrowedBy: List<String>) -> StripPosts,
) {
  val signedIn by session.isSignedIn.collectAsState()
  val gOnly by privacy.gOnly.collectAsState()
  val narrowedBy = if (ratingLocked(signedIn, gOnly)) listOf(Rating.G.tag) else emptyList()
  val loader by rememberUpdatedState(load)
  var onScreen by remember { mutableStateOf(false) }
  var attempt by remember { mutableIntStateOf(0) }
  // Keyed by post, so the previous post's strip never shows while the next one's loads.
  var state by remember(postId, narrowedBy) { mutableStateOf<StripState>(StripState.Loading) }
  LaunchedEffect(postId, narrowedBy, attempt) {
    snapshotFlow { onScreen }.first { it }
    state = StripState.Loading
    state = try {
      StripState.Loaded(loader(narrowedBy))
    } catch (_: ApiError) {
      StripState.Failed
    }
  }
  val current = state
  if (current is StripState.Loaded && current.strip.posts.isEmpty()) return

  val row = remember(postId) { LazyListState() }
  val tabList by tabs.tabs.collectAsState()
  val openInTabs = remember(tabList) { tabs.openPosts(tabList) }
  val overrides by store.overrides.collectAsState()
  val alreadyInTab = rememberAlreadyInTab()
  // Gone (no posts), it can't tell when it's off screen, so the next post must see it come back first.
  DisposableEffect(Unit) { onDispose { onScreen = false } }
  Column(
    modifier.onGloballyPositioned { onScreen = it.boundsInWindow().let { b -> b.width > 0f && b.height > 0f } },
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(strip.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    when (current) {
      StripState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          strip.failed,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { attempt++ }) { Text("Retry") }
      }
      // A lazy row too, so the cells run off the end as the loaded ones do instead of squeezing into the width.
      StripState.Loading -> LazyRow(horizontalArrangement = Arrangement.spacedBy(THUMB_GAP), userScrollEnabled = false) {
        items(strip.placeholders) {
          Box(Modifier.size(THUMB_WIDTH, THUMB_HEIGHT).clip(THUMB_SHAPE).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        }
      }
      is StripState.Loaded -> LazyRow(
        state = row,
        horizontalArrangement = Arrangement.spacedBy(THUMB_GAP),
        modifier = Modifier.wheelScrollsSideways(row),
      ) {
        items(current.strip.posts, key = { it.id }) { post ->
          val openInTab = post.id in openInTabs
          PostThumb(
            post,
            strip = strip,
            favourited = favouritedIn(post, overrides),
            openInTab = openInTab,
            asOne = opensAsOne(post, openInTab, gOnly),
            alreadyInTab = alreadyInTab,
            navigator = navigator,
            repository = repository,
            tabs = tabs,
          )
        }
        current.strip.more?.let { more ->
          item(key = MORE_KEY) { MoreTile(more, navigator) }
        }
      }
    }
  }
}

/** Whether [post] is favourited: the grid's rule, the [PostStateStore]'s override over the list's own flag. */
internal fun favouritedIn(post: PostDto, overrides: Map<String, PostOverride>): Boolean =
  overrides[post.id]?.isFavourited ?: post.isFavorited

/**
 * Whether a tap on [post]'s thumbnail opens it as one picture (#321, Motion): not one open in a tab already (it's
 * shown there, greyed here), nor one G only hides (the strip asks with `rating:s` then, so only in case), and only with
 * a file to show, so the post it opens is loaded at once from the copy here.
 */
internal fun opensAsOne(post: PostDto, openInTab: Boolean, gOnly: Boolean): Boolean =
  !openInTab && post.fileUrl != null && !(gOnly && Rating.ofCode(post.rating) != Rating.G)

/**
 * A tap opens the post's lone post, after this one ([Navigator.goTo], `openLone`). On a phone, if [asOne], it goes
 * through the shell's [PostHandoff]: the post is held first so it opens loaded, and the thumbnail, the post's picture
 * as one element with the lone post's (#321), moves into it. The element is attached all along, on the item's own
 * visibility, and switched on only while it's the one moving (in this [strip]: a post can be in two), so it has its
 * bounds then.
 */
@Composable
private fun PostThumb(
  post: PostDto,
  strip: SheetStrip,
  favourited: Boolean,
  openInTab: Boolean,
  asOne: Boolean,
  alreadyInTab: AlreadyInTab,
  navigator: Navigator,
  repository: PostsRepository,
  tabs: TabsController,
) {
  val context = LocalPlatformContext.current
  // Not from the search the details belong to, so it opens without one.
  val name = PostTitle.of(post)
  val route = Route.Post(post.id, post.md5, emptyList(), name, post.rating)
  val label = (name ?: strip.label).let { if (favourited) "$it, favourited" else it }
  val visibility = LocalItemVisibility.current
  val handoff = LocalPostHandoff.current?.takeIf { asOne && visibility != null }
  val from = LocalTabEntry.current?.tab?.id
  val scope = rememberCoroutineScope()
  val sharing = from != null && handoff?.sharing(from, post.id, strip) == true
  Box(
    Modifier
      .sharedPicture(post.id, visibility, enabled = sharing, slow = true, clip = THUMB_SHAPE)
      .size(THUMB_WIDTH, THUMB_HEIGHT)
      .clip(THUMB_SHAPE)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .semantics { contentDescription = label }
      .openOnClick(
        onOpen = {
          if (handoff == null || from == null) {
            navigator.goTo(route)
          } else {
            scope.launch {
              handoff.open(from, post.id, active = { tabs.active.value }, strip = strip) {
                repository.hold(post)
                navigator.goTo(route)
              }
            }
          }
        },
        onOpenInNewTab = {
          val existing = navigator.openInNewTab(route)
          if (existing != null) alreadyInTab.show(post.id, existing)
        },
        inTab = openInTab,
      ),
  ) {
    AsyncImage(
      model = postImageRequest(context, PostImage.of(post, Variant.Preview)),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      colorFilter = if (openInTab) OpenInTabFilter else null,
      modifier = Modifier.fillMaxSize(),
    )
    if (openInTab) {
      InTabWash()
      InTabChip(Modifier.align(Alignment.BottomStart).padding(6.dp), alreadyInTab.of(post.id))
    }
    PostMediaBadge(post,Modifier.align(Alignment.TopStart).padding(6.dp))
    // The grid cell's heart at rest (`CellOverlay`): a badge, not a button; the label above says it.
    FavouriteBadgeButton(
      favourited = favourited,
      expanded = false,
      onToggle = {},
      modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clearAndSetSemantics {},
    )
  }
}

/** A strip's last tile when it may hold more than it shows: opens them all, as a grid ([more]). */
@Composable
private fun MoreTile(more: Route, navigator: Navigator) {
  Box(
    Modifier
      .size(THUMB_WIDTH, THUMB_HEIGHT)
      .clip(THUMB_SHAPE)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .openOnClick({ navigator.goTo(more) }, { navigator.openInNewTab(more) }),
    contentAlignment = Alignment.Center,
  ) {
    Text("All", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
  }
}

/**
 * A mouse wheel's vertical turn scrolls [row] sideways while it can still move that way; at either end the turn goes on
 * to the details' own scroll. Only a plain vertical turn: a trackpad's sideways swipe and Shift+wheel already scroll
 * it, and touch has no wheel.
 */
private fun Modifier.wheelScrollsSideways(row: LazyListState): Modifier = pointerInput(row) {
  val step = WHEEL_STEP.toPx()
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent()
      if (event.type != PointerEventType.Scroll || event.keyboardModifiers.isShiftPressed) continue
      val change = event.changes.firstOrNull() ?: continue
      val delta = change.scrollDelta
      if (delta.y == 0f || abs(delta.x) >= abs(delta.y)) continue
      if (row.dispatchRawDelta(delta.y * step) != 0f) change.consume()
    }
  }
}

private sealed interface StripState {
  data object Loading : StripState
  data object Failed : StripState
  data class Loaded(val strip: StripPosts) : StripState
}

private val THUMB_WIDTH = 72.dp
private val THUMB_HEIGHT = 96.dp
private val THUMB_GAP = 8.dp
private val THUMB_SHAPE = RoundedCornerShape(10.dp)
private const val MORE_KEY = "more"

/** How far one wheel notch moves the strip: half a preview. */
private val WHEEL_STEP = 52.dp
