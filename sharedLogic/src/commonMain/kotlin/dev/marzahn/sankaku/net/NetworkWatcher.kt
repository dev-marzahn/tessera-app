package dev.marzahn.sankaku.net

/**
 * Turns the platform's default-network events into [ConnectivityState.networkAvailable] (#128): once each time a
 * default network is up and validated (Android has reached the internet through it), so the retries don't race the
 * connection coming up. Android's `DefaultNetworkCallback` feeds it; a test feeds it directly. Callers deliver the
 * events one at a time, as Android does for one callback.
 */
class NetworkWatcher(private val connectivity: ConnectivityState) {
  /** The network last announced, until it's lost or loses its validation. */
  private var announced: Any? = null

  /** The default network is [network], [validated] or not yet. */
  fun onDefaultNetwork(network: Any, validated: Boolean) {
    when {
      !validated -> if (network == announced) announced = null
      network != announced -> {
        announced = network
        connectivity.networkAvailable()
      }
    }
  }

  /** [network] is gone; there may be no default network now. */
  fun onLost(network: Any) {
    if (network == announced) announced = null
  }
}
