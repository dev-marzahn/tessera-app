package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import dev.marzahn.sankaku.ui.components.ButtonHint
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.stateDescription
import dev.marzahn.sankaku.queue.SeenStore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import com.google.fonts.icons.add
import com.google.fonts.icons.close
import com.google.fonts.icons.favorite_filled
import com.google.fonts.icons.grid_view
import com.google.fonts.icons.image
import com.google.fonts.icons.more_vert
import com.google.fonts.icons.menu_book
import dev.marzahn.sankaku.isBooks
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.cachedPostImageRequest
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.KbdBadge
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.ReopenTabBadge
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.rememberPulse
import dev.marzahn.sankaku.ui.openOnClick
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

private val TabShape = RoundedCornerShape(8.dp)

/** How far the strip moves per unit of wheel delta: Compose desktop's own step on macOS. */
private val WheelStep = 10.dp

/**
 * A click activates a tab; a middle click or the close button closes it. Each tab shows what it holds: a grid icon
 * and the search, or the post's thumbnail (from the image cache) and its characters. The tabs scroll with the wheel
 * (with or without Shift) or by dragging the strip; a count in its own slot at each end says how many are out of view
 * on that side, and the + stays at the right edge. Tabs next to each other with the same title are numbered. New
 * tabs open at the end, and the strip scrolls to the active tab. Resting the pointer on a tab shows its preview. ⋮ at
 * the end holds Clear viewed (#183).
 */
