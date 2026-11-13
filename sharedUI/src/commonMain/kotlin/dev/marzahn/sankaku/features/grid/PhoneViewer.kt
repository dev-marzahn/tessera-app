package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.scaleToBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.fadeOut
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import com.google.fonts.icons.download
import com.google.fonts.icons.refresh
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.features.post.Rejection
import dev.marzahn.sankaku.features.post.PostCounts
import dev.marzahn.sankaku.features.post.keepingFrom
import dev.marzahn.sankaku.features.post.rememberViewing
import dev.marzahn.sankaku.features.post.SaveOutcome
import dev.marzahn.sankaku.features.post.rememberRejections
import dev.marzahn.sankaku.ui.components.CornerActionChip
import dev.marzahn.sankaku.ui.components.LiveAnnouncement
import dev.marzahn.sankaku.ui.components.MenuButton
import dev.marzahn.sankaku.ui.components.MenuSaveState
import dev.marzahn.sankaku.ui.rememberActionWindowMillis
import dev.marzahn.sankaku.ui.runWindow
import com.google.fonts.icons.open_in_new
import com.google.fonts.icons.share
import com.google.fonts.icons.grid_view
import com.google.fonts.icons.bookmark
import dev.marzahn.sankaku.tabs.LeavingEdge
import dev.marzahn.sankaku.tabs.LocalQueueActions
import dev.marzahn.sankaku.tabs.LocalQueuePictureSwipe
import dev.marzahn.sankaku.tabs.PicturePeek
import dev.marzahn.sankaku.tabs.TabId
import dev.marzahn.sankaku.tabs.queueSwipe
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.marzahn.sankaku.PlatformBackHandler
import dev.marzahn.sankaku.PlatformPredictiveBackHandler
import dev.marzahn.sankaku.PlatformSystemBars
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.features.post.LocalVideoKeys
import dev.marzahn.sankaku.features.post.PlayerPreferences
import dev.marzahn.sankaku.features.post.PostActions
import dev.marzahn.sankaku.features.post.PostDetails
import dev.marzahn.sankaku.features.post.PostImageView
import dev.marzahn.sankaku.features.post.PostSaver
import dev.marzahn.sankaku.features.post.PostStats
import dev.marzahn.sankaku.features.post.PostZoom
import dev.marzahn.sankaku.features.post.VIDEO_SEEK_ROW_HEIGHT
import dev.marzahn.sankaku.features.post.VideoKeys
import dev.marzahn.sankaku.features.post.VideoProgressLine
import dev.marzahn.sankaku.features.post.VideoSeekRow
import dev.marzahn.sankaku.features.post.VideoDragTime
import dev.marzahn.sankaku.features.post.VIDEO_DRAG_TIME_HEIGHT
import dev.marzahn.sankaku.features.post.aspectRatio
import dev.marzahn.sankaku.features.post.fittedBox
import dev.marzahn.sankaku.features.post.decodedInMemory
import dev.marzahn.sankaku.features.post.isVideo
import dev.marzahn.sankaku.features.post.postByline
import dev.marzahn.sankaku.data.PostTitle
import dev.marzahn.sankaku.features.post.postPageUrl
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.postImageRequest
import dev.marzahn.sankaku.tabs.MIN_FLING
import dev.marzahn.sankaku.openUrl
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.platformShareText
import dev.marzahn.sankaku.shell.CoverChrome
import dev.marzahn.sankaku.tabs.PauseUndoWhile
import dev.marzahn.sankaku.tabs.QueueBar
import dev.marzahn.sankaku.shell.LocalTabBarSlide
import dev.marzahn.sankaku.shell.LocalQueuePageOpening
import dev.marzahn.sankaku.shell.LocalHandoffLeaving
import androidx.compose.runtime.derivedStateOf
import dev.marzahn.sankaku.tabs.LocalOpenTabList
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import dev.marzahn.sankaku.ui.components.FavouriteBursts
import dev.marzahn.sankaku.ui.components.TipAnchor
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.offerLongPressTip
import dev.marzahn.sankaku.ui.components.PictureBurstSize
import dev.marzahn.sankaku.features.post.forShown
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.MenuAction
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.spatialBoundsTransform
import dev.marzahn.sankaku.ui.components.spatialFadeSpec
import dev.marzahn.sankaku.ui.components.spatialSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The sheet's header at peek: its handle (16 dp), the favourite and vote group (48 dp) and 16 dp under it. */
private val PEEK_HEADER = 80.dp

/** Full, the room between the top bar and the favourite and vote group, as much as there is under the group. */
private val STATS_GAP = 16.dp

/** The row at the top with the position and the title, under the status bar. */
private val TOP_ROW = 56.dp

/**
 * A′ (#178, `.scratch/bar-less/`): the count pill floats over the picture, its drawn 40 dp this far above the sheet's
 * top (or a video's seek row).
 */
private val PILL_GAP = 12.dp

/** The pill's line above what it floats over: [PILL_GAP] and half its drawn 40 dp. */
private val PILL_LINE = PILL_GAP + 20.dp

/**
 * How far above the sheet's top the fitted picture ends while the chrome shows (#260), so it's never under the
 * controls that float there: the count pill's row (the pill, Undo, and ✕ with "Show queue buttons") is
 * [BottomRowLayout.Height] tall around its line, [PILL_LINE] above what it floats over, and a video adds its seek
 * row's [seekRoom] ([SEEK_ROOM]) under the pill. The picture ends at the row's top, 8 dp above the pill's drawn 40 dp.
 * A zoomed or tap-hidden picture takes the whole window as before; the inset is only for the fitted one.
 *
 * At a [compactHeight] (a phone in landscape, #307) there's no inset: the room is too short to give a fifth of it to
 * the controls, so they float over the picture's bottom edge, as they did before #260.
 */
internal fun fittedPictureInset(seekRoom: Dp = 0.dp, compactHeight: Boolean = false): Dp =
  if (compactHeight) 0.dp else PILL_LINE + BottomRowLayout.Height / 2 + seekRoom

/**
 * Below this window height (Material's compact height class: a phone in landscape) the picture takes the room from
 * the status bar to the sheet's peek band, the top row floating over it, and has no [fittedPictureInset] (#307). The
 * top row, the peek band and the inset are sized for portrait, and in a 412 dp landscape window they left the picture
 * about a seventh of the height.
 */
private val COMPACT_HEIGHT = 480.dp

/** Whether a window [height] tall is a compact one ([COMPACT_HEIGHT]). */
internal fun isCompactHeight(height: Dp): Boolean = height < COMPACT_HEIGHT

/**
 * Where the fitted picture's room starts, in px: under the [TOP_ROW] below the status bar ([statusTop] px), or at a
 * [compactHeight] right under the status bar, the top row floating over it (#307). [PhoneViewer] and [LonePostPeek]
 * both lay out from it, so a peek lands where the post draws.
 */
private fun Density.pictureTop(statusTop: Int, compactHeight: Boolean): Float =
  statusTop + if (compactHeight) 0f else TOP_ROW.toPx()

/** How far past the navigation bar's band the sheet lifts before its details are fully shown. */
private val DETAILS_FADE_IN = 48.dp

/** Half: the sheet's top at this share of the window, from the top. */
private const val HALF_TOP = 0.5f

/** Below this height the image fades out: the sheet is nearly full, and the header's thumbnail stands in for it. */
private val IMAGE_FADE_HEIGHT = 120.dp

/** A drag down past this many dp, or a flick, closes the viewer; less springs back. */
private val DISMISS_DISTANCE = 120.dp
private const val DISMISS_VELOCITY = 1500f

/** How far a drag or predictive back can shrink the card: to 80% at half the window's height. */
private const val DISMISS_MIN_SCALE = 0.8f

/** Predictive back moves the card a quarter of the window at most, shrinking it to about 90%. */
private const val BACK_SHARE = 0.25f

/** A fling faster than this (px/s) moves the sheet on to the next stop. */
private const val SHEET_FLING = 1200f

