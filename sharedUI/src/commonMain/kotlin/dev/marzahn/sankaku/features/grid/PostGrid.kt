package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.shell.RetryWhenBack
import dev.marzahn.sankaku.shell.sharedMosaicThumb
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionDefaults
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import dev.marzahn.sankaku.gridScrollbarRoom
import dev.marzahn.sankaku.platformCellGap
import dev.marzahn.sankaku.platformGridEdge
import dev.marzahn.sankaku.platformMinCellWidth
import dev.marzahn.sankaku.features.post.decodedInMemory
import dev.marzahn.sankaku.features.post.PostVideoBadge
import dev.marzahn.sankaku.features.post.cellDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.marzahn.sankaku.tabs.LocalQueueActions
import org.koin.compose.koinInject
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.PostOverride
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.data.merge
import androidx.compose.runtime.mutableIntStateOf
import dev.marzahn.sankaku.images.ImageFailure
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.failure
import dev.marzahn.sankaku.images.postImageRequest
import dev.marzahn.sankaku.ui.components.ImageFailed
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.ui.components.DoneWith
import dev.marzahn.sankaku.ui.components.ErrorState
import dev.marzahn.sankaku.ui.components.FavouriteBurst
import dev.marzahn.sankaku.ui.components.TipAnchor
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import dev.marzahn.sankaku.features.post.PostActions
import dev.marzahn.sankaku.features.post.Rejection
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.AlreadyInTab
import dev.marzahn.sankaku.ui.components.OpenInTabFilter
import dev.marzahn.sankaku.ui.components.seenImage
import dev.marzahn.sankaku.ui.components.spatialBoundsTransform
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.components.spatialSpec
import dev.marzahn.sankaku.ui.dragToScroll
import dev.marzahn.sankaku.ui.openOnClick
import dev.marzahn.sankaku.ui.reflowWhenSettled

/** Between the top bar and the grid, fixed while the grid scrolls. */
val GRID_TOP_GAP: Dp = 8.dp

/** How dark the scrim is over the grid under the viewer, starting below [GRID_TOP_GAP]. */
const val OVERLAY_SCRIM_ALPHA = 0.6f

val CELL_CORNER: Dp = 8.dp

/**
 * A cell's long-press (touch) or middle-click (desktop), as TalkBack and screen readers name it: "Keep" on a phone,
 * which has no tabs (#249), "Open in background tab" on desktop.
 */
internal fun openInBackgroundLabel(touch: Boolean = platformIsTouch): String = if (touch) "Keep" else "Open in background tab"

/** The keyboard cursor's cell: rounder, and ringed [CURSOR_GAP] outside its edge. */
private val CURSOR_CORNER: Dp = 16.dp
private val CURSOR_GAP: Dp = 3.dp
private val CURSOR_RING: Dp = 3.dp

/**
 * How far the cursor's ring reaches outside its cell. This much of [GRID_TOP_GAP] is the grid's top content padding,
 * so a top-row ring isn't clipped; cells are still cut at the gap's usual line as they scroll ([clipLine]), so the gap
 * under the search bar looks the same at rest and while scrolling. The cursor keeps the ring clear of the bottom edge
 * too (see `fullyShows`).
 */
private val CURSOR_REACH: Dp = CURSOR_GAP + CURSOR_RING

/**
 * Where the gap's usual line (the end of the top content padding, item offset 0) is in the coordinates of [index]'s
 * cell: positive once the cell scrolls under it. Read while drawing, so it follows the scroll.
 */
private fun LazyStaggeredGridState.clipLine(index: Int): Float =
  -(layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.y ?: 0).toFloat()

/** Draws [block] cut off above [top] only: the ring around a cell reaches past its other edges. */
private inline fun DrawScope.clipAbove(top: Float, block: DrawScope.() -> Unit) {
  val room = CURSOR_REACH.toPx() * 2
  clipRect(left = -room, top = top, right = size.width + room, bottom = size.height + room, block = block)
}

val VIEWER_CORNER: Dp = 28.dp
val CELL_SHAPE = RoundedCornerShape(CELL_CORNER)

