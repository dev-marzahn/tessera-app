package dev.marzahn.sankaku.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Android's default network for [NetworkWatcher]. `onAvailable` is always followed by `onCapabilitiesChanged`, which
 * says whether the network is validated yet, so that's the one it forwards. Needs `ACCESS_NETWORK_STATE`.
 */
class DefaultNetworkCallback private constructor(
  private val watcher: NetworkWatcher,
) : ConnectivityManager.NetworkCallback() {
  override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
    watcher.onDefaultNetwork(network, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
  }

  override fun onLost(network: Network) {
    watcher.onLost(network)
  }

  companion object {
    /** Registers for the process's lifetime; never unregistered. */
    fun register(context: Context, connectivity: ConnectivityState): DefaultNetworkCallback {
      val callback = DefaultNetworkCallback(NetworkWatcher(connectivity))
      context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
      return callback
    }
  }
}
