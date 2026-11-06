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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
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
 * The count pill, "▤ 2 / 9": the current item's [position] in the queue of [count]. A tap opens the queue page
 * ([onOpen]). A hold reopens the most recently closed item ([onReopen], true when there was one), as a hold on ✕ did
 * (`.scratch/android-undo/`, E2): while held, ▤ turns to ↶ and the pill fills to the long-press timeout, then a confirm
 * haptic, or the "reject" haptic with nothing to reopen. While [canReopen], a ↶ badge sits on its top-right corner.
 *
 * With [arrows] ("Show queue buttons"), ‹ and › sit inside the pill around the count and step to the item on either
 * side ([onPrevious], [onNext]; off at the ends unless [previousEnabled] or [nextEnabled] say otherwise: past the last
 * item › brings the Up next card, and on the card ‹ goes back), named [previousLabel] and [nextLabel].
 *
 * It's drawn 40 dp tall on the primary container, floating on a FAB's shadow, in a 48 dp touch target. TalkBack reads
 * the count as "Queue, item 2 of 9" and gets [actions] as its custom actions, with the hold's when it can reopen.
 * [tip] is drawn over the count.
 *
 * On a [feed] (Following, For you), which stands after the [count] items and isn't one of them (#229), the pill says
 * the feed's name instead of a position: "▤ Following", read as "Queue, Following, after 9 items".
 */
@Composable
fun CountPill(
  position: Int,
  count: Int,
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
  previousEnabled: Boolean = position > 1,
  nextEnabled: Boolean = position < count,
  feed: String? = null,
  tip: @Composable BoxScope.() -> Unit = {},
) {
  val haptics = LocalHapticFeedback.current
  val presses = remember { MutableInteractionSource() }
  val hold = rememberHold(presses, active = canReopen)
  val reopen = {
    haptics.performHapticFeedback(if (onReopen()) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
  }
  val where = if (feed != null) "$feed, after $count ${if (count == 1) "item" else "items"}" else "item $position of $count"
  val description = "Queue, $where" + if (canReopen) ", closed items to reopen" else ""
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
            Text(feed ?: "$position / $count", style = PillCountStyle, color = colors.onPrimaryContainer, maxLines = 1)
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

/**
 * "↶ Undo" after a close (#178; `.scratch/android-feedback/`, C): on the inverse surface, as a snackbar's action is,
 * 40 dp tall in a 48 dp target, growing out of the pill's left side ([PillRow]) as [visibleState] turns true. A state
 * rather than a Boolean, so a bar composed after the close can still grow it, and one composed later shows it at once
 * (#285). The window's [left]
 * runs as a line along its bottom; [compact] (beside ✕, with queue buttons on, where there's less room) it's ↶ alone in
 * a 48 dp circle with the window as a ring. [interactionSource] lets the window pause while it's pressed or focused.
 * TalkBack calls it [UNDO_CLOSE_LABEL]; the pill has the same custom action.
 */
@Composable
fun UndoChip(
  visibleState: MutableTransitionState<Boolean>,
  onUndo: () -> Unit,
  left: () -> Float,
  interactionSource: MutableInteractionSource,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  AnimatedVisibility(
    visibleState,
    modifier = modifier,
    enter = expandHorizontally(spatialSpec(), expandFrom = Alignment.End) + fadeIn(effectsSpec()),
    exit = shrinkHorizontally(spatialSpec(), shrinkTowards = Alignment.End) + fadeOut(effectsSpec()),
  ) {
    val colors = MaterialTheme.colorScheme
    Box(
      Modifier
        .height(BottomRowLayout.PieceTarget)
        .widthIn(min = BottomRowLayout.PieceTarget)
        .clickable(interactionSource, indication = null, role = Role.Button, onClick = onUndo)
        .semantics { contentDescription = UNDO_CLOSE_LABEL },
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        shape = CircleShape,
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shadowElevation = FabElevation,
        // The chip's name says it all; the word inside isn't read again.
        modifier = Modifier
          .clearAndSetSemantics {}
          .padding(vertical = 4.dp)
          .then(if (compact) Modifier.size(BottomRowLayout.PieceDrawn) else Modifier.height(BottomRowLayout.PieceDrawn)),
      ) {
        val line = colors.inversePrimary
        Box(
          Modifier
            .clip(CircleShape)
            .indication(interactionSource, ripple())
            .then(
              if (compact) {
                Modifier.countdownRing(left, inset = 2.dp)
              } else {
                Modifier.drawBehind {
                  val inset = 14.dp.toPx()
                  val y = size.height - 4.dp.toPx()
                  drawLine(line, Offset(inset, y), Offset(inset + (size.width - 2 * inset) * left(), y), 2.dp.toPx(), cap = StrokeCap.Round)
                }
              },
            ),
          contentAlignment = Alignment.Center,
        ) {
          if (compact) {
            Icon(undo, contentDescription = null, modifier = Modifier.size(20.dp))
          } else {
            Row(
              Modifier.padding(start = 12.dp, end = 16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(undo, contentDescription = null, modifier = Modifier.size(18.dp))
              Text("Undo", style = MaterialTheme.typography.labelLarge, color = colors.inversePrimary, maxLines = 1)
            }
          }
        }
      }
    }
  }
}

/** What TalkBack calls Undo after a close, on the chip and as the count pill's custom action. */
const val UNDO_CLOSE_LABEL = "Undo close"