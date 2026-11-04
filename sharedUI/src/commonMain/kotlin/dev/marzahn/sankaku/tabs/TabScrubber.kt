package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.platformGestureExclusion
import kotlin.math.roundToInt

/**
 * The tab list's fast scroll (#132, `.scratch/ux-review/` finding 6, A): a thumb at the right end that follows the
 * list, shown only when its [rowCount] tab rows of [rowHeight] run past about two screens. Its track spans every item
 * of the list, Recently closed included. Dragging the thumb moves through them in proportion and shows a bubble with
 * [label] of the item at the thumb ("90 / 123"). Only the thumb takes touches, in a [SCRUBBER_WIDTH] strip the rows
 * leave clear, so their swipes and ≡ work everywhere. TalkBack users scroll the list itself and keep each row's
 * actions; the thumb has no semantics of its own.
 */
@Composable
internal fun TabScrubber(
  list: LazyListState,
  rowCount: Int,
  rowHeight: Dp,
  label: (item: Int) -> String,
  modifier: Modifier = Modifier,
) {
  val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
  val thumbPx = with(LocalDensity.current) { THUMB_HEIGHT.toPx() }
  val rows by rememberUpdatedState(rowCount)
  var track by remember { mutableIntStateOf(0) }
  // The rows a screen of the list holds, at least one.
  val perScreen by remember(rowPx) {
    derivedStateOf { (list.layoutInfo.viewportSize.height / rowPx).toInt().coerceAtLeast(1) }
  }
  val long by remember { derivedStateOf { rows > perScreen * SCREENS } }
  // Everything the list holds, Recently closed included: the thumb's track spans the whole list.
  val items by remember { derivedStateOf { list.layoutInfo.totalItemsCount.coerceAtLeast(1) } }
  // Where the list is, 0 at the top and 1 with the last item at the bottom.
  val scrolled by remember(rowPx) {
    derivedStateOf {
      val first = list.firstVisibleItemIndex + list.firstVisibleItemScrollOffset / rowPx
      (first / (items - perScreen).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
  }
  // The thumb's top while it's dragged, in px; null otherwise.
  var dragged by remember { mutableStateOf<Float?>(null) }
  Box(modifier.onSizeChanged { track = it.height }) {
    if (!long || track == 0) return@Box
    val room = (track - thumbPx).coerceAtLeast(1f)
    val top = dragged ?: (scrolled * room)
    val fraction = top / room
    // The item at the thumb's height in the list: the first at the top, the last at the bottom.
    val row = (fraction * (items - 1)).roundToInt()
    if (dragged != null) {
      Box(
        Modifier
          .align(Alignment.TopEnd)
          .offset { IntOffset(0, (top + thumbPx / 2).roundToInt()) }
          .offset(y = -BUBBLE_HEIGHT / 2)
          .padding(end = SCRUBBER_WIDTH + 12.dp)
          .height(BUBBLE_HEIGHT)
          .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(BUBBLE_HEIGHT / 2))
          .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          label(row),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }
    Box(
      Modifier
        .align(Alignment.TopEnd)
        .offset { IntOffset(0, top.roundToInt()) }
        .size(width = SCRUBBER_WIDTH, height = THUMB_HEIGHT)
        .testTag(SCRUBBER_TAG)
        .platformGestureExclusion()
        .pointerInput(room) {
          detectVerticalDragGestures(
            onDragStart = { dragged = scrolled * room },
            onDragEnd = { dragged = null },
            onDragCancel = { dragged = null },
            onVerticalDrag = { change, dy ->
              change.consume()
              val now = ((dragged ?: 0f) + dy).coerceIn(0f, room)
              dragged = now
              // The row at the thumb lands at the same height on screen: near the top at the top, and so on.
              val at = now / room
              val target = (at * (items - 1)).roundToInt()
              list.requestScrollToItem((target - (at * (perScreen - 1)).roundToInt()).coerceIn(0, items - 1))
            },
          )
        },
      contentAlignment = Alignment.CenterEnd,
    ) {
      Box(
        Modifier
          .padding(end = 2.dp)
          .width(if (dragged != null) 8.dp else 6.dp)
          .height(THUMB_HEIGHT)
          .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
      )
    }
  }
}

/** The thumb's test tag; tags aren't read out. */
internal const val SCRUBBER_TAG = "tab-scrubber"

private val THUMB_HEIGHT = 48.dp

/** The thumb's touch target at the right end, wider than it's drawn. The rows keep their ≡ clear of it. */
internal val SCRUBBER_WIDTH = 24.dp
private val BUBBLE_HEIGHT = 44.dp

/** The list takes a scrubber once its rows run past this many screens. */
private const val SCREENS = 2
