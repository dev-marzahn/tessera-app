package dev.marzahn.sankaku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import dev.marzahn.sankaku.net.ConnectivityState
import org.koin.compose.koinInject

/**
 * Calls [retry] each time the network may be back ([ConnectivityState.retryRequests]), so a load that failed offline
 * loads again with no tap (#128). [retry] retries only what failed: Paging's `retry()` already does nothing when
 * nothing failed.
 *
 * The last request handled is saved with the screen's state, so a screen shown again (a background tab brought
 * forward) retries for a request it missed while it wasn't shown, and only then.
 */
@Composable
fun RetryWhenBack(connectivity: ConnectivityState = koinInject(), retry: () -> Unit) {
  var handled by rememberSaveable { mutableIntStateOf(connectivity.retryRequests.value) }
  val latest by rememberUpdatedState(retry)
  LaunchedEffect(connectivity) {
    connectivity.retryRequests.collect {
      if (it != handled) {
        handled = it
        latest()
      }
    }
  }
}

/**
 * Desktop has no network callback: the window gaining focus after a request failed to connect is the sign the user
 * may have fixed the connection, so it asks for a retry, and the response clears `offline` as usual. Android's sign
 * comes from `DefaultNetworkCallback`.
 */
@Composable
fun RetryOnWindowFocus(connectivity: ConnectivityState = koinInject()) {
  val focused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(focused) {
    if (focused && connectivity.connectionFailedSinceRetry) connectivity.requestRetry()
  }
}
