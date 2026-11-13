package dev.marzahn.sankaku.features.post

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.google.fonts.icons.download
import com.google.fonts.icons.open_in_new
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.api.dto.TagType
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.features.books.FromTheBook
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.grid.GridCursorRequest
import dev.marzahn.sankaku.features.tag.TagPages
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.grid.RatingBadge
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.openUrl
import dev.marzahn.sankaku.ui.components.HoverHint
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.LocalKeyFocus
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.TagChip
import dev.marzahn.sankaku.ui.components.TagChipState
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.components.fastSpatialSpec
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.spatialSpec
import dev.marzahn.sankaku.ui.onRightClick
import dev.marzahn.sankaku.ui.openOnClick
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.color
import org.koin.compose.koinInject

/**
 * The info panel's body under its title row ([PostTitle]): the favourite and vote group, the facts, the post's book
 * ([FromTheBook]), the parent and the children, the recommended posts, the tags. Opening a link navigates the tab; a viewer stays open underneath, so Back
 * returns to the same post. The screen around it handles the favourite key ([postShortcuts]). [section] decorates each
 * of the panel's sections by its index (0 = the favourite and vote group), for the viewer's entrance motion. The
 * parent, the children and the recommended posts are [PostStrip]s (#342).
 * Reactions come later.
 * The phone viewer's sheet holds the favourite and vote group in its own header ([showStats] false) and this whole
 * body under it, so what's added here shows on both platforms. Its header's byline already names the uploader and the
 * date, so it leaves out those rows ([showUploader] false, #139).
 */
