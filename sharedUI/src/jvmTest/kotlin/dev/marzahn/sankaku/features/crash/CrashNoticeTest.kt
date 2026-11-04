package dev.marzahn.sankaku.features.crash

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.NoAppForLink
import dev.marzahn.sankaku.OpenUrlResult
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.log.AppEnvironment
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.log.Diagnostics
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** "Tessera closed unexpectedly" (#208): Report copies the trace and opens the form; either answer deletes the file. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class CrashNoticeTest {
  private val clock = object : Clock {
    override fun now(): Instant = Instant.parse("2026-10-29T12:00:00Z")
  }
  private val diagnostics =
    Diagnostics(RequestLog(clock), AppEnvironment("1.2.3", "macOS", "macOS 26.1", "Mac15,12 (Apple M3)"))

  private class FakeClipboard : Clipboard {
    var entry: ClipEntry? = null
    override suspend fun getClipEntry(): ClipEntry? = entry
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
      entry = clipEntry
    }
    override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("test")
  }

  /** A crash file as the last run left it, and the next launch's reports reading it. */
  private fun crashed(): Pair<InMemoryTextFile, CrashReports> {
    val file = InMemoryTextFile()
    CrashReports(file, clock, version = "1.2.3").record(IllegalStateException("https://sankakuapi.com/posts/1"))
    return file to CrashReports(file, clock, version = "1.2.3")
  }

  @Test
  fun reportCopiesTheTraceOpensTheFormWithoutItAndDeletesTheFile() = runComposeUiTest {
    val (file, reports) = crashed()
    val crash = reports.pending.value!!
    val clipboard = FakeClipboard()
    val opened = mutableListOf<String>()
    setContent {
      SankakuTheme {
        CompositionLocalProvider(LocalClipboard provides clipboard) {
          CrashNotice(crash, reports, diagnostics, open = {
            opened += it
            OpenUrlResult.Opened
          })
        }
      }
    }
    onNodeWithText(CrashNoticeTitle).assertIsDisplayed()
    // What Report copies is on screen.
    onNodeWithText(diagnostics.crashText(crash)).assertExists()

    onNodeWithTag(CrashReportTag).performClick()
    waitUntil(timeoutMillis = 5_000) { opened.isNotEmpty() }

    val copied = (clipboard.entry!!.nativeClipEntry as Transferable).getTransferData(DataFlavor.stringFlavor) as String
    assertEquals(diagnostics.crashText(crash), copied)
    assertTrue("java.lang.IllegalStateException" in copied, copied)
    assertTrue("Device: Mac15,12 (Apple M3)" in copied, copied)
    assertEquals(listOf(diagnostics.bugReportUrl()), opened)
    assertFalse("IllegalStateException" in opened.single() || "Crashed" in opened.single(), opened.single())
    assertNull(file.read())
    assertNull(reports.pending.value)
  }

  @Test
  fun dismissDeletesTheFile() = runComposeUiTest {
    val (file, reports) = crashed()
    setContent { SankakuTheme { CrashNotice(reports.pending.value!!, reports, diagnostics, open = { error("opened") }) } }

    onNodeWithTag(CrashDismissTag).performClick()
    waitForIdle()

    assertNull(file.read())
    assertNull(reports.pending.value)
  }

  @Test
  fun noAppForTheFormSaysSoInlineAndKeepsTheNoticeButNotTheFile() = runComposeUiTest {
    val (file, reports) = crashed()
    setContent {
      SankakuTheme {
        CompositionLocalProvider(LocalClipboard provides FakeClipboard()) {
          CrashNotice(reports.pending.value!!, reports, diagnostics, open = { OpenUrlResult.Failed })
        }
      }
    }

    onNodeWithTag(CrashReportTag).performClick()
    waitForIdle()

    onNodeWithText("$CrashReportCopied $NoAppForLink").assertIsDisplayed()
    assertTrue(reports.pending.value != null)
    // The text is on the clipboard, so the file is gone already.
    assertNull(file.read())
    onNodeWithTag(CrashDismissTag).performClick()
    waitForIdle()
    assertNull(reports.pending.value)
  }
}
