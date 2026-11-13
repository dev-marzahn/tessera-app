package dev.marzahn.sankaku.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #542: desktop has no network callback, so while Sankaku is unreachable the watcher looks at which interfaces have an
 * address (no request) and, when one gains one, says the network may be back, as Android's callback does. That clears
 * `offline`, which sends the outbox, and asks the shown tab to retry, with no input from the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceWatcherTest {
  private val connectivity = ConnectivityState()
  private var interfaces: Set<String>? = setOf("en0")
  private var reads = 0

  private fun TestScope.watch() = InterfaceWatcher(connectivity, backgroundScope, connected = { reads++; interfaces }).start()

  private fun TestScope.offline() {
    connectivity.setOffline(true)
    runCurrent()
  }

  /** Past the next read and the settling time. */
  private fun TestScope.nextChange() = advanceTimeBy(InterfaceWatcher.POLL_MS + InterfaceWatcher.SETTLE_MS + 1)

  @Test
  fun theNetworkComingBackClearsOfflineAndAsksForARetry() = runTest {
    watch()
    offline()
    // Wi-Fi off: no interface with an address.
    interfaces = emptySet()
    advanceTimeBy(InterfaceWatcher.POLL_MS + 1)
    assertTrue(connectivity.offline.value)

    // Wi-Fi back.
    interfaces = setOf("en0")
    nextChange()

    assertFalse(connectivity.offline.value)
    assertEquals(1, connectivity.retryRequests.value)
  }

  @Test
  fun anotherNetworkClearsOfflineToo() = runTest {
    watch()
    offline()
    interfaces = setOf("en0", "en7")
    nextChange()

    assertFalse(connectivity.offline.value)
  }

  @Test
  fun nothingChangedSaysNothing() = runTest {
    watch()
    offline()
    advanceTimeBy(10 * InterfaceWatcher.POLL_MS)

    assertTrue(connectivity.offline.value)
    assertEquals(0, connectivity.retryRequests.value)
  }

  // An address rotating or renewing keeps the interface's name in the set: nothing came back.
  @Test
  fun oneOfTwoNetworksGoingAwaySaysNothing() = runTest {
    interfaces = setOf("en0", "en7")
    watch()
    offline()
    interfaces = setOf("en0")
    advanceTimeBy(10 * InterfaceWatcher.POLL_MS)

    assertTrue(connectivity.offline.value)
  }

  // A read that fails (an interface vanishing as it's read) isn't "no network": the next read isn't a change.
  @Test
  fun aFailedReadSaysNothing() = runTest {
    watch()
    offline()
    interfaces = null
    advanceTimeBy(InterfaceWatcher.POLL_MS + 1)
    interfaces = setOf("en0")
    advanceTimeBy(10 * InterfaceWatcher.POLL_MS)

    assertTrue(connectivity.offline.value)
  }

  // A 5xx doesn't set offline (#520, ConnectivityStateTest), so it's the next case. Online, it reads nothing: the watch costs only while Sankaku is unreachable.
  @Test
  fun onlineItReadsNothing() = runTest {
    watch()
    advanceTimeBy(10 * InterfaceWatcher.POLL_MS)

    assertEquals(0, reads)
  }

  // One try per return of the network (REVIEW.md): one that still can't connect waits for focus or the next request.
  @Test
  fun aTryThatFailsIsNotRepeated() = runTest {
    watch()
    offline()
    interfaces = setOf("en0", "en7")
    nextChange()
    assertEquals(1, connectivity.retryRequests.value)
    offline()

    advanceTimeBy(60_000)

    assertTrue(connectivity.offline.value)
    assertEquals(1, connectivity.retryRequests.value)
  }

  // A later outage starts from the interfaces as they are then, not from the last one's.
  @Test
  fun aLaterOutageStartsFromItsOwnInterfaces() = runTest {
    watch()
    offline()
    interfaces = setOf("en0", "en7")
    nextChange()
    assertFalse(connectivity.offline.value)
    // A VPN came up while online; hours on, Sankaku is unreachable with nothing else changing.
    interfaces = setOf("en0", "en7", "utun4")
    advanceTimeBy(60_000)
    offline()
    advanceTimeBy(10 * InterfaceWatcher.POLL_MS)

    assertTrue(connectivity.offline.value)
    assertEquals(1, connectivity.retryRequests.value)
  }
}
