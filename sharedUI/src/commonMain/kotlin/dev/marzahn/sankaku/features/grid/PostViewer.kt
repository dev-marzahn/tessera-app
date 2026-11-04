package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.google.fonts.icons.chevron_left
import com.google.fonts.icons.chevron_right
import dev.marzahn.sankaku.api.dto.PostDto
import androidx.compose.runtime.CompositionLocalProvider
import dev.marzahn.sankaku.features.post.LocalVideoKeys
import dev.marzahn.sankaku.features.post.PlayerPreferences
import dev.marzahn.sankaku.features.post.rememberViewing
import dev.marzahn.sankaku.features.post.PostActions
import dev.marzahn.sankaku.features.post.PostDetails
import dev.marzahn.sankaku.features.post.PostImageView
import dev.marzahn.sankaku.features.post.PostTitle
import dev.marzahn.sankaku.features.post.RightPanelClose
import dev.marzahn.sankaku.features.post.RightPanelOpen
import dev.marzahn.sankaku.features.post.VideoKeys
import dev.marzahn.sankaku.features.post.postShortcuts
import dev.marzahn.sankaku.shell.BackOverride
import dev.marzahn.sankaku.ui.components.KbdBadge
import dev.marzahn.sankaku.ui.components.KeyFocusHolder
import dev.marzahn.sankaku.ui.components.PanelToggleButton
import dev.marzahn.sankaku.ui.components.Shortcut
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.components.entrance
import dev.marzahn.sankaku.ui.components.fastSpatialSpec
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.spatialBoundsTransform
import dev.marzahn.sankaku.ui.components.spatialSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** The viewer's info panel; narrower than the post tab's details. */
val INFO_PANEL_WIDTH: Dp = 300.dp

/** The info panel's shadow over a grid, one level above the image panel. */
private val INFO_PANEL_ELEVATION = 6.dp
private val PANEL_GAP = 10.dp

/** The corners where the two panels face each other: small, so they read as one group (as in M3 connected groups). */
private val INNER_CORNER = 8.dp

/**
 * Progress of the open transition at which the one panel has landed: it parts at the details seam and the controls
 * start to arrive. The progress spring overshoots 1 a little, so it does reach this.
 */
private const val SPLIT_AT = 0.97f
private const val CONTROLS_AT = SPLIT_AT

/** Below this split the card is still (or again) one panel: the info half isn't drawn and closing may shrink it. */
private const val MERGED = 0.05f

/** A post tab's card sits this far in from the tab's edges: the image gets the room, the card still reads as one. */
val TAB_INSET: Dp = 12.dp

/** The Details toggle (key badge and a 48 dp icon button, 8 dp in) at the top end, which the title row keeps clear. */
private val TOGGLE_CLEARANCE = 80.dp

/** Where the viewer's card is shown. The card is the same composable in each, so the hand-offs between them match. */
enum class ViewerMode {
  /** Over its grid, opened from a cell: the scrim, the arrows and the position. */
  Overlay,

  /**
   * A lone post (#181): the card fills the tab with a small inset; no scrim, arrows or position. Back goes to the
   * previous queue item.
   */
  Tab,
  ;

  /** The card's inset from the screen's edges. */
  val inset: PaddingValues get() = if (this == Overlay) PaddingValues(horizontal = 48.dp, vertical = 24.dp) else PaddingValues(TAB_INSET)

  /** Arrows, ← → and the position label: only over a grid opened from it. */
  val steps: Boolean get() = this == Overlay
}

