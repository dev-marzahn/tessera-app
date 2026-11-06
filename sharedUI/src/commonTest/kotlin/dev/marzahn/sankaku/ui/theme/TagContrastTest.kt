package dev.marzahn.sankaku.ui.theme

import androidx.compose.ui.graphics.Color
import dev.marzahn.sankaku.api.dto.TagType
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.grid.tinted
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every tag type's chip and every rating's badge, in both schemes, reads at WCAG AA (#131). */
class TagContrastTest {
  /** Each tag type's chip and each rating's badge, by name, in each scheme. */
  private fun chips(): List<Pair<String, Tinted>> = listOf(false, true).flatMap { dark ->
    val panel = sankakuColorScheme(isDark = dark).surfaceContainerLowest
    val scheme = if (dark) "dark" else "light"
    TagType.entries.map { "$scheme ${it.name}" to it.tinted(panel) } +
      Rating.entries.map { "$scheme rating ${it.badge}" to it.tinted(panel) }
  }

  @Test
  fun everyNameAndCountReachesAaAtRest() {
    val failing = chips().filter { (_, chip) ->
      wcagContrast(chip.text, chip.fill) < MinTextContrast || wcagContrast(chip.count, chip.fill) < MinTextContrast
    }
    assertTrue(failing.isEmpty(), "Under $MinTextContrast:1 at rest: ${failing.map { it.first }}")
  }

  @Test
  fun everyNameAndCountReachesAaHoveredOrPressed() {
    // The chip's shade over the whole chip, and the badge's over its fill alone (#150 review).
    val failing = chips().filter { (_, chip) ->
      val text = wcagContrast(shaded(chip.text), shaded(chip.fill))
      val count = wcagContrast(shaded(chip.count), shaded(chip.fill))
      minOf(text, count, wcagContrast(chip.text, shaded(chip.fill))) < MinTextContrast
    }
    assertTrue(failing.isEmpty(), "Under $MinTextContrast:1 hovered: ${failing.map { it.first }}")
  }

  @Test
  fun eachSchemesPanelIsTheOneItsTonesAreFor() {
    // The chips and badges tell the scheme from the panel, so the dark one must be dark and the light one light.
    assertTrue(wcagContrast(sankakuColorScheme(isDark = true).surfaceContainerLowest, Color.Black) < 2f)
    assertTrue(wcagContrast(sankakuColorScheme(isDark = false).surfaceContainerLowest, Color.White) < 2f)
  }
}
