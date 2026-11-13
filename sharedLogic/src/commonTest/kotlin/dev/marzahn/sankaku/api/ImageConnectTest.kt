package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.json
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The original-file host drops a new connection's first packets now and then (2026-11-11: 3 of 20 connects took
 * 1 to 2 s, 3 more none within 3 s), so image hosts give up on a connect early and try again.
 */
class ImageConnectTest {
  private fun connectTimeout(h: Harness, host: String) =
    h.requests.first { it.url.host == host }.getCapabilityOrNull(HttpTimeoutCapability)?.connectTimeoutMillis

  @Test
  fun imageHostsConnectWithTheShortTimeoutAndTheApiWithTheLongOne() = runTest {
    val h = Harness { request ->
      if (request.url.host in IMAGE_HOSTS) respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
      else json("{}")
    }
    h.mainClient.get("https://s.sankakucomplex.com/o/x.png?e=1")
    h.mainClient.get("https://v.sankakucomplex.com/data/x.jpg?e=1")
    h.mainClient.get("$API_BASE/users/me")

    assertEquals(IMAGE_CONNECT_TIMEOUT_MS, connectTimeout(h, "s.sankakucomplex.com"))
    assertEquals(IMAGE_CONNECT_TIMEOUT_MS, connectTimeout(h, "v.sankakucomplex.com"))
    assertEquals(CONNECT_TIMEOUT_MS, connectTimeout(h, API_HOST))
  }

  @Test
  fun anImageWhoseConnectsTimeOutIsTriedAgainUntilOneConnects() = runTest {
    var attempts = 0
    val h = Harness { _ ->
      attempts++
      if (attempts <= IMAGE_CONNECT_RETRIES) throw ConnectTimeoutException("connect timed out", null)
      respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
    }

    val response = h.mainClient.get("https://s.sankakucomplex.com/o/x.png?e=1")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(IMAGE_CONNECT_RETRIES + 1, attempts)
  }

  @Test
  fun anImageWhoseReadsFailIsTriedAgainOnlyTwice() = runTest {
    var attempts = 0
    val h = Harness { _ ->
      attempts++
      throw IOException("read timed out")
    }

    assertFailsWith<IOException> { h.mainClient.get("https://s.sankakucomplex.com/o/x.png?e=1") }
    assertEquals(IMAGE_RETRIES + 1, attempts)
  }
}