/**
 * The post viewer over the grid, in the same window. Opening is a container transform: the cell's background grows
 * into one panel the size of both, with the image already in its final place, and lands. Then the panel parts where
 * the details are: the gap opens on a bouncy spring, the facing corners round, and the info half turns the info
 * panel's colour and lifts to its content's height. The controls arrive as it parts, staggered (arrows, then key
 * badges, then the toggle and the position), and the info panel's sections rise in one by one. Closing runs it
 * backwards: the controls leave, the halves merge into one panel, and the panel shrinks into the cell.
 *
 * ← and → slide the image to the neighbouring posts while the info panel stays and only resizes; F toggles favourite;
 * I collapses or expands the info panel (remembered for the session); Esc, the top bar's Back or a click on the
 * backdrop closes. The card has no Back of its own.
 *
 * [opening] counts the viewer's openings. A click on the grid while the viewer shrinks away reopens it in the same
 * composition, and the closing state of the previous opening must not carry over. [mode] says where the card is: a
 * lone post shows the same card ([ViewerMode.Tab]) with no grid behind it.
 *
 * [index] is the post's place in the grid's list; [positionOf] gives a list index's place in the order ← → step in
 * (the grid's reading order, #110), which the position label, the arrows and the slide's direction follow. [onStep]
 * asks the grid for the next or previous post in that order.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.PostViewer(
  index: Int,
  postAt: (Int) -> PostDto?,
  loadedCount: Int,
  hasMore: Boolean,
  sharedId: String?,
  visibilityScope: AnimatedVisibilityScope,
  onStep: (forward: Boolean) -> Unit,
  positionOf: (index: Int) -> Int = { it },
  onClose: () -> Unit,
  opening: Int = 0,
  mode: ViewerMode = ViewerMode.Overlay,
  searchTags: List<String> = emptyList(),
  actions: PostActions = koinInject(),
  preferences: ViewerPreferences = koinInject(),
  playerPreferences: PlayerPreferences = koinInject(),
) {
  val scope = rememberCoroutineScope()
  // T moves the keys to the info panel's tag list (see PostDetails); Esc there gives them back.
  val tagListFocus = remember { FocusRequester() }
  // A video post's keys: Space, M, and V to scrub, which takes A/D and Esc until it ends (see VideoKeys).
  val videoKeys = remember(playerPreferences) { VideoKeys(playerPreferences) }
  val transition = visibilityScope.transition
  val settled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
  // Set as soon as closing is asked for; the shared-element exit starts once the info panel has merged back. Per
  // opening: reopened while it shrinks away, the viewer must come back with its controls and keys.
  var leaving by remember(opening) { mutableStateOf(false) }
  val currentOpening by rememberUpdatedState(opening)
  val closing = leaving || transition.targetState != EnterExitState.Visible
  // The shared elements are attached only while opening or closing. Settled, stepping moves the hidden grid cell
  // to the current post (see GridScreen) without anything flying between the grid and the card. Closing, they attach
  // as soon as it's asked for, so they have bounds when the exit starts; attached with the exit, the card snaps.
  // A step during the opening's last stretch lets go of them first, so the first image never stays behind in the
  // transition overlay: the card lands in place (it's nearly there) and the image slides away as when settled.
  var stepped by remember(opening) { mutableStateOf(false) }
  val attachShared = (!settled && !stepped) || leaving
  fun step(forward: Boolean) {
    stepped = true
    onStep(forward)
  }
  // Seen (#174): stepping on, or closing, after the current post was fully shown.
  val viewing = rememberViewing(postAt(index))
  // While the card opens or closes, its image is drawn in the transition overlay, above the rest of the card. The
  // controls go into the overlay too, a level higher, or the image covered them until the springs settled.
  val aboveImage = Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)

  // Mirrors the shared bounds' spring, so the split and the controls can start partway through the opening.
  val openSpec = spatialSpec<Float>(slow = true)
  val closeSpec = spatialSpec<Float>()
  val progress by transition.animateFloat({ if (targetState == EnterExitState.Visible) openSpec else closeSpec }) {
    if (it == EnterExitState.Visible) 1f else 0f
  }
  val panelColor by transition.animateColor({ effectsSpec() }) {
    if (it == EnterExitState.Visible) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHighest
  }
  val openDp = spatialSpec<Dp>(slow = true)
  val closeDp = spatialSpec<Dp>()
  val corner by transition.animateDp({ if (targetState == EnterExitState.Visible) openDp else closeDp }) {
    if (it == EnterExitState.Visible) VIEWER_CORNER else CELL_CORNER
  }
  val bounds = spatialBoundsTransform(slow = !closing)
  val slide = spatialSpec<IntOffset>()

  // 0 = one panel the size of both, 1 = the info panel split off its right side. Shown already open (the grid behind
  // a post tab), the panel starts split, so closing merges it back as after an opening.
  val split = remember { Animatable(if (settled && !preferences.infoCollapsed) 1f else 0f) }
  // Parting bounces: the gap and the facing corners follow the overshoot (see cardLayout), the widths don't.
  val splitIn = fastSpatialSpec<Float>()
  // Merging back doesn't bounce: the expressive fast spring overshot past 0, so the image panel grew wider than the
  // viewer and the info panel vanished and flashed back while it settled (the jitter when collapsing the details).
  // Closing, the merge is only the prelude to the shrink, so it is quicker: as long as it lasts, the closing card
  // covers the grid and takes clicks meant for the next post.
  val splitOut = fastSpatialSpec<Float>().withoutBounce()
  val splitClose = fastSpatialSpec<Float>().withoutBounce(stiffnessScale = 4f)
  // Expanding mirrors collapsing, on the same quick spring: the toggle is already under the pointer.
  val splitExpand = splitOut
  val wantSplit = progress > SPLIT_AT && !preferences.infoCollapsed && !leaving
  // Whether the running split is the I toggle (settled, not leaving) rather than the opening or closing. Toggled, the
  // info panel only slides in or out sideways at its own height; opening and closing, it grows from and merges into
  // the one panel, so its height follows the split too.
  var toggling by remember(opening) { mutableStateOf(false) }
  LaunchedEffect(wantSplit) {
    toggling = settled && !leaving
    split.animateTo(
      if (wantSplit) 1f else 0f,
      when {
        leaving -> splitClose
        !wantSplit -> splitOut
        toggling -> splitExpand
        else -> splitIn
      },
    )
  }
  // The layout never follows the spring past its ends: past 1 the info panel would be wider than it is laid out.
  val splitAt = splitFraction(split.value)
  val controlsShown = progress > CONTROLS_AT && !leaving
  // The arrows work as soon as they show; waiting for the springs to settle left them dead for a moment.
  val canStep = controlsShown
  // A new transition per opening: reopened on another post while it shrank away, the image must not slide over from
  // the previous post as if stepping.
  // Keyed by list index, so a page that loads and slots in before the post (moving its position) slides nothing.
  val slider = key(opening) { updateTransition(index, label = "slide") }
  val position = positionOf(index)

  fun close() {
    // A lone post goes back at once, to the previous queue item (#181).
    if (mode == ViewerMode.Tab) return onClose()
    if (leaving) return
    leaving = true
    val closing = currentOpening
    // The controls leave, the info panel merges back and a running slide lands first; then the panel shrinks into
    // its cell. Without waiting for the slide, posts still sliding out would shrink along with the current one.
    scope.launch {
      // The shared elements attach with `leaving`; they need a laid-out frame before the exit, or the card snaps.
      repeat(2) { withFrameNanos {} }
      snapshotFlow { split.value < MERGED && !slider.isRunning && slider.currentState == slider.targetState }.first { it }
      // Reopened meanwhile (a click on the grid underneath): that opening isn't closing.
      if (currentOpening == closing) onClose()
    }
  }
  // Back is the top bar's: over a grid it closes the viewer, with the same motion as Esc. On a lone post it goes to
  // the previous queue item (#181).
  if (mode == ViewerMode.Overlay) BackOverride(enabled = !closing, onBack = ::close)

  Box(
    Modifier
      .fillMaxSize()
      // Closing, the viewer no longer handles keys: they belong to what is underneath again.
      .postShortcuts(post = { postAt(index) }, actions = actions, onBack = ::close, enabled = { !closing })
      .onKeyEvent { event ->
        if (closing || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        // Letter keys with Cmd or Ctrl are app shortcuts (Cmd-D, Ctrl-A), not the viewer's.
        if (event.isCommand) return@onKeyEvent false
        when {
          videoKeys.onKey(event.key) -> true
          Shortcuts.Previous.matches(event.key) -> (mode.steps && canStep && position > 0).also { if (it) step(false) }
          Shortcuts.Next.matches(event.key) -> (mode.steps && canStep && position < loadedCount - 1).also { if (it) step(true) }
          event.key == Shortcuts.Details.key -> true.also { preferences.infoCollapsed = !preferences.infoCollapsed }
          event.key == Shortcuts.TagList.key -> true.also {
            // A collapsed panel opens first; the tag list takes focus once it's composed.
            preferences.infoCollapsed = false
            scope.launch {
              repeat(2) { withFrameNanos {} }
              tagListFocus.requestFocus()
            }
          }
          else -> false
        }
      },
  ) {
    // Taken again on each opening: reopened from the grid while it shrank away, the viewer stays composed, and the
    // click on the grid cell moved focus there.
    key(opening) { KeyFocusHolder() }
    // While closing, neither the scrim nor the panels catch clicks, so the grid underneath works at once. A post tab
    // has no scrim: the window's surface shows around the card. The scrim dims only the grid, not the gap between it
    // and the top bar, where it read as cut off by the search bar.
    if (mode != ViewerMode.Tab) {
      Box(
        Modifier
          .fillMaxSize()
          .padding(top = GRID_TOP_GAP)
          .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = OVERLAY_SCRIM_ALPHA))
          // Decorative: as an accessibility element spanning the viewer it came first in hit-testing and took every
          // press meant for the panels. Esc and Back close.
          .semantics { hideFromAccessibility() }
          .then(if (closing) Modifier else Modifier.swallowClicks(onClick = ::close)),
      )
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(mode.inset)) {
      val region = IntSize(constraints.maxWidth, constraints.maxHeight)
      val layout = cardLayout(maxWidth, split.value, parting = !toggling, collapsed = preferences.infoCollapsed, corner = corner)
      val shape = PanelShape(start = corner, end = layout.panelEndCorner, endInset = layout.panelEndInset)
      val post = postAt(index)

      if (post != null && layout.infoShown) {
        InfoPanel(
          post = post,
          split = splitAt,
          // Parting, the info half starts as part of the one panel, in its colour; slid in with I, it is white
          // throughout and fades in while it's narrower than its corners.
          color = if (toggling) MaterialTheme.colorScheme.surfaceContainerLowest else lerp(panelColor, MaterialTheme.colorScheme.surfaceContainerLowest, splitAt),
          alpha = if (toggling) infoPanelAlpha(splitAt) else 1f,
          innerCorner = layout.infoInnerCorner,
          regionHeight = region.height,
          heightFollowsSplit = !toggling,
          controlsShown = controlsShown,
          tagListFocus = tagListFocus,
          // Over a grid the info panel is one level above the image panel; in a post tab both lie flat.
          elevation = if (mode == ViewerMode.Overlay) INFO_PANEL_ELEVATION else 0.dp,
          searchTags = searchTags,
          modifier = Modifier.offset(x = layout.infoX).width(layout.infoWidth),
        )
      }

      // The image panel: the cell's background grown into place, the one shared element of the card. Parting, it stays
      // laid out the size of both and its shape ends short of the info half, so its shared bounds never move while the
      // opening still runs; only its content keeps to the image panel's width. It takes no clicks itself: what it
      // covers of the info half stays clickable.
      Box(
        Modifier
          .width(layout.panelWidth)
          .fillMaxHeight()
          .then(
            if (sharedId == null || !attachShared) {
              Modifier
            } else {
              Modifier.sharedBounds(
                rememberSharedContentState(cardKey(sharedId)),
                visibilityScope,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                boundsTransform = bounds,
                resizeMode = RemeasureToBounds,
                // The overlay clip carries the animated corners, so they round while the panel grows.
                clipInOverlayDuringTransition = OverlayClip(shape),
              )
            },
          )
          .clip(shape)
          .background(panelColor),
      ) {
        Box(
          Modifier
            .width(layout.contentWidth)
            .fillMaxHeight()
            .then(if (closing) Modifier else Modifier.swallowClicks {})
            // Accessibility hit-testing takes the first child in traversal order that contains the point, so the
            // image, which spans the panel, goes after the controls drawn on top of it.
            .semantics { isTraversalGroup = true },
        ) {
          slider.AnimatedContent(
            modifier = Modifier.semantics { traversalIndex = 1f },
            transitionSpec = {
              val forward = positionOf(targetState) > positionOf(initialState)
              slideInHorizontally(slide) { if (forward) it else -it } togetherWith
                slideOutHorizontally(slide) { if (forward) -it else it }
            },
          ) { shown ->
            val shownPost = postAt(shown) ?: return@AnimatedContent
            CompositionLocalProvider(LocalVideoKeys provides videoKeys) {
              PostImageView(
                shownPost,
                modifier = Modifier.fillMaxSize(),
                imageBox = if (shownPost.id == sharedId && attachShared) {
                  Modifier.sharedElement(rememberSharedContentState(imageKey(shownPost.id)), visibilityScope, bounds)
                } else {
                  Modifier
                },
                showSample = settled,
                onShown = { viewing.loaded(shownPost.id) },
              )
            }
          }
          if (mode.steps) {
            Surface(
              shape = CircleShape,
              color = overImage(),
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
              // Top left, so a video's controls have the whole bottom edge.
              modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .then(aboveImage)
                .entrance(controlsShown, delayMillis = 140, offsetY = (-16).dp),
            ) {
              Text(
                positionLabel(position, loadedCount, hasMore),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }
          if (mode.steps && position > 0) {
            StepButton(chevron_left, "Previous post", Shortcuts.Previous, Alignment.CenterStart, controlsShown, towards = -1, aboveImage) {
              if (canStep) step(false)
            }
          }
          if (mode.steps && position < loadedCount - 1) {
            StepButton(chevron_right, "Next post", Shortcuts.Next, Alignment.CenterEnd, controlsShown, towards = 1, aboveImage) {
              if (canStep) step(true)
            }
          }
        }
      }

      // One toggle for both states, drawn over the card at its top end: over the info panel's title row when that is
      // open, over the image panel when it's collapsed. It never moves, so the pointer that collapsed the panel is
      // still on it to expand it again, and the panel slides in and out underneath.
      DetailsToggle(
        collapsed = preferences.infoCollapsed,
        shown = controlsShown,
        modifier = Modifier.align(Alignment.TopEnd).then(aboveImage),
        onClick = { preferences.infoCollapsed = !preferences.infoCollapsed },
      )
    }
  }
}

/**
 * The info panel, one level above the image panel. It's laid out at its full width and clipped, so the split reveals
 * it instead of squeezing it. Opening and closing ([heightFollowsSplit]), its height goes from the region's (still
 * part of the one panel) to its content's; collapsed or expanded with I, it keeps its height and only slides.
 * Slid in with I, it fades in over the first part of the split ([alpha]): at a few pixels wide it was a full-height
 * light line beside the image panel's rounded corners, which flashed at the start and end of every expand and collapse.
 *
 * Its title row stays put while the details scroll under it; the card draws the Details toggle over its end.
 */
