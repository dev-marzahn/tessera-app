package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.shell.RetryWhenBack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibilityScope
import dev.marzahn.sankaku.shell.LocalHandoffEntering
import dev.marzahn.sankaku.shell.LocalUnderQueuePage
import dev.marzahn.sankaku.shell.SharedTransitions
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.PostOverride
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostTitle
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.merge
import dev.marzahn.sankaku.features.grid.PostViewer
import dev.marzahn.sankaku.features.grid.PhoneViewer
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.tabs.QueueBar
import dev.marzahn.sankaku.tabs.LocalOpenTabList
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.features.grid.PostOrigin
import dev.marzahn.sankaku.tabs.ClosedTabUndo
import dev.marzahn.sankaku.tabs.LocalTabEntry
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.UpNextState
import dev.marzahn.sankaku.tabs.kindLabel
import dev.marzahn.sankaku.tabs.queueSwipe
import dev.marzahn.sankaku.tabs.tabTitle
import dev.marzahn.sankaku.features.grid.ViewerMode
import dev.marzahn.sankaku.tabs.DescribeTab
import dev.marzahn.sankaku.tabs.TabContents
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import dev.marzahn.sankaku.ui.components.ErrorState
import dev.marzahn.sankaku.ui.components.KeyFocusHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

sealed interface PostUiState {
  data object Loading : PostUiState

  data class Loaded(val post: PostDto) : PostUiState

  /** [unreachable]: Sankaku couldn't be reached, so the network coming back retries it (#128). */
  data class Failed(val message: String, val unreachable: Boolean = false) : PostUiState
}

/**
 * A post in a tab of its own. It shows the copy the app already holds (from the grid it was opened from) at once,
 * and fetches only a post it doesn't hold, such as a parent link's; it retries by itself when the signed-in status
 * changes. The details panel fetches the full tag list either way.
 */
class PostViewModel(
  private val id: String,
  private val repository: PostsRepository,
  store: PostStateStore,
  session: AuthSession,
) : ViewModel() {
  // A post the app holds is loaded from the first frame: started as loading, a queue swipe's landing showed the
  // loading box for a frame between the peek and the post (#277).
  private val loaded = MutableStateFlow(repository.heldPost(id)?.let(PostUiState::Loaded) ?: PostUiState.Loading)

  val state: StateFlow<PostUiState> = combine(loaded, store.overrides, ::withOverrides)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), withOverrides(loaded.value, store.overrides.value))

  private fun withOverrides(state: PostUiState, overrides: Map<String, PostOverride>) =
    if (state is PostUiState.Loaded) PostUiState.Loaded(state.post.merge(overrides[state.post.id])) else state

  init {
    if (loaded.value !is PostUiState.Loaded) load()
    viewModelScope.launch { session.isSignedIn.drop(1).collect { if (loaded.value is PostUiState.Failed) load() } }
  }

  fun load() {
    loaded.value = PostUiState.Loading
    viewModelScope.launch {
      loaded.value = try {
        PostUiState.Loaded(repository.cachedPost(id) ?: repository.fullPost(id))
      } catch (e: ApiError) {
        PostUiState.Failed(e.message ?: "Couldn't load the post", unreachable = e is ApiError.Network)
      }
    }
  }
}

/**
 * Where the lone post [route] in [tab] came from, for its top line (#181): "Kept · from artist_x" while its source is in
 * the queue ([TabsController.sourceOf]), going there; else its own search, which opens as a search does from a post
 * (its source if one is open, [TabsController.sourceFor], else a new source in front, "now"); else nothing.
 */
internal fun postOrigin(tab: TabState, route: Route.Post, tabs: TabsController, navigator: Navigator): PostOrigin? {
  val label = postOriginLabel(tab, route, tabs) ?: return null
  val source = tabs.sourceOf(tab)
  return PostOrigin(label) { if (source != null) tabs.activate(source.id) else navigator.goTo(Route.Grid(route.originTags)) }
}

/** [postOrigin]'s label alone, for the queue swipe's peek of a lone post (#263), which goes nowhere. */
internal fun postOriginLabel(tab: TabState, route: Route.Post, tabs: TabsController): String? {
  tabs.sourceOf(tab)?.let { source -> return kindLabel(tab, source) }
  if (route.originTags.isEmpty()) return null
  return "From ${tabTitle(Route.Grid(route.originTags), names = tabs.tagNames)}"
}

