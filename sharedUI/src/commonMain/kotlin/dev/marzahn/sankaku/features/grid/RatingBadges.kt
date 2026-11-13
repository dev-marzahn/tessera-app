package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.components.TagBadgeHeight
import dev.marzahn.sankaku.ui.components.BadgeLabel
import dev.marzahn.sankaku.ui.theme.Tinted
import dev.marzahn.sankaku.ui.theme.tinted

/** The site's badge colours: green G, amber R15, red R18. */
val Rating.color: Color
  get() = when (this) {
    Rating.G -> Color(0xFF2E9D4F)
    Rating.R15 -> Color(0xFFD99A00)
    Rating.R18 -> Color(0xFFD93636)
  }

/**
 * [Rating.color] as a tinted badge on [panel], the theme's lowest surface; see [tinted]. A stronger tint than a tag's,
 * so a small badge still reads as green, amber or red.
 */
fun Rating.tinted(panel: Color): Tinted = tinted(color, panel, strength = 0.4f)

/** How tall a rating badge is: [Tag] beside tag pills (as tall as one), [Small] inside a chip or a list row. */
enum class RatingBadgeSize(val height: Dp) { Small(20.dp), Tag(TagBadgeHeight) }

/**
 * One rating's badge: a pill tinted in its colour like the tags ([tinted]), its label centred on the line box rather
 * than the font's.
 */
@Composable
fun RatingBadge(rating: Rating, modifier: Modifier = Modifier, size: RatingBadgeSize = RatingBadgeSize.Small) {
  val tag = size == RatingBadgeSize.Tag
  val tint = rating.tinted(MaterialTheme.colorScheme.surfaceContainerLowest)
  BadgeLabel(
    rating.badge,
    color = tint.text,
    style = if (tag) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    // At least its height, taller with large text rather than cutting the label off (#525).
    modifier = modifier
      .defaultMinSize(minWidth = size.height, minHeight = size.height)
      .background(tint.fill, CircleShape)
      .padding(horizontal = if (tag) 8.dp else 4.dp, vertical = 1.dp),
  )
}

/**
 * The ratings a search lets through as badges stacked like cards (decision 2026-10-30), so every combination looks
 * different.
 */
@Composable
fun RatingStack(ratings: Set<Rating>, modifier: Modifier = Modifier, size: RatingBadgeSize = RatingBadgeSize.Small) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp), verticalAlignment = Alignment.CenterVertically) {
    Rating.entries.filter { it in ratings }.forEach { RatingBadge(it, size = size) }
  }
}