@Composable
fun TabStrip(
  modifier: Modifier = Modifier,
  controller: TabsController = koinInject(),
  clearViewed: ClearViewed = koinInject(),
  tabPosts: TabPosts = koinInject(),
  // What dims a seen kept item's tab (#502); without it (a test of the strip's layout) none is dimmed.
  seen: SeenStore? = null,
  // The + (#542): the shell's NewTab.open, a Browse tab with its search panel open, as Cmd/Ctrl-T does. A test of the
  // strip's layout leaves it out.
  onNewTab: () -> Unit = {},
  // What a "+N" count opens (#521): the tab switcher's queue list, every tab at once.
  onShowAll: (() -> Unit)? = null,
  // What ⋮'s Keyboard shortcuts opens (#521); without it, ⋮ has only Clear viewed.
  onShortcuts: (() -> Unit)? = null,
) {
  val tabs by controller.tabs.collectAsStateWithLifecycle()
  // Blocks (#502): each block with kept items underlined in one of [blockTones], and a seen kept item dimmed.
  val seenRevision = seen?.revision?.collectAsStateWithLifecycle()?.value ?: 0
  val stripBlocks = stripBlocks(tabs)
  val tones = blockTones()
  val active by controller.active.collectAsStateWithLifecycle()
  // A post tab's ♥ (#419): the PostStateStore's favourite over the held copy's, so it follows a toggle anywhere.
  val postIds = remember(tabs) { tabs.mapNotNullTo(mutableSetOf()) { (it.top as? Route.Post)?.id } }
  val posts by remember(tabPosts, postIds) { tabPosts.of(postIds) }.collectAsStateWithLifecycle(emptyMap())
  val scroll = rememberScrollState()
  // Where each tab sits in the scrolling row, to count those out of view and to bring the active one in.
  val spans = remember { mutableStateMapOf<TabId, IntRange>() }
  var viewport by remember { mutableIntStateOf(0) }

  // The tabs' width together, the start gap included, to tell whether they overflow the room they have.
  var content by remember { mutableIntStateOf(0) }
  var room by remember { mutableIntStateOf(0) }
  // The most the tabs could have (#521): they shrink toward [TabMinWidth] to share it before the strip overflows.
  var available by remember { mutableIntStateOf(0) }
  val density = LocalDensity.current
  val tabMax = with(density) {
    val shared = sharedTabWidth(available.toDp(), tabs.size, stripBlocks.count { it.startsBlock } - 1)
    // Scrolling, never wider than the view of the strip, so a narrow window still shows the active tab whole. Only
    // then: while the tabs fit, the view is only as wide as they are, and a tab capped by it could never grow back.
    if (content > room && viewport > 0) minOf(shared, viewport.toDp() - StripStartGap) else shared
  }
  // The tab to focus when the keys come into the strip (#521): the active one, not the first.
  val focusers = remember { mutableMapOf<TabId, FocusRequester>() }
  LaunchedEffect(tabs) { focusers.keys.retainAll(tabs.map { it.id }.toSet()) }
  val drag = rememberDraggableState { delta -> scroll.dispatchRawDelta(-delta) }
  val closed by controller.closed.collectAsStateWithLifecycle()
  // The tabs Z last brought back, each pulsing once; the serial pulses a tab reopened again.
  var pulse by remember { mutableStateOf(Pulse(emptySet(), 0)) }
  LaunchedEffect(controller) { controller.reopened.collect { pulse = Pulse(it, pulse.serial + 1) } }
  // Derived, so the strip's recomposing on every scroll frame doesn't rebuild them, while a post's title loading
  // (a snapshot read in `title`) still does.
  val twins by remember(tabs) { derivedStateOf { twinSuffixes(tabs.map { it.title }) } }

  // A reopened tab is made active, so this scrolls it into view too; so does the window's resizing (#521).
  LaunchedEffect(active, spans[active], viewport) { spans[active]?.let { scroll.bringIntoView(it, viewport) } }

  // No padding at the start: the first tab's gap from the window's edge is inside the scrolling row, so tabs scroll
  // all the way to the edge instead of vanishing 8 dp short of it.
  Row(
    modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // While the tabs overflow, each "+N" count has a slot of its own at its end of the strip, so it never covers a
    // tab's title (#422). Both slots stay while it overflows, and are as wide as the widest count can be (only a tab
    // count gaining a digit widens them), so a count coming, going or changing never resizes the viewport (which
    // changed the count again as it appeared). Whether it overflows is measured against the room without the slots,
    // which the slots only shrink, so it settles a frame after the tabs change and never flips back.
    val overflows = content > room
    val (before, after) =
      if (overflows) hiddenCounts(tabs.mapNotNull { spans[it.id] }, scroll.value, viewport) else 0 to 0
    Row(
      Modifier
        .weight(1f, fill = false)
        .layout { measurable, constraints ->
          available = constraints.maxWidth
          val placeable = measurable.measure(constraints)
          layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        .onSizeChanged { room = it.width },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (overflows) {
        HiddenCountSlot(before, widest = tabs.size, Alignment.CenterStart, onShowAll, before = true, Modifier.testTag(HIDDEN_BEFORE_TAG))
      }
      Box(Modifier.weight(1f, fill = false).testTag(TABS_VIEWPORT_TAG).onSizeChanged { size: IntSize -> viewport = size.width }) {
        Row(
          Modifier
            .horizontalScroll(scroll)
            .onSizeChanged { content = it.width }
            // The plain (vertical) wheel scrolls the strip too, not only Shift and the wheel; a sideways trackpad
            // swipe is left to horizontalScroll.
            .pointerInput(scroll) {
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent()
                  if (event.type != PointerEventType.Scroll) continue
                  val change = event.changes.firstOrNull() ?: continue
                  val delta = change.scrollDelta
                  if (abs(delta.y) <= abs(delta.x)) continue
                  scroll.dispatchRawDelta(delta.y * WheelStep.toPx())
                  change.consume()
                }
              }
            }
            // A mouse drag scrolls the strip too, past the touch slop, so a click still activates a tab.
            .draggable(drag, Orientation.Horizontal)
            // Room above and below, inside the scroll's clip, for a reopened tab's pulse (#420).
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            // Tab from outside lands on the active tab, not the first, which would scroll the strip away (#521).
            .focusProperties { onEnter = { focusers[active]?.requestFocus() } }
            .focusGroup()
            .semantics { selectableGroup() },
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          tabs.forEachIndexed { index, tab ->
            // Keyed, so a closed tab's remembered state never moves to its neighbour.
            key(tab.id) {
              val place = stripBlocks.getOrNull(index)
              Tab(
                tab,
                twins[index],
                selected = tab.id == active,
                favourited = (tab.top as? Route.Post)?.let { posts[it.id]?.favourited } == true,
                controller,
                pulse = pulse.serial.takeIf { tab.id in pulse.tabs },
                // Read with the revision, so a post seen anywhere dims at once.
                seen = seen != null && seenRevision >= 0 && tab.kind.isKept && tab.seenSinceKept(seen),
                underline = place?.takeIf { it.underlined }?.let { tones[it.block % tones.size] },
                joinsNext = place?.joinsNext == true,
                maxWidth = tabMax,
                focus = focusers.getOrPut(tab.id) { FocusRequester() },
                modifier = Modifier
                  // A gap between blocks, wider than between the tabs of one.
                  .padding(start = if (place?.startsBlock == true && index > 0) BlockGap else 0.dp)
                  .onPlaced {
                    val x = it.positionInParent().x.toInt()
                    spans[tab.id] = x until x + it.size.width
                  },
              )
            }
          }
        }
      }
      if (overflows) {
        HiddenCountSlot(after, widest = tabs.size, Alignment.CenterEnd, onShowAll, before = false, Modifier.testTag(HIDDEN_AFTER_TAG))
      }
    }
    // While something can be reopened, the + carries the phone pill's ↶ badge, which reopens as Z does (#420).
    ReopenTabBadge(canReopen = closed.isNotEmpty(), onReopen = controller::reopenClosed) {
      ButtonHint(NEW_TAB_LABEL) {
        IconButton(onClick = onNewTab) {
          Icon(add, contentDescription = NEW_TAB_LABEL)
        }
      }
    }
    StripMenu(viewed = { clearViewed.status().count }, onClearViewed = { clearViewed() }, onShortcuts = onShortcuts)
  }
}

