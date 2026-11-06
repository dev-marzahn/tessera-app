package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.close

/**
 * "Done with artist_x?" at the end of a source's finite grid (#184), full-span, with a "Close artist_x" button that does
 * what ✕ does ([onClose]). Never automatic: a source ends only when the user closes it. The button has the heading
 * role, and everything wraps at a large font. With [keptStay] kept posts the close leaves, "30 kept stay" says so under
 * the button.
 */
@Composable
fun DoneWith(name: String, keptStay: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().padding(vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("Done with $name?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    // The button is the heading, so a screen reader's heading navigation lands on the way to close the source.
    TonalButton("Close $name", close, onClose, Modifier.semantics { heading() })
    if (keptStay > 0) {
      Text(
        "$keptStay kept stay",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}