@Composable
private fun InfoPanel(
  post: PostDto,
  split: Float,
  color: Color,
  alpha: Float,
  innerCorner: Dp,
  regionHeight: Int,
  heightFollowsSplit: Boolean,
  controlsShown: Boolean,
  tagListFocus: FocusRequester,
  elevation: Dp,
  searchTags: List<String>,
  modifier: Modifier,
) {
  Surface(
    shape = RoundedCornerShape(topStart = innerCorner, topEnd = VIEWER_CORNER, bottomEnd = VIEWER_CORNER, bottomStart = innerCorner),
    color = color,
    shadowElevation = elevation * split,
    modifier = modifier.graphicsLayer { this.alpha = alpha }.layout { measurable, constraints ->
      val width = constraints.maxWidth
      val content = measurable.measure(Constraints(maxWidth = width, maxHeight = regionHeight))
      val height = infoPanelHeight(regionHeight, content.height, split, heightFollowsSplit)
      layout(width, height) { content.place(0, 0) }
    },
  ) {
    Box(Modifier.layout { measurable, constraints ->
      // Always measured at the full panel width, whatever width the split has reached.
      val full = INFO_PANEL_WIDTH.roundToPx()
      val placeable = measurable.measure(constraints.copy(minWidth = full, maxWidth = full))
      layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }) {
      Column {
        val scroll = rememberScrollState()
        PostTitle(
          post,
          endInset = TOGGLE_CLEARANCE,
          scrolled = scroll.value > 0,
          modifier = Modifier.entrance(controlsShown, delayMillis = 60L, offsetY = 14.dp),
        )
        PostDetails(
          post,
          scroll = scroll,
          searchTags = searchTags,
          tagListFocus = tagListFocus,
          modifier = Modifier.weight(1f, fill = false).animateContentSize(spatialSpec()),
          // The sections rise in one by one as the controls arrive.
          section = { i -> Modifier.entrance(controlsShown, delayMillis = 90L + i * 30L, offsetY = 14.dp) },
        )
      }
    }
  }
}

