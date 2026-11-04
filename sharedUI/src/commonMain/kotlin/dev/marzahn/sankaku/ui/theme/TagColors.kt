package dev.marzahn.sankaku.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.marzahn.sankaku.api.dto.TagType

/** Sankaku's own tag colours, from the web app's `configs/constants/tag-types.js`. */
val TagType.color: Color
  get() = when (this) {
    TagType.General -> Color(0xFFFB8A00)
    TagType.Artist -> Color(0xFFFF3620)
    TagType.Studio -> Color(0xFFE61700)
    TagType.Copyright -> Color(0xFF821B7B)
    TagType.Character -> Color(0xFFA3229A)
    TagType.Genre -> Color(0xFFB698FF)
    TagType.Medium -> Color(0xFF98AEFF)
    TagType.Meta -> Color(0xFF0042BD)
    TagType.Fashion -> Color(0xFFEE005B)
    TagType.Anatomy -> Color(0xFF9B7A00)
    TagType.Pose -> Color(0xFFC29900)
    TagType.Activity -> Color(0xFFE9B800)
    TagType.Role -> Color(0xFFB6C8D8)
    TagType.Flora -> Color(0xFF009E2E)
    TagType.Fauna -> Color(0xFF00C53A)
    TagType.Entity -> Color(0xFF00EC46)
    TagType.Object -> Color(0xFF016E4E)
    TagType.Substance -> Color(0xFF018961)
    TagType.Setting -> Color(0xFF01A474)
    TagType.Language -> Color(0xFF1C6CFF)
    TagType.Automatic -> Color(0xFF0053EC)
    TagType.Other -> Color(0xFFAAAAAA)
  }

/** Readable text on [TagType.color]: dark on the light fills (such as Genre, Medium and Role), white otherwise. */
val TagType.onColor: Color
  get() = if (color.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White

/** WCAG AA's minimum contrast for text under 18 sp, which every tag name, count and rating badge meets. */
const val MinTextContrast = 4.5f

/** How opaque a chip's count is in its text colour, so it reads as secondary to the name. */
const val CountAlpha = 0.8f

/** How much black a hovered or pressed tag is shaded with: a `TagChip`'s whole chip, a `TagBadge`'s fill. */
const val HoverShade = 0.08f

/** [color] under [HoverShade]. */
fun shaded(color: Color): Color = Color.Black.copy(alpha = HoverShade).compositeOver(color)

/**
 * A tinted chip's colours: a faint [fill] of a colour, its [text] in a deeper (on dark, lighter) tone of the same
 * colour, and the [count] in that tone at [CountAlpha].
 */
class Tinted(val fill: Color, val text: Color, val count: Color) {
  /**
   * The lowest contrast the name or count has: at rest, with the whole chip shaded (a hovered `TagChip`), or with
   * only the fill shaded (a hovered `TagBadge`, which has no count).
   */
  val worstContrast: Float
    get() = minOf(
      minOf(wcagContrast(text, fill), wcagContrast(count, fill)),
      minOf(wcagContrast(shaded(text), shaded(fill)), wcagContrast(shaded(count), shaded(fill))),
      wcagContrast(text, shaded(fill)),
    )
}

/** [TagType.color] as a tinted chip on [panel]; see [tinted]. */
fun TagType.tinted(panel: Color): Tinted = tinted(color, panel)

/**
 * [color] as a tinted chip on [panel] (the theme's lowest surface), the one style a tag (and a rating) has
 * everywhere: [strength] of the colour over the panel, or by default a faint tint, and the colour as text, darkened on
 * a light panel (lightened on a dark one) just far enough that the name and count reach [MinTextContrast] on the fill,
 * hovered or not ([Tinted.worstContrast]). So the hue stays as close to Sankaku's as reading allows.
 */
fun tinted(color: Color, panel: Color, strength: Float? = null): Tinted {
  val dark = panel.luminance() < 0.5f
  val fill = lerp(panel, color, strength ?: if (dark) 0.26f else 0.16f)
  val toward = if (dark) Color.White else Color.Black
  // Where the tone starts before the contrast check: dark purples (Copyright, Character) need a big step to show on
  // a dark panel, and pale colours (Genre, Medium, Role, Activity) on a light one.
  var amount = when {
    dark -> if (color.luminance() < 0.15f) 0.5f else 0.25f
    color.luminance() > 0.3f -> 0.45f
    else -> 0f
  }
  fun chip() = lerp(color, toward, amount).let { Tinted(fill, it, it.copy(alpha = CountAlpha).compositeOver(fill)) }
  while (chip().worstContrast < MinTextContrast && amount < 1f) amount = minOf(1f, amount + 0.05f)
  return chip()
}

/** WCAG's contrast ratio of two opaque colours, from 1 (the same) to 21 (black on white). */
fun wcagContrast(a: Color, b: Color): Float {
  val (light, dark) = if (a.luminance() > b.luminance()) a to b else b to a
  return (light.luminance() + 0.05f) / (dark.luminance() + 0.05f)
}
