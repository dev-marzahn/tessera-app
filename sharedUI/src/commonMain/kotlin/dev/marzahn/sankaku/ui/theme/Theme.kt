package dev.marzahn.sankaku.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme

/**
 * Tailwind amber-500, the Nuxt app's `primary: 'amber'`: the scheme's seed. The scheme's `primary` is a darker (light
 * theme) or paler (dark theme) tone of it, so a mark that must stand out on any picture, the grid cursor's ring, uses
 * this one.
 */
val Amber = Color(0xFFF59E0B)

/**
 * A warm graphite (HCT hue 64, chroma 2) for the neutral palettes, so the surfaces stay almost colourless and the images
 * and tag colours carry the colour. Slate (the Nuxt app's neutral) made every surface blue against the amber text.
 */
private val Graphite = Color(0xFF7B7673)

/** One of the two places (with `ui/components`) that opt in to the expressive APIs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SankakuTheme(isDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialExpressiveTheme(
    colorScheme = remember(isDark) { sankakuColorScheme(isDark) },
    motionScheme = MotionScheme.expressive(),
    content = content,
  )
}

/** The app's colour scheme, outside composition so tests can check the colours drawn on it. */
fun sankakuColorScheme(isDark: Boolean): ColorScheme =
  dynamicColorScheme(seedColor = Amber, isDark = isDark, neutral = Graphite, neutralVariant = Graphite)
