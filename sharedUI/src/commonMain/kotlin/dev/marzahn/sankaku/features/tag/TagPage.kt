package dev.marzahn.sankaku.features.tag

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventType
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import dev.marzahn.sankaku.api.dto.TagNames
import dev.marzahn.sankaku.ui.components.EmphasizedAccelerate
import dev.marzahn.sankaku.ui.components.EmphasizedDecelerate
import dev.marzahn.sankaku.ui.components.SCRIM_ALPHA
import dev.marzahn.sankaku.ui.components.SHEET_IN_MS
import dev.marzahn.sankaku.ui.components.SHEET_OUT_MS
import dev.marzahn.sankaku.ui.components.SideSheetWidth
import dev.marzahn.sankaku.ui.components.ButtonHint
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.shell.RetryWhenBack
import dev.marzahn.sankaku.ui.components.AppSnackbarHost
import dev.marzahn.sankaku.ui.components.Shortcuts
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.arrow_back
import com.google.fonts.icons.chevron_right
import com.google.fonts.icons.grid_view
import com.google.fonts.icons.menu_book
import com.google.fonts.icons.more_vert
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.PlatformBackHandler
import dev.marzahn.sankaku.PlatformSystemBars
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.TagAndWiki
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.books.BookReader
import dev.marzahn.sankaku.features.post.BlacklistConfirmation
import dev.marzahn.sankaku.features.post.PostActions
import dev.marzahn.sankaku.features.post.TagBlacklisting
import dev.marzahn.sankaku.features.post.compactCount
import dev.marzahn.sankaku.plainTextClip
import dev.marzahn.sankaku.platformBackKeys
import dev.marzahn.sankaku.tabs.TabId
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.ui.components.ApiErrorState
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.openOnClick
import dev.marzahn.sankaku.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * A tag's page open over the queue [item] that showed [over] when it opened (#486), with the [tag] as its opener knew
 * it, so its name and, if [typed], its type show while the page loads. A wiki link knows only the tag's name (#522).
 */
data class OpenTagPage(val tag: TagDto, val item: TabId?, val over: Route?, val typed: Boolean = true)

/**
 * Which tags' pages are open, if any (#486): opened from a tag in the book sheet or the tag menu's "About this tag",
 * then from the wiki links on them, each over the one before ([stack], #522). They belong to the item they were opened
 * over ([tabs]' active one): a search opened from one is a new item, as one from a grid's tag is, and Back from that
 * search comes back to the page. The last is the one [shown], only while its item is active and shows what it showed
 * then ([tagPageShown]). ← and Back go back to the one before ([back]); they all go when the item is closed or the
 * desktop's scrim is clicked ([close]).
 */
class TagPages(internal val tabs: TabsController? = null) {
  private val _stack = MutableStateFlow<List<OpenTagPage>>(emptyList())

  /** The pages open, the first opened first. */
  val stack: StateFlow<List<OpenTagPage>> = _stack.asStateFlow()

  private val _shown = MutableStateFlow<OpenTagPage?>(null)

  /** The last of [stack], the page that shows. */
  val shown: StateFlow<OpenTagPage?> = _shown.asStateFlow()

  fun open(tag: TagDto) {
    val active = tabs?.activeTab
    set(listOf(OpenTagPage(tag, active?.id, active?.top)))
  }

  /**
   * A wiki link on the shown page: [tag]'s page over it, ← coming back to it. A link to a page already open goes back
   * to that one as it was, so links between two pages don't stack them without end (#577 review).
   */
  internal fun openLinked(tag: WikiTag) {
    val top = _stack.value.lastOrNull() ?: return
    val open = _stack.value.indexOfFirst { it.tag.searchName == tag.searchName }
    if (open >= 0) {
      set(_stack.value.take(open + 1))
      return
    }
    // A tag known only by its key reads as the site names one ("other_tag" is "Other Tag"); one typed with its case
    // ("Admire Groove (umamusume)") as it was typed. A section ("#History") isn't part of its name.
    val shown = tag.shown.substringBefore('#').trim()
    val name = if (shown == shown.lowercase()) TagNames.ofKey(tag.searchName) else shown
    set(_stack.value + top.copy(tag = TagDto(name = name, tagName = tag.searchName), typed = false))
  }