@Composable
fun PostDetails(
  post: PostDto,
  modifier: Modifier = Modifier,
  scroll: ScrollState = rememberScrollState(),
  showStats: Boolean = true,
  showUploader: Boolean = true,
  searchTags: List<String> = emptyList(),
  tagListFocus: FocusRequester = remember { FocusRequester() },
  section: (index: Int) -> Modifier = { Modifier },
  navigator: Navigator = koinInject(),
  actions: PostActions = koinInject(),
  repository: PostsRepository = koinInject(),
  session: AuthSession = koinInject(),
  saver: PostSaver = koinInject(),
  tabs: TabsController = koinInject(),
  messages: Messages = koinInject(),
  cursorRequest: GridCursorRequest = koinInject(),
  blacklisting: TagBlacklisting = koinInject(),
  tagPages: TagPages = koinInject(),
) {
  fun open(route: Route) = navigator.goTo(route)
  val signedIn by session.isSignedIn.collectAsState()
  val followOverrides by actions.follows.overrides.collectAsState()
  // Touch: failed follows by tag name, so the chip answers (`.scratch/android-feedback/`, C, row 10).
  val followRejections = remember { mutableStateMapOf<String, Int>() }
  if (platformIsTouch) {
    LaunchedEffect(actions) {
      actions.rejections.collect { if (it is Rejection.Follow) followRejections[it.tagName] = (followRejections[it.tagName] ?: 0) + 1 }
    }
  }
  // A tag action is acting on the post, so it's seen (#174).
  val tagActions = TagActions(
    search = searchTags,
    onOpen = { actions.actedOn(post); open(it) },
    onOpenInNewTab = { actions.actedOn(post); navigator.openInNewTab(it) },
    isFollowing = { actions.follows.isFollowing(it, followOverrides) },
    onToggleFollow = if (signedIn) { tag -> actions.actedOn(post); actions.toggleFollow(tag) } else null,
    onKeySearch = { actions.actedOn(post); cursorRequest.request(it.tags); open(it) },
    followRejected = { followRejections[it] ?: 0 },
    onAlreadySearching = { messages.show("Already searching ${it.displayName}") },
    onBlacklist = if (signedIn) { tag -> actions.actedOn(post); blacklisting.blacklist(tag) } else null,
    usedBlacklistRules = blacklisting::usedRules,
    onAbout = { actions.actedOn(post); tagPages.open(it) },
  )

  Column(
    modifier.verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (showStats) {
      PostStats(
        post,
        onToggleFavourite = { actions.toggleFavourite(post) },
        onVote = { actions.vote(post, it) },
        onSignIn = if (signedIn) null else actions::openSignIn,
        modifier = section(0),
      )
    }

    // The facts are always there, so they tell the book's row when the details are read (a sheet at peek isn't).
    var factsOnScreen by remember { mutableStateOf(false) }
    Column(
      section(1).onGloballyPositioned { factsOnScreen = it.boundsInWindow().let { b -> b.width > 0f && b.height > 0f } },
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (showUploader) {
        post.author?.let { Fact("Uploader", it.name) }
        post.createdAt?.let { Fact("Uploaded", it.toString().take(10)) }
      }
      if (post.fileWidth != null && post.fileHeight != null) Fact("Size", "${post.fileWidth} × ${post.fileHeight}")
      Fact("File", listOfNotNull(post.fileSize?.let(::humanSize), post.fileType).joinToString(" · "))
      // The search bar's badge and name (#139); a code the app doesn't know stays as the site sent it.
      val rating = Rating.ofCode(post.rating)
      if (rating == null) {
        Fact("Rating", post.rating.uppercase())
      } else {
        Fact("Rating") {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RatingBadge(rating)
            Text(rating.label, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }

    // The book the post is a page of (#386), under the facts with their entrance; nothing for a post in no book.
    FromTheBook(post, section(1), onScreen = { factsOnScreen }, onOpen = { actions.actedOn(post) }, navigator = navigator)

    // The parent and the children as Recommended's previews (#342), each loaded only once it's on screen; a post with
    // neither asks nothing. Past the strip's first page, its last tile opens them all as a grid.
    post.parentId?.let { parent ->
      PostStrip(SheetStrip.Parent, post.id, section(2), navigator = navigator, repository = repository, tabs = tabs) {
        StripPosts(listOfNotNull(repository.parent(parent, it)))
      }
    }
    // Parent and Children share an entrance: they're one part of the details, the post's family.
    if (post.hasChildren) {
      PostStrip(SheetStrip.Children, post.id, section(2), navigator = navigator, repository = repository, tabs = tabs) {
        val children = repository.children(post.id, it)
        StripPosts(children.posts, more = Route.Grid(listOf("parent:${post.id}")).takeIf { children.mayHaveMore })
      }
    }

    // Above the tags (option B of finding 16, decision 2026-11-01, #142): it's the part of the details that leads to
    // more posts, and with 70 tags it was six flings down. Small previews, so the tags stay close.
    RecommendedPosts(post.id, section(3), navigator, repository, tabs)

    Column(section(4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      HorizontalDivider()
      val fullTags = fullTags(post.id, repository)
      // One call, so the list (and its keyboard focus) stays the same composable as the tags fill in and the viewer
      // steps on.
      val (tags, typed) = when {
        fullTags != null -> fullTags to true
        post.tagNames.isNotEmpty() -> post.tagNames.map { TagDto(name = it.replace('_', ' '), tagName = it) } to false
        else -> post.tags to true
      }
      TagList(tags, tagActions, tagListFocus, typed)
      HorizontalDivider()
    }

    Row(section(5), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      // On the phone Save is in the viewer's ⋮ (with Share), so the sheet doesn't show it twice.
      if (!platformIsTouch) {
        val saving by saver.saving.collectAsState()
        DetailsButton(if (post.id in saving) "Saving…" else "Save", download, enabled = post.id !in saving) { saver.save(post) }
      }
      DetailsButton("Open in browser", open_in_new) { messages.openUrl(postPageUrl(post)) }
    }
  }
}

@Composable
private fun DetailsButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
  TextButton(onClick = onClick, enabled = enabled) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(label)
  }
}

@Composable
private fun Fact(label: String, value: String) = Fact(label) { Text(value, style = MaterialTheme.typography.bodySmall) }

@Composable
private fun Fact(label: String, value: @Composable () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
    value()
  }
}

/**
 * Every tag of [postId], from the full post (the list's post has only 5 tag objects), or null until it arrives or if
 * it fails. Only the tags are taken: favourite state stays with the caller's post, which carries the overrides.
 */
@Composable
private fun fullTags(postId: String, repository: PostsRepository, tags: TagsRepository = koinInject()): List<TagDto>? {
  // Keyed by post: when the viewer steps on, the previous post's tags must not show while the next one loads. Tags
  // already shown start from the first frame, so a card shown again (its tab back in front) doesn't
  // flash the plain names.
  val loaded by produceState(shownTags[postId]?.let { postId to it }, postId) {
    value = try {
      repository.fullPost(postId).tags.takeIf { it.isNotEmpty() }?.also { tags.learnFromPosts(it) }
        ?.also { shownTags.putBounded(postId, it, SHOWN_TAGS_MAX) }?.let { postId to it }
    } catch (_: ApiError) {
      value
    }
  }
  return loaded?.takeIf { it.first == postId }?.second
}

/** The full tags of the posts shown last, by post id; main thread only. */
private val shownTags = LinkedHashMap<String, List<TagDto>>()
private const val SHOWN_TAGS_MAX = 32

private fun <K, V> LinkedHashMap<K, V>.putBounded(key: K, value: V, max: Int) {
  remove(key)
  put(key, value)
  while (size > max) remove(keys.first())
}

/** What the tag list does, and the state it shows: the search it belongs to and which tags are followed. */
internal class TagActions(
  val search: List<String>,
  val onOpen: (Route) -> Unit,
  val onOpenInNewTab: (Route) -> Unit,
  val isFollowing: (TagDto) -> Boolean,
  /** Null when signed out: following needs an account, so it isn't offered. */
  val onToggleFollow: ((TagDto) -> Unit)?,
  /**
   * A search from the keys (E, Q, Space, Backspace) in this tab: as [onOpen], and the grid it opens starts with its
   * cursor on the first cell, as after Enter in the search panel (#421).
   */
  val onKeySearch: (Route.Grid) -> Unit,
  /** How many follows of the tag named so have failed here (touch), for its chip's shake. */
  val followRejected: (String) -> Int = { 0 },
  /**
   * A click or tap on a chip the search already includes, instead of [onOpen]: its popular search would run in the
   * source shown and lose the grid's order and place, so it only says so (#313).
   */
  val onAlreadySearching: (TagDto) -> Unit = {},
  /** Null when signed out: a blacklist rule is the account's, so Blacklist tag… isn't offered. Runs once confirmed. */
  val onBlacklist: ((TagDto) -> Unit)? = null,
  /** How many blacklist rules the account uses, for the confirmation; null if it couldn't be loaded. */
  val usedBlacklistRules: suspend () -> Int? = { null },
  /** Opens the tag's page (#486), "About this tag"; null where it isn't offered. */
  val onAbout: ((TagDto) -> Unit)? = null,
)

/**
 * The tag list's category order: the site's, except Medium, which sits just above Automatic near the end instead of
 * after Genre (owner feedback, 2026-10-30), and General, which sits right after Setting, the last green type, instead
 * of first (#88).
 */
internal val TagListOrder: List<TagType> =
  TagType.entries.filter { it != TagType.Medium && it != TagType.General }.let { types ->
    val afterSetting = types.indexOf(TagType.Setting) + 1
    val automatic = types.indexOf(TagType.Automatic)
    types.take(afterSetting) + TagType.General + types.subList(afterSetting, automatic) + TagType.Medium +
      types.drop(automatic)
  }

/**
 * A chip cloud (direction C of `.scratch/tag-list-redesign`, chosen 2026-10-30): the tags wrap as compact chips per
 * type, in [TagListOrder], so most posts' tags show without scrolling. [typed] false is `tag_names` while the full
 * post loads (every tag, but without type or count, so one plain group). A chip in the current search is the solid
 * pill with a plus, or a red outline with a minus ([TagChip]); a followed tag has a bell. A click searches for the
 * tag alone (middle-click in a new tab), except on an included tag, where it only says so
 * ([TagActions.onAlreadySearching], #313); a right-click (long-press on touch) opens [TagMenu].
 *
 * The keyboard (T, which the screen around it handles, requests [focus]): A/D or ←/→ walk the chips in reading order,
 * across rows and types; W/S or ↑/↓ go to the chip on the row above or below nearest it sideways ([chipAboveOrBelow]);
 * E includes the selected one, Q excludes it, the same key again or Backspace removes it ([searchAfterKey]);
 * Space searches for it alone. Shift, Cmd or Ctrl sends the change to a background tab. Esc hands the keys back to the
 * post. Other keys (F, I, 1–5) go on to the post's own handlers. While it has the keys, the header turns primary
 * and a card under the selected chip lists what each key would do to it.
 */
@Composable
private fun TagList(tags: List<TagDto>, actions: TagActions, focus: FocusRequester, typed: Boolean = true) {
  val groups = if (typed) tags.groupBy { it.category }.entries.sortedBy { TagListOrder.indexOf(it.key) }.map { it.key to it.value } else listOf(null to tags)
  val ordered = groups.flatMap { it.second }
  val keyFocus = LocalKeyFocus.current
  var focused by remember { mutableStateOf(false) }
  // Leaving while it has the keys (the info panel collapsed, the post gone) would leave nothing focused and every key
  // unheard: they go back to the post.
  val stillFocused by rememberUpdatedState(focused)
  DisposableEffect(Unit) { onDispose { if (stillFocused) keyFocus.returnToContent() } }
  // By name, so the selection stays on its tag when the full tags replace the names.
  var selectedName by remember { mutableStateOf<String?>(null) }
  val selected = ordered.indexOfFirst { it.searchName == selectedName }.coerceAtLeast(0)
  // The chip whose menu is open, by name: a right-click or long-press on it, or R on the selected one.
  var menuName by remember { mutableStateOf<String?>(null) }
  // Each chip's bounds in the window, by name, for W/S. Read only on a key press, so not state.
  val chipBounds = remember { HashMap<String, Rect>() }

  fun onKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || ordered.isEmpty()) return false
    val tag = ordered[selected]
    val newTab = event.isShiftPressed || event.isCommand
    fun change(tags: List<String>?) = true.also {
      val route = Route.Grid(tags ?: return@also)
      if (newTab) actions.onOpenInNewTab(route) else actions.onKeySearch(route)
    }
    fun select(index: Int) = true.also { selectedName = ordered[index.coerceIn(ordered.indices)].searchName }
    fun vertical(down: Boolean) = true.also {
      chipAboveOrBelow(ordered.map { chipBounds[it.searchName] }, selected, down)?.let(::select)
    }
    val key = event.key
    return when {
      Shortcuts.Previous.matches(key) -> select(selected - 1)
      Shortcuts.Next.matches(key) -> select(selected + 1)
      Shortcuts.Up.matches(key) -> vertical(down = false)
      Shortcuts.Down.matches(key) -> vertical(down = true)
      Shortcuts.Include.matches(key) -> change(searchAfterKey(actions.search, tag.searchName, TagKey.Include))
      Shortcuts.Exclude.matches(key) -> change(searchAfterKey(actions.search, tag.searchName, TagKey.Exclude))
      Shortcuts.Remove.matches(key) -> change(searchAfterKey(actions.search, tag.searchName, TagKey.Remove))
      Shortcuts.OpenHere.matches(key) -> change(SearchTags.searchForOpenedTag(tag.searchName))
      Shortcuts.Menu.matches(key) && !newTab -> true.also { menuName = tag.searchName }
      Shortcuts.Back.matches(key) -> true.also { keyFocus.returnToContent() }
      else -> false
    }
  }

  Column(
    Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }.onKeyEvent(::onKey).focusable(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    TagListHeader(count = ordered.size, focused = focused)
    groups.forEach { (type, group) ->
      Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (type != null) {
          // In the neutral text colour with the type's dot: the type colours themselves fail contrast as text (the
          // purples in dark, the pastels in light).
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(type.color, CircleShape))
            Text(type.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        // On touch each chip's 48 dp touch target already spaces the rows, so they add no gap of their own.
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(if (platformIsTouch) 6.dp else 5.dp),
          verticalArrangement = Arrangement.spacedBy(if (platformIsTouch) 0.dp else 5.dp),
        ) {
          // Keyed by name, so a chip's state (an open confirmation) stays with its tag when the full tags replace the
          // names and the chips regroup.
          group.forEach { tag ->
            key(tag.searchName) {
              TagChipItem(
                tag, type, actions,
                selected = focused && tag === ordered.getOrNull(selected),
                menu = menuName == tag.searchName,
                onMenu = { open -> menuName = if (open) tag.searchName else null },
                onPlaced = { chipBounds[tag.searchName] = it },
              )
            }
          }
        }
      }
    }
  }
}