/**
 * The post viewer on a phone (`.scratch/android-viewer/` direction A, with the count pill floating over the picture as
 * in `.scratch/bar-less/` A′, #178): the image fills the window, a pager steps through the grid's posts, and the
 * details rise from a sheet with three stops (peek, half, full). At peek the sheet is its header alone: the stars, ♡
 * and ⋮. The queue's count pill floats 12 dp above it, rises with it and hides with the chrome.
 *
 * Opening grows the cell into the full-window card, as on desktop but without the split; the chrome arrives at 90%.
 * Dragging the image down at peek, or predictive back, shrinks the card while the finger moves; letting go past the
 * threshold closes it into the current post's cell. Dragging up anywhere lifts the sheet, and the image shrinks to
 * stay whole above it; a tap on the sheet's handle steps it peek → half → full → peek, and TalkBack has Expand and
 * Collapse on the picture and the handle (#494). A tap on the image hides all chrome and the system bars (as does zooming past 1×); a tap or a
 * drag up brings them back. A long-press on the picture favourites the post, never unfavourites it, with the ♡'s
 * burst and haptic (#527; TalkBack's Favourite on the picture). Back lowers a raised sheet and zooms out first. A video plays once its page settles; its
 * seek row is part of the chrome, a 2 dp progress line stays while that's hidden, and a double-tap on either half
 * seeks 5 s.
 *
 * [mode] is as for [PostViewer]. A lone post ([ViewerMode.Tab], #181) has no pager or position: a sideways swipe on
 * its image moves between queue items instead ([queueSwipe], at 1× only), the top line names where it came from
 * ([origin]), and a drag down closes it ([onDragClose]) only at peek, at 1×, from outside a video's seek row, and on
 * release past [LONE_CLOSE_SHARE] of the height or with a fling ([loneDragCloses]). Under the queue page ([sharedId]
 * set, [visibilityScope] the page's, #320), only its picture is shared: it grows out of its row's thumbnail over the
 * window's black card as the page closes, and shrinks back into it as the page opens. [onShow] gets the page the pager
 * settles on, so the hidden grid cell follows. Over a grid, ⋮'s Keep for later keeps the post shown ([onKeep], #504).
 *
 * Pages are positions in the order the pager steps in, which over a grid is its reading order (#110): [index],
 * [postAt], [onShow] and the position label all count in it, and the grid maps them to its list. [pageKey] keys each
 * page by its post, so posts slotted in before the current page as more load leave the pager on the same post.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.PhoneViewer(
  index: Int,
  postAt: (Int) -> PostDto?,
  pageKey: ((Int) -> Any)? = null,
  loadedCount: Int,
  hasMore: Boolean,
  sharedId: String?,
  visibilityScope: AnimatedVisibilityScope,
  onShow: (Int) -> Unit,
  end: ViewerEnd = ViewerEnd.NoMore,
  onRetryEnd: () -> Unit = {},
  onClose: () -> Unit,
  opening: Int = 0,
  mode: ViewerMode = ViewerMode.Overlay,
  searchTags: List<String> = emptyList(),
  origin: PostOrigin? = null,
  onDragClose: (() -> Unit)? = null,
  queueFrom: TabId? = null,
  onKeep: ((PostDto) -> Unit)? = null,
  actions: PostActions = koinInject(),
  playerPreferences: PlayerPreferences = koinInject(),
  tips: Tips = koinInject(),
) {
  val scope = rememberCoroutineScope()
  // The settled page's video, for the seek row, the progress line, a double-tap's seek and the volume keys.
  val video = remember(playerPreferences) { VideoKeys(playerPreferences) }
  val density = LocalDensity.current
  val transition = visibilityScope.transition
  val settled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
  // Open and close. Opening grows the grid's cell into the card through the shared elements (`transition` goes
  // Visible); closing is `close()`, then the grid clears its viewer index and `transition` heads back out. While the
  // card shrinks, touches reach the grid (`touchesPass`), and a tapped cell reopens the viewer in this composition
  // with a new `opening`. What a close can leave set is therefore keyed on `opening`: `leaving`, `stepped`,
  // `tapHidden`, the dismiss pull and the back handlers. Unkeyed, the last close's state carries over and the new card
  // is left half-way (#105). The sheet and the zoom aren't keyed: a close only starts at peek and at 1× (the drag needs
  // both, and Back lowers the sheet and zooms out first). The shared transition is the grid's, and simply turns round.
  // That's only on the post it was closing into: a different post's opening is a new composition (`ViewerOnScreen`,
  // #323), so its pager and its card start from the cell tapped.
  var leaving by remember(opening) { mutableStateOf(false) }
  val currentOpening by rememberUpdatedState(opening)
  val closing = leaving || transition.targetState != EnterExitState.Visible
  // As on desktop: the shared elements are attached only while opening or closing, so settled, the hidden cell can
  // follow the pager without anything flying between the grid and the card. Also while a drag down or predictive back
  // pulls the card (the pager can't step then): attached only as the close began, they needed two laid-out frames
  // before the exit, and the card held still for them after the finger let go (#324).
  var stepped by remember(opening) { mutableStateOf(false) }
  val closeSpec = spatialSpec<Float>()
  // The drag down (or predictive back) that shrinks the card, new for each opening.
  val dismiss = rememberCardDismiss(opening, closeSpec)
  val pulled by remember(dismiss) { derivedStateOf { !dismiss.atRest } }
  val attachShared = (!settled && !stepped) || leaving || (pulled && mode != ViewerMode.Tab)
  // A lone post's picture, about to leave for its row on the queue page (#320).
  val queuePageOpening = mode == ViewerMode.Tab && LocalQueuePageOpening.current
  // Whether the shared elements have been attached through a laid-out frame, so a close can start its exit at once: a
  // pulled card's, or one still opening. Read only by `close()`, so not state.
  val attachedReady = remember(opening) { booleanArrayOf(false) }
  LaunchedEffect(opening, attachShared) {
    attachedReady[0] = false
    if (attachShared) {
      repeat(2) { withFrameNanos {} }
      attachedReady[0] = true
    }
  }
  // The sample (a picture's, a GIF, a video's surface) shows once the opening has settled (#180), and then stays
  // through the close, a video paused on its frame: dropped as the close began, it jumped back to the preview (#283).
  var sampleKept by remember(opening) { mutableStateOf(false) }
  SideEffect { if (settled) sampleKept = true }
  // A lone post's chrome stays out of the overlay (#320): the scope is the shell's, and while its picture moves to or
  // from its row the chrome would draw above the queue page coming in. It's under the page then, and fades with it.
  // Nor does the chrome of a post fading out as a sheet's thumbnail opens another (#321): drawn in the overlay, it
  // wouldn't fade with its post.
  val handoffLeaving = LocalHandoffLeaving.current
  val aboveImage = Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f) {
    mode != ViewerMode.Tab && isTransitionActive && !handoffLeaving
  }
  // Closing into its cell, the card and the chrome are drawn in the transition's overlay but still laid out over the
  // grid, and took every tap there until the card's bounds had shrunk away from it. They let touches through then, so
  // a cell can be opened while the last one is still closing.
  // Only over a grid: a lone post's card isn't shared, so the layer's empty clip hid it, picture and chrome, in the
  // first frame of the queue page opening over it (#320), and the queue page above it takes the touches anyway.
  val touchesPass = mode != ViewerMode.Tab && transition.targetState != EnterExitState.Visible && sharedId != null && attachShared

  val openSpec = spatialSpec<Float>(slow = true)
  // Alphas run on the effects springs, which don't overshoot: on the spatial one they went below 0 and back (#281).
  val openFadeSpec = effectsSpec<Float>(slow = true)
  val fadeSpec = effectsSpec<Float>()
  val cardFadeSpec = spatialFadeSpec<Float>()
  val scrimAlpha by transition.animateFloat({ if (targetState == EnterExitState.Visible) openFadeSpec else fadeSpec }) {
    if (it == EnterExitState.Visible) 1f else 0f
  }
  val progress by transition.animateFloat({ if (targetState == EnterExitState.Visible) openSpec else closeSpec }) {
    if (it == EnterExitState.Visible) 1f else 0f
  }
  val controlsShown = progress > CONTROLS_SHOWN_AT && !leaving
  val bounds = spatialBoundsTransform(slow = !closing)

  // The count pill is one element across open and close (#103, #178). It keeps its x between the grid and a post, so
  // the viewer draws the pill itself from its first frame, on the grid's line (`docked` 0), moves it up to float over
  // the picture above the sheet as the chrome arrives, and on closing (or while a drag down or predictive back pulls
  // the card) moves it back down. Once it's on the grid's line, closing, the shell's pill takes over in the same place
  // (`handBack`) and the viewer's goes a couple of frames later, so there's never a frame with none; an overlap is the
  // same pixels. Reopened mid-close, `handBack` is new with the opening and the viewer covers the shell's pill again,
  // its own already drawn. A lone post has no grid to come from: its pill is over the picture from the start.
  val floatingBar = mode != ViewerMode.Tab
  val dockedState = animateFloatAsState(if (controlsShown) 1f else 0f, closeSpec)
  // The window's height, for predictive back and the drag's range.
  var windowHeight by remember { mutableFloatStateOf(0f) }
  // A drag down moves the pill back to the grid's line as it fades the rest of the chrome; letting go short of closing
  // moves it up again.
  val docked: () -> Float = { dockedState.value * dragFade(dismiss.fraction(windowHeight / 2f)) }
  val floated by remember(opening) { derivedStateOf { docked() == 0f } }
  val handBack = closing && floatingBar && floated
  var handedBack by remember(opening) { mutableStateOf(false) }
  LaunchedEffect(handBack) {
    handedBack = false
    if (handBack) {
      repeat(2) { withFrameNanos {} }
      handedBack = true
    }
  }
  // Where the grid's pill had slid to as the viewer opened (hidden on scroll): it rises from there. The shell shows it
  // again while covered, so closing ends at its place.
  val tabBarSlide = LocalTabBarSlide.current
  val slideFrom = remember(opening) { tabBarSlide() }

  // Covers the top bar and the grid's pill, until the pill is back on the grid's line on its way into the cell.
  CoverChrome(enabled = !handBack)

  val minFling = with(density) { MIN_FLING.toPx() }
  // Under the post it shows: the viewer comes back on that post (#560). A grid's is known before its list is loaded
  // again ([sharedId], saved with the grid); a lone post's viewer comes only once its post is loaded.
  val sheet = rememberViewerSheetState(scope, closeSpec, SHEET_FLING, postId = sharedId ?: postAt(if (mode.steps) index else 0)?.id)
  val zoom = remember { PostZoom() }
  // A new opening starts with the chrome shown, even if the last one closed with it hidden.
  var tapHidden by remember(opening) { mutableStateOf(false) }
  // The chrome slides out when a tap hides it or the image is zoomed past 1×, and fades in as it arrives. The image
  // is fitted around it only as a tap leaves it: resizing the image while zoomed made zoomimage measure again, which
  // cut a double-tap's zoom short, so zoomed, the image keeps its room and the zoom spills over the hidden chrome.
  // Settled on the trailing page (#524), the last post's chrome goes, as a tap would send it: the page is the viewer's
  // black with what it says.
  var onEndPage by remember { mutableStateOf(false) }
  val chromeShown = !tapHidden && !zoom.zoomed && !onEndPage
  // Leaving for a post opened from its sheet (#321), the sheet goes down and the top row up as a tap would send them,
  // while the system bars stay: the post coming in shows them.
  val chromeLayout by animateFloatAsState(if (chromeShown && !handoffLeaving) 1f else 0f, spatialSpec())
  // Once a zoom has settled, the image takes the whole window (zoomimage keeps the zoom across that one resize), and
  // keeps it through later pinches until it's back at 1×.
  var zoomRoom by remember { mutableStateOf(false) }
  LaunchedEffect(zoom.zoomed, zoom.settled) { zoomRoom = zoom.zoomed && (zoomRoom || zoom.settled) }
  // The image's room moves with the chrome on its spring, clamped to [0, 1] (#308): unclamped it went to −0.015 and
  // 1.015, so the picture grew about 1.5% past its size and back. A spring of its own without the bounce would keep
  // it in range too, but it'd fall out of step with the sheet and the top row it fits between.
  val imageSpring = animateFloatAsState(if (tapHidden || zoomRoom) 0f else 1f, if (zoom.zoomed) snap() else spatialSpec())
  val imageLayout by remember { derivedStateOf { imageSpring.value.coerceIn(0f, 1f) } }
  val chromeAlpha by animateFloatAsState(if (chromeShown && controlsShown) 1f else 0f, fadeSpec)

  // Over a grid, one page past the loaded posts: the trailing page (#524), loading the next page, its failure with
  // Retry, or the end of the grid.
  val pager = rememberPagerState(initialPage = if (mode.steps) index else 0) { if (mode.steps) loadedCount + 1 else 1 }
  // The settled page becomes the viewer's post, so the hidden grid cell follows; a new index from outside (the list
  // reloaded after signing in) moves the pager. Settled on the trailing page, the viewer's post stays the last one,
  // until the next page loads and that page becomes its first post.
  val currentIndex by rememberUpdatedState(index)
  val currentLoaded by rememberUpdatedState(loadedCount)
  if (mode.steps) {
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage >= currentLoaded }.collect { onEndPage = it } }
    LaunchedEffect(pager) {
      snapshotFlow { pager.settledPage to currentLoaded }.collect { (page, loaded) ->
        if (page != currentIndex && page < loaded) {
          stepped = true
          onShow(page)
        }
      }
    }
    // On each opening too: closed from the trailing page (#524) and reopened on the same post while it shrank away,
    // the pager would otherwise still stand past it.
    LaunchedEffect(index, opening) { if (!pager.isScrollInProgress && pager.settledPage != index) pager.scrollToPage(index) }
  }
  val post = sameWhileEqual(postAt(if (mode.steps) index else 0))
  // Seen (#174): swiping on, or closing, after the settled post was fully shown.
  val viewing = rememberViewing(post)
  // Kept from here, the showing it's kept from doesn't make it seen since kept (#537).
  val keep = onKeep?.let(viewing::keepingFrom)

  fun close() {
    // A lone post closes at once: Back goes to the previous queue item (#181).
    if (mode == ViewerMode.Tab) return onClose()
    if (leaving) return
    leaving = true
    // Let go of a drag down or predictive back: attached all along, so the exit starts in the next frame.
    if (attachedReady[0]) return onClose()
    val closingOpening = currentOpening
    scope.launch {
      // The shared elements attach with `leaving`; they need a laid-out frame before the exit, or the card snaps.
      repeat(2) { withFrameNanos {} }
      if (currentOpening == closingOpening) onClose()
    }
  }

  fun toggleChrome() {
    if (closing || zoom.zoomed) return
    tapHidden = !tapHidden
    if (tapHidden) sheet.animateTo(0f)
  }

  // A long-press on the picture, or TalkBack's Favourite on it, favourites the post and never unfavourites it (#527):
  // the sheet's ♡'s path, with its haptic and the burst over the picture.
  val haptics = LocalHapticFeedback.current
  fun favouriteFromPicture(shown: PostDto) {
    if (closing) return
    favouriteWithHaptic(haptics) { actions.addFavourite(shown) }
  }

  // Back: zoomed, it zooms out; with the sheet raised, it lowers it; otherwise (over a grid) it closes, predictively.
  // The handler composed last wins. They're new for each opening, as when the viewer opens from scratch: one reopened
  // while still closing (the same composition) kept the handlers that had closed it, and Back no longer reached them.
  key(opening) {
    if (mode == ViewerMode.Overlay) {
      PlatformPredictiveBackHandler(
        enabled = !closing,
        onProgress = { dismiss.pullTo(it * windowHeight * BACK_SHARE) },
        onCancel = dismiss::springBack,
        onBack = ::close,
      )
    }
    PlatformBackHandler(enabled = !closing && !sheet.atPeek) { sheet.animateTo(0f) }
    PlatformBackHandler(enabled = !closing && zoom.zoomed) { scope.launch { zoom.zoomOut() } }
  }

  // The status bar's icons go light over the black around the image and follow the theme over a full sheet.
  val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val (halfFraction, fullFraction) = sheetFractions(sheet.lift, sheet.stops)
  PlatformSystemBars(
    hidden = !chromeShown && !leaving,
    lightIcons = if (leaving || progress < 0.5f || fullFraction > 0.5f) darkTheme else true,
  )

  // The hardware volume keys reach the viewer only while it holds focus. They're never consumed: the system sets the
  // media volume, and volume up lifts a video's shared mute (VideoKeys.onVolumeKey).
  val keyFocus = remember { FocusRequester() }
  // Asked again as each video starts, in case something else took focus and let it go since the viewer opened.
  LaunchedEffect(video.player) { keyFocus.requestFocus() }
  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown) video.onVolumeKey(it.key)
        false
      }
      .focusRequester(keyFocus)
      .focusable(),
  ) {
    val height = constraints.maxHeight.toFloat()
    // The largest insets seen: hiding the system bars takes their insets away, and the layout mustn't jump with them.
    // Seeded from the window's last seen ([BarInsets]), so a lone post landed on with the bars still hidden lays out
    // as one with them shown, as its peek did (#263).
    val seenBars = LocalBarInsets.current
    var maxStatusTop by remember { mutableIntStateOf(seenBars?.statusTop ?: 0) }
    var maxNavBottom by remember { mutableIntStateOf(seenBars?.navBottom ?: 0) }
    val currentStatusTop = WindowInsets.statusBars.getTop(density)
    val currentNavBottom = WindowInsets.navigationBars.getBottom(density)
    val statusTop = max(maxStatusTop, currentStatusTop)
    val navBottom = max(maxNavBottom, currentNavBottom)
    SideEffect {
      windowHeight = height
      maxStatusTop = statusTop
      maxNavBottom = navBottom
      // What the bars take while they show, for the next viewer and the peek; 0 is a bar not back yet.
      if (seenBars != null && chromeShown) {
        if (currentStatusTop > 0) seenBars.statusTop = currentStatusTop
        if (currentNavBottom > 0) seenBars.navBottom = currentNavBottom
      }
      // A window new since the bars were hidden (a rotation, a split screen) starts empty: this viewer's own are the
      // best known until they show again, so the peek lays out as this viewer does.
      if (seenBars != null && seenBars.statusTop == 0 && seenBars.navBottom == 0) {
        seenBars.statusTop = statusTop
        seenBars.navBottom = navBottom
      }
    }
    // At peek the sheet is its header alone, over the navigation bar (A′, #178).
    val peekTop = height - with(density) { PEEK_HEADER.toPx() } - navBottom
    // The grid's pill's line, where the viewer's pill starts from and closes into (BottomRowLayout, R2).
    val gridLine = height - navBottom - with(density) { BottomRowLayout.CentreLine.toPx() }
    LaunchedEffect(peekTop, height) { sheet.update(SheetStops(half = peekTop - height * HALF_TOP, full = peekTop)) }
    val topRow = statusTop + with(density) { TOP_ROW.toPx() }
    // At a compact height (a phone in landscape, #307) the top row floats over the picture, which starts under the
    // status bar, and the controls float over its bottom edge (no `fittedPictureInset`).
    val compactHeight = isCompactHeight(maxHeight)
    val pictureTop = density.pictureTop(statusTop, compactHeight)
    // Where the sheet's top is: lifted from peek, or slid out with the chrome.
    fun sheetTop(shown: Float = chromeLayout) = peekTop - sheet.lift + (1f - shown) * (height - peekTop + sheet.lift)
    val dismissRange = height / 2f
    fun dismissFraction() = dismiss.fraction(dismissRange)
    // Where the image and a video's seek row are, for a lone post's drag down, which mustn't start on the seek row.
    val imageBox = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val seekRow = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun seekRowBounds(): Rect? = seekRow[0]?.takeIf { it.isAttached }?.boundsInRoot()

    // Over a grid, the grid shows through as the card shrinks (opening, closing, a drag down). Taps don't reach it, except
    // while it closes into its cell (passTouchesThrough on the card and the chrome).
    if (mode != ViewerMode.Tab) {
      Box(
        Modifier
          .fillMaxSize()
          .graphicsLayer { alpha = scrimAlpha * (1f - dismissFraction()) }
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = OVERLAY_SCRIM_ALPHA))
          .semantics { hideFromAccessibility() }
          .then(if (closing) Modifier else Modifier.pointerInput(Unit) { detectTapGestures {} }),
      )
    }

    // The card: the cell grown to the window, black around the image. A drag down shrinks it (in layout, so the
    // closing transform starts from where the finger left it).
    // A lone post's card stays the window's: only its picture moves, into and out of its row on the queue page (#320).
    val cardCorner by transition.animateDp({ spatialSpec(slow = !closing) }) {
      if (it == EnterExitState.Visible || mode == ViewerMode.Tab) 0.dp else CELL_CORNER
    }
    val cardShared = sharedId != null && mode != ViewerMode.Tab
    Box(
      Modifier
        .layout { measurable, constraints ->
          val scale = 1f - (1f - DISMISS_MIN_SCALE) * dismissFraction()
          val width = (constraints.maxWidth * scale).roundToInt()
          val cardHeight = (constraints.maxHeight * scale).roundToInt()
          val placeable = measurable.measure(Constraints.fixed(width, cardHeight))
          layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - width) / 2, ((constraints.maxHeight - cardHeight) / 2 + dismiss.offset * 0.6f).roundToInt())
          }
        }
        // Outside the shared bounds, whose overlay drawing it doesn't clip.
        .passTouchesThrough(touchesPass)
        .then(
          if (!cardShared || sharedId == null || !attachShared) {
            Modifier
          } else {
            Modifier.sharedBounds(
              rememberSharedContentState(cardKey(sharedId)),
              visibilityScope,
              enter = EnterTransition.None,
              // Closing, the black card fades into the cell's background as it shrinks, instead of switching to it. The
              // fade keeps pace with the shrink: on the effects spring it was nearly done while the card was still the
              // window's size, a grey card that only then shrank (#298).
              exit = fadeOut(cardFadeSpec),
              boundsTransform = bounds,
              resizeMode = RemeasureToBounds,
              clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(max(cardCorner, lerp(0.dp, VIEWER_CORNER, dismissFraction())))),
              zIndexInOverlay = CARD_Z,
            )
          },
        )
        .clip(RoundedCornerShape(max(cardCorner, lerp(0.dp, VIEWER_CORNER, dismissFraction()))))
        .background(Color.Black),
    ) {
      // The image keeps to the room between the top row (at a compact height, the status bar) and the sheet, as shares
      // of the card, so it shrinks with the card while the card opens, closes or follows a drag. Each page then keeps
      // its picture clear of the controls over the room's bottom (`fittedPictureInset`, #260; none at a compact
      // height), at the same share ([cardShare]).
      val cardShare = remember { floatArrayOf(1f) }
      var dragDismisses by remember { mutableStateOf<Boolean?>(null) }
      // Whether the drag began on a video's seek row, which a lone post's drag down mustn't start from.
      var fromSeekRow by remember { mutableStateOf(false) }
      val drag = rememberDraggableState { dy ->
        val dismissing = dragDismisses
          ?: dragDownCloses(dy, sheet.headedForPeek, zoom.zoomed, mode, closable = mode == ViewerMode.Overlay || onDragClose != null, fromSeekRow)
            .also { dragDismisses = it }
        if (dismissing) {
          dismiss.dragBy(dy)
        } else {
          // A drag up brings hidden chrome back and goes on lifting the sheet.
          if (dy < 0f && tapHidden) tapHidden = false
          sheet.dragBy(dy)
        }
      }
      Box(
        Modifier
          .layout { measurable, constraints ->
            val share = constraints.maxHeight / height
            cardShare[0] = share
            val top = pictureTop * imageLayout * share
            val bottom = sheetTop(imageLayout).coerceAtMost(height) * share
            val roomHeight = (bottom - top).roundToInt().coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, roomHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, top.roundToInt()) }
          }
          .onGloballyPositioned { imageBox[0] = it }
          .draggable(
            drag,
            Orientation.Vertical,
            enabled = !closing && !zoom.zoomed,
            onDragStarted = { start ->
              dragDismisses = null
              fromSeekRow = imageBox[0]?.takeIf { it.isAttached }?.localToRoot(start)?.let { seekRowBounds()?.contains(it) } == true
            },
            onDragStopped = { velocity ->
              if (dragDismisses == true && mode == ViewerMode.Tab) {
                if (loneDragCloses(dismiss.offset, height, velocity, minFling)) onDragClose?.invoke() else dismiss.springBack()
              } else if (dragDismisses == true) {
                if (dismiss.offset > with(density) { DISMISS_DISTANCE.toPx() } || velocity > DISMISS_VELOCITY) close() else dismiss.springBack()
              } else {
                sheet.settle(velocity)
              }
              dragDismisses = null
            },
          ),
      ) {
        // The picture's gestures for TalkBack: the drag up's and down's on the sheet (#494), the long-press's
        // Favourite while the post isn't one (#527, added per page), and on a lone post the queue swipe's and the drag
        // down's (#181). Zoomed, a drag pans, and the sheet is away with the chrome.
        val queueActions = LocalQueueActions.current
        val sheetMoves = if (zoom.zoomed) emptyList() else sheetActions(sheet) { tapHidden = false }
        val loneActions = if (mode != ViewerMode.Tab) {
          emptyList()
        } else {
          queueActions + listOfNotNull(onDragClose?.let { close -> CustomAccessibilityAction(CLOSE_ACTION) { close(); true } })
        }
        // Between two lone posts a queue swipe moves only the picture, as the pager does over a grid (#263): the
        // neighbour's comes in beside it in the same room, and the top row and the sheet stay until it's shown.
        val pictureSwipe = LocalQueuePictureSwipe.current.takeIf { mode == ViewerMode.Tab }
        if (pictureSwipe != null) {
          DisposableEffect(pictureSwipe) { onDispose(pictureSwipe.drawing()) }
          for ((peek, side) in listOf(pictureSwipe.previous to -1f, pictureSwipe.next to 1f)) {
            if (peek == null) continue
            val peekInset = with(density) { fittedPictureInset(seekRoom = if (peek.picture?.let { isVideo(it.post) } == true) SEEK_ROOM else 0.dp, compactHeight).toPx() }
            NeighbourPicture(
              peek,
              forward = side > 0f,
              insetPx = { peekInset * imageLayout * cardShare[0] },
              // A room's width beside the picture: the layer's own, as it fills the room.
              modifier = Modifier.graphicsLayer { translationX = (pictureSwipe.offset() + side * size.width) * pictureSwipe.direction },
            )
          }
        }
        HorizontalPager(
          state = pager,
          key = pageKey.takeIf { mode.steps },
          userScrollEnabled = mode.steps && !closing && dismiss.atRest,
          beyondViewportPageCount = 1,
          // A lone post has no pager to step: a sideways swipe moves between queue items (#181), at 1× only, so a pan
          // or a pinch is the zoom's. It claims sideways moves either way, or one would end as a tap and hide the chrome.
          modifier = Modifier
            .fillMaxSize()
            .then(if (pictureSwipe == null) Modifier else Modifier.graphicsLayer { translationX = pictureSwipe.offset() * pictureSwipe.direction })
            .then(
              if (mode.steps) {
                Modifier
              } else {
                Modifier.queueSwipe(enabled = !closing && !zoom.zoomed && dismiss.atRest, from = queueFrom, claimSideways = true)
              },
            ),
        ) { page ->
          if (mode.steps && page >= loadedCount) {
            // On the viewer's black, so drawn dark whatever the theme.
            SankakuTheme(isDark = true) {
              ViewerEndPage(end, onRetry = onRetryEnd, modifier = Modifier.pointerInput(Unit) { detectTapGestures { } })
            }
            return@HorizontalPager
          }
          // Through postAt, which loads the next page for the pages around the end, and is null past it (#472).
          val shown = sameWhileEqual(postAt(if (mode.steps) page else 0)) ?: return@HorizontalPager
          // Each page keeps its own picture clear of the controls (a video's of its seek row too), so a neighbour is
          // fitted mid-swipe as it will be once settled. The inset goes with the chrome (`imageLayout`): zoomed or
          // tap-hidden, the picture takes the whole room.
          val inset = fittedPictureInset(seekRoom = if (isVideo(shown)) SEEK_ROOM else 0.dp, compactHeight)
          val fullInsetPx = with(density) { inset.toPx() }
          val insetPx = { fullInsetPx * imageLayout * cardShare[0] }
          Box(Modifier.fillMaxSize()) {
            // The band the inset leaves under the picture takes a tap as the picture does; drags there still reach
            // the pager and the sheet.
            Box(
              Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                  val band = insetPx().roundToInt().coerceIn(0, constraints.maxHeight)
                  val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, band))
                  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .pointerInput(Unit) { detectTapGestures { toggleChrome() } },
            )
            // Only the settled page has the video's handle, so a double-tap on a neighbour mid-swipe can't seek it.
            // The picture's element, and whether it's leaving for its row on the queue page as the page opens (#320).
            // Then the row's thumbnail draws it in flight, and the post's own picture (a sample among it, which isn't part
            // of the element) goes, so the picture isn't left behind under the page. With no row to go to (a blurred
            // one), it stays and the page fades over it.
            val pictureState = rememberSharedContentState(imageKey(shown.id))
            val leavingForRow = mode == ViewerMode.Tab && shown.id == sharedId && transition.targetState != EnterExitState.Visible &&
              pictureState.isMatchFound
            CompositionLocalProvider(LocalVideoKeys provides video.takeIf { page == pager.settledPage }) {
              PostImageView(
                shown,
                modifier = Modifier
                  .layout { measurable, constraints ->
                    val pictureHeight = (constraints.maxHeight - insetPx().roundToInt()).coerceIn(1, constraints.maxHeight)
                    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, pictureHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                  }
                  // Below this height the picture fades out: the sheet is nearly full, and the header's thumbnail
                  // stands in for it. The pill and a video's seek row fade with it. This layer is inside the layout
                  // above (what it measures), so its size is the picture's `pictureHeight`, not the room the layout
                  // reports to the page.
                  .graphicsLayer { alpha = if (leavingForRow) 0f else (size.height / IMAGE_FADE_HEIGHT.toPx()).coerceIn(0f, 1f) },
                // A lone post's is also attached while the queue page is about to open over it (#320), so it's laid out
                // matched with its row before it leaves for it. Left attached once settled, it drew no picture for a
                // frame as a later opening ended.
                imageBox = if (shown.id == sharedId && (attachShared || queuePageOpening)) {
                  Modifier.sharedElement(pictureState, visibilityScope, bounds)
                } else {
                  Modifier
                },
                // Closing, the shared image draws the cell's side, which has only the preview of a GIF or a video.
                // Their own frame goes into the cell over it, cropped as the cell crops, below the chrome (#283).
                // A row's thumbnail has no frame to take it.
                sampleBox = if (cardShared && shown.id == sharedId && attachShared) {
                  Modifier.sharedBounds(
                    rememberSharedContentState(sampleKey(shown.id)),
                    visibilityScope,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                    boundsTransform = bounds,
                    resizeMode = scaleToBounds(ContentScale.Crop),
                    zIndexInOverlay = SAMPLE_Z,
                    // Cut to its own bounds, as the cell cuts its preview: a panorama's frame mustn't spill past them.
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(cardCorner.coerceAtLeast(0.dp))),
                  )
                } else {
                  Modifier
                },
                showSample = settled || sampleKept,
                playing = settled,
                onTap = ::toggleChrome,
                onLongPress = {
                  // Long-pressed before its tip came: the tip never shows.
                  tips.used(Tip.LongPressFavourite)
                  favouriteFromPicture(shown)
                },
                zoom = zoom.takeIf { page == pager.currentPage },
                // Only the settled page plays: the pages beside it are composed too, and would play over it.
                playVideo = page == pager.settledPage,
                onShown = { viewing.loaded(shown.id) },
                actions = sheetMoves + pictureFavouriteAction(shown) { favouriteFromPicture(shown) } + loneActions,
              )
            }
          }
        }
      }
    }

    if (post == null) return@BoxWithConstraints
    val chromeFade = { chromeAlpha * dragFade(dismissFraction()) }
    // Hidden (a tap, a zoom, a drag down, closing), the chrome is out of the accessibility tree too, not only faded.
    val chromeHidden = if (chromeShown && !closing && dismiss.atRest) Modifier else Modifier.semantics { hideFromAccessibility() }
    val chrome = Modifier
      .passTouchesThrough(touchesPass)
      .then(aboveImage)
      .graphicsLayer { alpha = chromeFade() }
      .then(chromeHidden)

    // The top row: the position and, at peek, the title, over a fade.
    TopRow(
      position = if (mode.steps) positionLabel(index, loadedCount, hasMore) else null,
      positionSpoken = if (mode.steps) positionDescription(index, loadedCount, hasMore) else null,
      origin = origin.takeIf { mode == ViewerMode.Tab },
      title = PostTitle.of(post),
      titleAlpha = { 1f - halfFraction },
      statusTop = with(density) { statusTop.toDp() },
      modifier = chrome.graphicsLayer { translationY = -(1f - chromeLayout) * topRow },
    )

    // Zooming, the level shows for a moment at the top (`.scratch/android-viewer/` direction A, row 5).
    var levelShown by remember { mutableStateOf(false) }
    LaunchedEffect(zoom.level, zoom.zoomed) {
      levelShown = zoom.zoomed
      if (levelShown) {
        delay(ZOOM_LEVEL_SHOWN_MS)
        levelShown = false
      }
    }
    val levelAlpha by animateFloatAsState(if (levelShown) 1f else 0f, fadeSpec)
    if (levelAlpha > 0f) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = with(density) { statusTop.toDp() } + 16.dp)
          .graphicsLayer { alpha = levelAlpha },
      ) {
        Text(zoomLevelLabel(zoom.level), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
      }
    }

    // A video's seek row is chrome: it rides 12 dp above the sheet's edge, slides out with it and fades as the image
    // does once the sheet nears full (`.scratch/android-shell/` option 3, row 9). With the chrome hidden, a 2 dp
    // progress line stays at the bottom. Settling on a video by a swipe, after a picture or another video, the row
    // fades in (#282); the video the viewer opened on has it at once, under the chrome's own fade. Swiping off a video,
    // it fades as its page leaves: its player goes with the settle, so there's no row left to fade after that.
    val seekShown = remember(post.id) { Animatable(if (isVideo(post) && !stepped) 1f else 0f) }
    LaunchedEffect(seekShown) { if (isVideo(post)) seekShown.animateTo(1f, fadeSpec) }
    val seekStaying = { (1f - 2f * abs(pager.currentPage - pager.settledPage + pager.currentPageOffsetFraction)).coerceIn(0f, 1f) }
    if (isVideo(post)) {
      // Hidden, it's left out, not only transparent, so its buttons can't take taps meant for the video. While it
      // slides out it goes below the window's bottom with the sheet, not just to it.
      if (chromeAlpha > 0f) VideoSeekRow(
        video,
        preferences = playerPreferences,
        // Offset before the fades: a layer faded below 1 draws offscreen at its own bounds, and with the offset inside
        // it the row was clipped away (#282).
        modifier = Modifier
          .offset { IntOffset(0, (sheetTop() - SEEK_ROOM.toPx() * chromeLayout).roundToInt()) }
          .then(chrome)
          .graphicsLayer {
            // The video's own height, above its inset (#260), so the row fades with it.
            val room = sheetTop().coerceAtMost(height) - pictureTop - fittedPictureInset(SEEK_ROOM, compactHeight).toPx()
            alpha = (room / IMAGE_FADE_HEIGHT.toPx()).coerceIn(0f, 1f) * seekShown.value * seekStaying()
          }
          .padding(horizontal = SEEK_ROW_SIDE)
          .fillMaxWidth()
          .onGloballyPositioned { seekRow[0] = it },
      )
      // Dragging the track, where the video will land shows large just above the row (#189).
      if (chromeAlpha > 0f) VideoDragTime(
        video,
        modifier = Modifier
          .then(aboveImage)
          .offset {
            val above = (SEEK_ROOM + DRAG_TIME_GAP + VIDEO_DRAG_TIME_HEIGHT).toPx()
            IntOffset(0, (sheetTop() - above).roundToInt())
          }
          .fillMaxWidth()
          .semantics { hideFromAccessibility() },
      )
      val lineAlpha by animateFloatAsState(if (!chromeShown && !closing) 1f else 0f, fadeSpec)
      VideoProgressLine(
        video,
        Modifier
          .align(Alignment.BottomCenter)
          .then(aboveImage)
          .graphicsLayer { alpha = lineAlpha }
          .semantics { hideFromAccessibility() },
      )
    }

    // The sheet, translated to its lift (never relaid out by it), the details' scroll nested into it. The sheet stays
    // across pages, so the scroll is the post's: each post's details start at the top (#286).
    val scroll = rememberSaveable(post.id, saver = ScrollState.Saver, key = viewerDetailsKey(post.id)) { ScrollState(0) }
    // At peek the details are below the window and transparent, yet composing and measuring them (a chip per tag) was
    // most of the frame a post is first shown in: a queue swipe's landing, a viewer's opening (#318). They come once the
    // opening has settled, a frame after the post shows, while nothing moves; or at once if the sheet lifts first
    // ([detailsComposed]). Composed in the opening's second frame instead, they cost a frame of the card's growth.
    var detailsFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(post.id, settled) {
      if (!settled) return@LaunchedEffect
      withFrameNanos {}
      detailsFor = post.id
    }
    val composeDetails by remember(post.id) { derivedStateOf { detailsComposed(detailsFor == post.id, sheet.lift) } }
    Column(
      Modifier
        .offset { IntOffset(0, sheetTop().roundToInt()) }
        .then(chrome)
        .fillMaxWidth()
        .height(with(density) { height.toDp() })
        .clip(RoundedCornerShape(topStart = lerp(SHEET_CORNER, 0.dp, fullFraction), topEnd = lerp(SHEET_CORNER, 0.dp, fullFraction)))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .viewerSheet(sheet),
    ) {
      SheetHeader(
        post = post,
        sheet = sheet,
        halfFraction = { halfFraction },
        fullFraction = { fullFraction },
        statusTop = with(density) { statusTop.toDp() },
        onThumbnail = { sheet.animateTo(0f) },
        onToggleFavourite = {
          haptics.performHapticFeedback(if (post.isFavorited) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
          // The first favourite made here teaches the long-press on the picture (#527).
          tips.offerLongPressTip(wasFavourited = post.isFavorited, ran = actions.toggleFavourite(post))
        },
        onVote = {
          haptics.performHapticFeedback(HapticFeedbackType.Confirm)
          actions.vote(post, it)
        },
        // ⋮ after ♡, in the peek row (#178): Keep for later over a grid (#504), Share, Save with its states, Open in
        // browser.
        menu = { PostMenu(post, shown = chromeShown, onKeep = keep?.let { keepShown -> { keepShown(post) } }) },
        modifier = Modifier.draggable(
          rememberDraggableState { sheet.dragBy(it) },
          Orientation.Vertical,
          enabled = !closing,
          onDragStopped = { sheet.settle(it) },
        ),
      )
      if (!composeDetails) Spacer(Modifier.weight(1f)) else PostDetails(
        post,
        scroll = scroll,
        showStats = false,
        showUploader = false,
        searchTags = searchTags,
        // At peek the sheet is its header alone (A′, #178): the details' first line would show in the band over the
        // navigation bar, so they fade in as the sheet lifts past it.
        modifier = Modifier.weight(1f).graphicsLayer {
          alpha = (sheet.lift / (navBottom + DETAILS_FADE_IN.toPx())).coerceIn(0f, 1f)
        },
      )
      // The details end above the navigation bar.
      Spacer(Modifier.height(with(density) { navBottom.toDp() }))
    }

    // The count pill (A′, #178): over the picture, 12 dp above the sheet (above a video's seek row), rising with the
    // sheet and sliding out with it when a tap or a zoom hides the chrome. Over a grid it starts and ends on the grid's
    // line, where the shell's pill is (see `docked` above), so it stays while opening and closing. It gives way while
    // a drag on a video's track shows the time in its place.
    // Settling on a video after a picture, or back, the pill moves between the two lines on the spring (#282).
    val seekRoom by animateFloatAsState(if (isVideo(post)) with(density) { SEEK_ROOM.toPx() } else 0f, spatialSpec())
    val scrubbing = video.draggedTo != null
    val pillAlpha by animateFloatAsState(if (scrubbing) 0f else 1f, fadeSpec)
    val rowHalf = with(density) { (BottomRowLayout.Height / 2).toPx() }
    val pillAbove = { with(density) { PILL_LINE.toPx() } + seekRoom }
    // Hidden, the sheet's top is the window's bottom, and the pill's row goes just past it.
    val pillLine = { sheetTop() - pillAbove() * chromeLayout + rowHalf * (1f - chromeLayout) }
    // Near full there's no picture left to float over: the pill fades out as the image does, before it reaches the top
    // row, and leaves the accessibility tree and takes no touches once it's all but gone.
    // At a compact height too: the top row floats over the picture there, and the pill mustn't pass under it.
    val pillRoom = { ((pillLine() - rowHalf - topRow) /with(density) { IMAGE_FADE_HEIGHT.toPx() }).coerceIn(0f, 1f) }
    val pillAway by remember(pillRoom) { derivedStateOf { pillRoom() < 0.05f } }
    // While the pill is away (with the chrome, or faded out near full), a close's Undo can't be reached: its window
    // waits.
    PauseUndoWhile(!chromeShown || pillAway)
    QueueBar(
      onOpenQueue = LocalOpenTabList.current,
      // Over a grid a sideways swipe steps posts: no swipe tip here (#538).
      swipesQueue = mode.swipesQueue,
      modifier = Modifier
        .passTouchesThrough(touchesPass || pillAway)
        .then(aboveImage)
        .then(if (scrubbing || pillAway) Modifier.semantics { hideFromAccessibility() } else chromeHidden)
        .graphicsLayer {
          val line = if (floatingBar) {
            alpha = (if (handedBack) 0f else 1f) * pillAlpha * (if (closing) 1f else pillRoom())
            // Rising from where the grid's pill had slid to; closing, it goes back to the grid's line.
            val from = gridLine + if (closing) 0f else slideFrom
            from + (pillLine() - from) * docked()
          } else {
            alpha = chromeFade() * pillAlpha * pillRoom()
            pillLine()
          }
          translationY = line - rowHalf
        },
    )

    // The ♡'s heart (#469), as on desktop (#419): over the picture's centre, drawn above the sheet, at peek, half or
    // full. Only the shown post's, so a toggle still running when a swipe settles on the next post doesn't pop on it.
    val currentId by rememberUpdatedState(post.id)
    val hearts = remember(actions) { actions.hearts.forShown { currentId } }
    val burstInset = with(density) { fittedPictureInset(seekRoom = if (isVideo(post)) SEEK_ROOM else 0.dp, compactHeight).toPx() }
    // A PictureBurstSize box over the picture's centre: the burst's, and the long-press tip's anchor.
    val overPictureCentre = Modifier
      .align(Alignment.TopCenter)
      .offset {
        val half = PictureBurstSize.toPx() / 2
        val centre = phoneBurstCentre(
          pictureTop = pictureTop * imageLayout,
          sheetTop = sheetTop(imageLayout).coerceAtMost(height),
          inset = burstInset * imageLayout,
          minCentre = statusTop + half,
        )
        IntOffset(0, (centre - half).roundToInt())
      }
    FavouriteBursts(hearts, overPictureCentre.then(aboveImage), size = PictureBurstSize)

    // The long-press's one-time tip (#527), where the burst plays, after the first ♡ that favourited here. It goes
    // with the chrome.
    if (tips.current == Tip.LongPressFavourite) {
      TipAnchor(
        Tip.LongPressFavourite,
        tips,
        visible = chromeShown && !closing && dismiss.atRest,
        modifier = overPictureCentre.size(PictureBurstSize),
      )
    }
  }
}

/**
 * TalkBack's and Switch Access's Favourite on the phone viewer's picture (#527), the long-press's: only while [post]
 * isn't a favourite, as the long-press only ever sets ♡ and unfavouriting stays on the sheet's.
 */
