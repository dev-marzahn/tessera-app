package dev.marzahn.sankaku.features.crash

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.NoAppForLink
import dev.marzahn.sankaku.OpenUrlResult
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.log.Diagnostics
import dev.marzahn.sankaku.plainTextClip
import dev.marzahn.sankaku.platformOpenUrl
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal const val CrashNoticeTag = "crash-notice"
internal const val CrashReportTag = "crash-notice-report"
internal const val CrashDismissTag = "crash-notice-dismiss"
internal const val CrashTextTag = "crash-notice-text"

internal const val CrashNoticeTitle = "Tessera closed unexpectedly"
internal const val CrashNoticeHelp =
  "Report copies the text below and opens the bug form on GitHub; paste it there. It holds the app's version, your " +
    "device and where in the code it stopped: no searches, posts or account details. Nothing is sent unless you do."
internal const val CrashReportCopied = "The report is copied."

/**
 * Report (#208): puts [Diagnostics.crashText] on the clipboard, then opens [Diagnostics.bugReportUrl] (#207), which
 * never holds the trace. Either way the file is deleted, since the text is on the clipboard; if no app can open the
 * form, the notice stays to say so until Dismiss.
 */
internal suspend fun reportCrash(
  crash: String,
  diagnostics: Diagnostics,
  reports: CrashReports,
  clipboard: Clipboard,
  open: (String) -> OpenUrlResult,
): OpenUrlResult {
  clipboard.setClipEntry(plainTextClip(diagnostics.crashText(crash)))
  reports.deleteFile()
  return open(diagnostics.bugReportUrl()).also { if (it == OpenUrlResult.Opened) reports.clear() }
}

/**
 * "Tessera closed unexpectedly" (#208), at the launch after a crash, in place of the app until Report or Dismiss:
 * after the first-launch screen (#210) has been accepted, before the shell. A crash at start would otherwise happen
 * again before the user could report it. It shows what Report copies, so the user sees what they share.
 *
 * Its own page rather than a dialog over the shell, so it says [NoAppForLink] inline: the snackbar lives in the shell.
 */
@Composable
internal fun CrashNotice(
  crash: String,
  reports: CrashReports = koinInject(),
  diagnostics: Diagnostics = koinInject(),
  open: (String) -> OpenUrlResult = ::platformOpenUrl,
) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  var noApp by remember { mutableStateOf(false) }
  val text = remember(crash) { diagnostics.crashText(crash) }
  Surface(Modifier.fillMaxSize().testTag(CrashNoticeTag), color = MaterialTheme.colorScheme.background) {
    // Centred when it fits; scrolls on a short window or with large text.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth(),
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
          Text(
            CrashNoticeTitle,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
          )
          Text(CrashNoticeHelp, style = MaterialTheme.typography.bodyLarge)
          Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 240.dp)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
              .verticalScroll(rememberScrollState())
              .horizontalScroll(rememberScrollState())
              .padding(12.dp)
              .testTag(CrashTextTag),
          )
          if (noApp) {
            Text(
              "$CrashReportCopied $NoAppForLink",
              Modifier.semantics { liveRegion = LiveRegionMode.Polite },
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          ) {
            TextButton(onClick = reports::clear, modifier = Modifier.testTag(CrashDismissTag)) { Text("Dismiss") }
            Button(
              onClick = {
                scope.launch {
                  noApp = reportCrash(crash, diagnostics, reports, clipboard, open) == OpenUrlResult.Failed
                }
              },
              modifier = Modifier.testTag(CrashReportTag),
            ) { Text("Report") }
          }
        }
      }
    }
  }
}
