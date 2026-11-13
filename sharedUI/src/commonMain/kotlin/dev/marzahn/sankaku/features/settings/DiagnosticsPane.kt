package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.log.Diagnostics
import dev.marzahn.sankaku.log.RequestLogEntry
import dev.marzahn.sankaku.plainTextClip
import dev.marzahn.sankaku.NoAppForLink
import dev.marzahn.sankaku.OpenUrlResult
import dev.marzahn.sankaku.openUrl
import dev.marzahn.sankaku.platformOpenUrl
import dev.marzahn.sankaku.ui.Messages
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

/** What Copy log says it copies. */
internal const val CopyLogHelp = "The app's version, your device and the request log, to paste into a bug report"

/** What Report a bug says it does: the log is never sent, the user pastes it. */
internal const val ReportBugHelp = "Opens the bug form on GitHub. Copy the log first and paste it there."

/** What the request log says it holds (decision 18 in #196). */
internal const val RequestLogHelp = "The last 200 requests: paths, statuses and timings, with no searches or post ids"

internal const val RequestLogTag = "settings-request-log"

/** Copy log (#207): puts [Diagnostics.copyText] on the clipboard and says so. */
internal suspend fun copyLog(diagnostics: Diagnostics, clipboard: Clipboard, messages: Messages) {
  clipboard.setClipEntry(plainTextClip(diagnostics.copyText()))
  messages.show("Log copied")
}

/**
 * Report a bug (#207): opens the bug form, prefilled with where the app runs, never the log, or says [NoAppForLink]
 * when nothing can open it (#358). The crash notice (#208) opens the same form, saying so inline instead: it's shown
 * before the shell, where the snackbar lives.
 */
internal fun reportBug(
  diagnostics: Diagnostics,
  messages: Messages,
  open: (String) -> OpenUrlResult = ::platformOpenUrl,
) = messages.openUrl(diagnostics.bugReportUrl(), open)

/**
 * Settings → Diagnostics (#207): Copy log, Report a bug, and the request log, newest first, failures in the error
 * colour. The log scrolls in its own box, so the sections under it stay a short scroll away.
 */
@Composable
internal fun ColumnScope.DiagnosticsSection(
  diagnostics: Diagnostics = koinInject(),
  messages: Messages = koinInject(),
  open: (String) -> OpenUrlResult = ::platformOpenUrl,
) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val entries by diagnostics.entries.collectAsStateWithLifecycle()
  ActionRow("Copy log", CopyLogHelp, onClick = { scope.launch { copyLog(diagnostics, clipboard, messages) } })
  ActionRow("Report a bug", ReportBugHelp, onClick = { reportBug(diagnostics, messages, open) })
  Text("Request log", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
  Text(
    RequestLogHelp,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 8.dp),
  )
  Column(
    Modifier
      .fillMaxWidth()
      .heightIn(max = 360.dp)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
      .verticalScroll(rememberScrollState())
      .padding(vertical = 4.dp)
      .testTag(RequestLogTag),
  ) {
    if (entries.isEmpty()) {
      Text(
        "No requests yet",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(12.dp),
      )
    }
    entries.asReversed().forEachIndexed { index, entry ->
      if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
      RequestRow(entry)
    }
  }
}

/** The entry's time on this device's clock, `14:03:09`. */
internal fun RequestLogEntry.localTime(zone: TimeZone = TimeZone.currentSystemDefault()): String {
  val time = time.toLocalDateTime(zone).time
  return listOf(time.hour, time.minute, time.second).joinToString(":") { it.toString().padStart(2, '0') }
}

/** One request: time, method and host, then the path template, with the outcome and duration at the end. */
@Composable
private fun RequestRow(entry: RequestLogEntry) {
  val failed = entry.failed
  val small = MaterialTheme.typography.bodySmall
  val variant = MaterialTheme.colorScheme.onSurfaceVariant
  // One node for a screen reader, read as the row's lines in order.
  Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 12.dp, vertical = 6.dp)) {
    Text("${entry.localTime()}  ${entry.method}  ${entry.host}", style = small, color = variant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        entry.pathTemplate,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.weight(1f),
      )
      Text(
        "${entry.outcome} · ${entry.durationMs} ms",
        style = small,
        color = if (failed) MaterialTheme.colorScheme.error else variant,
      )
    }
  }
}
