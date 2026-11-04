package dev.marzahn.sankaku.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * Written by the response hook in `SankakuHttpClients`, and by the platform when the network may be back (#128):
 * Android's default network coming up ([NetworkWatcher] calls [networkAvailable]) and desktop's window gaining focus
 * after a request failed to connect (which calls [requestRetry]).
 */
class ConnectivityState {
  private val _offline = MutableStateFlow(false)
  private val _rejected = MutableStateFlow(false)
  private val _retryRequests = MutableStateFlow(0)
  private val _unreachable = MutableStateFlow(false)

  /**
   * Set by a connection error, timeout or 5xx; cleared by the next response that isn't a 5xx, or by the platform's
   * network coming back ([networkAvailable]) when a connection error set it.
   */
  val offline: StateFlow<Boolean> = _offline.asStateFlow()

  /** Set by a 403 or a non-JSON body from `sankakuapi.com`; cleared by the next JSON success from it. */
  val rejected: StateFlow<Boolean> = _rejected.asStateFlow()

  /**
   * Counts the times the network may have come back. A screen retries its loads that failed for want of a connection
   * when this changes, including a change it missed while it wasn't shown (a background tab, when it's shown again).
   */
  val retryRequests: StateFlow<Int> = _retryRequests.asStateFlow()

  /**
   * [offline] because Sankaku couldn't be reached (a connection error or timeout), not because it answered with a 5xx.
   * Only then does a network coming back say anything about it.
   */
  val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

  /**
   * A request failed to connect since the last retry request. Unlike [unreachable], a later response doesn't clear it:
   * one image that loads as the network comes back mustn't leave the grid's failed pages waiting for a tap.
   */
  @Volatile
  var connectionFailedSinceRetry: Boolean = false
    private set

  /**
   * The platform's network is back. If Sankaku was [unreachable], the app is no longer offline; if a request failed to
   * connect since the last retry, the shown failed loads retry. Otherwise nothing changes: a 5xx stays until a
   * response says otherwise, and a network handoff while nothing failed retries nothing.
   */
  fun networkAvailable() {
    if (_unreachable.value) setOffline(false)
    if (connectionFailedSinceRetry) requestRetry()
  }

  /**
   * The network may be back (desktop: the window gained focus after a request failed to connect): failed loads retry.
   */
  fun requestRetry() {
    connectionFailedSinceRetry = false
    _retryRequests.update { it + 1 }
  }

  /** [value] is whether the app is offline; [byConnection], whether no response arrived at all. */
  internal fun setOffline(value: Boolean, byConnection: Boolean = value) {
    // Ordered so a reader between the two writes never sees offline with a cause it hasn't got: unreachable goes up
    // before offline, and comes down after it, so "Back online" knows what cleared.
    if (value) {
      if (byConnection) connectionFailedSinceRetry = true
      _unreachable.value = byConnection
      _offline.value = true
    } else {
      _offline.value = false
      _unreachable.value = false
    }
  }

  internal fun setRejected(value: Boolean) {
    _rejected.value = value
  }
}
