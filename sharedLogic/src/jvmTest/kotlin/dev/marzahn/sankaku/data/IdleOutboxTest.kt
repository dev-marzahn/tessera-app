package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.SankakuJson
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.net.InterfaceWatcher
import dev.marzahn.sankaku.storedTokens
import io.ktor.http.HttpMethod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #542, desktop: a favourite made with no connection is sent once the network is back, while the app sits idle: no
 * request, click or window focus, only the machine's addresses changing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleOutboxTest {
  private val post: PostDto = SankakuJson.decodeFromString(Fixtures.post(favourited = false))

  @Test
  fun aWaitingFavouriteIsSentWhenTheNetworkComesBackWithNoInput() = runTest {
    var up = false
    var interfaces = setOf("en0")
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      if (up) json("""{"success":true}""") else throw IOException("unreachable")
    }
    val outbox = Outbox(InMemoryTextFile()) { (h.session.state.value as? AuthState.SignedIn)?.user?.id }
    val store = PostStateStore()
    val posts = PostsRepository(h.posts, store, h.session, backgroundScope, outbox = outbox)
    val sender = OutboxSender(outbox, posts, TagFollows(h.tags, outbox), h.session, h.connectivity, backgroundScope)
    InterfaceWatcher(h.connectivity, backgroundScope, connected = { interfaces }).start()
    h.session.restore()
    sender.start()
    runCurrent()

    // Wi-Fi drops; the favourite waits and the app is offline.
    interfaces = emptySet()
    posts.toggleFavourite(post)
    runCurrent()
    assertTrue(h.connectivity.offline.value)
    assertEquals(1, outbox.changes.value.size)

    // Wi-Fi comes back, and nothing else happens.
    up = true
    interfaces = setOf("en0")
    advanceTimeBy(InterfaceWatcher.POLL_MS + InterfaceWatcher.SETTLE_MS + 1)
    runCurrent()
    sender.idle()

    val sent = h.requests.filter { it.url.encodedPath == "/posts/${post.id}/favorite" && it.method == HttpMethod.Post }
    assertEquals(2, sent.size)
    assertTrue(outbox.changes.value.isEmpty())
    assertFalse(h.connectivity.offline.value)
  }
}
