package dev.marzahn.sankaku.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/**
 * Desktop's sign that the network may be back (#542), standing in for Android's default-network callback (#128): while
 * Sankaku is [ConnectivityState.unreachable], it reads which of the machine's interfaces have a routable address
 * ([connected]) every [POLL_MS] (a system call, no request). When one gains an address (Wi-Fi rejoined, a cable, a VPN
 * coming up) it waits [SETTLE_MS] for the route and calls [ConnectivityState.networkAvailable], once per change. That
 * clears `offline`, so the outbox sends what waits, and the shown tab retries what failed, without the user touching
 * the app.
 *
 * An address that rotates or renews on a connected interface, or one network of two going away, isn't a network coming
 * back and says nothing. A try that still can't connect (the route wasn't up yet) waits, as before, for the window
 * gaining focus or the next request: REVIEW.md allows one retry per return of the network.
 *
 * Online, or offline by a 5xx, it reads nothing. A connection that comes back on interfaces that never lost their
 * address (a router that lost its uplink) isn't seen here either.
 */
class InterfaceWatcher(
  private val connectivity: ConnectivityState,
  private val scope: CoroutineScope,
  private val connected: suspend () -> Set<String>? = ::connectedInterfaces,
) {
  fun start(): Job = scope.launch {
    connectivity.unreachable.collectLatest { unreachable ->
      if (!unreachable) return@collectLatest
      // Each outage starts from the interfaces as they are now, not from an earlier one's.
      var last = connected()
      while (true) {
        delay(POLL_MS)
        val now = connected() ?: continue
        val gained = last?.let { now - it }.orEmpty().isNotEmpty()
        last = now
        if (gained) {
          delay(SETTLE_MS)
          // Clears unreachable, which ends this watch until a request fails to connect again.
          connectivity.networkAvailable()
        }
      }
    }
  }

  companion object {
    const val POLL_MS = 2_000L

    /** Time for the route and DNS to come up after the address, as there's only the one try. */
    const val SETTLE_MS = 3_000L

    /**
     * The names of the up, non-loopback interfaces with a routable address, or null when they couldn't be read (one
     * vanishing while it's read). Link-local addresses don't count: macOS keeps them on idle tunnels and on Wi-Fi with
     * no network. Only compared, never logged.
     */
    suspend fun connectedInterfaces(): Set<String>? = withContext(Dispatchers.IO) {
      runCatching {
        NetworkInterface.networkInterfaces()
          .filter { it.isUp && !it.isLoopback }
          .filter { nif -> nif.inetAddresses().anyMatch { !it.isLinkLocalAddress && !it.isLoopbackAddress } }
          .map { it.name }
          .toList()
          .toSet()
      }.getOrNull()
    }
  }
}
