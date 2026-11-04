package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.marzahn.sankaku.api.dto.TagType
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.theme.color
import dev.marzahn.sankaku.ui.theme.onColor
import dev.marzahn.sankaku.ui.theme.CountAlpha
import dev.marzahn.sankaku.ui.theme.HoverShade
import dev.marzahn.sankaku.ui.theme.Tinted
import dev.marzahn.sankaku.ui.theme.tinted

/** Where a chip's tag stands in the search it's shown against. */
enum class TagChipState { Plain, Included, Excluded }

/**
 * A post's tag in its tag list: a compact chip tinted with its [type]'s colour (neutral when the type isn't known
 * yet), the [count] inside. [TagChipState.Included] is the solid Sankaku pill with a plus, [TagChipState.Excluded] a
 * red outline with a minus and the name struck through. [selected] (the keyboard's selection) rings it with a gap of
 * the panel's colour, the same mark as the grid's keyboard cursor. [trailing] sits after the name (the followed
 * bell), in the chip's text colour; it adds the gap it's given only when it shows something. The chip shows the
 * hover of [interactionSource], which the caller also gives its pointer input. On [touch] the chip is 32 dp tall (the
 * approved Android mockup) in a 48 dp touch target that [modifier] is applied to, and a press darkens it as hover does.
 */
@Composable
fun TagChip(
  name: String,
  type: TagType?,
  count: String?,
  state: TagChipState,
  selected: Boolean,
  interactionSource: MutableInteractionSource,
  modifier: Modifier = Modifier,
  touch: Boolean = platformIsTouch,
  /** Each time it goes up (a failed follow), the chip shakes with an error outline ([rejected]). */
  rejected: Int = 0,
  trailing: (@Composable (gap: Dp) -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  // The info panel's colour, which the tonal fill and the ring's gap are mixed against.
  val panel = colors.surfaceContainerLowest
  fun solid(fill: Color, text: Color) = Tinted(fill, text, text.copy(alpha = CountAlpha))
  val chip = when (state) {
    TagChipState.Excluded -> solid(Color.Transparent, colors.error)
    TagChipState.Included -> solid(type?.color ?: colors.primary, type?.onColor ?: colors.onPrimary)
    TagChipState.Plain -> type?.tinted(panel) ?: solid(colors.surfaceContainerHigh, colors.onSurface)
  }
  val fill = chip.fill
  val text = chip.text
  val hovered by interactionSource.collectIsHoveredAsState()
  // Touch has no hover, so a press darkens it instead.
  val pressed by interactionSource.collectIsPressedAsState()
  val darken = hovered || (touch && pressed)
  val measures = if (touch) TouchMeasures else DesktopMeasures
  val ring = colors.primary
  // On touch [modifier] (the caller's clicks) sits on a box at least 48 dp each way with the chip centred in it, so
  // the touch target is 48 dp while the chip is drawn at the mockup's 32. Above its neighbours when selected, so the
  // ring isn't covered by the next chip.
  Box(
    modifier
      .then(if (touch) Modifier.sizeIn(minWidth = TouchTarget, minHeight = TouchTarget) else Modifier)
      .zIndex(if (selected) 1f else 0f),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      Modifier
        // The ring is drawn outside the chip, in the gap between chips, so selecting one moves nothing.
        .drawBehind {
          if (!selected) return@drawBehind
          val out = (RingGap + SelectionRing / 2).toPx()
          drawRoundRect(
            ring,
            topLeft = Offset(-out, -out),
            size = Size(size.width + 2 * out, size.height + 2 * out),
            cornerRadius = CornerRadius((ChipCorner + RingGap + SelectionRing / 2).toPx()),
            style = Stroke(SelectionRing.toPx()),
          )
        }
        .rejected(rejected, ChipShape)
        .height(measures.height)
        .clip(ChipShape)
        .background(fill)
        .then(if (state == TagChipState.Excluded) Modifier.border(1.5.dp, colors.error, ChipShape) else Modifier)
        // Hover (a press on touch) darkens the chip a little, whatever its fill.
        .drawWithContent {
          drawContent()
          if (darken) drawRect(Color.Black.copy(alpha = HoverShade))
        }
        .padding(horizontal = measures.padding),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(measures.gap),
    ) {
      val sign = when (state) {
        TagChipState.Included -> "+"
        TagChipState.Excluded -> "−"
        TagChipState.Plain -> null
      }
      if (sign != null) Text(sign, color = text, fontSize = measures.name, fontWeight = FontWeight.Bold)
      // The name and [trailing] share a slot, so a trailing that shows nothing leaves no gap.
      Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
        Text(
          name,
          color = text,
          fontSize = measures.name,
          fontWeight = FontWeight.Medium,
          textDecoration = if (state == TagChipState.Excluded) TextDecoration.LineThrough else null,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        CompositionLocalProvider(LocalContentColor provides text) { trailing?.invoke(measures.gap) }
      }
      if (count != null) Text(count, color = chip.count, fontSize = measures.count)
    }
  }
}

/** A chip's measures: desktop's compact chip, and the approved Android mockup's (`.scratch/android-viewer/`). */
private class ChipMeasures(val height: Dp, val padding: Dp, val gap: Dp, val name: TextUnit, val count: TextUnit)

private val DesktopMeasures = ChipMeasures(height = 26.dp, padding = 8.dp, gap = 5.dp, name = 12.5.sp, count = 11.sp)
private val TouchMeasures = ChipMeasures(height = 32.dp, padding = 10.dp, gap = 6.dp, name = 13.sp, count = 11.5.sp)
private val TouchTarget = 48.dp
private val ChipCorner = 8.dp
private val ChipShape = RoundedCornerShape(ChipCorner)
private val RingGap = 2.dp
private val SelectionRing = 2.dp
