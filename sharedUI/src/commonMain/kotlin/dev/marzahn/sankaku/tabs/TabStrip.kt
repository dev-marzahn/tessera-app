package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
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
import com.google.fonts.icons.grid_view
import com.google.fonts.icons.image
import com.google.fonts.icons.more_vert
import com.google.fonts.icons.settings
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.cachedPostImageRequest
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.KbdBadge
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.openOnClick
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private val TabShape = RoundedCornerShape(8.dp)

/** How far the strip moves per unit of wheel delta: Compose desktop's own step on macOS. */
private val WheelStep = 10.dp

/**
 * A click activates a tab; a middle click or the close button closes it. Each tab shows what it holds: a grid icon
 * and the search, or the post's thumbnail (from the image cache) and its characters. The tabs scroll with the wheel
 * (with or without Shift) or by dragging the strip; a count says how many are out of view, and the + stays at the right edge. New
 * tabs open at the end, and the strip scrolls to the active tab. Resting the pointer on a tab shows its preview. ⋮ at
 * the end holds Clear viewed (#183).
 */
@Composable
fun TabStrip(
  modifier: Modifier = Modifier,
  controller: TabsController = koinInject(),
  clearViewed: ClearViewed = koinInject(),
) {
  val tabs by controller.tabs.collectAsStateWithLifecycle()
  val active by controller.active.collectAsStateWithLifecycle()
  val scroll = rememberScrollState()
  // Where each tab sits in the scrolling row, to count those out of view and to bring the active one in.
  val spans = remember { mutableStateMapOf<TabId, IntRange>() }
  var viewport by remember { mutableIntStateOf(0) }
  val drag = rememberDraggableState { delta -> scroll.dispatchRawDelta(-delta) }

  LaunchedEffect(active, spans[active]) { spans[active]?.let { scroll.bringIntoView(it, viewport) } }

  // No padding at the start: the first tab's gap from the window's edge is inside the scrolling row, so tabs scroll
  // all the way to the edge instead of vanishing 8 dp short of it.
  Row(
    modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(Modifier.weight(1f, fill = false).onSizeChanged { size: IntSize -> viewport = size.width }) {
      Row(
        Modifier
          .horizontalScroll(scroll)
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
          .padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        tabs.forEach { tab ->
          // Keyed, so a closed tab's remembered state never moves to its neighbour.
          key(tab.id) {
            Tab(
              tab,
              selected = tab.id == active,
              controller,
              Modifier.onPlaced {
                val x = it.positionInParent().x.toInt()
                spans[tab.id] = x until x + it.size.width
              },
            )
          }
        }
      }
      // A count at each edge for the tabs out of view on that side. Drawn over the strip's ends, so showing or
      // changing one never resizes the viewport (which changed the count again as it appeared).
      val (before, after) = hiddenCounts(tabs.mapNotNull { spans[it.id] }, scroll.value, viewport)
      if (before > 0) HiddenCount(before, Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
      if (after > 0) HiddenCount(after, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
    }
    IconButton(onClick = { controller.open(Route.Grid(emptyList()), background = false) }) {
      Icon(add, contentDescription = "New tab (⌘T)")
    }
    StripMenu(viewed = { clearViewed.status().count }, onClearViewed = { clearViewed() })
  }
}

/**
 * ⋮ at the strip's end (#183): "Clear viewed · N" with its shortcut, off while there's nothing to clear. The count is read
 * each time the menu opens, as what is seen changes outside the composition.
 */
@Composable
private fun StripMenu(viewed: () -> Int, onClearViewed: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }) { Icon(more_vert, contentDescription = "More") }
    GroupedMenu(expanded = open, onDismissRequest = { open = false }) {
      MenuGroup(0, 1) {
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
    }
  }
}

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

/** How many tabs are scrolled out of view on one side, so a strip wider than the window doesn't hide that they exist. */
@Composable
private fun HiddenCount(count: Int, modifier: Modifier) {
  Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp, modifier = modifier) {
    Text(
      "+$count",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun Tab(tab: TabState, selected: Boolean, controller: TabsController, modifier: Modifier = Modifier) {
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
      .height(36.dp)
      .widthIn(min = 120.dp, max = 240.dp)
      .hoverable(interaction)
      // Clipped first, so the hover and press highlight keeps the tab's rounded corners.
      .clip(TabShape)
      .openOnClick(onOpen = { controller.activate(tab.id) }, onOpenInNewTab = { controller.close(tab.id) }),
  ) {
    // Inside the surface, so the popup is placed against the tab.
    if (previewing) TabPreview(tab)
    // The title takes the free width, so the close button sits at the right edge with the same 8 dp to the
    // title on its left as to the tab's edge on its right.
    Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      TabIcon(tab.top)
      Spacer(Modifier.width(8.dp))
      Text(
        tab.title,
        color = if (tab.untitled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      // A feed item (#229) is never closed: no ✕, and the same room, so it lines up with the others.
      if (controller.canClose(tab)) {
        Box(
          Modifier.size(16.dp).clip(CircleShape).openOnClick({ controller.close(tab.id) }, { controller.close(tab.id) }),
          contentAlignment = Alignment.Center,
        ) {
          Icon(close, contentDescription = "Close tab", modifier = Modifier.size(14.dp))
        }
      } else {
        Spacer(Modifier.size(16.dp))
      }
    }
  }
}

/**
 * A post's thumbnail comes from the image cache only: a restored post tab has no URL, and fetching one would cost an
 * API call per tab at launch. Not cached, it shows the post icon.
 */
@Composable
internal fun TabIcon(route: Route?, size: Dp = 18.dp, decorative: Boolean = false) {
  val iconModifier = Modifier.size(size)
  val thumbnail = (route as? Route.Post)?.md5?.let { md5 ->
    rememberAsyncImagePainter(cachedPostImageRequest(LocalPlatformContext.current, PostImage(route.id, md5, Variant.Preview, null)))
  }
  val failed = thumbnail?.state?.collectAsState()?.value is AsyncImagePainter.State.Error
  when {
    thumbnail != null && !failed -> Image(
      thumbnail,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = iconModifier.clip(RoundedCornerShape(4.dp)),
    )
    route is Route.Post -> Icon(image, contentDescription = "Post".takeUnless { decorative }, modifier = iconModifier)
    route is Route.Settings -> Icon(settings, contentDescription = SETTINGS_TITLE.takeUnless { decorative }, modifier = iconModifier)
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
private fun TabPreview(tab: TabState, imageLoader: ImageLoader = koinInject()) {
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
        Text(
          tab.title,
          color = if (tab.untitled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
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