/**
 * The info panel's height in px: from the region's (merged into the one panel, [split] 0) to its content's (split
 * off, 1) while opening or closing; its content's throughout an I toggle, which only slides it sideways.
 */
internal fun infoPanelHeight(regionHeight: Int, contentHeight: Int, split: Float, followsSplit: Boolean): Int =
  if (followsSplit) (regionHeight + (contentHeight - regionHeight) * split).roundToInt() else contentHeight

/**
 * Where the card's panels are, in a region [width] wide. [panelWidth] is the image panel's layout width and
 * [panelEndInset] how far short of its end its shape stops; [contentWidth] is what its image and controls get.
 */
internal data class CardLayout(
  val panelWidth: Dp,
  val panelEndInset: Dp,
  val panelEndCorner: Dp,
  val contentWidth: Dp,
  val infoShown: Boolean,
  val infoX: Dp,
  val infoWidth: Dp,
  val infoInnerCorner: Dp,
)

/**
 * The card at [split], which may overshoot either end.
 *
 * [parting] (opening and closing): the image panel is laid out the size of both and its shape ends short of the info
 * half. Below [MERGED] it is the one panel. Then it parts at the details seam: the gap and the facing corners follow
 * the spring, overshoot included, while the info half's end stays at the card's end. The image keeps to the image
 * panel's final width throughout, so it lands in place while the card is still one panel and never moves as it parts.
 *
 * Otherwise (the I toggle) the info panel slides in or out sideways and the image panel and its image resize.
 */
