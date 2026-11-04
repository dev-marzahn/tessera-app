package dev.marzahn.sankaku.net

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.html
import dev.marzahn.sankaku.json
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectivityStateTest {
  @Test
  fun connectionErrorSetsOfflineAndNextSuccessClearsIt() = runTest {
    var fail = true
    val h = Harness { if (fail) throw IOException("unreachable") else json(Fixtures.page()) }
    h.session.restore()

    runCatching { h.posts.keyset(emptyList()) }
    assertTrue(h.connectivity.offline.value)

    fail = false
    h.posts.keyset(emptyList())
    assertFalse(h.connectivity.offline.value)
  }

  @Test
  fun serverErrorSetsOffline() = runTest {
    val h = Harness { json("{}", HttpStatusCode.BadGateway) }
    h.session.restore()

    runCatching { h.posts.keyset(emptyList()) }

    assertTrue(h.connectivity.offline.value)
    assertFalse(h.connectivity.rejected.value)
  }

  @Test
  fun forbiddenFromTheApiSetsRejectedNotOffline() = runTest {
    var forbidden = true
    val h = Harness { if (forbidden) json("{}", HttpStatusCode.Forbidden) else json(Fixtures.page()) }
    h.session.restore()

    runCatching { h.posts.keyset(emptyList()) }
    assertTrue(h.connectivity.rejected.value)
    assertFalse(h.connectivity.offline.value)

    forbidden = false
    h.posts.keyset(emptyList())
    assertFalse(h.connectivity.rejected.value)
  }

  @Test
  fun nonJsonBodyFromTheApiSetsRejected() = runTest {
    val h = Harness { html() }
    h.session.restore()

    runCatching { h.posts.keyset(emptyList()) }

    assertTrue(h.connectivity.rejected.value)
    assertFalse(h.connectivity.offline.value)
  }
}
