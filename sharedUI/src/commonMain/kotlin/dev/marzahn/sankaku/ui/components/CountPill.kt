package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.fonts.icons.chevron_left
import com.google.fonts.icons.chevron_right
import com.google.fonts.icons.undo
import com.google.fonts.icons.view_agenda

/*
 * Touch: the count pill that replaced the phone's tab bar (#178, option A′ of `.scratch/bar-less/`), and Undo beside it.
 */

/**
 * The count pill, "▤ 2 / 9": where the current item is in the queue, as [text] says it. A tap opens the queue page
 * ([onOpen]). A hold reopens the most recently closed item ([onReopen], true when there was one), as a hold on ✕ did
 * (`.scratch/android-undo/`, E2): while held, ▤ turns to ↶ and the pill fills to the long-press timeout, then a confirm
 * haptic, or the "reject" haptic with nothing to reopen. While [canReopen], a ↶ badge sits on its top-right corner.
 *
 * With [arrows] ("Show queue buttons"), ‹ and › sit inside the pill around the count and step to the item on either
 * side ([onPrevious], [onNext]; each on only as [previousEnabled] and [nextEnabled] say: past the last item › brings
 * the Up next card, and on the card ‹ goes back), named [previousLabel] and [nextLabel].
 *
 * It's drawn 40 dp tall on the primary container, floating on a FAB's shadow, in a 48 dp touch target. TalkBack reads
 * [CountPillText.spoken] ("Queue, item 2 of 9") and gets [actions] as its custom actions, with the hold's when it can
 * reopen.
 * [tip] is drawn over the count.
 *
 * What it says is [text]: "▤ 2 / 9", or a block's place, "▤ Landscape · 3 / 8" (#501), with the source cut short
 * before the figures ever are.
 */
@Composable
fun CountPill(
  text: CountPillText,
  onOpen: () -> Unit,
  canReopen: Boolean,
  onReopen: () -> Boolean,
  actions: List<CustomAccessibilityAction>,
  modifier: Modifier = Modifier,
  arrows: Boolean = false,
  onPrevious: () -> Unit = {},
  onNext: () -> Unit = {},
  previousLabel: String = "",
  nextLabel: String = "",
  previousEnabled: Boolean = false,
  nextEnabled: Boolean = false,
  tip: @Composable BoxScope.() -> Unit = {},
) {
  val haptics = LocalHapticFeedback.current
  val presses = remember { MutableInteractionSource() }
  val hold = rememberHold(presses, active = canReopen)
  val reopen = {
    haptics.performHapticFeedback(if (onReopen()) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
  }
  val description = text.spoken + if (canReopen) ", closed items to reopen" else ""
  val allActions = actions + listOfNotNull(CustomAccessibilityAction(REOPEN_LABEL) { onReopen() }.takeIf { canReopen })
  val colors = MaterialTheme.colorScheme
  BadgedBox(badge = { if (canReopen) ReopenBadge() }, modifier = modifier) {
    // The pill's 40 dp drawn in a 48 dp row, so its pieces keep 48 dp targets reaching 4 dp past it.
    Box(Modifier.height(BottomRowLayout.PieceTarget), contentAlignment = Alignment.Center) {
      Surface(shape = CircleShape, color = colors.primaryContainer, shadowElevation = FabElevation, modifier = Modifier.matchParentSize().padding(vertical = 4.dp)) {}
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(BottomRowLayout.PieceTarget)) {
        if (arrows) PillArrow(chevron_left, previousLabel, enabled = previousEnabled, onClick = onPrevious)
        Box(
          Modifier
            .fillMaxHeight()
            .widthIn(min = if (arrows) 48.dp else 64.dp)
            .combinedClickable(
              interactionSource = presses,
              indication = null,
              role = Role.Button,
              // Our own haptics: confirm or reject once the hold has done its work, not the long-press buzz.
              hapticFeedbackEnabled = false,
              onClickLabel = "Show queue",
              onLongClickLabel = if (canReopen) REOPEN_LABEL else null,
              onLongClick = reopen,
              onClick = onOpen,
            )
            .semantics {
              contentDescription = description
              customActions = allActions
            },
          contentAlignment = Alignment.Center,
        ) {
          val fill = colors.onPrimaryContainer.copy(alpha = 0.16f)
          // The ripple and the hold's fill keep to the pill's 40 dp.
          Box(
            Modifier
              .matchParentSize()
              .padding(vertical = 4.dp)
              .clip(CircleShape)
              .indication(presses, ripple())
              .drawBehind { if (hold.held) drawRect(fill, size = Size(size.width * hold.progress, size.height)) },
          )
          Row(
            Modifier.padding(horizontal = if (arrows) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            // Between ‹ and › the count needs no ▤ to say it's the queue, and leaves Undo room beside ✕; ↶ still shows
            // during a hold.
            if (!arrows || hold.showsReopen) {
              Icon(if (hold.showsReopen) undo else view_agenda, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(18.dp))
            }
            if (text.source != null) {
              Text(
                text.source,
                style = PillSourceStyle,
                color = colors.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Measured after the figures, so a pill given less room (Undo's, #581) shortens the name, not them.
                modifier = Modifier.weight(1f, fill = false).widthIn(max = PillSourceWidth),
              )
              Text("·", style = PillSourceStyle, color = colors.onPrimaryContainer.copy(alpha = 0.6f), maxLines = 1)
            }
            Text(text.figures, style = PillCountStyle, color = colors.onPrimaryContainer, maxLines = 1)
          }
          tip()
        }
        if (arrows) PillArrow(chevron_right, nextLabel, enabled = nextEnabled, onClick = onNext)
      }
    }
  }
}

/** ‹ or › inside the pill: a 48 dp square target, its ripple a 40 dp circle. */
@Composable
private fun PillArrow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
  val presses = remember { MutableInteractionSource() }
  Box(
    Modifier
      .size(48.dp)
      .clickable(presses, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.size(40.dp).clip(CircleShape).indication(presses, ripple()))
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.alpha(if (enabled) 1f else 0.38f))
  }
}