/**
 * ⋮ at the strip's end (#183): "Clear viewed · N" with its shortcut, off while there's nothing to clear. The count is read
 * each time the menu opens, as what is seen changes outside the composition.
 */
@Composable
private fun StripMenu(viewed: () -> Int, onClearViewed: () -> Unit, onShortcuts: (() -> Unit)?) {
  var open by remember { mutableStateOf(false) }
  val groups = if (onShortcuts != null) 2 else 1
  Box {
    ButtonHint(MORE_LABEL) { IconButton(onClick = { open = true }) { Icon(more_vert, contentDescription = MORE_LABEL) } }
    GroupedMenu(expanded = open, onDismissRequest = { open = false }) {
      MenuGroup(0, groups) {
        val count = viewed()
        MenuAction(
          text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              Text("Clear viewed · $count", modifier = Modifier.semantics { contentDescription = clearViewedLabel(count) })
              KbdBadge(Shortcuts.ClearViewed.label)
            }
          },
          enabled = count > 0,
          onClick = {
            open = false
            onClearViewed()
          },
        )
      }
      if (onShortcuts != null) {
        MenuGroup(1, groups) {
          MenuAction(
            text = {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(SHORTCUTS_ITEM, Modifier.weight(1f, fill = false))
                KbdBadge(Shortcuts.Help.label)
              }
            },
            onClick = {
              open = false
              onShortcuts()
            },
          )
        }
      }
    }
  }
}

/** ⋮'s second group (#521): opens the keyboard shortcuts sheet, as ? does. */
internal const val SHORTCUTS_ITEM = "Keyboard shortcuts"

/** Scrolls the least distance that shows all of [span] in a viewport [width] wide. */
private suspend fun ScrollState.bringIntoView(span: IntRange, width: Int) {
  if (width <= 0) return
  val target = when {
    span.first < value -> span.first
    span.last >= value + width -> span.last + 1 - width
    else -> return
  }
  animateScrollTo(target.coerceIn(0, maxValue))
}

/**
 * Whether less than half of [span] shows in the viewport at [scrolled]. A tab cut by a few pixels at the edge isn't
 * out of view: counting it made the count change on the slightest scroll with no tab coming in.
 */
