package dev.marzahn.sankaku.update

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.NOW
import dev.marzahn.sankaku.PUBLIC_REPO_URL
import dev.marzahn.sankaku.api.BrowserFingerprint
import dev.marzahn.sankaku.api.HeaderSet
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckTest {
  private class MovableClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now
  }

  private fun release(tag: String, url: String = "$PUBLIC_REPO_URL/releases/tag/$tag") =
    """{ "tag_name": "$tag", "html_url": "$url", "name": "Tessera $tag", "draft": false, "prerelease": false }"""

  /** A check over a [MockEngine] answering with [handler]; [requests] holds what it sent. */
  private class Fixture(
    val file: InMemoryTextFile = InMemoryTextFile(),
    val clock: MovableClock = MovableClock(),
    current: String = "100.0.0",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ) {
    val requests = mutableListOf<HttpRequestData>()
    val check = UpdateCheck(
      file,
      createGitHubClient(MockEngine { requests += it; handler(it) }),
      clock,
      ReleaseVersion.parse(current),
    )
  }

  /**
   * Waits, in real time, for what [start][UpdateCheck.start] does in the background: the mock engine answers on its own
   * thread, so the test scheduler's idle doesn't wait for it.
   */
  private suspend fun <T> eventually(read: () -> T, done: (T) -> Boolean): T = withContext(Dispatchers.Default) {
    withTimeout(5.seconds) {
      while (!done(read())) delay(10)
      read()
    }
  }

  @Test
  fun versionsCompareAsNumbersInTheVxyzScheme() {
    assertEquals(ReleaseVersion(100, 0, 0), ReleaseVersion.parse("v100.0.0"))
    assertEquals(ReleaseVersion(100, 0, 0), ReleaseVersion.parse("100.0.0"))
    assertTrue(ReleaseVersion.parse("v100.10.0")!! > ReleaseVersion.parse("v100.9.0")!!)
    assertTrue(ReleaseVersion.parse("v101.0.0")!! > ReleaseVersion.parse("v100.99.99")!!)
    assertTrue(ReleaseVersion.parse("v100.0.1")!! > ReleaseVersion.parse("v100.0.0")!!)
    assertEquals(0, ReleaseVersion.parse("v100.0.0")!!.compareTo(ReleaseVersion.parse("100.0.0")!!))
    listOf("", "v100", "v100.0", "v100.0.0-rc1", "latest", "v1.2.3.4", "vx.y.z").forEach {
      assertNull(ReleaseVersion.parse(it), it)
    }
  }

  @Test
  fun aNewerReleaseIsAvailableWithItsPage() = runTest {
    val f = Fixture { json(release("v100.1.0")) }
    f.check.checkIfDue()
    advanceUntilIdle()
    assertEquals(AvailableUpdate(ReleaseVersion(100, 1, 0), "$PUBLIC_REPO_URL/releases/tag/v100.1.0"), f.check.available.value)
    assertEquals(1, f.requests.size)
    assertEquals(LATEST_RELEASE_URL, f.requests.single().url.toString())
  }

  @Test
  fun theSameOrAnOlderReleaseIsNoUpdate() = runTest {
    listOf("v100.0.0", "v99.9.9").forEach { tag ->
      val f = Fixture { json(release(tag)) }
      f.check.checkIfDue()
      advanceUntilIdle()
      assertNull(f.check.available.value, tag)
    }
  }

  @Test
  fun noReleaseYetIsNoUpdateAndCountsAsTheDaysCheck() = runTest {
    val f = Fixture { json("""{ "message": "Not Found", "status": "404" }""", HttpStatusCode.NotFound) }
    f.check.checkIfDue()
    f.check.checkIfDue()
    advanceUntilIdle()
    assertNull(f.check.available.value)
    assertEquals(1, f.requests.size)
  }

  @Test
  fun failuresAreSilentAndTriedAgainNextLaunch() = runTest {
    val answers = mutableListOf<suspend MockRequestHandleScope.() -> HttpResponseData>(
      { throw IOException("offline") },
      { respond("", HttpStatusCode.ServiceUnavailable) },
      { json("""{ "message": "API rate limit exceeded" }""", HttpStatusCode.Forbidden) },
      { json("not json") },
      { json(release("v100.2.0")) },
    )
    val f = Fixture { answers.removeAt(0)() }
    repeat(4) { f.check.checkIfDue() }
    advanceUntilIdle()
    assertNull(f.check.available.value)
    f.check.checkIfDue()
    advanceUntilIdle()
    assertEquals(ReleaseVersion(100, 2, 0), f.check.available.value?.version)
    assertEquals(5, f.requests.size)
  }

  @Test
  fun asksAtMostOnceADay() = runTest {
    val clock = MovableClock()
    val f = Fixture(clock = clock) { json(release("v100.1.0")) }
    f.check.checkIfDue()
    clock.now = NOW + 23.hours
    f.check.checkIfDue()
    assertEquals(1, f.requests.size)
    clock.now = NOW + 24.hours
    f.check.checkIfDue()
    assertEquals(2, f.requests.size)
  }

  @Test
  fun theDayAndTheReleaseOutliveARestart() = runTest {
    val file = InMemoryTextFile()
    val clock = MovableClock()
    Fixture(file, clock) { json(release("v100.1.0")) }.check.checkIfDue()
    clock.now = NOW + 2.hours
    val next = Fixture(file, clock) { error("asked again within the day") }
    next.check.checkIfDue()
    advanceUntilIdle()
    assertEquals(ReleaseVersion(100, 1, 0), next.check.available.value?.version)
    assertTrue(next.requests.isEmpty())
  }

  @Test
  fun anUpdatedAppNoLongerShowsTheRelease() = runTest {
    val file = InMemoryTextFile()
    Fixture(file) { json(release("v100.1.0")) }.check.checkIfDue()
    val updated = Fixture(file, current = "100.1.0") { error("asked again within the day") }
    advanceUntilIdle()
    assertNull(updated.check.available.value)
  }

  @Test
  fun aPageOffTheMirrorFallsBackToItsLatestRelease() = runTest {
    val f = Fixture { json(release("v100.1.0", url = "https://example.com/evil")) }
    f.check.checkIfDue()
    advanceUntilIdle()
    assertEquals("$PUBLIC_REPO_URL/releases/latest", f.check.available.value?.url)
  }

  @Test
  fun theSwitchIsOnByDefaultAndOffAsksNothingAndHidesTheDot() = runTest {
    val file = InMemoryTextFile()
    val f = Fixture(file) { json(release("v100.1.0")) }
    assertTrue(f.check.enabled.value)
    f.check.checkIfDue()
    advanceUntilIdle()
    assertEquals(ReleaseVersion(100, 1, 0), f.check.available.value?.version)

    f.check.setEnabled(false)
    advanceUntilIdle()
    assertNull(f.check.available.value)

    val clock = MovableClock(NOW + 48.hours)
    val restarted = Fixture(file, clock) { json(release("v100.2.0")) }
    assertFalse(restarted.check.enabled.value)
    restarted.check.checkIfDue()
    advanceUntilIdle()
    assertTrue(restarted.requests.isEmpty())
    assertNull(restarted.check.available.value)
  }

  @Test
  fun startWaitsForTheFirstLaunchScreenAndChecksWhenTurnedOn() = runTest {
    val f = Fixture { json(release("v100.1.0")) }
    val accepted = MutableStateFlow(false)
    f.check.setEnabled(false)
    f.check.start(backgroundScope, accepted)
    advanceUntilIdle()
    assertTrue(f.requests.isEmpty())

    accepted.value = true
    advanceUntilIdle()
    assertTrue(f.requests.isEmpty())

    f.check.setEnabled(true)
    assertEquals(ReleaseVersion(100, 1, 0), eventually({ f.check.available.value }) { it != null }?.version)
    assertEquals(1, f.requests.size)
  }

  /** The switch and answers changing at once on several threads: what shows and what's kept agree at the end. */
  @Test
  fun theSwitchAndAnAnswerAtOnceLeaveTheDotTheSwitchAndTheFileAgreeing() = runTest {
    repeat(50) { round ->
      val clock = MovableClock()
      val f = Fixture(clock = clock) { json(release("v100.1.0")) }
      // Known from the start, so the dot shows whenever the switch is on.
      f.check.checkIfDue()
      withContext(Dispatchers.Default) {
        val jobs = List(8) { worker ->
          launch {
            repeat(20) { i ->
              if (worker % 2 == 0) {
                f.check.setEnabled((i + worker) % 3 != 0)
              } else {
                clock.now += 25.hours
                f.check.checkIfDue()
              }
            }
          }
        }
        jobs.joinAll()
      }
      val enabled = f.check.enabled.value
      assertEquals(enabled, f.check.available.value != null, "round $round: the dot follows the switch")
      assertTrue(f.file.read()!!.contains("\"enabled\":$enabled") || (enabled && "\"enabled\"" !in f.file.read()!!), "round $round: ${f.file.read()}")
    }
  }

  @Test
  fun startChecksAtLaunchOnceAccepted() = runTest {
    val f = Fixture { json(release("v100.1.0")) }
    f.check.start(backgroundScope, MutableStateFlow(true))
    assertEquals(ReleaseVersion(100, 1, 0), eventually({ f.check.available.value }) { it != null }?.version)
    assertEquals(1, f.requests.size)
  }

  /**
   * Over the engine the signed-in main client uses, as the app wires it: GitHub gets no bearer token, no cookie, no
   * Sankaku endpoint header and none of the browser fingerprint (#204), only its own User-Agent and media type.
   */
  @Test
  fun theRequestCarriesNoSankakuHeadersTokensOrFingerprint() = runTest {
    val h = Harness(store = InMemoryTokenStore(storedTokens())) { request ->
      if (request.url.host == "api.github.com") json(release("v100.1.0")) else json(Fixtures.USER_ME)
    }
    h.session.restore()
    h.users.me()
    val sankaku = h.requests.last { it.url.host == "sankakuapi.com" }
    assertTrue(sankaku.headers[HttpHeaders.Authorization]!!.startsWith("Bearer "), "the main client is signed in")

    UpdateCheck(InMemoryTextFile(), createGitHubClient(h.engine), MovableClock()).checkIfDue()
    val github = h.requests.last()
    assertEquals("api.github.com", github.url.host)
    assertEquals("/repos/dev-marzahn/tessera-app/releases/latest", github.url.encodedPath)
    assertTrue(github.url.parameters.isEmpty())
    val names = github.headers.names().map { it.lowercase() }.toSet()
    assertFalse(HttpHeaders.Authorization.lowercase() in names)
    assertFalse(HttpHeaders.Cookie.lowercase() in names)
    assertTrue(names.none { it.startsWith("sec-ch-") }, "client hints: $names")
    // GitHub's own Accept is allowed; Sankaku's value for it, or any other Sankaku endpoint header, isn't.
    HeaderSet.entries.flatMap { it.headers.entries }.forEach { (name, value) ->
      assertFalse(github.headers.getAll(name).orEmpty().contains(value), name)
      if (!name.equals(HttpHeaders.Accept, ignoreCase = true)) assertFalse(name.lowercase() in names, name)
    }
    val fingerprint = BrowserFingerprint.Current
    assertTrue(github.headers[HttpHeaders.UserAgent]!!.startsWith("Tessera/"))
    assertFalse(github.headers[HttpHeaders.UserAgent] == fingerprint.userAgent)
    assertTrue(
      github.headers.entries().none { (_, values) -> values.any { it.contains("old-access") || it.contains("old-refresh") } },
    )
    assertEquals("application/vnd.github+json", github.headers[HttpHeaders.Accept])
  }
}
