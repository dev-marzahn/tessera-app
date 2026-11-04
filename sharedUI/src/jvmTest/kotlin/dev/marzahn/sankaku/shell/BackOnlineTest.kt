package dev.marzahn.sankaku.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.net.NetworkWatcher
import dev.marzahn.sankaku.ui.components.BACK_ONLINE_SHOWN
import dev.marzahn.sankaku.ui.components.ConnectivityBannerState
import dev.marzahn.sankaku.ui.components.connectivityBannerState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** #128: the network coming back retries what failed and clears the offline banner, through "Back online". */
@OptIn(ExperimentalTestApi::class)
class BackOnlineTest {
  /** Offline as a request that couldn't reach Sankaku leaves it: the response hook sets it. */
  private fun ConnectivityState.loseConnection() = apply {
    val client = createAuthClient(MockEngine { throw IOException("unreachable") }, RequestLog(), RateLimiter(limits = emptyMap()), this)
    runBlocking { runCatching { client.get("https://sankakuapi.com/posts") } }
    assertTrue(unreachable.value)
  }

  @Test
  fun theNetworkComingBackRetriesAShownScreenOnce() = runComposeUiTest {
    val connectivity = ConnectivityState().loseConnection()
    val watcher = NetworkWatcher(connectivity)
    var retries = 0
    setContent { RetryWhenBack(connectivity) { retries++ } }
    waitForIdle()
    assertEquals(0, retries)

    // The fake default-network callback: Wi-Fi up, then validated.
    val wifi = Any()
    watcher.onDefaultNetwork(wifi, validated = false)
    waitForIdle()
    assertEquals(0, retries)
    watcher.onDefaultNetwork(wifi, validated = true)
    waitForIdle()

    assertEquals(1, retries)
    assertEquals(false, connectivity.offline.value)
  }

  @Test
  fun desktopsWindowGainingFocusWhileOfflineAsksForARetry() = runComposeUiTest {
    val connectivity = ConnectivityState()
    var focused by mutableStateOf(true)
    var retries = 0
    setContent {
      val real = LocalWindowInfo.current
      val window = remember(real) { object : WindowInfo by real { override val isWindowFocused get() = focused } }
      CompositionLocalProvider(LocalWindowInfo provides window) {
        RetryOnWindowFocus(connectivity)
        RetryWhenBack(connectivity) { retries++ }
      }
    }
    waitForIdle()
    // Focused while online: nothing to retry.
    assertEquals(0, retries)

    connectivity.loseConnection()
    focused = false
    waitForIdle()
    assertEquals(0, retries)

    focused = true
    waitForIdle()
    assertEquals(1, retries)
    // Only the response that follows clears it.
    assertTrue(connectivity.offline.value)
  }

  @Test
  fun aScreenNotShownWhenTheNetworkCameBackRetriesWhenItIsShown() = runComposeUiTest {
    val connectivity = ConnectivityState()
    var shown by mutableStateOf(true)
    var retries = 0
    setContent {
      val holder = rememberSaveableStateHolder()
      // A tab's screen, its state saved by the tab's id while another tab is in front.
      if (shown) holder.SaveableStateProvider("tab") { RetryWhenBack(connectivity) { retries++ } }
    }
    waitForIdle()

    shown = false
    waitForIdle()
    connectivity.loseConnection().networkAvailable()
    waitForIdle()
    assertEquals(0, retries)

    shown = true
    waitForIdle()
    assertEquals(1, retries)

    // Shown again with nothing new: no retry.
    shown = false
    waitForIdle()
    shown = true
    waitForIdle()
    assertEquals(1, retries)
  }

  @Test
  fun theBannerSaysBackOnlineFromTheFrameOfflineClearsThenGoes() = runComposeUiTest {
    mainClock.autoAdvance = false
    var offline by mutableStateOf(true)
    var state: ConnectivityBannerState? = null
    // As `ConnectivityState` clears them: offline first, then unreachable.
    setContent { state = connectivityBannerState(offline, unreachable = true, rejected = false) }
    mainClock.advanceTimeByFrame()
    assertEquals(ConnectivityBannerState.Offline, state)

    offline = false
    mainClock.advanceTimeByFrame()
    assertEquals(ConnectivityBannerState.BackOnline, state)

    mainClock.advanceTimeBy((BACK_ONLINE_SHOWN - 100.milliseconds).inWholeMilliseconds)
    assertEquals(ConnectivityBannerState.BackOnline, state)
    mainClock.advanceTimeBy(200)
    assertEquals(null, state)
  }

  @Test
  fun offlineFromAServerErrorJustGoesWhenItClears() = runComposeUiTest {
    var offline by mutableStateOf(true)
    var state: ConnectivityBannerState? = null
    setContent { state = connectivityBannerState(offline, unreachable = false, rejected = false) }
    waitForIdle()
    assertEquals(ConnectivityBannerState.Offline, state)

    // Nothing reloads after a 5xx, so no "Back online · reloading".
    offline = false
    waitForIdle()
    assertEquals(null, state)
  }

  @Test
  fun rejectedOutranksOfflineAndNoBannerShowsWhileOnline() = runComposeUiTest {
    var offline by mutableStateOf(false)
    var rejected by mutableStateOf(false)
    var state: ConnectivityBannerState? = ConnectivityBannerState.Offline
    setContent { state = connectivityBannerState(offline, unreachable = offline, rejected = rejected) }
    waitForIdle()
    assertEquals(null, state)

    offline = true
    rejected = true
    waitForIdle()
    assertEquals(ConnectivityBannerState.Rejected, state)
  }
}