internal fun mostlyHidden(span: IntRange, scrolled: Int, viewport: Int): Boolean {
  val shown = minOf(span.last + 1, scrolled + viewport) - maxOf(span.first, scrolled)
  return shown * 2 < span.last + 1 - span.first
}

/** How many of the tabs at [spans] are [mostlyHidden] before the viewport (scrolled past) and after it. */
internal fun hiddenCounts(spans: List<IntRange>, scrolled: Int, viewport: Int): Pair<Int, Int> {
  if (viewport <= 0) return 0 to 0
  val hidden = spans.filter { mostlyHidden(it, scrolled, viewport) }
  val before = hidden.count { it.first < scrolled }
  return before to hidden.size - before
}

/**
 * What the strip shows after each of [titles] (#422): a tab whose title is the same as the one before it gets " · 2",
 * " · 3"… by its place in that run of twins, so three tabs from one artist read "Xingtong", "Xingtong · 2",
 * "Xingtong · 3"; any other tab gets "". Only tabs next to each other count, and only for display: the tab's own
 * title is unchanged.
 */
internal fun twinSuffixes(titles: List<String>): List<String> {
  var run = 0
  return titles.mapIndexed { index, title ->
    run = if (index > 0 && titles[index - 1] == title) run + 1 else 1
    if (run == 1) "" else " · $run"
  }
}

/**
 * [title] then its [twin] number ([twinSuffixes]). Only the title is cut short, so a long title's number still shows:
 * two long twins never both read "Hatsune Miku, Kasane T…".
 */
@Composable
internal fun TwinTitle(title: String, twin: String, untitled: Boolean, maxLines: Int, modifier: Modifier = Modifier) {
  val color = if (untitled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
  val style = MaterialTheme.typography.labelLarge
  Row(modifier, verticalAlignment = Alignment.Bottom) {
    Text(
      title,
      color = color,
      style = style,
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false).testTag(TAB_TITLE_TAG),
    )
    if (twin.isNotEmpty()) Text(twin, color = color, style = style, maxLines = 1, modifier = Modifier.testTag(TAB_TWIN_TAG))
  }
}

internal const val HIDDEN_BEFORE_TAG = "tabs-hidden-before"
internal const val HIDDEN_AFTER_TAG = "tabs-hidden-after"
internal const val TAB_TITLE_TAG = "tab-title"
internal const val TAB_TWIN_TAG = "tab-twin"
internal const val TABS_VIEWPORT_TAG = "tabs-viewport"

/**
 * The room for the "+[count]" at one end of the strip: as wide as "+[widest]" (no count can be more than the tabs),
 * with the count, when there is one, against the tabs' side [alignment] names.
 */
@Composable
private fun HiddenCountSlot(
  count: Int,
  widest: Int,
  alignment: Alignment,
  onShowAll: (() -> Unit)?,
  before: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(modifier.padding(horizontal = 4.dp), contentAlignment = alignment) {
    // Holds the width only: never seen or read out.
    HiddenCount(widest, Modifier.alpha(0f).clearAndSetSemantics {})
    if (count > 0) {
      // A button that opens every tab at once (#521): it looked like one and did nothing.
      val name = hiddenCountLabel(count, before)
      ButtonHint(SHOW_ALL_TABS_HINT.takeIf { onShowAll != null }) {
        HiddenCount(
          count,
          if (onShowAll == null) {
            Modifier
          } else {
            Modifier
              .clip(CircleShape)
              .clickable(role = Role.Button, onClickLabel = SHOW_ALL_TABS, onClick = onShowAll)
              // The name only: the click and the role are the clickable's.
              .clearAndSetSemantics { contentDescription = name }
          },
        )
      }
    }
  }
}

/** What a "+N" count is called for a screen reader (#521). */
internal fun hiddenCountLabel(count: Int, before: Boolean): String =
  "$count more ${if (count == 1) "tab" else "tabs"} ${if (before) "before" else "after"}"

/** What a "+N" count does, as its click label. */
internal const val SHOW_ALL_TABS = "Show all tabs"

/** A "+N" count's tooltip: what it opens, and its key (C, then Q for the list). */
internal val SHOW_ALL_TABS_HINT = "Show all tabs (${Shortcuts.TabSwitcher.label})"

