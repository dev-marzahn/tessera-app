package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import kotlin.math.roundToInt

/**
 * This style with the text centred in its line and the font's extra leading trimmed, so a short label (a key, a
 * rating) sits in the middle of a small badge instead of above it.
 */
fun TextStyle.centredOnLine(): TextStyle =
  copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

/** Cap height as a share of the font size, close for the system and Roboto faces alike. */
private const val CAP_HEIGHT = 0.7f

/**
 * A short label (G, R15, Tab, Esc) centred by its capitals rather than its line box: the font leaves more room above
 * the capitals than below the baseline, which puts a label in a badge about a dp low. Fills the space it's given.
 */
@Composable
fun BadgeLabel(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  color: Color = Color.Unspecified,
  fontWeight: FontWeight? = null,
) {
  Layout(content = { Text(text, style = style, color = color, fontWeight = fontWeight, maxLines = 1) }, modifier = modifier) {
    measurables, constraints ->
    val label = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = maxOf(label.width, constraints.minWidth)
    val height = maxOf(label.height, constraints.minHeight)
    val baseline = label[FirstBaseline]
    val cap = style.fontSize.toPx() * CAP_HEIGHT
    layout(width, height) {
      // The capitals span baseline - cap .. baseline; put their middle on the box's middle.
      val y = if (baseline == androidx.compose.ui.layout.AlignmentLine.Unspecified) {
        (height - label.height) / 2
      } else {
        (height / 2f + cap / 2f - baseline).roundToInt()
      }
      label.placeRelative((width - label.width) / 2, y)
    }
  }
}