  /** Back to the page before the shown one, or none. */
  fun back() {
    set(_stack.value.dropLast(1))
  }

  fun close() {
    set(emptyList())
  }

  private fun set(pages: List<OpenTagPage>) {
    _stack.value = pages
    _shown.value = pages.lastOrNull()
  }

  /** Counts the times a key reached the window under the shown page ([underTagPage]): each brings the keys back to it. */
  internal var keyReturns by mutableIntStateOf(0)
    private set

  /** A key reached the window under the shown page: the page asks for the keys again ([keyReturns]). */
  internal fun takeKeys() {
    keyReturns++
  }
}

/** Whether [page] shows now: while its item is [active] and shows what it did when the page opened. */
internal fun tagPageShown(page: OpenTagPage, active: TabId?, activeTop: Route?): Boolean =
  page.item == null || (page.item == active && page.over == activeTop)

/** Whether a tag's page shows now (#522): one is open and its item is shown as it was ([tagPageShown]). */
@Composable
fun rememberTagPageShown(pages: TagPages = koinInject()): Boolean {
  val open by pages.shown.collectAsStateWithLifecycle()
  // No queue (a test's): an open page always shows. The same instance throughout, so the calls below never change.
  val tabs = pages.tabs ?: return open != null
  val all by tabs.tabs.collectAsStateWithLifecycle()
  val active by tabs.active.collectAsStateWithLifecycle()
  val page = open ?: return false
  return tagPageShown(page, active, all.firstOrNull { it.id == active }?.top)
}

/**
 * The window under a shown tag's page (#522): out of the accessibility tree, and none of its keys. A key that reaches it
 * anyway (a menu closing gave the focus back to the screen it was opened on) is the page's: Esc goes back as on the
 * page, any other key brings the keys back to it. Without this, Esc went back in the grid under the page, or closed its
 * viewer.
 */
fun Modifier.underTagPage(shown: Boolean, pages: TagPages): Modifier =
  if (!shown) {
    this
  } else {
    clearAndSetSemantics {}.onPreviewKeyEvent { event ->
      if (event.type == KeyEventType.KeyDown) {
        if (Shortcuts.Back.matches(event.key)) pages.back() else pages.takeKeys()
      }
      true
    }
  }

/**
 * The tag pages while any is open ([TagPages.stack]) and their item shown, over everything, the book reader too, with
 * the window's messages ([snackbar]) over the shown one: the window's own host is under it (#522). As a [sheet]
 * (desktop, #522) they're Settings' side sheet: [SideSheetWidth] at the window's right edge over a scrim that closes
 * them, sliding in and out as it does; otherwise (touch) they cover the window. When they stop showing (closed, or
 * their item left) [onKeysBack] hands the keys back to the screen under them.
 */