/** The strip's + (#521: its tooltip as well as its name). */
internal val NEW_TAB_LABEL = "New tab (${Shortcuts.NewTab.label})"

/** The strip's ⋮. */
internal const val MORE_LABEL = "More"

/** The narrowest a tab gets before the strip overflows and scrolls (#521). */
internal val TabMinWidth = 120.dp

/** The widest a tab gets, with room to spare. */
internal val TabMaxWidth = 240.dp

/**
 * How wide each of [count] tabs may be to share [available] (the strip's room for its tabs) without scrolling (#521):
 * from [TabMaxWidth] down to [TabMinWidth], past which the strip scrolls. [blockGaps] are the gaps between blocks.
 */
internal fun sharedTabWidth(available: Dp, count: Int, blockGaps: Int): Dp {
  if (count <= 0 || available <= 0.dp) return TabMaxWidth
  val room = available - StripStartGap - TabGap * (count - 1) - BlockGap * blockGaps.coerceAtLeast(0)
  // Whole dp, rounded down: a share rounded up to whole pixels made the tabs a pixel too wide, and the strip overflow.
  val share = floor((room / count).value).dp
  // Too many to fit even at their narrowest: they scroll anyway, so each keeps its own width, as before (#422), rather
  // than every title cut to "Br… · 4".
  return if (share < TabMinWidth) TabMaxWidth else share.coerceAtMost(TabMaxWidth)
}

/** The first tab's gap from the window's edge, inside the scrolling row. */
private val StripStartGap = 8.dp

/** How many tabs are scrolled out of view on one side, so a strip wider than the window doesn't hide that they exist. */
@Composable
private fun HiddenCount(count: Int, modifier: Modifier = Modifier) {
  Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp, modifier = modifier) {
    Text(
      "+$count",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
  }
}

/** The tabs a reopen brought back ([TabsController.reopened]), and which reopen it was. */
private data class Pulse(val tabs: Set<TabId>, val serial: Int)

/**
 * How far a reopened tab's chip swells as it pulses: less than the "In tab" chip's 1.12, since a tab is up to 240 dp wide
 * and would cover its neighbours.
 */
private const val PULSE_SCALE = 1.08f

/** The room between two tabs of the strip; a block's line carries on over it to the next tab of the block. */
private val TabGap = 4.dp

/** A focused tab's ring (#521). */
private val FocusRingWidth = 2.dp

/** A tab's ✕, and the tab's action that does the same. */
internal const val CLOSE_TAB = "Close tab"

/** The room added before the first tab of a block (#502), so blocks stand apart. */
private val BlockGap = 6.dp

private val UnderlineWidth = 3.dp

/** How far under the tab the block's line runs: inside the room the scrolling row keeps below the tabs for a pulse. */
private val UnderlineOffset = 2.dp


/** The block lines' tones, in turn (#502): four, so neighbouring blocks always differ. */
@Composable
private fun blockTones(): List<Color> = MaterialTheme.colorScheme.run { listOf(primary, tertiary, secondary, outline) }

/**
 * A tab's chip. Each new [pulse] (the serial of the reopen that brought it back, #420) swells it once on the spatial
 * spring and back ([rememberPulse]), so the eye finds what Z reopened; with animations off it stays still.
 */