/**
 * Masonry columns sized from each preview's aspect ratio. Hovering a cell shows its favourite button
 * ([onToggleFavourite], which returns whether it toggled); on touch TalkBack's Favourite action toggles it too, with a
 * heart that pops and fades ([FavouriteBurst]) when it did. A tap opens at once: there's no double-tap (#324). With
 * [dimUnfavourited] (a favourites grid) a post unfavourited since loading stays in place, dimmed, with Undo (see
 * [CellOverlay]). Video posts have a [PostVideoBadge] at the top left.
 * Posts in [openInTabs] are nearly grey with an "In tab" chip, which says "Already tab N" while [alreadyInTab] shows
 * it for the post (or "Kept again"), posts [isSeen] reports are drawn at about 85% with an eye-slash badge and "Seen" in
 * their description (#182; [isSeen] reads snapshot state, so a cell follows it), and the keyboard [cursor]'s cell is rounder with a ring around it. The cell of [hiddenId] is hidden while
 * the viewer shows that post: its background and its image are the two shared elements the viewer grows from. The
 * cell of [gestureTipId] carries the one-time gestures tip ([Tip.GridGestures]), shown while [gestureTipShown]. With an
 * [endOfSource], the footer of a grid that reached the end of its results offers "Done with …?" ([DoneWith]).
 *
 * Only the cell of [sharedId] (the post the viewer grows from or shrinks into) takes part in a shared transition.
 * Every cell keeps its shared elements attached, so the one that becomes [sharedId] has bounds at once, but the others
 * are switched off: a post reopened while the previous one still shrinks into its cell would otherwise leave that
 * cell in the transition overlay, drawn over the new viewer, until the new opening ends.
 *
 * Under the queue page (#322), the cells of the md5s in [mosaicThumbs] (the grid's mosaic, in order) are each one
 * element with its thumbnail on the grid's row while there's an [underPage], the content's visibility under the page as
 * it opens or closes: each moves between its thumbnail and its cell.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.PostGrid(
  items: LazyPagingItems<PostDto>,
  overrides: Map<String, PostOverride>,
  state: LazyStaggeredGridState,
  hiddenId: String?,
  sharedId: String?,
  viewerClosing: Boolean,
  cursor: Int?,
  onOpen: (index: Int) -> Unit,
  onOpenInNewTab: (PostDto) -> Unit,
  onSignIn: () -> Unit,
  onToggleFavourite: (post: PostDto, viaDoubleTap: Boolean) -> Boolean,
  modifier: Modifier = Modifier,
  dimUnfavourited: Boolean = false,
  openInTabs: Set<String> = emptySet(),
  alreadyInTab: AlreadyInTab? = null,
  isSeen: (PostDto) -> Boolean = { false },
  gestureTipId: String? = null,
  gestureTipShown: Boolean = false,
  endOfSource: EndOfSource? = null,
  mosaicThumbs: List<String> = emptyList(),
  underPage: AnimatedVisibilityScope? = null,
) {
  val context = LocalPlatformContext.current
  val haptics = LocalHapticFeedback.current
  val imageLoader = koinInject<ImageLoader>()
  val tips = koinInject<Tips>()
  val queueActions = LocalQueueActions.current
  // Touch: the cell's own toggles (TalkBack's action, `viaDoubleTap`, the name from when a double-tap made them) that
  // failed and rolled back, by post, until their cell has answered. Only those: a toggle from the viewer's ♡ is
  // answered there. Only cells on screen: one scrolled away when its failure came shows
  // it on its badge, and doesn't pop the heart later when it scrolls back.
  val actions = koinInject<PostActions>()
  val rejected = remember { mutableStateMapOf<String, Int>() }
  val composedCells = remember { mutableSetOf<String>() }
  if (platformIsTouch) {
    LaunchedEffect(actions) {
      actions.rejections.collect {
        if (it is Rejection.Favourite && it.viaDoubleTap && it.postId in composedCells) {
          rejected[it.postId] = (rejected[it.postId] ?: 0) + 1
        }
      }
    }
  }
  // The incoming element's transform drives a shared transition; a cell is incoming when the viewer closes.
  val bounds = spatialBoundsTransform(slow = !viewerClosing)
  val cornerSpec = spatialSpec<Dp>(slow = !viewerClosing)
  val cellFade = effectsSpec<Float>()
  LazyVerticalStaggeredGrid(
    columns = StaggeredGridCells.Adaptive(platformMinCellWidth),
    state = state,
    // Most of the gap under the top bar is outside the scrolling content: the grid is clipped below it, so cells never
    // scroll up against the search bar. The rest is the cursor ring's room.
    contentPadding = PaddingValues(
      start = platformGridEdge,
      top = CURSOR_REACH,
      end = platformGridEdge + gridScrollbarRoom,
      bottom = platformGridEdge,
    ),
    verticalItemSpacing = platformCellGap,
    horizontalArrangement = Arrangement.spacedBy(platformCellGap),
    // While the window is resized the grid keeps its columns, scaled, and reflows once the resize pauses.
    modifier = modifier.reflowWhenSettled().padding(top = GRID_TOP_GAP - CURSOR_REACH).dragToScroll(state),
  ) {
    items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
      val loaded = items[index] ?: return@items
      val post = loaded.merge(overrides[loaded.id])
      if (post.redirectToSignup) {
        RestrictedCell(onSignIn)
        return@items
      }
      val hover = remember { MutableInteractionSource() }
      val hovered by hover.collectIsHoveredAsState()
      val atCursor = index == cursor
      val openInTab = post.id in openInTabs
      val alreadyTab = alreadyInTab?.of(post.id)
      val seen = isSeen(post)
      // The keyboard cursor: a ring just outside the cell, in the gap between cells (whose surface colour is the ring's
      // gap), and rounder corners. Drawn above the neighbours, which would cover the ring.
      val ring = MaterialTheme.colorScheme.primary
      val cellCorner by animateDpAsState(if (atCursor) CURSOR_CORNER else CELL_CORNER, spatialSpec())
      // The cell's toggle's heart, for the state it made; none signed out, where the toggle opens sign-in. A rollback
      // after a failure (`.scratch/android-feedback/`, C, row 4) pops the heart of the state it went back to, with the
      // "reject" haptic, and shows on the badge; the reason is on the top line.
      var burst by remember { mutableStateOf<Burst?>(null) }
      DisposableEffect(post.id) {
        composedCells += post.id
        onDispose {
          composedCells -= post.id
          rejected.remove(post.id)
        }
      }
      val cellRejected = rejected[post.id]
      LaunchedEffect(cellRejected) {
        if (cellRejected == null) return@LaunchedEffect
        // Answered once: a cell scrolled back in later doesn't replay it.
        rejected.remove(post.id)
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        burst = Burst(post.isFavorited, (burst?.id ?: 0) + 1)
      }
      // TalkBack's Favourite and Unfavourite on the cell: what a double-tap did before #324, heart and haptic included.
      val favouriteFromCell = {
        if (onToggleFavourite(post, true)) {
          val favourited = !post.isFavorited
          haptics.performHapticFeedback(if (favourited) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
          // A new id each time, so a second toggle restarts the heart even for the same state.
          burst = Burst(favourited, (burst?.id ?: 0) + 1)
        }
      }
      val unfavourited = dimUnfavourited && overrides[post.id]?.isFavourited == false
      Box(
        Modifier
          .fillMaxWidth()
          .aspectRatio(cellRatio(post))
          // The whole cell, so it's never left empty in its place while the thumbnail flies to it (#322).
          .then(
            when (val at = mosaicThumbs.indexOf(post.md5)) {
              -1 -> Modifier
              else -> Modifier.sharedMosaicThumb(post.md5, underPage, enabled = true, clip = CELL_SHAPE, cell = true).testTag(mosaicCellTag(at))
            },
          )
          .hoverable(hover)
          .zIndex(if (atCursor) 1f else 0f)
          .drawBehind {
            if (!atCursor) return@drawBehind
            val out = (CURSOR_GAP + CURSOR_RING / 2).toPx()
            // Whole below the line, the ring may use the room above it; scrolling under, it's cut with the cell.
            val line = state.clipLine(index)
            clipAbove(if (line > 0f) line else -out * 2) {
              drawRoundRect(
                ring,
                topLeft = Offset(-out, -out),
                size = Size(size.width + 2 * out, size.height + 2 * out),
                cornerRadius = CornerRadius(cellCorner.toPx() + out),
                style = Stroke(CURSOR_RING.toPx()),
              )
            }
          }
          // Cells scroll away at the gap's usual line, not into the ring's room above it.
          .drawWithContent { clipAbove(state.clipLine(index)) { this@drawWithContent.drawContent() } },
      ) {
        // No enter or exit effects: the cell only moves, through its shared elements.
        AnimatedVisibility(visible = post.id != hiddenId, enter = EnterTransition.None, exit = ExitTransition.None) {
          // Shrinking back from the viewer, the corners go from the card's radius to the cell's.
          val corner by transition.animateDp({ cornerSpec }) { if (it == EnterExitState.Visible) CELL_CORNER else VIEWER_CORNER }
          val sharing = sharingConfig(post.id == sharedId)
          Surface(
            shape = RoundedCornerShape(cellCorner),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
              .fillMaxSize()
              .sharedBounds(
                rememberSharedContentState(cardKey(post.id), sharing),
                animatedVisibilityScope = this,
                enter = EnterTransition.None,
                // Opening, the cell's background is drawn under the card's at the same bounds, and showed where the
                // card parts for the info panel. It fades out while the card still covers it.
                exit = fadeOut(cellFade),
                boundsTransform = bounds,
                resizeMode = RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner)),
              )
              // No double-tap: with one, every tap waited out the system's double-tap timeout (about 300 ms) before
              // opening (#324). A tap opens at once.
              .openOnClick(
                onOpen = { onOpen(index) },
                onOpenInNewTab = { onOpenInNewTab(post) },
                inTab = openInTab,
                longPressLabel = openInBackgroundLabel(),
              )
              // One node for TalkBack: its title and state, the overlay's marks being silent below (#143). The
              // long-press (middle-click) and favouriting are custom actions too: TalkBack's Actions list leaves a
              // long-press out, naming it only in the hint ("double-tap and hold to …").
              .semantics {
                contentDescription = cellDescription(
                  post,
                  openInTab,
                  unfavourited,
                  alreadyTab?.position,
                  seen,
                  keptAgain = alreadyTab?.again == true,
                )
                // The chip's own live region is silent below, so the cell says "Already in tab N" (#130).
                if (alreadyTab != null) liveRegion = LiveRegionMode.Polite
                customActions = listOf(
                  CustomAccessibilityAction(openInBackgroundLabel()) {
                    onOpenInNewTab(post)
                    true
                  },
                  CustomAccessibilityAction(if (post.isFavorited) "Unfavourite" else "Favourite") {
                    favouriteFromCell()
                    true
                  },
                  // The grid's sideways swipe between queue items (#181).
                ) + queueActions
              },
          ) {
            // The cell the viewer shrinks into draws the viewer's sample if it's decoded: a shared element shows the
            // incoming side, so with the preview the closing card's image went soft at once, like a reload.
            val sample = remember(post.md5, post.id == sharedId) {
              if (post.id == sharedId) decodedInMemory(imageLoader, post.md5, previewToo = false) else null
            }
            // The shared element sits on a box around the swap. On the image itself, a cell that became the shared
            // one as the viewer opened swapped to the sample and so got a new element with no bounds yet: the image
            // flew in far too big over the card and the arrows waited until it had shrunk into place.
            Box(Modifier.fillMaxSize().sharedElement(rememberSharedContentState(imageKey(post.id), sharing), this@AnimatedVisibility, bounds)) {
              // A post open in a tab is nearly grey: the strongest mark at a glance while scrolling, without the
              // black wash that read as "disabled".
              val filter = if (openInTab) OpenInTabFilter else null
              if (sample != null) {
                Image(
                  sample.asPainter(context),
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  colorFilter = filter,
                  modifier = Modifier.fillMaxSize().seenImage(seen),
                )
              } else {
                // A failed load shows itself with a retry, instead of the cell's bare colour, which reads as loading.
                // A retry is a new composition, so a new request.
                var attempt by remember(post.id) { mutableIntStateOf(0) }
                var failure by remember(post.id) { mutableStateOf<ImageFailure?>(null) }
                RetryWhenBack {
                  if (failure == ImageFailure.Unreachable) {
                    failure = null
                    attempt++
                  }
                }
                key(attempt) {
                  AsyncImage(
                    model = gridCellRequest(context, post),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = filter,
                    onState = { failure = it.failure() },
                    modifier = Modifier.fillMaxSize().seenImage(seen),
                  )
                }
                failure?.let {
                  ImageFailed(
                    noImage = it == ImageFailure.NoImage,
                    onRetry = {
                      failure = null
                      attempt++
                    },
                    compact = true,
                    modifier = Modifier.fillMaxSize(),
                  )
                }
              }
            }
            // Where a closing viewer's GIF or video frame lands: it has nothing of its own to draw, and the frame goes
            // once the card has shrunk into the cell (#283).
            if (post.id == sharedId) {
              Box(
                Modifier.fillMaxSize().sharedBounds(
                  rememberSharedContentState(sampleKey(post.id), sharing),
                  this@AnimatedVisibility,
                  enter = EnterTransition.None,
                  exit = ExitTransition.None,
                  boundsTransform = bounds,
                ),
              )
            }
            // The cell the viewer closes into draws its picture (and a GIF's or video's frame) in the transition's
            // overlay, above the rest of the cell, until the transition ends. Its marks go there too, above them, so
            // they ride on the shrinking card and are on the cell from the frame it lands (#324).
            val marksOnTop = Modifier.renderInSharedTransitionScopeOverlay(MARKS_Z) {
              post.id == sharedId && viewerClosing && isTransitionActive
            }
            // Silent to TalkBack: the cell's description says what they show, and its actions do what they do.
            CellOverlay(
              favourited = post.isFavorited,
              unfavourited = unfavourited,
              hovered = hovered,
              onToggleFavourite = { onToggleFavourite(post, false) },
              openInTab = openInTab,
              alreadyTab = alreadyTab,
              seen = seen,
              keyHints = atCursor && !openInTab,
              modifier = marksOnTop.clearAndSetSemantics {},
            )
            // The top-left corner, the one no other mark uses; the burst's heart below draws over it. In a box of
            // its own: the Surface's passes the cell's size down as a minimum, which stretched the badge over the cell.
            Box(Modifier.fillMaxSize().then(marksOnTop).clearAndSetSemantics {}) {
              PostVideoBadge(post, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            burst?.let {
              key(it.id) { FavouriteBurst(it.favourited, onFinished = { burst = null }, Modifier.align(Alignment.Center)) }
            }
          }
        }
        // Last, over the whole cell, so the cell under it is the same with or without the tip.
        if (post.id == gestureTipId) {
          TipAnchor(Tip.GridGestures, tips, gestureTipShown, Modifier.matchParentSize())
        }
      }
    }
    item(span = StaggeredGridItemSpan.FullLine) { Footer(items, endOfSource) }
  }
}

/** The cell's toggle's heart: the state it made, and [id], new for each toggle. */
private data class Burst(val favourited: Boolean, val id: Int)