/**
 * For W/S in the tag list: the chip on the nearest row above (or, if [down], below) the chip at [from], among [chips]
 * (each chip's bounds, in reading order; null while not laid out), whose centre is nearest [from]'s sideways; null at
 * the top or bottom. A row is the chips level with the nearest one, so a type's header between two rows is passed over.
 */
internal fun chipAboveOrBelow(chips: List<Rect?>, from: Int, down: Boolean): Int? {
  val at = chips.getOrNull(from) ?: return null
  val side = chips.withIndex().mapNotNull { (i, r) -> r?.let { i to it } }
    .filter { (_, r) -> if (down) r.top >= at.center.y else r.bottom <= at.center.y }
  val nearest = side.minByOrNull { (_, r) -> if (down) r.top else -r.bottom }?.second ?: return null
  return side.filter { (_, r) -> r.top < nearest.bottom && r.bottom > nearest.top }
    .minBy { (_, r) -> abs(r.center.x - at.center.x) }.first
}

/** "Tags · 31" with the keys to use: T while the list doesn't have them; WASD and Esc, and a primary bar, while it does. */
@Composable
private fun TagListHeader(count: Int, focused: Boolean) {
  val colors = MaterialTheme.colorScheme
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (focused) Box(Modifier.size(width = 3.dp, height = 14.dp).background(colors.primary, CircleShape))
    Text("Tags", style = MaterialTheme.typography.labelLarge, color = if (focused) colors.primary else colors.onSurface)
    Text("$count", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    Spacer(Modifier.weight(1f))
    // Android has no tag keys, so no hints for them.
    if (platformIsTouch) return@Row
    if (focused) {
      KeyHint("W A S D", "move")
      KeyHint(Shortcuts.Back.label, "back")
    } else {
      KeyHint(Shortcuts.TagList.label, "keys")
    }
  }
}

