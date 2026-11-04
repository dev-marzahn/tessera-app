package dev.marzahn.sankaku

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.marzahn.sankaku.data.FirstLaunch
import dev.marzahn.sankaku.di.startSankakuKoin
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.log.installUncaughtHandler
import dev.marzahn.sankaku.shell.GridRefresh
import dev.marzahn.sankaku.tabs.ClearViewed
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.system.exitProcess

@OptIn(ExperimentalComposeUiApi::class) // LocalWindowExceptionHandlerFactory, for recordingWindowCrashes.
fun main() {
  // First, so a failure in start-up is recorded (#208). Only main's closes the app here: another thread's (a
  // background one, or an AWT listener's) leaves it running, so it's passed on unrecorded. A crash in the window is
  // recorded by recordingWindowCrashes, the one place that knows the window is closing.
  val mainThread = Thread.currentThread()
  val crashReports = desktopCrashReports().also {
    it.installUncaughtHandler(closesApp = { Thread.currentThread() === mainThread })
  }
  LaunchFocus.remember()
  migrateAppData()
  val instance = SingleInstance.acquire(APP_DATA_DIR) ?: exitProcess(0)
  val focusRequests = Channel<Unit>(Channel.CONFLATED)
  instance.listen { focusRequests.trySend(Unit) }

  val koin = startSankakuKoin(desktopPlatformModule(crashReports)).koin
  val tabs = koin.get<TabsController>()
  val navigator = koin.get<Navigator>()
  val refresh = koin.get<GridRefresh>()
  val clearViewed = koin.get<ClearViewed>()
  val firstLaunch = koin.get<FirstLaunch>()
  val controlPort = ControlPort.fromProperty()?.also { it.requestLog = koin.get() }

  keepAccessibleFocusInBackground()
  application {
    val windowState = rememberWindowState(width = 1280.dp, height = 860.dp)
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides recordingWindowCrashes(crashReports)) {
      Window(
        onCloseRequest = ::exitApplication,
        title = "Tessera",
        icon = painterResource("icon.png"),
        state = windowState,
        // Behind the first-launch screen (#210) the shortcuts and Esc do nothing: there are no tabs to act on yet.
        onPreviewKeyEvent = { firstLaunch.accepted.value && tabShortcuts(it, tabs, refresh, clearViewed) },
      ) {
        // Esc that nothing focused handled goes back, under any open dialog's own Esc (#364). First, so it's the last
        // handler asked.
        val accepted by firstLaunch.accepted.collectAsState()
        WindowBackHandler(enabled = accepted, onBack = navigator::goBack)
        // Started from a terminal or Gradle, hand focus back to what the user was doing (see LaunchFocus.kt).
        LaunchedEffect(Unit) {
          delay(300)
          LaunchFocus.yieldFocus()
        }
        LaunchedEffect(Unit) { controlPort?.attach(window) }
        LaunchedEffect(Unit) {
          focusRequests.receiveAsFlow().collect {
            windowState.isMinimized = false
            window.toFront()
          }
        }
        App(onQuit = ::exitApplication)
      }
    }
  }
}

/**
 * Compose's own window exception handler, recording first (#208). A crash in the window (composition, layout, input,
 * its effects) reaches it; the default then shows its error dialog, closes the window (which quits the app) and
 * rethrows on the AWT event thread. That rethrow isn't recorded again: the uncaught handler records only `main`'s.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun recordingWindowCrashes(reports: CrashReports) = WindowExceptionHandlerFactory { window ->
  val default = DefaultWindowExceptionHandlerFactory.exceptionHandler(window)
  WindowExceptionHandler { error ->
    reports.record(error)
    default.onException(error)
  }
}

/**
 * Cmd/Ctrl-T, Cmd/Ctrl-Shift-T (reopen a closed tab), Cmd/Ctrl-Shift-E (Clear viewed), Cmd/Ctrl-W, Ctrl-(Shift-)Tab
 * and Cmd/Ctrl-R or F5 (refresh the grid), at window level so they work wherever focus is.
 */
private fun tabShortcuts(event: KeyEvent, tabs: TabsController, refresh: GridRefresh, clearViewed: ClearViewed): Boolean {
  if (event.type != KeyEventType.KeyDown) return false
  val command = event.isCommand
  return when {
    event.key == Key.Tab && event.isCtrlPressed -> true.also { tabs.cycle(forward = !event.isShiftPressed) }
    command && event.isShiftPressed && event.key == Key.T -> true.also { tabs.reopenClosed() }
    command && event.isShiftPressed && Shortcuts.ClearViewed.matches(event.key) -> true.also { clearViewed() }
    command && event.key == Key.T -> true.also { tabs.open(Route.Grid(emptyList()), background = false) }
    command && event.key == Key.W -> true.also { tabs.close(tabs.active.value) }
    (command && event.key == Key.R) || event.key == Key.F5 -> true.also { refresh.request() }
    else -> false
  }
}
