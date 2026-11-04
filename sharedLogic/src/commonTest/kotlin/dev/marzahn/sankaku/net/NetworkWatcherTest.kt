package dev.marzahn.sankaku.net

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.json
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The watcher fed as Android's default-network callback feeds it, with plain objects for networks. */
class NetworkWatcherTest {
  private val wifi = Any()
  private val mobile = Any()

  /** Offline as a connection error leaves it, as the response hook marks it. */
  private fun ConnectivityState.loseConnection() = apply { setOffline(true) }

  @Test
  fun aValidatedNetworkClearsOfflineAndAsksForARetry() = runTest {
    val h = Harness { throw IOException("unreachable") }
    h.session.restore()
    runCatching { h.posts.keyset(emptyList()) }
    assertTrue(h.connectivity.offline.value)
    assertTrue(h.connectivity.unreachable.value)
    val watcher = NetworkWatcher(h.connectivity)

    watcher.onDefaultNetwork(wifi, validated = true)

    assertFalse(h.connectivity.offline.value)
    assertFalse(h.connectivity.unreachable.value)
    assertEquals(1, h.connectivity.retryRequests.value)
  }

  @Test
  fun aServerErrorStaysOfflineAndRetriesNothingWhenTheNetworkComesBack() = runTest {
    val h = Harness { json("{}", HttpStatusCode.BadGateway) }
    h.session.restore()
    runCatching { h.posts.keyset(emptyList()) }
    assertTrue(h.connectivity.offline.value)
    assertFalse(h.connectivity.unreachable.value)

    NetworkWatcher(h.connectivity).onDefaultNetwork(wifi, validated = true)

    // A network says nothing about Sankaku's servers: offline until a response says otherwise.
    assertTrue(h.connectivity.offline.value)
    assertEquals(0, h.connectivity.retryRequests.value)
  }

  @Test
  fun aResponseBeforeTheCallbackStillLeavesTheFailedLoadsToRetry() = runTest {
    var fail = true
    val h = Harness { if (fail) throw IOException("unreachable") else json(Fixtures.page()) }
    h.session.restore()
    runCatching { h.posts.keyset(emptyList()) }

    // The network is up and one request gets through before Android validates it.
    fail = false
    h.posts.keyset(emptyList())
    assertFalse(h.connectivity.offline.value)
    NetworkWatcher(h.connectivity).onDefaultNetwork(wifi, validated = true)

    assertEquals(1, h.connectivity.retryRequests.value)
    assertFalse(h.connectivity.connectionFailedSinceRetry)
  }

  @Test
  fun aNetworkWhileNothingFailedRetriesNothing() {
    val connectivity = ConnectivityState()

    // Registering at start, or a handoff from Wi-Fi to mobile while online.
    NetworkWatcher(connectivity).onDefaultNetwork(wifi, validated = true)

    assertEquals(0, connectivity.retryRequests.value)
  }

  @Test
  fun waitsForTheNetworkToBeValidated() {
    val connectivity = ConnectivityState().loseConnection()
    val watcher = NetworkWatcher(connectivity)

    watcher.onDefaultNetwork(wifi, validated = false)
    assertTrue(connectivity.offline.value)
    assertEquals(0, connectivity.retryRequests.value)

    watcher.onDefaultNetwork(wifi, validated = true)
    assertFalse(connectivity.offline.value)
    assertEquals(1, connectivity.retryRequests.value)
  }

  @Test
  fun announcesEachNetworkOnceUntilItsLost() {
    val connectivity = ConnectivityState()
    val watcher = NetworkWatcher(connectivity)

    watcher.onDefaultNetwork(wifi, validated = true)
    connectivity.loseConnection()
    // Android repeats the capabilities on any change (signal strength, say): not a network coming back.
    watcher.onDefaultNetwork(wifi, validated = true)
    assertEquals(0, connectivity.retryRequests.value)

    watcher.onLost(wifi)
    watcher.onDefaultNetwork(wifi, validated = true)
    assertEquals(1, connectivity.retryRequests.value)

    connectivity.loseConnection()
    watcher.onDefaultNetwork(mobile, validated = true)
    assertEquals(2, connectivity.retryRequests.value)
  }

  @Test
  fun aNetworkThatLostItsValidationIsAnnouncedAgainWhenItRegainsIt() {
    val connectivity = ConnectivityState()
    val watcher = NetworkWatcher(connectivity)

    watcher.onDefaultNetwork(wifi, validated = true)
    watcher.onDefaultNetwork(wifi, validated = false)
    connectivity.loseConnection()
    watcher.onDefaultNetwork(wifi, validated = true)

    assertEquals(1, connectivity.retryRequests.value)
    assertFalse(connectivity.offline.value)
  }

  @Test
  fun aRetryRequestLeavesOfflineToTheNextResponse() {
    val connectivity = ConnectivityState().loseConnection()

    connectivity.requestRetry()

    assertTrue(connectivity.offline.value)
    assertEquals(1, connectivity.retryRequests.value)
  }
}
