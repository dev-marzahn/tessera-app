package dev.marzahn.sankaku.shell

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Refreshing the grid on screen: the top bar's Refresh button and Cmd/Ctrl-R (desktop) or pulling the grid down
 * (Android) ask with [request]; the grid shown (only the active tab's is composed) collects [requests], reloads past
 * the page cache, and reports [running] while it does, which the button shows by spinning and the pull by its
 * loading indicator.
 */
class GridRefresh {
  private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val requests: SharedFlow<Unit> = _requests.asSharedFlow()

  private val _running = MutableStateFlow(false)
  val running: StateFlow<Boolean> = _running.asStateFlow()

  fun request() {
    _requests.tryEmit(Unit)
  }

  internal fun setRunning(value: Boolean) {
    _running.value = value
  }
}