internal fun cardLayout(width: Dp, split: Float, parting: Boolean, collapsed: Boolean, corner: Dp): CardLayout {
  val beside = INFO_PANEL_WIDTH + PANEL_GAP
  if (!parting) {
    val at = splitFraction(split)
    val image = lerp(width, width - beside, at)
    val infoX = image + PANEL_GAP * at
    return CardLayout(image, 0.dp, lerp(corner, INNER_CORNER, at), image, at > 0.001f, infoX, width - infoX, INNER_CORNER)
  }
  val content = if (collapsed) width else width - beside
  if (split < MERGED) return CardLayout(width, 0.dp, corner, content, false, width, 0.dp, 0.dp)
  val gap = PANEL_GAP * split
  val infoX = width - INFO_PANEL_WIDTH - PANEL_GAP / 2 + gap / 2
  val seam = INNER_CORNER * split
  return CardLayout(width, width - infoX + gap, seam, content, true, infoX, width - infoX, seam)
}

/** A rounded rectangle that ends [endInset] short of its bounds' end: the image panel while the info half parts. */
private class PanelShape(private val start: Dp, private val end: Dp, private val endInset: Dp) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val width = (size.width - with(density) { endInset.toPx() }).coerceAtLeast(0f)
    return RoundedCornerShape(topStart = start, topEnd = end, bottomEnd = end, bottomStart = start)
      .createOutline(Size(width, size.height), layoutDirection, density)
  }
}