@Composable
fun TagPageHost(
  pages: TagPages = koinInject(),
  tabs: TabsController = koinInject(),
  snackbar: SnackbarHostState? = null,
  sheet: Boolean = !platformIsTouch,
  onKeysBack: () -> Unit = {},
) {
  val shownNow = rememberTagPageShown(pages)
  var wasShown by remember { mutableStateOf(false) }
  // The latest: the effect below runs on the frame the page goes, with what the window under it is by then.
  val keysBack by rememberUpdatedState(onKeysBack)
  LaunchedEffect(shownNow) {
    if (!shownNow && wasShown) keysBack()
    wasShown = shownNow
  }
  val stack by pages.stack.collectAsStateWithLifecycle()
  // The pages last open, kept through the sheet's slide out once closed.
  val lastOpen = remember { mutableStateOf(emptyList<OpenTagPage>()) }
  SideEffect { if (stack.isNotEmpty()) lastOpen.value = stack }
  val slide = remember { Animatable(1f) }
  LaunchedEffect(stack.isEmpty()) {
    if (stack.isNotEmpty()) {
      if (sheet) slide.animateTo(0f, tween(SHEET_IN_MS, easing = EmphasizedDecelerate)) else slide.snapTo(0f)
    } else {
      if (sheet && lastOpen.value.isNotEmpty()) slide.animateTo(1f, tween(SHEET_OUT_MS, easing = EmphasizedAccelerate))
      lastOpen.value = emptyList()
    }
  }
  val closing = sheet && stack.isEmpty() && lastOpen.value.isNotEmpty()
  val drawn = if (closing) lastOpen.value else stack
  if (drawn.isEmpty()) return
  val all by tabs.tabs.collectAsStateWithLifecycle()
  // Their item closed: the pages go with it.
  LaunchedEffect(stack, all) { stack.firstOrNull()?.item?.let { item -> if (all.none { it.id == item }) pages.close() } }
  Box(Modifier.fillMaxSize()) {
    if (sheet && (shownNow || closing)) {
      Box(
        Modifier
          .fillMaxSize()
          .testTag(TAG_PAGE_SCRIM)
          .graphicsLayer { alpha = SCRIM_ALPHA * (1f - slide.value) }
          .background(Color.Black)
          .pointerInput(closing) { if (!closing) detectTapGestures { pages.close() } },
      )
    }
    Box(
      if (sheet) {
        Modifier
          .align(Alignment.CenterEnd)
          .widthIn(max = SideSheetWidth)
          .fillMaxWidth()
          .fillMaxHeight()
          .graphicsLayer { translationX = slide.value * size.width }
          .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
      } else {
        Modifier.fillMaxSize()
      },
    ) {
      // All kept composed: the one under the shown one, and all while their item isn't shown (the search one opened
      // is), so ← and Back find each as it was: loaded, scrolled and expanded, with no second request (#487 review).
      drawn.forEachIndexed { i, open ->
        val top = i == drawn.lastIndex
        key(i, open) {
          TagPage(
            open.tag,
            onClose = pages::back,
            shown = shownNow && top,
            leaving = closing && top,
            typed = open.typed,
            keyReturns = pages.keyReturns,
            snackbar = snackbar,
            color = if (sheet) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
            onOpenTag = pages::openLinked,
          )
        }
      }
    }
  }
}

/** What the page shows below the tag's rows: loading, its wiki, or why it couldn't load. */
internal sealed interface TagPageState {
  data object Loading : TagPageState

  data class Loaded(val page: TagAndWiki) : TagPageState

  /** [unreachable]: Sankaku couldn't be reached, so the network coming back loads it again (#522). */
  data class Failed(val error: ApiError) : TagPageState {
    val unreachable: Boolean get() = error is ApiError.Network
  }
}

/** "1 post", "9.8k posts": the Posts row's count. */
internal fun postsCount(count: Int): String = if (count == 1) "1 post" else "${compactCount(count)} posts"

/** "1 book", "42 books": the Books row's count. */
internal fun booksCount(count: Int): String = if (count == 1) "1 book" else "${compactCount(count)} books"

/**
 * A tag's page (#486, the mockup the owner OK'd in its thread): ← and ⋮ (Copy name; Blacklist tag… signed in), the
 * tag's type and name, Follow (signed in, once its id is known), a row to its posts and one to its books with their
 * counts, and its wiki text, collapsed past [WIKI_LINES] lines. A row opens its search with a tap as a grid's tag does,
 * and the page stays with its item for Back to return to (over the window the book reader closes); a long-press or
 * middle-click opens it for later. ←, Back and Esc call [onClose].
 *
 * [seed] is the tag as its opener knew it: its name, type (if [typed]) and rows show at once, the counts and wiki once
 * `/tag-and-wiki` answers; a row with nothing in it then goes (#522). Failed, the rows stay (#522), and a failure for
 * want of a connection loads again once back online. The wiki reads as DText ([wikiText]): a link to a tag calls
 * [onOpenTag], and a long-press or middle-click on one opens a search for it for later. Shown, it has the keys, asked
 * for again each time [keyReturns] changes, and Tab doesn't leave it; [snackbar]'s messages show over it. [leaving], it
 * is drawn as it slides out but has none of that.
 */