/**
 * A lone post (#181) draws the viewer's card ([ViewerMode.Tab]): the same panels, corners and controls, with a small
 * inset and no arrows. It has no grid behind it: Back goes to the previous queue item.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PostScreen(
  route: Route.Post,
  navigator: Navigator = koinInject(),
  actions: PostActions = koinInject(),
  tabs: TabsController = koinInject(),
  undo: ClosedTabUndo = koinInject(),
  upNext: UpNextState = koinInject(),
  // The phone's lone post; a JVM test draws it too.
  touch: Boolean = platformIsTouch,
) {
  val viewModel = koinViewModel<PostViewModel>(key = "post-${route.id}") { parametersOf(route.id) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  RetryWhenBack { if ((viewModel.state.value as? PostUiState.Failed)?.unreachable == true) viewModel.load() }
  // A post opened without a label (a parent link) is named once it loads, in the strip, the queue page and the previews.
  val loadedPost = (state as? PostUiState.Loaded)?.post
  val name = loadedPost?.let(PostTitle::of)
  // A reload that names it differently, or no longer at all, changes or clears the title.
  if (route.label == null && loadedPost != null) LaunchedEffect(route.id, name) { tabs.loadedTitle(route, name) }
  DescribeTab(route) { TabContents.post(route.id, route.md5 ?: (viewModel.state.value as? PostUiState.Loaded)?.post?.md5) }
  // A lone post (#181): its tab, for its top line, its drag down and the queue swipe on its image.
  val tab = LocalTabEntry.current?.tab
  val tabList by tabs.tabs.collectAsStateWithLifecycle()
  val origin = remember(tab, tabList, route) { tab?.let { postOrigin(it, route, tabs, navigator) } }
  Surface(Modifier.fillMaxSize()) {
    when (val s = state) {
      is PostUiState.Loaded -> SharedTransitions {
        // On a phone, under the queue page (#320): the picture grows out of its row's thumbnail as the page closes and
        // shrinks back into it as the page opens, on the page's transition, in the shell's scope. Opened from another
        // post's Recommended thumbnail (#321), it grows out of that thumbnail on its own coming in, then is the page's.
        val underPage = (LocalHandoffEntering.current ?: LocalUnderQueuePage.current)?.takeIf { touch }
        // A phone shows the same sheet without the pager; system back goes to the previous queue item (#181).
        if (touch) {
          // Elsewhere shown, settled, from the first frame: the card has nothing to grow from.
          val shown = remember { MutableTransitionState(true) }
          val phone: @Composable AnimatedVisibilityScope.() -> Unit = {
            PhoneViewer(
              index = 0,
              postAt = { s.post },
              loadedCount = 1,
              hasMore = false,
              // Only the picture is shared, with its row's thumbnail.
              sharedId = s.post.id.takeIf { underPage != null },
              visibilityScope = this,
              onShow = {},
              onClose = navigator::goBack,
              mode = ViewerMode.Tab,
              searchTags = route.originTags,
              origin = origin,
              // Dragged down, it closes through the close ✕ makes, with its Undo, and moves on: to the next item, or at
              // the end to the card moving on would show (#326).
              onDragClose = tab?.let { { undo.dragClose(it.id, upNext) } },
              queueFrom = tab?.id,
            )
          }
          if (underPage != null) {
            underPage.phone()
          } else {
            AnimatedVisibility(shown, enter = EnterTransition.None, exit = ExitTransition.None) { phone() }
          }
          return@SharedTransitions
        }
        // Shown, settled, from the first frame: the card has nothing to grow from here.
        AnimatedVisibility(remember { MutableTransitionState(true) }, enter = EnterTransition.None, exit = ExitTransition.None) {
          PostViewer(
            index = 0,
            postAt = { s.post },
            loadedCount = 1,
            hasMore = false,
            sharedId = null,
            visibilityScope = this,
            onStep = {},
            onClose = navigator::goBack,
            mode = ViewerMode.Tab,
            searchTags = route.originTags,
          )
        }
      }
      else -> Box(
        Modifier
          .fillMaxSize()
          .postShortcuts(post = { null }, actions = actions, onBack = navigator::goBack)
          // Still loading, or failed, it can be swiped past all the same (#181).
          .queueSwipe(enabled = true, from = tab?.id),
        contentAlignment = Alignment.Center,
      ) {
        KeyFocusHolder()
        if (s is PostUiState.Failed) ErrorState(s.message, onRetry = viewModel::load) else AppLoadingIndicator()
        // Touch: a lone post covers the shell's count pill from its first frame, so its own stands in while the post
        // loads or has failed, on the grid's line (there's no sheet yet), and the queue is still one tap away.
        if (touch) {
          QueueBar(
            onOpenQueue = LocalOpenTabList.current,
            bottom = BottomRowLayout.WindowRoom,
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }
    }
  }
}