@Composable
private fun TagChipItem(
  tag: TagDto,
  type: TagType?,
  actions: TagActions,
  selected: Boolean,
  menu: Boolean,
  onMenu: (Boolean) -> Unit,
  onPlaced: (Rect) -> Unit,
) {
  val route = Route.Grid(SearchTags.searchForOpenedTag(tag.searchName))
  val state = inSearch(tag.searchName, actions.search)
  val following = actions.isFollowing(tag)
  val interactions = remember { MutableInteractionSource() }
  // The keyboard's selection scrolls into view as it moves.
  val inView = remember { BringIntoViewRequester() }
  LaunchedEffect(selected) { if (selected) inView.bringIntoView() }
  // On touch the chip whose sheet is open keeps the ring (the approved mockup's long-pressed chip).
  val ringed = selected || (platformIsTouch && menu)
  // Above its neighbours, so the ring (drawn outside the chip) isn't covered by the next chip.
  Box(Modifier.zIndex(if (ringed) 1f else 0f).bringIntoViewRequester(inView).onGloballyPositioned { onPlaced(it.boundsInWindow()) }) {
    TagChip(
      name = tag.displayName,
      type = type,
      count = tag.postCount?.let(::compactCount),
      state = when (state) {
        InSearch.Included -> TagChipState.Included
        InSearch.Excluded -> TagChipState.Excluded
        InSearch.No -> TagChipState.Plain
      },
      selected = ringed,
      interactionSource = interactions,
      // Touch: a failed follow shakes the chip back (`.scratch/android-feedback/`, C, row 10).
      rejected = actions.followRejected(tag.name),
      modifier = Modifier
        .onContextClick { onMenu(true) }
        // A long-press opens the tag's menu (a sheet on touch), as a right-click does: a tag has too many actions for
        // one gesture.
        .openOnClick(
          onOpen = { if (state == InSearch.Included) actions.onAlreadySearching(tag) else actions.onOpen(route) },
          onOpenInNewTab = { actions.onOpenInNewTab(route) },
          interactionSource = interactions,
          onLongPress = { onMenu(true) },
        ),
      // On touch the bell only shows the state: as a 13 dp target inside the chip's 48 dp one, a tap meant to search
      // would unfollow. The sheet's Unfollow does it there.
      trailing = { gap ->
        val unfollow = actions.onToggleFollow?.takeUnless { platformIsTouch }?.let { toggle -> { toggle(tag) } }
        FollowBell(following, onClick = unfollow, gap)
      },
    )
    // Not over the open menu: the card would cover its top items.
    if (selected && !menu) TagKeysCard(tag, type, state)
    TagMenu(
      expanded = menu, onDismiss = { onMenu(false) }, tag = tag, type = type, following = following, actions = actions,
    )
  }
}