internal fun pictureFavouriteAction(post: PostDto, favourite: () -> Unit): List<CustomAccessibilityAction> =
  if (post.isFavorited) emptyList() else listOf(CustomAccessibilityAction(FAVOURITE_ACTION) { favourite(); true })

/** TalkBack's name for [pictureFavouriteAction]. */
internal const val FAVOURITE_ACTION = "Favourite"

/**
 * The picture's favourite (#527): its haptic, then [favourite], in the same call. The long-press calls it as it
 * completes, so the haptic comes with the burst whether the finger lifts at once or stays down (#578). It's Confirm,
 * the grid long-press's tick, not the ♡'s ToggleOn: Samsung plays ToggleOn as a soft 244 ms swell that a finger
 * lifting as the burst shows didn't feel (#578, the owner's phone).
 */
internal fun favouriteWithHaptic(haptics: HapticFeedback, favourite: () -> Unit) {
  haptics.performHapticFeedback(HapticFeedbackType.Confirm)
  favourite()
}

/**
 * Where the phone viewer's heart burst is centred, in px from the window's top (#469): the picture's centre, between
 * [pictureTop] and [sheetTop] less the [inset] kept clear under it. With the sheet near full there's no picture left,
 * so it stays at least at [minCentre], whole below the status bar.
 */