/**
 * The closing cell's marks in the transition's overlay: over its picture (0) and a closing GIF's or video's frame
 * (`SAMPLE_Z`, 0.5), under the viewer's chrome (1), which fades as the card shrinks.
 */
private const val MARKS_Z = 0.75f

/** A post the API hides from signed-out users: it has no image URLs, so it never makes a request. */
@Composable
private fun RestrictedCell(onSignIn: () -> Unit) {
  Surface(
    onClick = onSignIn,
    shape = CELL_SHAPE,
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text("Sign in to view", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/**
 * A source's name, and what closing it does, for the "Done with …?" row at the end of its grid (#184): [keptStay] kept
 * posts stay as a block, and [onClose] is what ✕ does.
 */
class EndOfSource(val name: String, val keptStay: Int, val onClose: () -> Unit)

@Composable
private fun Footer(items: LazyPagingItems<PostDto>, endOfSource: EndOfSource?) {
  Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
    when (val append = items.loadState.append) {
      is LoadState.Loading -> AppLoadingIndicator(size = 64.dp)
      is LoadState.Error -> ErrorState(append.error.message ?: "Couldn't load more", onRetry = items::retry)
      // The end of a finite grid offers to close its source; never by itself.
      is LoadState.NotLoading ->
        if (endOfSource != null && append.endOfPaginationReached && items.itemCount > 0) {
          DoneWith(endOfSource.name, endOfSource.keptStay, endOfSource.onClose)
        }
    }
  }
}

/**
 * A cell's shared elements: on for the viewer's post. Off, they stop at once, even mid-animation (the default keeps an
 * ongoing animation running to its end, which is what left the previous cell over the reopened viewer).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun sharingConfig(enabled: Boolean): SharedTransitionScope.SharedContentConfig =
  if (enabled) SharingOn else SharingOff

@OptIn(ExperimentalSharedTransitionApi::class)
private val SharingOn = SharedTransitionDefaults.SharedContentConfig

@OptIn(ExperimentalSharedTransitionApi::class)
private val SharingOff = object : SharedTransitionScope.SharedContentConfig {
  override val SharedTransitionScope.SharedContentState.isEnabled: Boolean get() = false
  override val shouldKeepEnabledForOngoingAnimation: Boolean get() = false
}

/** For tests: the cell of the [index]th thumbnail of the grid's mosaic, under the queue page (#322). */
internal fun mosaicCellTag(index: Int) = "mosaic cell $index"

/** The cell background that grows into the viewer's card. */
fun cardKey(id: String) = "card-$id"

/** The image, which only changes size on the way into the viewer. */
fun imageKey(id: String) = "image-$id"

/** A GIF's or a video's frame, which only moves on the way back into the cell (#283). */
fun sampleKey(id: String) = "sample-$id"

/**
 * A cell's thumbnail request. The queue's preload decodes a grid's first screen with it (#317, `PreloadNeighbours`), so
 * the cells find those thumbnails in memory: keep the two the same request.
 */
internal fun gridCellRequest(context: PlatformContext, post: PostDto): ImageRequest =
  postImageRequest(context, PostImage.of(post, Variant.Preview))

/** Extreme ratios are clamped so one panorama or strip doesn't dominate a column. */
internal fun cellRatio(post: PostDto): Float {
  val w = post.previewWidth ?: return 1f
  val h = post.previewHeight ?: return 1f
  if (w <= 0 || h <= 0) return 1f
  return (w.toFloat() / h).coerceIn(0.3f, 3f)
}