/**
 * Under the keyboard's selected chip: its name and type, and what each key would do to it now (E removes a tag that's
 * already included, and so on). A popup, so it lies over the chips below instead of pushing them down.
 */
@Composable
private fun TagKeysCard(tag: TagDto, type: TagType?, state: InSearch) {
  val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
  Popup(popupPositionProvider = remember(gap) { BelowAnchor(gap) }, properties = PopupProperties(focusable = false)) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.inverseSurface,
      contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      shadowElevation = 4.dp,
      modifier = Modifier.widthIn(max = 240.dp),
    ) {
      Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          listOfNotNull(tag.displayName, type?.name).joinToString(" · "),
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val hint = MaterialTheme.colorScheme.inverseOnSurface
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          KeyHint(Shortcuts.Include.label, if (state == InSearch.Included) "remove" else "add", hint)
          KeyHint(Shortcuts.Exclude.label, if (state == InSearch.Excluded) "remove" else "exclude", hint)
          KeyHint("␣", "only this", hint)
          KeyHint(Shortcuts.Menu.label, "menu", hint)
          KeyHint("⇧", "new tab", hint)
        }
      }
    }
  }
}

/** Places a popup [gap] px under its anchor, left-aligned with it, kept inside the window. */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
    IntOffset(
      anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
      anchorBounds.bottom + gap,
    )
}