internal fun phoneBurstCentre(pictureTop: Float, sheetTop: Float, inset: Float, minCentre: Float): Float =
  maxOf(pictureTop + (sheetTop - inset - pictureTop) / 2f, minCentre)

/**
 * Whether a drag that starts by [dy] px (positive is down) closes the post rather than moving the sheet: only down, at
 * peek, at 1×, and where a close is [closable]. Over a grid ([ViewerMode.Overlay]) that's all; a lone post's drag down
 * also mustn't start on a video's seek row ([fromSeekRow], #181).
 */
internal fun dragDownCloses(dy: Float, atPeek: Boolean, zoomed: Boolean, mode: ViewerMode, closable: Boolean, fromSeekRow: Boolean): Boolean =
  dy > 0f && atPeek && !zoomed && closable && (mode == ViewerMode.Overlay || !fromSeekRow)

/**
 * The share of the window's height a lone post's drag down travels before letting go closes it (#235, "about 20%";
 * #181 had 30%, which the journey check #228 found needed too much arm over 40 kept posts). It stays well past the
 * viewer's 120 dp over a grid (about 14% of a 850 dp window), since closing a lone post drops it from the queue rather
 * than returning to a grid, and a fifth of the screen is still further than a drag that begins as a scroll or a
 * sheet lift would go by accident. A downward fling closes it sooner ([loneDragCloses]).
 */
