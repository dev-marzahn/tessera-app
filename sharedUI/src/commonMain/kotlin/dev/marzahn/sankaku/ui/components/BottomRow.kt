package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Touch: where the phone's bottom pieces sit. R2 (`.scratch/android-grid-top/`) put ✕, the pill ‹ n / N › and ↑ Top
 * (or a post's ⋮) on one centre line; #178 retired that three-piece row for the count pill ([PillRow]), which keeps
 * R2's line and its middle, with ↑ Top still in the right corner and ✕ in the left one when queue buttons are shown.
 * ⋮ moved into the post sheet's peek row.
 */
object BottomRowLayout {
  /**
   * The row's height: ✕'s 56 dp (#118). The pill's, Undo's and ↑ Top's 48 dp (#261) sit on the same middle. It's the
   * controls' height for the post viewer too, which keeps the fitted picture clear of the row by it
   * (`fittedPictureInset`, #260): a piece of the row taller than this needs it raised.
   */
  val Height: Dp = 56.dp

  /** The centre line's height above the navigation bar's inset (R2: y 859 on a 915 dp phone with a 24 dp inset). */
  val CentreLine: Dp = 32.dp

  /**
   * The room under the row: the [navigationBar]'s inset and the 4 dp that put the 56 dp row's middle on [CentreLine].
   * The line is where R2 put it; only the pieces shrank to 56 dp around it (#118).
   */
  fun roomAbove(navigationBar: Dp): Dp = navigationBar + CentreLine - Height / 2

  /** [roomAbove] this window's navigation bar now. */
  val WindowRoom: Dp
    @Composable get() = with(LocalDensity.current) { roomAbove(WindowInsets.navigationBars.getBottom(this).toDp()) }

  /** A corner piece's middle (✕ on the left, ↑ Top on the right), in from its screen edge. */
  val CornerCentre: Dp = 44.dp

  /** ✕, the floating corner button on the left with queue buttons on: round, FAB sized. */
  val CornerButton: Dp = 56.dp

  /**
   * The touch target's height of the count pill, Undo and ↑ Top (#261), each centred on [CentreLine]: shorter than
   * [Height], so the row's height (and the viewer's inset) stays ✕'s.
   */
  val PieceTarget: Dp = 48.dp

  /** How tall the pill, Undo and ↑ Top are drawn inside [PieceTarget]. */
  val PieceDrawn: Dp = 40.dp

  /**
   * Where ↑ Top goes, aligned to the bottom end of a box that ends at the navigation bar's top, [room] above that
   * box's bottom ([WindowRoom], less what the box already keeps clear): its middle [CornerCentre] in from the edge,
   * and on [CentreLine], as a [Height] tall piece on [room] would be.
   */
  fun topButtonPadding(room: Dp): PaddingValues =
    PaddingValues(end = CornerCentre - PieceTarget / 2, bottom = room + (Height - PieceTarget) / 2)

  /** The window's sides the corners keep in from: a side navigation bar or a cutout, in landscape. */
  val SideInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
}

/**
 * The count pill's row (#178): [pill] centred, [undo] against the pill's left side with [PillGap] between them, so it
 * grows out of the pill leftwards while the pill keeps its place, and [start] (✕, with queue buttons on) with its
 * middle [BottomRowLayout.CornerCentre] in from the left edge; where [undo] wouldn't fit between them it goes above
 * [start]. Each is centred on the row's line: the middle of the
 * row's height but the [bottom] room under it ([BottomRowLayout.roomAbove] for the floating row, which lets the pill
 * slide out past it as it hides on scroll). Fills the width; the gaps take no touches.
 */
@Composable
fun PillRow(
  modifier: Modifier = Modifier,
  bottom: Dp = 0.dp,
  start: @Composable () -> Unit = {},
  undo: @Composable () -> Unit = {},
  pill: @Composable () -> Unit,
) {
  // Kept clear of a side navigation bar or a cutout in landscape; nothing in portrait.
  Layout(contents = listOf(start, undo, pill), modifier = modifier.windowInsetsPadding(BottomRowLayout.SideInsets)) { (starts, undos, pills), constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
    val placedStarts = starts.map { it.measure(loose) }
    val placedPills = pills.map { it.measure(loose) }
    val placedUndos = undos.map { it.measure(loose) }
    val width = constraints.maxWidth
    val bottomPx = bottom.roundToPx()
    val tallest = (placedStarts + placedPills + placedUndos).maxOfOrNull { it.height } ?: 0
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else tallest + bottomPx
    val line = (height - bottomPx) / 2
    val corner = BottomRowLayout.CornerCentre.roundToPx()
    val pillLeft = (width - (placedPills.maxOfOrNull { it.width } ?: 0)) / 2
    val startRight = placedStarts.maxOfOrNull { corner + it.width / 2 }
    layout(width, height) {
      placedStarts.forEach { it.placeRelative(corner - it.width / 2, line - it.height / 2) }
      placedPills.forEach { it.placeRelative((width - it.width) / 2, line - it.height / 2) }
      placedUndos.forEach {
        val x = pillLeft - PillGap.roundToPx() - it.width
        if (startRight == null || x >= startRight) {
          it.placeRelative(x, line - it.height / 2)
        } else {
          // No room between ✕ and a wide pill (a long count, a narrow phone): above ✕ instead, never over either.
          val above = (startRight - corner) * 2
          it.placeRelative(corner - it.width / 2, line - above / 2 - PillGap.roundToPx() - it.height)
        }
      }
    }
  }
}

/** The gap between the count pill and Undo beside it. */
val PillGap: Dp = 8.dp