@Composable
private fun Tab(
  tab: TabState,
  twin: String,
  selected: Boolean,
  favourited: Boolean,
  controller: TabsController,
  pulse: Int?,
  seen: Boolean = false,
  underline: Color? = null,
  joinsNext: Boolean = false,
  maxWidth: Dp = TabMaxWidth,
  focus: FocusRequester = remember { FocusRequester() },
  modifier: Modifier = Modifier,
) {
  val swell = rememberPulse(pulse, PULSE_SCALE)
  var focused by remember { mutableStateOf(false) }
  val closable = controller.canClose(tab)
  val name = tab.title + twin
  val isActive = selected
  val gap = with(LocalDensity.current) { TabGap.toPx() }
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  var previewing by remember { mutableStateOf(false) }
  LaunchedEffect(hovered) {
    if (hovered) delay(PREVIEW_DELAY)
    previewing = hovered
  }
  Surface(
    shape = TabShape,
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    modifier = modifier
      // The block's line under the tab, carried on over the gap to the next tab of the block (#502).
      .drawBehind {
        if (underline == null) return@drawBehind
        val y = size.height + UnderlineOffset.toPx()
        val end = size.width + if (joinsNext) gap else 0f
        drawLine(underline, Offset(0f, y), Offset(end, y), strokeWidth = UnderlineWidth.toPx())
      }
      .alpha(if (seen) SEEN_ALPHA else 1f)
      // One node per tab, named by its title (#521): its icon said "Grid" for every grid tab. A tab of a tab list,
      // selected when active, with its ✕ as an action too, the ✕ itself being no stop of its own for Tab.
      .semantics(mergeDescendants = true) {
        contentDescription = name
        role = Role.Tab
        this.selected = isActive
        // Its states, not parts of its name: a merged "Favourited" from its ♥ made that the tab's second name (#536 review).
        tabStates(seen, favourited)?.let { stateDescription = it }
        if (closable) customActions = listOf(CustomAccessibilityAction(CLOSE_TAB) { controller.close(tab.id); true })
      }
      .graphicsLayer {
        scaleX = swell.value
        scaleY = swell.value
      }
      .height(36.dp)
      .widthIn(min = TabMinWidth, max = maxOf(TabMinWidth, maxWidth))
      .hoverable(interaction)
      // A ring that shows where the keys are (#521): the ripple's focus shade was too faint to find.
      .border(
        width = if (focused) FocusRingWidth else 0.dp,
        color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        shape = TabShape,
      )
      // Clipped first, so the hover and press highlight keeps the tab's rounded corners.
      .clip(TabShape)
      .focusRequester(focus)
      .onFocusChanged { focused = it.isFocused }
      .openOnClick(onOpen = { controller.activate(tab.id) }, onOpenInNewTab = { controller.close(tab.id) }),
  ) {
    // Inside the surface, so the popup is placed against the tab.
    if (previewing) TabPreview(tab, twin)
    // The title takes the free width, so the close button sits at the right edge with the same 8 dp to the
    // title on its left as to the tab's edge on its right.
    Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Box {
        TabIcon(tab.top, decorative = true)
        if (favourited) FavouritedMark(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp), spoken = false)
      }
      Spacer(Modifier.width(8.dp))
      TwinTitle(tab.title, twin, tab.untitled, maxLines = 1, Modifier.weight(1f))
      Spacer(Modifier.width(8.dp))
      // A feed item (#229) is never closed: no ✕, and the same room, so it lines up with the others.
      if (closable) {
        Box(
          Modifier
            .size(16.dp)
            .clip(CircleShape)
            // The tab's own stop is enough: X, Cmd/Ctrl-W and its Close tab action close it from the keys.
            .focusProperties { canFocus = false }
            .openOnClick({ controller.close(tab.id) }, { controller.close(tab.id) }),
          contentAlignment = Alignment.Center,
        ) {
          Icon(close, contentDescription = CLOSE_TAB, modifier = Modifier.size(14.dp))
        }
      } else {
        Spacer(Modifier.size(16.dp))
      }
    }
  }
}

/**
 * A favourited post tab's ♥ on its icon's corner (#419): the grid's favourite badge in small, a filled heart on the
 * primary colour, so it reads on any thumbnail.
 */
@Composable
internal fun FavouritedMark(modifier: Modifier, spoken: Boolean = true) {
  Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = modifier) {
    Icon(
      favorite_filled,
      contentDescription = FAVOURITED_TAB.takeIf { spoken },
      modifier = Modifier.padding(2.dp).size(8.dp).testTag(FAVOURITED_MARK_TAG),
    )
  }
}

/** The ♥ itself, said or not: the strip's tab says "Favourited" as its state (#521), not as part of its name. */
internal const val FAVOURITED_MARK_TAG = "favourited-mark"

