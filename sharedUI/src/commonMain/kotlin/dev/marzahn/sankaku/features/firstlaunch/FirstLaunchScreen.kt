package dev.marzahn.sankaku.features.firstlaunch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.components.focusRing

internal const val FirstLaunchTag = "first-launch"
internal const val FirstLaunchContinueTag = "first-launch-continue"
internal const val FirstLaunchQuitTag = "first-launch-quit"

internal const val FirstLaunchAbout =
  "Tessera is an unofficial client for Sankaku. It isn't made by Sankaku or affiliated with it."
internal const val FirstLaunchRisk =
  "Sankaku's terms prohibit 'unauthorized User software'. Using Tessera, your account or IP may be restricted; " +
    "that risk is yours."
internal const val FirstLaunchAge = "Sankaku is an adult site. I'm 18 or older."

/**
 * The first-launch screen (#210), shown once and in place of the whole app until Continue, so nothing loads before
 * it. There is no account check behind the age line. Quit closes the app; it asks again at the next launch.
 */
@Composable
internal fun FirstLaunchScreen(onContinue: () -> Unit, onQuit: () -> Unit) {
  Surface(Modifier.fillMaxSize().testTag(FirstLaunchTag), color = MaterialTheme.colorScheme.background) {
    // Centred when it fits; scrolls on a short window or with large text.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth(),
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth(),
        ) {
          Text("Tessera", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
          Text(FirstLaunchAbout, style = MaterialTheme.typography.bodyLarge)
          Text(FirstLaunchRisk, style = MaterialTheme.typography.bodyLarge)
          Text(FirstLaunchAge, style = MaterialTheme.typography.titleMedium)
          Row(
            // Room for the focus ring (7 dp out; one button has it at a time) between them.
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          ) {
            // Enter acts on the focused one, so focus shows (#418).
            TextButton(onClick = onQuit, modifier = Modifier.focusRing(ButtonDefaults.textShape).testTag(FirstLaunchQuitTag)) { Text("Quit") }
            Button(onClick = onContinue, modifier = Modifier.focusRing(ButtonDefaults.shape).testTag(FirstLaunchContinueTag)) { Text("Continue") }
          }
        }
      }
    }
  }
}
