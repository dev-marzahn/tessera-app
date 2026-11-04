package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.fonts.icons.arrow_back
import com.google.fonts.icons.close
import dev.marzahn.sankaku.APP_VERSION
import dev.marzahn.sankaku.PUBLIC_REPO_URL
import dev.marzahn.sankaku.platformBackKeys
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.openUrl
import dev.marzahn.sankaku.resources.Res
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.ui.components.AppTopBar
import dev.marzahn.sankaku.ui.components.LargeIndicatorSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** About's licence line, as the spec and `LICENSE` word it (#196). */
internal const val AboutLicence = "GPL-3.0-only · Copyright the Tessera authors"

/** The licences page's title, and About's row that opens it. */
internal const val OpenSourceLicencesTitle = "Open-source licences"

/** [PUBLIC_REPO_URL] as the Source code row shows it, without the scheme. */
internal val SourceLinkText = PUBLIC_REPO_URL.removePrefix("https://")

/**
 * Settings → About (#206): the version, the app's own licence, the public source (opened in the browser; "No app can open this link" if nothing can) and the
 * open-source licences of every library the app ships, which open over Settings.
 */
@Composable
internal fun ColumnScope.AboutRows(messages: Messages = koinInject()) {
  var showingLicences by rememberSaveable { mutableStateOf(false) }
  InfoRow("Version", APP_VERSION)
  InfoRow("Licence", AboutLicence)
  ActionRow("Source code", help = SourceLinkText, onClick = { messages.openUrl(PUBLIC_REPO_URL) })
  ActionRow(
    OpenSourceLicencesTitle,
    help = "The libraries Tessera is built with, and their licences",
    onClick = { showingLicences = true },
  )
  if (showingLicences) OpenSourceLicences(onDismiss = { showingLicences = false })
}

/** A row that only says something: [label], and [value] under it, read as one by a screen reader. */
@Composable
private fun InfoRow(label: String, value: String) {
  Column(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.Center,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** What the licences page holds while it reads the list. */
private sealed interface LicencesState {
  data object Loading : LicencesState

  data class Loaded(val libraries: List<OpenSourceLibrary>) : LicencesState

  data object Failed : LicencesState
}

/**
 * The open-source licences, over Settings: every library this platform's build ships, by name, each opening to its
 * licences' full text. Read from the list the build bundled ([LICENCES_RESOURCE]), so it needs no network. A whole
 * screen on a phone, with Back; a large dialog on desktop, with Close and Esc.
 */
@Composable
internal fun OpenSourceLicences(
  onDismiss: () -> Unit,
  touch: Boolean = platformIsTouch,
  load: suspend () -> String = { Res.readBytes(LICENCES_RESOURCE).decodeToString() },
) {
  val state by produceState<LicencesState>(LicencesState.Loading) {
    value = runCatching { withContext(Dispatchers.Default) { openSourceLibraries(load(), licencesTarget) } }
      .fold(onSuccess = { LicencesState.Loaded(it) }, onFailure = { LicencesState.Failed })
  }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    val size = if (touch) Modifier.fillMaxSize() else Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f).fillMaxHeight(0.9f)
    // Focus moves in, so Esc (and the mouse's back button) reaches the page and closes only it: left on the row behind, it would also reach the
    // Settings tab's own Esc, which closes the tab.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Surface(
      size
        .semantics { paneTitle = OpenSourceLicencesTitle }
        .focusRequester(focus)
        .focusable()
        .platformBackKeys(onDismiss),
      shape = if (touch) RectangleShape else MaterialTheme.shapes.extraLarge,
    ) {
      Scaffold(
        topBar = {
          AppTopBar(
            title = OpenSourceLicencesTitle,
            navigationIcon = {
              IconButton(onClick = onDismiss) {
                if (touch) Icon(arrow_back, contentDescription = "Back") else Icon(close, contentDescription = "Close")
              }
            },
          )
        },
      ) { padding ->
        when (val current = state) {
          LicencesState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            AppLoadingIndicator(size = LargeIndicatorSize)
          }
          LicencesState.Failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("Couldn't read the licences", color = MaterialTheme.colorScheme.error)
          }
          is LicencesState.Loaded -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
              top = padding.calculateTopPadding() + 8.dp,
              bottom = padding.calculateBottomPadding() + 16.dp,
            ),
          ) {
            items(current.libraries, key = { it.id }) { library -> LibraryRow(library) }
          }
        }
      }
    }
  }
}

/** A library: its name, coordinates and version, and its licences' names; tapping it shows or hides their full text. */
@Composable
private fun LibraryRow(library: OpenSourceLibrary) {
  var open by remember { mutableStateOf(false) }
  val canOpen = library.licences.any { it.text != null }
  Column(Modifier.fillMaxWidth()) {
    Column(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .clickable(enabled = canOpen, role = Role.Button, onClickLabel = if (open) "Hide licence" else "Show licence") {
          open = !open
        }
        .semantics(mergeDescendants = true) { if (canOpen) stateDescription = if (open) "Expanded" else "Collapsed" }
        .padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.Center,
    ) {
      Text(library.name, style = MaterialTheme.typography.bodyLarge)
      Text(
        listOfNotNull(library.id, library.version).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        library.licences.joinToString(", ") { it.name }.ifEmpty { "No licence named" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    if (open) {
      library.licences.forEach { licence ->
        val text = licence.text ?: return@forEach
        Text(
          text,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
      }
      HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    }
  }
}