/** The split as the layout uses it: a spring overshooting either end holds at that end. */
internal fun splitFraction(split: Float): Float = split.coerceIn(0f, 1f)

/** The same spring (stiffer by [stiffnessScale]), critically damped: it never passes its target. */
internal fun <T> FiniteAnimationSpec<T>.withoutBounce(stiffnessScale: Float = 1f): FiniteAnimationSpec<T> =
  if (this is SpringSpec) spring(Spring.DampingRatioNoBouncy, stiffness * stiffnessScale, visibilityThreshold) else this

/** The info panel's opacity at [split]: it fades in while it's narrower than its rounded corners. */
internal fun infoPanelAlpha(split: Float): Float = (split / INFO_FADE_SPLIT).coerceIn(0f, 1f)

/** About 30 dp of the info panel's 300. */
private const val INFO_FADE_SPLIT = 0.1f

/**
 * The backdrop of a control over the image: the info panel's white, a little see-through. The image fills the image
 * panel edge to edge (the panel's corners round it), and the controls (the arrows, the position, a collapsed panel's
 * Details toggle) lie over it.
 */
@Composable
private fun overImage(): Color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f)

/** The position in the result list. The keyset API has no total, so it counts what's loaded; "+" means more pages. */
internal fun positionLabel(index: Int, loadedCount: Int, hasMore: Boolean): String =
  "${index + 1} of $loadedCount${if (hasMore) "+" else ""}"

