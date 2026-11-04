package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.api.dto.TagType
import dev.marzahn.sankaku.ui.theme.color
import dev.marzahn.sankaku.ui.theme.HoverShade
import dev.marzahn.sankaku.ui.theme.onColor
import dev.marzahn.sankaku.ui.theme.tinted

/** A [TagBadge]'s height, which badges shown beside tags (such as ratings) match. */
val TagBadgeHeight = 28.dp

/**
 * A search tag as a pill tinted like a post's tag chips ([tinted]), with a dot in its [type]'s own colour on the left
 * that holds a minus when the tag is excluded (its name starts with `-`, which isn't shown) and a plus when [included]
 * says it's in a search shown elsewhere. A tag of unknown type is neutral. Hovering shows [hint] (such as the type and
 * post count) when there is one. [trailing] sits after the name, such as a remove icon. With an [interactionSource]
 * the pill shows the hover and press of a larger target around it, such as a whole row. A [flagged] tag (one the site
 * doesn't know) is in the error container, whatever its type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagBadge(
  label: AnnotatedString,
  type: TagType?,
  hint: String?,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  included: Boolean = false,
  interactionSource: MutableInteractionSource? = null,
  flagged: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
) {
  val excluded = label.startsWith("-")
  val colors = MaterialTheme.colorScheme
  // The same tint as a post's tag chips, mixed against the same panel, so a tag looks alike everywhere.
  val tint = if (flagged) null else type?.tinted(colors.surfaceContainerLowest)
  val container = if (flagged) colors.errorContainer else tint?.fill ?: colors.surfaceContainerHighest
  val content = if (flagged) colors.onErrorContainer else tint?.text ?: colors.onSurface
  // A flagged or untyped tag's dot is neutral, with its minus or plus in the text's colour.
  val typed = type.takeIf { tint != null }
  val dot = typed?.color ?: colors.outlineVariant
  val badge = @Composable {
    val body = @Composable {
      Row(
        Modifier.height(TagBadgeHeight).padding(start = 8.dp, end = if (trailing == null) 12.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Box(Modifier.size(14.dp).background(dot, CircleShape), contentAlignment = Alignment.Center) {
          val bar = typed?.onColor ?: content
          if (excluded || included) Box(Modifier.width(8.dp).height(2.dp).background(bar, CircleShape))
          if (included && !excluded) Box(Modifier.width(2.dp).height(8.dp).background(bar, CircleShape))
        }
        Text(
          if (excluded) label.subSequence(1, label.length) else label,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
      }
    }
    // Clickable through the modifier, not Surface(onClick): that one enforces a 48 dp touch target, which made the
    // search input's line grow from 32 to 48 dp as soon as it held a tag. Hovered or pressed, the pill darkens rather
    // than taking a light ripple, which washed the tag's colour out; by [HoverShade], as a post's tag chip does, which
    // the tinted text stays readable under.
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val shade by animateFloatAsState(
      if ((onClick != null || interactionSource != null) && (hovered || pressed)) HoverShade else 0f,
      effectsSpec(),
    )
    val clicks = if (onClick != null) Modifier.clickable(interactions, indication = null, onClick = onClick) else Modifier
    Surface(
      shape = CircleShape,
      color = lerp(container, Color.Black, shade),
      contentColor = content,
      modifier = modifier.then(clicks),
    ) { body() }
  }
  HoverHint(hint) { badge() }
}

/** Shows [hint] in a plain tooltip above [content] on hover or long press; no tooltip when it's null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoverHint(hint: String?, content: @Composable () -> Unit) {
  if (hint == null) {
    content()
  } else {
    TooltipBox(
      positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
      tooltip = { PlainTooltip { Text(hint) } },
      state = rememberTooltipState(),
    ) { content() }
  }
}

/** [TagBadge] for a plain name. */
@Composable
fun TagBadge(
  name: String,
  type: TagType?,
  hint: String?,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  included: Boolean = false,
  interactionSource: MutableInteractionSource? = null,
  flagged: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
) = TagBadge(AnnotatedString(name), type, hint, modifier, onClick, included, interactionSource, flagged, trailing)
