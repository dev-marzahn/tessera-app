package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.layout.Column
import app.cash.turbine.test
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.NoAppForLink
import dev.marzahn.sankaku.OpenUrlResult
import dev.marzahn.sankaku.log.AppEnvironment
import dev.marzahn.sankaku.log.Diagnostics
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Settings → Diagnostics (#207): the log's rows, Copy log and Report a bug. State and semantics only. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DiagnosticsPaneTest {
  private val clock = object : Clock {
    override fun now(): Instant = Instant.parse("2026-10-29T12:00:00Z")
  }

  private val environment = AppEnvironment("1.2.3", "macOS", "macOS 26.1", "Mac15,12 (Apple M3)")

  /** Keeps what's copied, so the test never touches the machine's clipboard. */
  private class FakeClipboard : Clipboard {
    var entry: ClipEntry? = null
    override suspend fun getClipEntry(): ClipEntry? = entry
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
      entry = clipEntry
    }
    override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("test")
  }

  @Test
  fun showsTheLogNewestFirstAndCopiesAndReports() = runComposeUiTest {
    val log = RequestLog(clock)
    log.record("GET", "sankakuapi.com", "/users/me", 200, 12, null)
    log.record("POST", "sankakuapi.com", "/posts/abc123/favorite", 401, 40, null)
    log.record("GET", "v.sankakucomplex.com", "/data/sample/01/23/sample-0123456789abcdef0123456789abcdef.jpg", null, 30_000, "SocketTimeoutException")
    val diagnostics = Diagnostics(log, environment)
    val messages = Messages()
    val shown = mutableListOf<String>()
    val opened = mutableListOf<String>()
    val clipboard = FakeClipboard()
    setContent {
      SankakuTheme {
        LaunchedEffect(messages) { messages.messages.collect { shown += it.text } }
        CompositionLocalProvider(LocalClipboard provides clipboard) {
          Column {
            DiagnosticsSection(diagnostics, messages, open = {
              opened += it
              OpenUrlResult.Opened
            })
          }
        }
      }
    }
    waitForIdle()

    // Newest first: each path's row comes above the one recorded before it.
    val tops = listOf("/data/sample/{md5}", "/posts/{id}/favorite", "/users/me").map {
      onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
    }
    assertEquals(tops.sorted(), tops)
    onNodeWithText("SocketTimeoutException · 30000 ms").assertExists()
    onNodeWithText("401 · 40 ms").assertExists()
    onNodeWithText("200 · 12 ms").assertExists()
    assertEquals(0, onAllNodesWithText("abc123", substring = true).fetchSemanticsNodes().size)

    onNodeWithText("Copy log").performClick()
    waitUntil(timeoutMillis = 5_000) { shown.isNotEmpty() }
    assertEquals(listOf("Log copied"), shown)
    val copied = (clipboard.entry!!.nativeClipEntry as Transferable).getTransferData(DataFlavor.stringFlavor)
    assertEquals(diagnostics.copyText(), copied)

    onNode(hasText("Report a bug")).performClick()
    waitForIdle()
    assertEquals(listOf(diagnostics.bugReportUrl()), opened)
    assertEquals(listOf("Log copied"), shown)
  }

  @Test
  fun reportABugSaysSoWhenNoAppCanOpenTheForm() = runTest {
    val messages = Messages()
    messages.messages.test {
      reportBug(Diagnostics(RequestLog(clock), environment), messages, open = { OpenUrlResult.Failed })
      assertEquals(Message(NoAppForLink, isError = true), awaitItem())
    }
  }

  @Test
  fun anEmptyLogSaysSo() = runComposeUiTest {
    val log = RequestLog(clock)
    setContent {
      SankakuTheme {
        Column { DiagnosticsSection(Diagnostics(log, environment), Messages(), open = { OpenUrlResult.Opened }) }
      }
    }
    onNodeWithText("No requests yet").assertExists()
  }

  @Test
  fun timesAreOnTheDevicesClock() {
    val log = RequestLog(clock)
    log.record("GET", "sankakuapi.com", "/users/me", 200, 12, null)
    val entry = log.entries.value.single()
    assertEquals("12:00:00", entry.localTime(TimeZone.UTC))
    assertEquals("13:00:00", entry.localTime(TimeZone.of("Europe/Berlin")))
    assertTrue(entry.copy(status = null).failed)
    assertTrue(entry.copy(status = 503).failed)
  }
}