/** [positionLabel] read aloud: "Post 3 of 29 or more". */
internal fun positionDescription(index: Int, loadedCount: Int, hasMore: Boolean): String =
  "Post ${index + 1} of $loadedCount${if (hasMore) " or more" else ""}"

/**
 * Hides or shows the info panel: its key, then an expressive toggle whose glyph says which. It's always white: a
 * rounded square on the panel's title row when on (details shown), a circle over the image when off, so it never
 * shows the panel's text through it while the panel slides under it, and morphs as it does. The image doesn't keep
 * clear of it. The 48 dp button sits 8 dp in from the top, level with the title row.
 */
@Composable
private fun DetailsToggle(collapsed: Boolean, shown: Boolean, modifier: Modifier, onClick: () -> Unit) {
  Row(
    modifier.padding(top = 8.dp, end = 8.dp).entrance(shown, delayMillis = 110, offsetY = (-24).dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    KbdBadge(Shortcuts.Details.label)
    PanelToggleButton(
      checked = !collapsed,
      onCheckedChange = { onClick() },
      checkedContainer = MaterialTheme.colorScheme.surfaceContainerLowest,
      uncheckedContainer = MaterialTheme.colorScheme.surfaceContainerLowest,
      // A click would take focus from the viewer's key holder, and with it the viewer's keys (Esc, ← →, F, I).
      modifier = Modifier.focusProperties { canFocus = false },
    ) {
      Icon(
        if (collapsed) RightPanelOpen else RightPanelClose,
        contentDescription = "${if (collapsed) "Show" else "Hide"} details (${Shortcuts.Details.label})",
      )
    }
  }
}

/** The arrows lead the controls' entrance, pushing in from their side; their key badges follow. */
@Composable
private fun BoxScope.StepButton(
  icon: ImageVector,
  label: String,
  shortcut: Shortcut,
  alignment: Alignment,
  shown: Boolean,
  towards: Int,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Column(
    Modifier.align(alignment).padding(12.dp).then(modifier),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    IconButton(
      onClick = onClick,
      colors = IconButtonDefaults.iconButtonColors(containerColor = overImage()),
      modifier = Modifier.entrance(shown, offsetX = (28 * towards).dp, fromScale = 0.85f),
    ) { Icon(icon, contentDescription = label) }
    KbdBadge(shortcut.label, Modifier.entrance(shown, delayMillis = 70, offsetY = 12.dp, fromScale = 0.8f, fast = true))
  }
}

/**
 * Takes taps without a ripple: the scrim (which closes) and the panels (so a tap on them doesn't close). Plain
 * pointer handling, not `clickable`, so these surfaces add no accessibility node: as clickable nodes they came first
 * in hit-testing and took presses meant for the buttons on top of them. Esc and Back close for keyboard and
 * screen-reader users.
 */
private fun Modifier.swallowClicks(onClick: () -> Unit): Modifier = composed {
  // The tap loop starts once; the handler may change with every recomposition.
  val handler by rememberUpdatedState(onClick)
  pointerInput(Unit) { detectTapGestures { handler() } }
}