/** 15 sp semibold with tabular figures, so the pill doesn't change width as the count ticks up. */
private val PillCountStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

/** The block's name before the figures: lighter, so the figures read first. */
private val PillSourceStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

/** About 12 characters of a block's name at 15 sp before it ends in "…". */
private val PillSourceWidth = 112.dp

/**
 * What the count pill says (#501): [figures] ("2 / 9", "3 / 8", "8 kept", "Following"), after a block's [source]
 * ("Landscape") when there is one, and [spoken] for TalkBack ("Queue, Landscape, kept post 3 of 8, item 5 of 11").
 */
data class CountPillText(val source: String?, val figures: String, val spoken: String)

/**
 * The count [pill] with [line] ([BlockEndLine]) centred over it when there is one, which takes no room in the row: the
 * pill keeps its place on the bottom line whether the line shows or not, and [modifier] (the slide on scrolling) moves
 * both. The line is drawn outside this layout's bounds, so it's for a slot as wide as the window with the pill centred
 * in it ([PillRow]'s): the line's width is kept to that slot's less [BlockEndLineMargin] on each side. It takes no
 * touches.
 */
@Composable
fun PillWithLine(line: String?, modifier: Modifier = Modifier, pill: @Composable () -> Unit) {
  Layout(
    contents = listOf(pill, { if (line != null) BlockEndLine(line) }),
    modifier = modifier,
  ) { (pills, lines), constraints ->
    val placedPill = pills.single().measure(constraints)
    val gap = 4.dp.roundToPx()
    val room = (constraints.maxWidth - 2 * BlockEndLineMargin.roundToPx()).coerceAtLeast(0)
    val placedLine = lines.firstOrNull()?.measure(Constraints(maxWidth = room))
    layout(placedPill.width, placedPill.height) {
      placedPill.placeRelative(0, 0)
      placedLine?.placeRelative((placedPill.width - placedLine.width) / 2, -placedLine.height - gap)
    }
  }
}

/** The room [PillWithLine] keeps its line from each side of the slot it's given (the window's, in [PillRow]). */
private val BlockEndLineMargin = 16.dp

/**
 * The line over the count pill on a block's last item (#501), "Last from Landscape · next: Cat": the next swipe leaves
 * the block. A small tonal chip, text only; TalkBack hears it in the pane title and the pill's description instead.
 */
@Composable
fun BlockEndLine(text: String, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Surface(
    shape = CircleShape,
    color = colors.surfaceContainerHighest,
    contentColor = colors.onSurface,
    shadowElevation = FabElevation,
    modifier = modifier.clearAndSetSemantics {},
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

/**
 * Undo after a close (#178; `.scratch/android-feedback/`, C): ↶ in a 40 dp circle on the inverse surface, in a 48 dp
 * target, growing out of the pill's left side ([PillRow]) as [visibleState] turns true. A state rather than a
 * Boolean, so a bar composed after the close can still grow it, and one composed later shows it at once (#285). The
 * window's [left] runs as a ring around ↶. [interactionSource] lets the window pause while it's pressed or focused.
 * TalkBack calls it [UNDO_CLOSE_LABEL]; the pill has the same custom action.
 *
 * Always this one form, in the accent a snackbar's action has (inverse primary) for ↶ and the ring (#581). It was
 * "↶ Undo" where the word fitted beside the pill and ↶ alone where it didn't, so it changed with the block's title on
 * the pill (#501), the phone's width and the text size, in two colourings, and read as two different controls.
 */
@Composable
fun UndoChip(
  visibleState: MutableTransitionState<Boolean>,
  onUndo: () -> Unit,
  left: () -> Float,
  interactionSource: MutableInteractionSource,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visibleState,
    modifier = modifier,
    enter = expandHorizontally(spatialSpec(), expandFrom = Alignment.End) + fadeIn(effectsSpec()),
    exit = shrinkHorizontally(spatialSpec(), shrinkTowards = Alignment.End) + fadeOut(effectsSpec()),
  ) {
    UndoChipBody(onUndo, left, interactionSource)
  }
}

/** [UndoChip]'s circle. */
@Composable
private fun UndoChipBody(onUndo: () -> Unit, left: () -> Float, interactionSource: MutableInteractionSource) {
  val colors = MaterialTheme.colorScheme
  Box(
    Modifier
      .size(BottomRowLayout.PieceTarget)
      .clickable(interactionSource, indication = null, role = Role.Button, onClick = onUndo)
      .semantics { contentDescription = UNDO_CLOSE_LABEL },
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = CircleShape,
      color = colors.inverseSurface,
      // ↶ and the ring take this, the accent of a snackbar's action (#581).
      contentColor = colors.inversePrimary,
      shadowElevation = FabElevation,
      modifier = Modifier.clearAndSetSemantics {}.size(BottomRowLayout.PieceDrawn),
    ) {
      Box(
        Modifier
          .clip(CircleShape)
          .indication(interactionSource, ripple())
          .countdownRing(left, inset = 2.dp),
        contentAlignment = Alignment.Center,
      ) {
        Icon(undo, contentDescription = null, modifier = Modifier.size(20.dp))
      }
    }
  }
}

/** What TalkBack calls Undo after a close, on the chip and as the count pill's custom action. */
const val UNDO_CLOSE_LABEL = "Undo close"