package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A thin indeterminate bar along a picture's bottom edge while a sharper copy loads over it (a sample, a GIF, the full
 * file for zoom; #524). It shows only once [loading] has lasted [LOAD_BAR_DELAY_MS], so a fast load never flashes it.
 */
@Composable
fun LoadBar(loading: Boolean, modifier: Modifier = Modifier) {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(loading) {
    shown = false
    if (!loading) return@LaunchedEffect
    delay(LOAD_BAR_DELAY_MS)
    shown = true
  }
  if (shown) {
    LinearProgressIndicator(
      modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = LoadBarDescription },
      gapSize = 0.dp,
    )
  }
}

/** What a screen reader calls the [LoadBar]. */
const val LoadBarDescription = "Loading a sharper picture"

/** How long a load runs before the [LoadBar] shows. */
const val LOAD_BAR_DELAY_MS = 500L