internal const val LONE_CLOSE_SHARE = 0.2f

/**
 * Whether letting go of a lone post's drag down closes it: the picture has followed the finger [offset] px down (of
 * [height] px, the window's) and the finger leaves at [velocity] px/s (positive is down). Either past
 * [LONE_CLOSE_SHARE] of the [height], or a fling down faster than [minFling], the queue swipe's [MIN_FLING] in px;
 * anything else springs back. Android's minimum fling (about 50 dp/s) closed on almost any release still moving, so
 * springing back needed the finger to stop first (#308). A fling only counts with the picture below where it started,
 * so a drag brought back up past its start doesn't close.
 */
internal fun loneDragCloses(offset: Float, height: Float, velocity: Float, minFling: Float): Boolean =
  offset > 0f && (offset > height * LONE_CLOSE_SHARE || velocity > minFling)

/** TalkBack's name for a lone post's drag down ([PhoneViewer]'s `onDragClose`). */
private const val CLOSE_ACTION = "Close"

/** A closing GIF's or video's frame in the transition's overlay: over the shared image (0), under the chrome (1). */
private const val SAMPLE_Z = 0.5f

/**
 * The card in the transition's overlay: under the shared image (0), over the grid cell's own card. The overlay draws
 * a top-level shared element at 0 as at -1, and equal ones in the order they joined, so closing, the cell (back in the
 * grid after the viewer) drew its grey background over the black card from the first frame (#298).
 */