/**
 * A followed tag's bell, in the chip's text colour, [gap] after the name (only while it shows, so an unfollowed chip
 * has no gap). Following springs it in and rings it, swinging from its top; unfollowing shrinks it away. [onClick]
 * (signed in, and not on touch) unfollows.
 */
@Composable
private fun FollowBell(following: Boolean, onClick: (() -> Unit)?, gap: Dp) {
  val swing = remember { Animatable(0f) }
  var rang by remember { mutableStateOf(following) }
  LaunchedEffect(following) {
    if (following && !rang) {
      swing.animateTo(0f, keyframes {
        durationMillis = 700
        18f at 90
        -16f at 220
        10f at 350
        -6f at 480
      })
    }
    rang = following
  }
  AnimatedVisibility(
    following,
    enter = scaleIn(spatialSpec()) + fadeIn(effectsSpec()),
    exit = scaleOut(fastSpatialSpec()) + fadeOut(effectsSpec()),
  ) {
    Box(Modifier.padding(start = gap)) {
      HoverHint(if (onClick != null) "Following · click to unfollow" else "Following") {
        Icon(
          TagFollowed,
          contentDescription = "Following",
          tint = LocalContentColor.current,
          modifier = Modifier
            .size(13.dp)
            .graphicsLayer {
              rotationZ = swing.value
              transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        )
      }
    }
  }
}


/** Where a tag stands in a search. */
internal enum class InSearch { No, Included, Excluded }

/** [tag] (a search key) in [search]: included as itself, excluded as `-tag`; case doesn't matter. */
internal fun inSearch(tag: String, search: List<String>): InSearch {
  val key = tag.lowercase()
  return when {
    search.any { it.lowercase() == key } -> InSearch.Included
    search.any { it.lowercase() == "-$key" } -> InSearch.Excluded
    else -> InSearch.No
  }
}

/**
 * [search] with [tag] set to [state]: switched in place when it's already there (so the search keeps its order),
 * else added at the end; [InSearch.No] removes it. Filter tokens stay first ([SearchTags.filtersFirst]).
 */
internal fun withTag(search: List<String>, tag: String, state: InSearch): List<String> {
  val key = tag.lowercase()
  val entry = when (state) {
    InSearch.No -> null
    InSearch.Included -> tag
    InSearch.Excluded -> "-$tag"
  }
  val at = search.indexOfFirst { it.lowercase().removePrefix("-") == key }
  val next = when {
    at < 0 -> if (entry == null) search else search + entry
    else -> search.toMutableList().apply { if (entry == null) removeAt(at) else set(at, entry) }
  }
  return SearchTags.filtersFirst(next)
}

/** A tag list key: E, Q, Backspace. */
internal enum class TagKey { Include, Exclude, Remove }

/**
 * [search] after [key] on [tag]: E includes and Q excludes, switching the tag if it's the other way; the same key
 * again, or Backspace, removes it. Null when nothing changes (Backspace on a tag not in the search).
 */
internal fun searchAfterKey(search: List<String>, tag: String, key: TagKey): List<String>? {
  val now = inSearch(tag, search)
  val next = when (key) {
    TagKey.Include -> if (now == InSearch.Included) InSearch.No else InSearch.Included
    TagKey.Exclude -> if (now == InSearch.Excluded) InSearch.No else InSearch.Excluded
    TagKey.Remove -> InSearch.No
  }
  return if (next == now) null else withTag(search, tag, next)
}

/** Right-click, or a long press on touch: opens a context menu instead of the click's action. */
private fun Modifier.onContextClick(onMenu: () -> Unit): Modifier = composed {
  val handler by rememberUpdatedState(onMenu)
  this
    .onRightClick { handler() }
    .pointerInput(Unit) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Touch) return@awaitEachGesture
        awaitLongPressOrCancellation(down.id)?.let {
          it.consume()
          handler()
        }
      }
    }
}

internal fun humanSize(bytes: Long): String = when {
  bytes >= 1_000_000 -> "${(bytes / 100_000) / 10.0} MB"
  bytes >= 1_000 -> "${bytes / 1_000} kB"
  else -> "$bytes B"
}

internal fun compactCount(count: Int): String = when {
  count >= 1_000_000 -> "${(count / 100_000) / 10.0}M"
  count >= 1_000 -> "${(count / 100) / 10.0}k"
  else -> count.toString()
}