@Composable
internal fun TagPage(
  seed: TagDto,
  onClose: () -> Unit,
  shown: Boolean = true,
  leaving: Boolean = false,
  typed: Boolean = true,
  keyReturns: Int = 0,
  snackbar: SnackbarHostState? = null,
  color: Color = MaterialTheme.colorScheme.surface,
  onOpenTag: (WikiTag) -> Unit = {},
  touch: Boolean = platformIsTouch,
  tags: TagsRepository = koinInject(),
  navigator: Navigator = koinInject(),
  reader: BookReader = koinInject(),
  session: AuthSession = koinInject(),
  actions: PostActions = koinInject(),
  blacklisting: TagBlacklisting = koinInject(),
) {
  var attempt by remember { mutableIntStateOf(0) }
  val state by produceState<TagPageState>(TagPageState.Loading, seed.searchName, attempt) {
    value = TagPageState.Loading
    value = try {
      TagPageState.Loaded(tags.page(seed.searchName))
    } catch (e: CancellationException) {
      throw e
    } catch (e: ApiError) {
      TagPageState.Failed(e)
    }
  }
  // Back online, a page that failed for want of a connection loads again, as a grid does (#128).
  // Hidden (the search it opened is shown), it waits to be shown again: no load from what isn't on screen (REVIEW.md).
  var retryWhenShown by remember { mutableStateOf(false) }
  RetryWhenBack {
    if ((state as? TagPageState.Failed)?.unreachable == true) {
      if (shown) attempt++ else retryWhenShown = true
    }
  }
  LaunchedEffect(shown) {
    if (shown && retryWhenShown) {
      retryWhenShown = false
      attempt++
    }
  }
  val loaded = (state as? TagPageState.Loaded)?.page
  // The page's own fields where they've come, the opener's until then. The name stays the opener's: the page's `name`
  // is the search key ("hinghoi"), where a chip shows "Hinghoi".
  val tag = loaded?.tag?.let { it.copy(name = seed.name, tagName = it.tagName ?: seed.tagName) } ?: seed
  val signedIn by session.isSignedIn.collectAsState()
  val overrides by actions.follows.overrides.collectAsState()
  val following = actions.follows.isFollowing(tag, overrides)
  val focus = remember { FocusRequester() }
  LaunchedEffect(shown, keyReturns) { if (shown) focus.requestFocus() }
  // Android gives Back to the handler registered last. Shown again after its search, the screen under it (its grid, its
  // viewer) has just registered its handlers anew, after the page's: the page registers its own a frame after it shows,
  // so Back is the page's (#522).
  var backArmed by remember { mutableStateOf(false) }
  LaunchedEffect(shown) {
    backArmed = false
    if (shown) {
      withFrameNanos {}
      backArmed = true
    }
  }
  if (backArmed) PlatformBackHandler(enabled = true, onBack = onClose)
  // Over the black reader, whose light icons and hidden bars would stay: the page's own, for its own surface.
  if (shown) PlatformSystemBars(hidden = false, lightIcons = MaterialTheme.colorScheme.surface.luminance() < 0.5f)

  // As a grid's tag opens its search (#486): a new item opened from this one, which Back closes, back to this page. The
  // page stays with its item; over the window, the reader closes for the search.
  fun open(route: Route) {
    reader.close()
    navigator.goTo(route)
  }

  val posts = Route.Grid(SearchTags.searchForOpenedTag(tag.searchName))
  val books = Route.Books(listOf(tag.searchName))
  Surface(
    Modifier
      // Hidden, it's measured but not placed: not drawn, not touched, not in the accessibility tree. Leaving, it's drawn.
      .then(
        when {
          shown -> Modifier
          leaving -> Modifier.clearAndSetSemantics {}
          else -> Modifier.clearAndSetSemantics {}.layout { measurable, constraints -> measurable.measure(constraints).let { layout(0, 0) {} } }
        },
      )
      .fillMaxSize()
      .semantics { paneTitle = tag.displayName }
      // Tab and Shift-Tab stay on the page: past its last control they'd reach the window under it (#522).
      .focusProperties {
        onExit = {
          if (shown && (requestedFocusDirection == FocusDirection.Next || requestedFocusDirection == FocusDirection.Previous)) {
            cancelFocusChange()
          }
        }
      }
      .focusRequester(focus)
      .focusable(enabled = shown)
      .platformBackKeys(onClose),
    color = color,
  ) {
    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TopRow(tag, signedIn, onClose, blacklisting)
        Column(
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 24.dp),
        ) {
          Header(tag, typed || loaded != null, following, onToggleFollow = { actions.toggleFollow(tag) }.takeIf { signedIn && tag.id != null })
          // The rows need only the tag's name: they're there while it loads and if it fails (#522), their counts once
          // it has loaded, and a row with nothing in it goes then (#522).
          if (loaded == null || tag.postCount != 0) {
            OpenRow(grid_view, POSTS, tag.postCount?.let(::postsCount), onOpen = { open(posts) }, onOpenInNewTab = { navigator.openInNewTab(posts) })
          }
          if (loaded == null || tag.poolCount != 0) {
            OpenRow(menu_book, BOOKS, tag.poolCount?.let(::booksCount), onOpen = { open(books) }, onOpenInNewTab = { navigator.openInNewTab(books) })
          }
          when (val s = state) {
            TagPageState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
              AppLoadingIndicator()
            }
            is TagPageState.Failed -> ApiErrorState(
              s.error,
              onRetry = { attempt++ },
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              onSignIn = koinInject<SignInController>()::open,
            )
            is TagPageState.Loaded -> s.page.wiki?.body?.takeIf { it.isNotBlank() }?.let { body ->
              About(
                body,
                onTag = onOpenTag,
                onTagForLater = { navigator.openInNewTab(Route.Grid(SearchTags.searchForOpenedTag(it))) },
                touch = touch,
              )
            }
          }
        }
      }
      // The window's messages (Blacklisted … · Undo, Couldn't follow …, a row opened for later): its own host is under
      // the page (#522). Where the window has it: desktop at the bottom, touch on the top line.
      if (snackbar != null && shown) {
        AppSnackbarHost(
          snackbar,
          swipeable = platformIsTouch,
          if (platformIsTouch) {
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)
          } else {
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
          },
        )
      }
    }
  }
}

