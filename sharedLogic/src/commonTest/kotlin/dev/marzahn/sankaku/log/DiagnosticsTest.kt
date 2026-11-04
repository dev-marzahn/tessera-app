package dev.marzahn.sankaku.log

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.NOW
import dev.marzahn.sankaku.api.dto.UserSummary
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.StoredTokens
import dev.marzahn.sankaku.html
import dev.marzahn.sankaku.json
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * Copy log and Report a bug (#207). The privacy test runs real calls through the app's two clients, so the log sees
 * the paths, queries, tokens and account the requests carried, then checks none of it reaches the copied text.
 */
class DiagnosticsTest {
  private val environment = AppEnvironment(version = "1.2.3", platform = "Android", os = "Android 16", device = "Pixel 9")

  // Each sensitive value is distinctive, so finding it in the text can only mean it leaked.
  private val postId = "PostIdQx7Lm"
  private val md5 = "9f8e7d6c5b4a39281706f5e4d3c2b1a0"
  private val tagSearched = "searchedtagzz"
  private val userId = "UserIdKp3"
  private val userName = "NickNameVw2"
  private val accessToken = "AccessTokenYy9"
  private val refreshToken = "RefreshTokenJj4"
  private val loginToken = "LoginTokenHh5"
  private val login = "someone@example.org"
  private val password = "PasswordGg6"
  private val signature = "SignatureTt8"

  private fun post(id: String) = """
    { "id": "$id", "md5": "$md5", "rating": "s", "status": "active",
      "file_url": "https://s.sankakucomplex.com/data/9f/8e/$md5.png?e=1&m=$signature",
      "tags": [] }
  """.trimIndent()

  private val tokens = """
    { "success": true, "token_type": "Bearer", "access_token": "$accessToken", "access_token_ttl": 604800,
      "refresh_token": "$refreshToken", "refresh_token_ttl": 7776000,
      "current_user": { "id": "$userId", "name": "$userName", "avatar_url": null } }
  """.trimIndent()

  private fun harness() = Harness(
    store = InMemoryTokenStore(
      StoredTokens(accessToken, refreshToken, NOW + 7.days, NOW + 90.days, UserSummary(userId, userName, null)),
    ),
  ) { request ->
    val path = request.url.encodedPath
    when {
      request.url.host == "login.sankakucomplex.com" ->
        json("""{ "success": true, "access_token": "$loginToken", "current_user": { "id": "$userId" } }""")
      path == "/sso/token-exchange" -> json(tokens)
      path == "/users/me" -> json("""{ "success": true, "user": { "id": "$userId", "name": "$userName" } }""")
      path == "/v2/posts/keyset" -> json("""{ "meta": { "next": "Cursor$postId" }, "data": [${post(postId)}] }""")
      path == "/posts/$postId" -> json(post(postId))
      path == "/posts/$postId/favorite" -> json("""{ "success": true, "post_id": "$postId", "score": 1 }""")
      path == "/posts/$postId/vote" -> throw IOException("connection reset reaching /posts/$postId/vote?t=$accessToken")
      path.startsWith("/data/") -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
      else -> html(HttpStatusCode.NotFound)
    }
  }

  @Test
  fun copyLogHoldsNoQueriesIdsMd5sTokensOrUserFields() = runTest {
    val h = harness()
    h.session.signIn(login, password)
    h.users.me()
    h.posts.keyset(listOf(tagSearched, "rating:s"), next = "Cursor$postId")
    h.posts.full(postId)
    h.posts.byId(postId)
    h.posts.favourite(postId)
    runCatching { h.posts.vote(postId, 5) }
    h.mainClient.get("https://s.sankakucomplex.com/data/9f/8e/$md5.png?e=1&m=$signature")
    h.mainClient.get("https://v.sankakucomplex.com/data/sample/9f/8e/sample-$md5.jpg?e=1&m=$signature")

    val text = Diagnostics(h.log, environment).copyText()

    val secrets = listOf(
      postId, md5, md5.take(8), "9f/8e", tagSearched, "rating", "Cursor", userId, userName, accessToken, refreshToken,
      loginToken, login, password, signature, "Bearer", "?", "&", "=",
    )
    for (secret in secrets) assertFalse(secret in text, "Copy log holds \"$secret\":\n$text")
    // The log did see every request, so the check above isn't passing on an empty log.
    assertEquals(h.requests.size, h.log.entries.value.size)
    assertTrue("/posts/{id}/favorite" in text, text)
    assertTrue("  IOException  " in text, text)
  }

  @Test
  fun copyLogStartsWithWhereItCameFrom() {
    val log = RequestLog(FixedClock)
    log.record("GET", "sankakuapi.com", "/users/me", 200, 12, null)
    log.record("GET", "v.sankakucomplex.com", "/data/sample/01/23/sample-0123456789abcdef0123456789abcdef.jpg", null, 30_000, "SocketTimeoutException")

    assertEquals(
      """
      Tessera 1.2.3
      Platform: Android
      OS: Android 16
      Device: Pixel 9

      Requests, newest first (2):
      2026-10-29T12:00:00Z  GET  v.sankakucomplex.com  /data/sample/{md5}  SocketTimeoutException  30000 ms
      2026-10-29T12:00:00Z  GET  sankakuapi.com  /users/me  200  12 ms
      """.trimIndent(),
      Diagnostics(log, environment).copyText(),
    )
  }

  @Test
  fun anEmptyLogSaysSo() {
    assertTrue(Diagnostics(RequestLog(), environment).copyText().endsWith("Requests: none yet"))
  }

  @Test
  fun reportABugPrefillsTheBugFormButNotTheLog() {
    val log = RequestLog()
    log.record("GET", "sankakuapi.com", "/users/me", 200, 12, null)
    val diagnostics = Diagnostics(log, environment.copy(device = "Pixel 9 Pro", os = "Android 16 & more"))

    assertEquals(
      "https://github.com/dev-marzahn/tessera-app/issues/new?template=bug.yml&version=1.2.3&platform=Android" +
        "&os=Android+16+%26+more&device=Pixel+9+Pro",
      diagnostics.bugReportUrl(),
    )
    assertFalse("users" in diagnostics.bugReportUrl())
  }

  @Test
  fun aPlatformTheFormDoesntOfferIsLeftForTheUser() {
    val linux = AppEnvironment(version = "1.2.3", platform = "Linux", os = "Linux 6.8", device = "aarch64")
    val diagnostics = Diagnostics(RequestLog(), linux)

    assertEquals(
      "https://github.com/dev-marzahn/tessera-app/issues/new?template=bug.yml&version=1.2.3&os=Linux+6.8&device=aarch64",
      diagnostics.bugReportUrl(),
    )
    assertTrue("Platform: Linux" in diagnostics.copyText())
  }

  /** #208: the crash notice's Report text is the environment and the saved report, not this run's log. */
  @Test
  fun crashTextIsTheEnvironmentThenTheCrash() {
    val log = RequestLog()
    log.record("GET", "sankakuapi.com", "/users/me", 200, 12, null)
    val crash = "Crashed: 2026-10-29T12:00:00Z, Tessera 1.2.2\njava.lang.IllegalStateException\n"

    assertEquals(
      "Tessera 1.2.3\nPlatform: Android\nOS: Android 16\nDevice: Pixel 9\n\n$crash",
      Diagnostics(log, environment).crashText(crash),
    )
  }
}