/** A strip tab's state for a screen reader: "Seen", "Favourited", both, or none. */
internal fun tabStates(seen: Boolean, favourited: Boolean): String? =
  listOfNotNull("Seen".takeIf { seen }, FAVOURITED_TAB.takeIf { favourited }).joinToString(", ").ifEmpty { null }

/** What the ♥ on a favourited post's tab says. */
internal const val FAVOURITED_TAB = "Favourited"

/**
 * A post's thumbnail comes from the image cache only: a restored post tab has no URL, and fetching one would cost an
 * API call per tab at launch. Not cached, it shows the post icon. Without [picture] it's always the kind's icon (the tab
 * switcher's, for a tab G only hides, #461).
 */
@Composable
internal fun TabIcon(route: Route?, size: Dp = 18.dp, decorative: Boolean = false, picture: Boolean = true) {
  val iconModifier = Modifier.size(size)
  // A kept book's is its cover's (#385), which its row loaded.
  val picture = if (!picture) null else when (route) {
    is Route.Post -> route.md5?.let { PostImage(route.id, it, Variant.Preview, null) }
    is Route.Book -> route.cover?.let { PostImage(route.id, it, Variant.Preview, null) }
    else -> null
  }
  val thumbnail = picture?.let { rememberAsyncImagePainter(cachedPostImageRequest(LocalPlatformContext.current, it)) }
  val failed = thumbnail?.state?.collectAsState()?.value is AsyncImagePainter.State.Error
  when {
    thumbnail != null && !failed -> Image(
      thumbnail,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = iconModifier.clip(RoundedCornerShape(4.dp)),
    )
    route is Route.Post -> Icon(image, contentDescription = "Post".takeUnless { decorative }, modifier = iconModifier)
    route?.isBooks == true || route is Route.Book -> Icon(menu_book, contentDescription = BOOKS_TITLE.takeUnless { decorative }, modifier = iconModifier)
    else -> Icon(grid_view, contentDescription = "Grid".takeUnless { decorative }, modifier = iconModifier)
  }
}

/** How long the pointer rests on a tab before its preview shows. */
private val PREVIEW_DELAY = 600.milliseconds

private val PreviewWidth = 264.dp

/**
 * What [tab] shows, under it: the title, a grid's thumbnails from where it was last scrolled to or a post's preview,
 * and roughly how much memory it holds ([estimateTabBytes]). Images come from the cache only, so a preview costs no
 * request; a tab never shown (in the background, or restored) has nothing to show but its title.
 */
@Composable
private fun TabPreview(tab: TabState, twin: String, imageLoader: ImageLoader = koinInject()) {
  val contents = remember(tab) { tab.topContents() }
  val bytes = remember(tab) {
    tab.allContents().takeIf { it.isNotEmpty() }
      ?.let { estimateTabBytes(it, imageLoader.memoryCache?.decodedBytes().orEmpty()) }
  }
  val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
  Popup(popupPositionProvider = remember(gap) { BelowAnchor(gap) }, properties = PopupProperties(focusable = false)) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      shadowElevation = 6.dp,
      modifier = Modifier.width(PreviewWidth),
    ) {
      Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TwinTitle(tab.title, twin, tab.untitled, maxLines = 2, Modifier.padding(horizontal = 4.dp))
        val images = contents?.preview.orEmpty()
        when {
          images.isEmpty() -> Unit
          tab.top is Route.Post -> PreviewImage(images.first(), Modifier.fillMaxWidth().height(160.dp))
          else -> images.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              row.forEach { PreviewImage(it, Modifier.weight(1f).aspectRatio(1f)) }
              repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
          }
        }
        if (bytes != null) {
          Text(
            "Memory usage ≈ ${formatBytes(bytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
internal fun PreviewImage(image: PostImage, modifier: Modifier) {
  Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
    AsyncImage(
      model = cachedPostImageRequest(LocalPlatformContext.current, image),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.matchParentSize(),
    )
  }
}

/** Under the anchor, its start edge aligned with the anchor's but kept inside the window. */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ) = IntOffset(
    anchorBounds.left.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
    anchorBounds.bottom + gap,
  )
}
