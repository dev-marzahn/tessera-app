package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * A slim slider for media (a video's position and volume): the M3 slider with a 4 dp track and a short handle, where
 * the expressive default's 16 dp track would outweigh the video's controls. A screen reader reads it as [description]
 * ("Seek", "Volume") at [state] ("01:05 of 02:10", "60%").
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  description: String,
  state: String,
  modifier: Modifier = Modifier,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  colors: SliderColors = SliderDefaults.colors(),
  onValueChangeFinished: (() -> Unit)? = null,
) {
  val interaction = remember { MutableInteractionSource() }
  Slider(
    value = value,
    onValueChange = onValueChange,
    onValueChangeFinished = onValueChangeFinished,
    valueRange = valueRange,
    interactionSource = interaction,
    colors = colors,
    thumb = { SliderDefaults.Thumb(interaction, colors = colors, thumbSize = DpSize(4.dp, 16.dp)) },
    track = {
      SliderDefaults.Track(
        sliderState = it,
        colors = colors,
        modifier = Modifier.height(4.dp),
        drawStopIndicator = null,
        thumbTrackGapSize = 3.dp,
        trackInsideCornerSize = 1.dp,
      )
    },
    modifier = modifier.semantics {
      contentDescription = description
      stateDescription = state
    },
  )
}