private const val CARD_Z = -0.5f

/**
 * Where a lone post came from, on its top line (#181): [label] ("Kept · from artist_x" for a kept post whose source is
 * in the queue, else the search it was opened from), and [open] going there.
 */
class PostOrigin(val label: String, val open: () -> Unit)

/** A lone post's [origin] on its top line: a pill over the image that goes there on a tap. */
@Composable
private fun OriginChip(origin: PostOrigin, wrap: Boolean = false) {
  Surface(
    onClick = origin.open,
    shape = if (wrap) RoundedCornerShape(20.dp) else CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
    // 48 dp tall to the touch, though drawn as small as the position pill it stands in for.
    modifier = Modifier.minimumInteractiveComponentSize().widthIn(max = if (wrap) Dp.Unspecified else ORIGIN_MAX_WIDTH),
  ) {
    Row(
      Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(bookmark, contentDescription = null, modifier = Modifier.size(14.dp))
      Text(origin.label, style = MaterialTheme.typography.labelMedium, maxLines = if (wrap) 3 else 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** The origin pill leaves the title at least a third of a phone's width. */
private val ORIGIN_MAX_WIDTH = 240.dp

/** How a drag down (or predictive back) at [fraction] of its range fades the chrome: gone a third of the way in. */
private fun dragFade(fraction: Float): Float = (1f - fraction * 3f).coerceAtLeast(0f)

/** The opening's progress at which the chrome starts to arrive, as the desktop's controls do at 90%. */
private const val CONTROLS_SHOWN_AT = 0.9f

/** A video's seek row's gap to the sheet's edge. */
private val SEEK_ROW_GAP = 12.dp

/** The room a video's seek row takes above the sheet, under the pill: its gap and its height. */
internal val SEEK_ROOM = SEEK_ROW_GAP + VIDEO_SEEK_ROW_HEIGHT

/**
 * A video's seek row's gap to the window's sides: its first and last icons then line up with the sheet's 16 dp content
 * edges, as an icon button's glyph sits 12 dp inside it (#180).
 */
private val SEEK_ROW_SIDE = 4.dp

/** The gap between the seek row and the time a drag on its track shows above it. */
private val DRAG_TIME_GAP = 16.dp

/** The sheet's top corners at peek and half; square once full. */
private val SHEET_CORNER = 28.dp

/**
 * The position pill ([positionLabel] after a grid glyph, read as [positionSpoken]) and the title over a fade from the
 * top, under the status bar. The words and glyph tell it from the tab pill's "n / N" (#136).
 */
@Composable
private fun TopRow(
  position: String?,
  positionSpoken: String?,
  origin: PostOrigin?,
  title: String?,
  titleAlpha: () -> Float,
  statusTop: Dp,
  modifier: Modifier,
) {
  // At a large font ("Kept · from artist_x" and the title can't share a line, #185) the title goes under the pills and
  // the origin pill wraps, so neither is cut off.
  val stacked = LocalDensity.current.fontScale >= STACK_TOP_ROW_AT
  Box(
    modifier
      .fillMaxWidth()
      .heightIn(min = statusTop + TOP_ROW)
      .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
  ) {
    val pills: @Composable () -> Unit = {
      if (position != null && positionSpoken != null) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
          modifier = Modifier.clearAndSetSemantics { contentDescription = positionSpoken },
        ) {
          Row(
            Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(grid_view, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(position, style = MaterialTheme.typography.labelMedium)
          }
        }
      }
      if (origin != null) OriginChip(origin, wrap = stacked)
    }
    // Over the image, so the variant colour of an untitled post is a dimmer white.
    val titleText: @Composable (Modifier) -> Unit = { titleModifier ->
      Text(
        title ?: PostTitle.UNTITLED,
        style = MaterialTheme.typography.titleSmall,
        color = if (title == null) Color.White.copy(alpha = 0.7f) else Color.White,
        maxLines = if (stacked) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = titleModifier.graphicsLayer { alpha = titleAlpha() },
      )
    }
    if (stacked) {
      Column(
        Modifier.padding(top = statusTop, start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { pills() }
        titleText(Modifier)
      }
    } else {
      Row(
        Modifier.padding(top = statusTop, start = 16.dp, end = 16.dp).height(TOP_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        pills()
        titleText(Modifier)
      }
    }
  }
}

/** From this font scale the top row stacks its title under the pills. */
private const val STACK_TOP_ROW_AT = 1.3f

/**
 * The sheet's header: the handle ([SheetHandle], a button for the sheet's stops), then from half up the title row (title and byline; at full also the post's 40 dp
 * thumbnail, which lowers the sheet back to the image), then the favourite and vote group with the post's [menu] (⋮)
 * after it. At peek only the handle and that row show, in [PEEK_HEADER].
 */
@Composable
private fun SheetHeader(
  post: PostDto,
  sheet: ViewerSheetState,
  halfFraction: () -> Float,
  fullFraction: () -> Float,
  statusTop: Dp,
  onThumbnail: () -> Unit,
  onToggleFavourite: () -> Unit,
  onVote: (Int?) -> Unit,
  menu: @Composable () -> Unit,
  modifier: Modifier,
  actions: PostActions = koinInject(),
  accessibility: AccessibilitySettings = koinInject(),
) {
  // Settings → Accessibility → Left-handed mirrors the group's row: ⋮, ♡, the stars (#528).
  val leftHanded by accessibility.leftHanded.collectAsStateWithLifecycle()
  // A failed vote or favourite shakes its control back (`.scratch/android-feedback/`, C, row 9).
  val voteRejected = rememberRejections(actions, Rejection.Vote(post.id))
  val favouriteRejected = rememberRejections(actions, Rejection.Favourite(post.id))
  val signedIn by actions.isSignedIn.collectAsStateWithLifecycle()
  Column(modifier.fillMaxWidth()) {
    // Full, the status bar and the title row read as the sheet's top bar, on the surface container.
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    Column(Modifier.drawBehind { drawRect(barColor.copy(alpha = fullFraction())) }) {
      // Full, the sheet reaches under the status bar.
      Spacer(Modifier.layout { _, constraints -> layout(constraints.maxWidth, (statusTop.roundToPx() * fullFraction()).roundToInt()) {} })
      SheetHandle(sheet, fullFraction)
      Box(
        Modifier
          .fillMaxWidth()
          .clipToBounds()
          .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(placeable.width, (placeable.height * halfFraction()).roundToInt()) { placeable.place(0, 0) }
          },
      ) {
        TitleRow(post, fullFraction, onThumbnail)
      }
    }
    // Full, the top bar's fill would touch the group without it (#104); at half and peek it's gone.
    Spacer(Modifier.layout { _, constraints -> layout(constraints.maxWidth, (STATS_GAP.roundToPx() * fullFraction()).roundToInt()) {} })
    PostStats(
      post,
      onToggleFavourite = onToggleFavourite,
      onVote = onVote,
      voteRejected = voteRejected,
      favouriteRejected = favouriteRejected,
      onSignIn = if (signedIn) null else actions::openSignIn,
      trailing = menu,
      // Five full 40 dp stars and ♡ without its count, so the peek row holds on any phone at any text size (#525).
      countsApart = true,
      mirrored = leftHanded,
      // ⋮'s 48 dp target reaches to 4 dp from the edge, its glyph lined up with the sheet's 16 dp content edge; at the
      // left edge when left-handed (#528).
      modifier = Modifier.padding(start = if (leftHanded) 4.dp else 16.dp, end = if (leftHanded) 16.dp else 4.dp, bottom = 16.dp),
    )
    // The counts, under the peek: they fade in as the sheet lifts, so at peek none shows over the gesture bar (owner's
    // option C, #525).
    PostCounts(post, Modifier.graphicsLayer { alpha = halfFraction() }.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
  }
}

/**
 * Half: the title with the byline at the end of the same line. Full: the thumbnail, then the title over the byline,
 * as the sheet's top bar.
 */
@Composable
internal fun TitleRow(post: PostDto, fullFraction: () -> Float, onThumbnail: () -> Unit) {
  val named = PostTitle.of(post)
  val title = named ?: PostTitle.UNTITLED
  val titleColor = if (named == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
  val byline = postByline(post)
  Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(
      Modifier
        .layout { measurable, constraints ->
          val placeable = measurable.measure(constraints)
          layout((placeable.width * fullFraction()).roundToInt(), placeable.height) { placeable.place(0, 0) }
        }
        .graphicsLayer { alpha = fullFraction() },
    ) {
      PostThumbnail(post, Modifier.padding(end = 12.dp).size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onThumbnail))
    }
    // At least 44 dp: the full sheet's title over the byline needs 40 sp, so large text makes it taller (#525).
    Box(Modifier.weight(1f).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
      Row(Modifier.graphicsLayer { alpha = 1f - fullFraction() }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = titleColor, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // Weighted too, so with large text the byline can't take the whole line from the title (#525).
        Text(
          byline,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
      Column(Modifier.graphicsLayer { alpha = fullFraction() }) {
        Text(title, color = titleColor, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
    }
  }
}

/** The post's sharpest copy already decoded (the sample, else the preview), else its preview from the cache. */
@Composable
private fun PostThumbnail(post: PostDto, modifier: Modifier, imageLoader: ImageLoader = koinInject()) {
  val context = LocalPlatformContext.current
  val decoded = remember(post.md5) { decodedInMemory(imageLoader, post.md5) }
  if (decoded != null) {
    Image(decoded.asPainter(context), contentDescription = "Back to the image", contentScale = ContentScale.Crop, modifier = modifier)
  } else {
    AsyncImage(
      model = postImageRequest(context, PostImage.of(post, Variant.Preview)),
      contentDescription = "Back to the image",
      contentScale = ContentScale.Crop,
      modifier = modifier,
    )
  }
}

/**
 * ⋮: Share (the post's sankakucomplex.com link through the system share sheet), Save (the original file to
 * `Download/Tessera`) and Open in browser, as the spec's Android section lists them. On the phone these are the only
 * Share and Save: the sheet's [PostDetails] leaves them out.
 *
 * Save's feedback is on ⋮ (`.scratch/android-feedback/`, C, rows 8 and 11): a ring while saving; "↗ Open" for the
 * window once saved, then ⋮ with a ✓ dot and "Open saved file" in the menu; "↻ Retry" for the window after a failure
 * (none when the post has no file), the reason on the top line. The window runs only while the chrome is [shown], so a
 * save that ends in immersive waits for it. A save that ends after ⋮ moved to another post goes to the top line.
 *
 * Over a grid ([onKeep], #504) a group of its own comes first: Keep for later, the cell's long-press, which answers on
 * the top line ("Kept · item N", "Already item N"). A lone post is a kept post already, and has no Keep.
 */
@Composable
internal fun PostMenu(
  post: PostDto,
  shown: Boolean,
  onKeep: (() -> Unit)? = null,
  saver: PostSaver = koinInject(),
  messages: Messages = koinInject(),
) {
  var open by remember { mutableStateOf(false) }
  val saving by saver.saving.collectAsStateWithLifecycle()
  val saved by saver.saved.collectAsStateWithLifecycle()
  val fresh by saver.fresh.collectAsStateWithLifecycle()
  DisposableEffect(post.id) {
    val release = saver.claim(post.id)
    // A "Saved" whose window hasn't ended moves to the top line, so Open stays one tap away.
    onDispose(release)
  }
  val file = saved[post.id]
  val outcome = fresh[post.id]?.takeUnless { it is SaveOutcome.Failed && it.noFile }
  // "No file" has nothing to retry: its reason on the top line is all.
  LaunchedEffect(fresh[post.id]) { if (fresh[post.id] != outcome) saver.settle(post.id) }
  val haptics = LocalHapticFeedback.current
  LaunchedEffect(outcome) { if (outcome is SaveOutcome.Saved) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
  val window = rememberActionWindowMillis()
  val chipInteractions = remember { MutableInteractionSource() }
  val chipPressed by chipInteractions.collectIsPressedAsState()
  val chipFocused by chipInteractions.collectIsFocusedAsState()
  var left by remember { mutableFloatStateOf(1f) }
  val showing by rememberUpdatedState(shown)
  val pressed by rememberUpdatedState(chipPressed || chipFocused)
  LaunchedEffect(outcome) {
    if (outcome == null) return@LaunchedEffect
    runWindow(window, isPaused = { !showing || pressed }) { left = it }
    saver.settle(post.id)
  }
  val openFile = file?.let { f -> { f.open() } }
  Box {
    when (outcome) {
      is SaveOutcome.Saved -> CornerActionChip(
        open_in_new,
        "Open",
        error = false,
        onClick = {
          saver.settle(post.id)
          outcome.file.open()
        },
        left = { left },
        interactionSource = chipInteractions,
      )
      is SaveOutcome.Failed -> CornerActionChip(
        refresh,
        "Retry",
        error = true,
        onClick = { saver.save(post) },
        left = { left },
        interactionSource = chipInteractions,
      )
      null -> MenuButton(
        state = when {
          post.id in saving -> MenuSaveState.Saving
          file != null -> MenuSaveState.Saved
          else -> MenuSaveState.Idle
        },
        onClick = { open = true },
        description = if (post.id in saving) "More, saving" else "More",
        modifier = if (openFile != null) {
          Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Open saved file") { openFile(); true }) }
        } else {
          Modifier
        },
      )
    }
    LiveAnnouncement(if (outcome is SaveOutcome.Saved) "Saved. Open available" else "")
    GroupedMenu(expanded = open, onDismissRequest = { open = false }) {
      val groups = if (onKeep != null) 2 else 1
      if (onKeep != null) {
        MenuGroup(0, groups) {
          MenuAction(
            text = { Text(KEEP_FOR_LATER) },
            leadingIcon = { Icon(bookmark, contentDescription = null) },
            onClick = {
              open = false
              onKeep()
            },
          )
        }
      }
      MenuGroup(groups - 1, groups) {
        MenuAction(
          text = { Text("Share") },
          leadingIcon = { Icon(share, contentDescription = null) },
          onClick = {
            open = false
            platformShareText(postPageUrl(post))
          },
        )
        if (openFile != null) {
          MenuAction(
            text = { Text("Open saved file") },
            leadingIcon = { Icon(open_in_new, contentDescription = null) },
            onClick = {
              open = false
              openFile()
            },
          )
        } else {
          MenuAction(
            // "Saving…" is ⋮'s ring alone (notes.md, open question 3).
            text = { Text("Save") },
            leadingIcon = { Icon(download, contentDescription = null) },
            onClick = {
              open = false
              saver.save(post)
            },
          )
        }
        MenuAction(
          text = { Text("Open in browser") },
          leadingIcon = { Icon(open_in_new, contentDescription = null) },
          onClick = {
            open = false
            messages.openUrl(postPageUrl(post))
          },
        )
      }
    }
  }
}

/**
 * While [enabled], nothing in this takes touches: they reach what's under it. Compose hit-tests children outside
 * their parent's bounds, so only a clipping layer stops it, and that clips the drawing too. Enable it only while the
 * content is drawn in a running shared transition's overlay (a shared element, or `renderInSharedTransitionScopeOverlay`),
 * which the clip doesn't reach.
 */
private fun Modifier.passTouchesThrough(enabled: Boolean): Modifier =
  // Always there, only switched: a node added mid-chain as the close starts would make the shared bounds after it anew.
  graphicsLayer {
    clip = enabled
    shape = if (enabled) NoArea else RectangleShape
  }

/** An outline with no area: a layer clipped to it hits nothing. */
private val NoArea = object : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) = Outline.Rectangle(Rect.Zero)
}

/**
 * Whether the sheet composes its details (#318): once the post has been shown for a frame ([shownAFrame]), or as soon
 * as the sheet lifts off peek ([lift] px), where they'd start to show.
 */
internal fun detailsComposed(shownAFrame: Boolean, lift: Float): Boolean = shownAFrame || lift > 0f

/**
 * [value], or the instance this call returned before while that is still equal to it (#318): `remember` compares its
 * key by equality. A grid's `postAt` merges a post's override into a new copy on every call, and the viewer recomposes
 * each frame of an opening or a close: a new but equal post recomposed the sheet's details, a chip per tag, every frame
 * of a close.
 */
@Composable
private fun <T> sameWhileEqual(value: T): T = remember(value) { value }

/** How long the zoom level stays after the zoom last changed. */
private const val ZOOM_LEVEL_SHOWN_MS = 1200L

/**
 * The saved-state key of the post [id]'s details scroll in the viewer (#560): a key of its own, as the sheet's stop
 * has ([viewerSheetKey]), since a grid's viewer is keyed on an object that's new when its tab comes back.
 */
internal fun viewerDetailsKey(id: String): String = "viewer-details-$id"

/** "2.5×": the zoom to one decimal. */
internal fun zoomLevelLabel(level: Float): String = "${(level * 10).roundToInt() / 10.0}×"

/**
 * A neighbouring lone post's picture in the shown one's room, during a queue swipe between them (#263): fitted above
 * its own inset ([insetPx]) as its viewer will fit it, so it stays put when it's shown; else its kind and title. In
 * another block it carries "Leaving …" on its edge facing the shown post ([forward]: it comes in from the end, #501).
 */
@Composable
private fun NeighbourPicture(peek: PicturePeek, forward: Boolean, insetPx: () -> Float, modifier: Modifier) {
  val context = LocalPlatformContext.current
  Box(modifier.fillMaxSize().semantics { hideFromAccessibility() }) {
    Box(
      Modifier.layout { measurable, constraints ->
        val pictureHeight = (constraints.maxHeight - insetPx().roundToInt()).coerceIn(1, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, pictureHeight))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
      },
      contentAlignment = Alignment.Center,
    ) {
      val loaded = peek.picture
      if (loaded != null) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val (width, height) = fittedBox(maxWidth, maxHeight, aspectRatio(loaded.post))
          Image(loaded.picture.asPainter(context), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size(width, height))
        }
      } else {
        Column(
          Modifier.padding(horizontal = 32.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(peek.kind, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
          Text(
            peek.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    peek.leaving?.let { LeavingEdge(it, forward) }
  }
}

/**
 * The status and navigation bar insets (px) a [PhoneViewer] last saw with its bars shown, in this window. A viewer
 * hides the bars with its chrome, which takes their insets away; the next viewer and the queue swipe's peek
 * ([LonePostPeek]) lay out with these, so neither jumps when the bars come back (#263). Each viewer writes what it sees
 * while it shows the bars, so a smaller inset (gesture navigation after three buttons) replaces a larger one, and a
 * new window size (rotation, split screen) starts afresh ([rememberBarInsets]).
 */
@Stable
class BarInsets(statusTop: Int = 0, navBottom: Int = 0) {
  var statusTop by mutableIntStateOf(statusTop)
    internal set
  var navBottom by mutableIntStateOf(navBottom)
    internal set
}

/** The window's [BarInsets], provided by the shell's queue swipe host; null elsewhere (the viewer over a grid). */
val LocalBarInsets = staticCompositionLocalOf<BarInsets?> { null }

/** A [BarInsets] for this window's size: a rotation or a split screen starts a new one. */
@Composable
fun rememberBarInsets(): BarInsets {
  val size = LocalWindowInfo.current.containerSize
  return remember(size) { BarInsets() }
}

/**
 * Touch: the lone post [post] as its first frame draws it, for the queue swipe's peek (#263, option A): [picture]
 * (decoded already, so no request) fitted where [PhoneViewer] fits it with the chrome shown and the sheet at peek, its
 * top row with [origin] ("Kept · from artist_x") and title, and the sheet's peek band under it. So the next item comes
 * in as itself, with no black frame, and landing on it changes nothing on screen but the sheet's controls. Its room
 * is worked out as [PhoneViewer] works it out: keep the two in step.
 */
@Composable
internal fun LonePostPeek(
  post: PostDto,
  picture: coil3.Image,
  origin: PostOrigin?,
  seenBars: BarInsets?,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val context = LocalPlatformContext.current
  BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).semantics { hideFromAccessibility() }) {
    val height = constraints.maxHeight.toFloat()
    // The insets the landed post lays out with: the window's seen ones, as the post shown now may hide the bars.
    val statusTop = max(seenBars?.statusTop ?: 0, WindowInsets.statusBars.getTop(density))
    val navBottom = max(seenBars?.navBottom ?: 0, WindowInsets.navigationBars.getBottom(density))
    with(density) {
      val compactHeight = isCompactHeight(maxHeight)
      val pictureTop = pictureTop(statusTop, compactHeight)
      val peekTop = height - PEEK_HEADER.toPx() - navBottom
      val roomHeight = (peekTop.coerceAtMost(height) - pictureTop).roundToInt().coerceAtLeast(1)
      val inset = fittedPictureInset(seekRoom = if (isVideo(post)) SEEK_ROOM else 0.dp, compactHeight).toPx().roundToInt()
      val pictureHeight = (roomHeight - inset).coerceIn(1, roomHeight)
      BoxWithConstraints(
        Modifier.offset { IntOffset(0, pictureTop.roundToInt()) }.fillMaxWidth().height(pictureHeight.toDp()),
        contentAlignment = Alignment.Center,
      ) {
        val (width, boxHeight) = fittedBox(maxWidth, maxHeight, aspectRatio(post))
        Image(picture.asPainter(context), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size(width, boxHeight))
      }
      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height((height - peekTop).coerceAtLeast(0f).toDp())
          .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
          .background(MaterialTheme.colorScheme.surfaceContainerLow),
      )
      TopRow(
        position = null,
        positionSpoken = null,
        origin = origin,
        title = PostTitle.of(post),
        titleAlpha = { 1f },
        statusTop = statusTop.toDp(),
        modifier = Modifier,
      )
    }
  }
}
