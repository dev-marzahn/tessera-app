package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.bug_report
import com.google.fonts.icons.logout
import com.google.fonts.icons.menu_book
import com.google.fonts.icons.open_in_new
import com.google.fonts.icons.search
import com.google.fonts.icons.settings
import com.google.fonts.icons.tab
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The M0 compatibility check: every expressive component on one screen. Run with
 * `./gradlew :desktopApp:run -Dsankaku.sample=true`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SampleScreen() {
  var favourited by remember { mutableStateOf(false) }
  var retryAt by remember { mutableStateOf(Clock.System.now() + 5.seconds) }
  var tapped by remember { mutableStateOf("none") }

  Scaffold(
    topBar = {
      AppTopBar(
        title = "Expressive sample",
        actions = {
          for ((icon, label) in listOf(search to "Search", menu_book to "Books", tab to "Tab", settings to "Settings")) {
            IconButton(onClick = { tapped = label }) { Icon(icon, contentDescription = label) }
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ConnectivityBanner(ConnectivityBannerState.Offline)
      Section("AppLoadingIndicator") { AppLoadingIndicator() }
      Section("FavouriteButton") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          FavouriteButton(checked = favourited, onCheckedChange = { favourited = it })
          Text(if (favourited) "Favourited" else "Not favourited")
        }
      }
      Section("TagChip") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (tag in listOf("blue_hair", "smile", "rating:s", "original")) TagChip(tag, onClick = { tapped = tag })
        }
      }
      Section("ErrorState") {
        ErrorState(
          message = "Rate-limited",
          onRetry = { retryAt = Clock.System.now() + 5.seconds },
          retryEnabledAt = retryAt,
        )
      }
      Section("Icons") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          for (icon in listOf(open_in_new, logout, bug_report)) Icon(icon, contentDescription = null)
        }
      }
      Text("Last tapped: $tapped", modifier = Modifier.padding(horizontal = 16.dp))
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    content()
    HorizontalDivider()
  }
}