@Composable
private fun TopRow(tag: TagDto, signedIn: Boolean, onClose: () -> Unit, blacklisting: TagBlacklisting) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  var menu by remember { mutableStateOf(false) }
  var confirming by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
    ButtonHint("Back (${Shortcuts.Back.label})") { IconButton(onClick = onClose) { Icon(arrow_back, contentDescription = "Back") } }
    Text(TAG, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
    Box {
      ButtonHint("More") { IconButton(onClick = { menu = true }) { Icon(more_vert, contentDescription = "More") } }
      GroupedMenu(expanded = menu, onDismissRequest = { menu = false }) {
        MenuGroup(0, 1) {
          MenuAction(
            text = { Text(COPY_NAME) },
            onClick = {
              menu = false
              scope.launch { clipboard.setClipEntry(plainTextClip(tag.searchName)) }
            },
          )
          if (signedIn) {
            MenuAction(
              text = { Text(BLACKLIST) },
              onClick = {
                menu = false
                confirming = true
              },
            )
          }
        }
      }
    }
  }
  if (confirming) {
    BlacklistConfirmation(tag, blacklisting::usedRules, onDismiss = { confirming = false }, onConfirm = { blacklisting.blacklist(tag) })
  }
}

@Composable
private fun Header(tag: TagDto, typed: Boolean, following: Boolean, onToggleFollow: (() -> Unit)?) {
  val colors = MaterialTheme.colorScheme
  Column(
    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // A tag a wiki links to has no type until it loads: none, rather than General for a moment.
    Row(
      Modifier.heightIn(min = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (typed) {
        Box(Modifier.size(10.dp).background(tag.category.color, CircleShape))
        Text(tag.category.name, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
      }
    }
    Text(
      tag.displayName,
      Modifier.semantics { heading() },
      style = MaterialTheme.typography.headlineSmall,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
    )
    if (onToggleFollow != null) {
      if (following) {
        OutlinedButton(onClick = onToggleFollow) { Text(FOLLOWING) }
      } else {
        FilledTonalButton(onClick = onToggleFollow) { Text(FOLLOW) }
      }
    }
  }
}

/** A row to a search: [label] and its [count] if known; a tap opens it, a long-press or middle-click for later. */
@Composable
private fun OpenRow(icon: ImageVector, label: String, count: String?, onOpen: () -> Unit, onOpenInNewTab: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .openOnClick(onOpen = onOpen, onOpenInNewTab = onOpenInNewTab)
      .semantics(mergeDescendants = true) { role = Role.Button }
      .padding(start = 24.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant)
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    if (count != null) Text(count, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    Icon(chevron_right, contentDescription = null, tint = colors.onSurfaceVariant)
  }
}

/**
 * The wiki ([body], DText, [wikiText]), [WIKI_LINES] lines until More shows the rest. A click on a link to a tag calls
 * [onTag]; a long-press (touch) or middle-click on one calls [onTagForLater] with its search name.
 */
@Composable
private fun About(body: String, onTag: (WikiTag) -> Unit, onTagForLater: (String) -> Unit, touch: Boolean = platformIsTouch) {
  val linkColor = MaterialTheme.colorScheme.primary
  val tagClicked by rememberUpdatedState(onTag)
  val forLater by rememberUpdatedState(onTagForLater)
  val text = remember(body, linkColor) { wikiText(body, linkColor) { tagClicked(it) } }
  if (text.isEmpty()) return
  var expanded by remember { mutableStateOf(false) }
  var overflows by remember { mutableStateOf(false) }
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  val haptics = LocalHapticFeedback.current

  // The tag linked where [at] is, if any.
  fun tagAt(at: Offset): String? {
    val laid = layout ?: return null
    // The caret's offset nearest to it: the character after it or the one before.
    val near = laid.getOffsetForPosition(at)
    val char = listOf(near, near - 1).firstOrNull { it in text.indices && laid.getBoundingBox(it).contains(at) } ?: return null
    return text.getLinkAnnotations(char, char + 1).firstNotNullOfOrNull { (it.item as? LinkAnnotation.Clickable)?.tag }
  }
  HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
  Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(ABOUT, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      text,
      Modifier
        // A middle-click or (touch) a long-press on a tag's link, as on a row. Seen before the link, which takes the
        // press and would open the tag's page on the long-press's release: that release goes no further.
        .pointerInput(text) {
          // Not a gesture: a gesture's first down is the primary button's.
          awaitPointerEventScope {
            var middleAt: Offset? = null
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              when (event.type) {
                PointerEventType.Press -> middleAt = event.changes.firstOrNull()?.position?.takeIf { event.buttons.isTertiaryPressed }
                PointerEventType.Release -> middleAt?.let { at ->
                  middleAt = null
                  tagAt(at)?.let(forLater)
                }
              }
            }
          }
        }
        .pointerInput(text, touch) {
          if (!touch) return@pointerInput
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tag = tagAt(down.position) ?: return@awaitEachGesture
            // Lifted or moved (a scroll) before the long-press timeout: not a long-press.
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
              while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
              }
              true
            }
            if (ended == null) {
              haptics.performHapticFeedback(HapticFeedbackType.Confirm)
              forLater(tag)
              do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
              } while (event.changes.any { it.pressed })
            }
          }
        },
      style = MaterialTheme.typography.bodyMedium,
      maxLines = if (expanded) Int.MAX_VALUE else WIKI_LINES,
      overflow = TextOverflow.Ellipsis,
      onTextLayout = {
        layout = it
        if (!expanded) overflows = it.hasVisualOverflow
      },
    )
    if (overflows || expanded) {
      TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) LESS else MORE) }
    }
  }
}

private const val WIKI_LINES = 4
internal const val TAG_PAGE_SCRIM = "tag-page-scrim"
internal const val TAG = "Tag"
internal const val POSTS = "Posts"
internal const val BOOKS = "Books"
internal const val FOLLOW = "Follow"
internal const val FOLLOWING = "Following"
private const val ABOUT = "About"
private const val MORE = "More"
private const val LESS = "Less"
private const val COPY_NAME = "Copy name"
private const val BLACKLIST = "Blacklist tag…"
