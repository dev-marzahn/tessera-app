package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.SankakuJson
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Favourites, votes and follows made with no connection wait in the [Outbox] and are sent once it's back (#505). */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxSenderTest {
  private val post: PostDto = SankakuJson.decodeFromString(Fixtures.post(favourited = false))
  private val tag = TagDto(name = "Blue Hair", tagName = "blue_hair", id = "t1")

  /** What Sankaku does with each request: no connection, or an answer. */
  private enum class Net { Down, Up, Refuses, Fails }

  private class Rig(
    scope: CoroutineScope,
    val file: TextFile = InMemoryTextFile(),
    tokens: TokenStore = InMemoryTokenStore(storedTokens()),
    var net: Net = Net.Down,
  ) {
    val h = Harness(tokens) { request ->
      when (net) {
        Net.Down -> throw IOException("unreachable")
        Net.Refuses -> json("""{"success":false,"code":"nope"}""", HttpStatusCode.NotFound)
        Net.Fails -> json("{}", HttpStatusCode.InternalServerError)
        Net.Up -> if (request.url.encodedPath.endsWith("/vote")) {
          json("""{"success":true,"vote_count":5}""")
        } else {
          json("""{"success":true}""")
        }
      }
    }
    val outbox = Outbox(file) { h.session.state.value.let { (it as? dev.marzahn.sankaku.auth.AuthState.SignedIn)?.user?.id } }
    val store = PostStateStore()
    val repository = PostsRepository(h.posts, store, h.session, scope, outbox = outbox)
    val follows = TagFollows(h.tags, outbox)
    val sender = OutboxSender(outbox, repository, follows, h.session, h.connectivity, scope)

    fun changes(path: String) = h.requests.filter { it.url.encodedPath == path && it.method != HttpMethod.Get }

    /** Waits for the round a trigger started: its requests run on the engine's threads, past [runCurrent]. */
    suspend fun roundDone() = sender.idle()
  }

  /** The connection may be back: the round that starts, finished. */
  private suspend fun TestScope.retry(rig: Rig) {
    rig.h.connectivity.requestRetry()
    runCurrent()
    rig.roundDone()
  }

  private suspend fun TestScope.rig(file: TextFile = InMemoryTextFile()): Rig {
    val rig = Rig(backgroundScope, file)
    rig.h.session.restore()
    rig.sender.start()
    runCurrent()
    return rig
  }

  @Test
  fun aFavouriteWithNoConnectionStaysAndWaits() = runTest {
    val rig = rig()

    assertTrue(rig.repository.toggleFavourite(post).isSuccess)

    assertEquals(true, post.merge(rig.store.overrides.value[post.id]).isFavorited)
    assertEquals(post.favCount + 1, post.merge(rig.store.overrides.value[post.id]).favCount)
    assertEquals(1, rig.outbox.changes.value.size)
  }

  @Test
  fun itIsSentWhenTheConnectionIsBack() = runTest {
    val rig = rig()
    rig.repository.toggleFavourite(post)
    rig.net = Net.Up

    retry(rig)

    assertEquals(listOf(HttpMethod.Post, HttpMethod.Post), rig.changes("/posts/${post.id}/favorite").map { it.method })
    assertTrue(rig.outbox.changes.value.isEmpty())
    val override = rig.store.overrides.value.getValue(post.id)
    assertEquals(true, override.isFavourited)
    assertFalse(override.inFlight)
  }

  @Test
  fun aFavouriteAndAnUnfavouriteOfflineSendNothingLater() = runTest {
    val rig = rig()
    rig.repository.toggleFavourite(post)
    rig.repository.toggleFavourite(post)
    rig.net = Net.Up
    val tried = rig.h.requests.size

    retry(rig)

    assertEquals(tried, rig.h.requests.size)
    assertEquals(false, post.merge(rig.store.overrides.value[post.id]).isFavorited)
  }

  @Test
  fun aWaitingFavouriteSurvivesARestartAndIsSentAtLaunch() = runTest {
    val file = InMemoryTextFile()
    rig(file).repository.toggleFavourite(post)

    val again = Rig(backgroundScope, file, net = Net.Up)
    again.h.session.restore()
    runCurrent()
    assertEquals(true, post.merge(again.store.overrides.value[post.id]).isFavorited)
    again.sender.start()
    runCurrent()
    again.roundDone()

    assertEquals(HttpMethod.Post, again.changes("/posts/${post.id}/favorite").single().method)
    assertTrue(again.outbox.changes.value.isEmpty())
    assertEquals(true, post.merge(again.store.overrides.value[post.id]).isFavorited)
  }

  @Test
  fun aChangeAToggleSettledSinceIsNotSent() = runTest {
    // A round took its list, then the user unfavourited online before the round reached the post.
    val rig = rig()
    rig.repository.toggleFavourite(post)
    val stale = rig.outbox.changes.value.single() as PendingChange.Favourite
    rig.net = Net.Up
    rig.repository.toggleFavourite(post)
    val tried = rig.h.requests.size

    assertEquals(null, rig.repository.send(stale))

    assertEquals(tried, rig.h.requests.size)
    assertEquals(false, post.merge(rig.store.overrides.value[post.id]).isFavorited)
  }

  @Test
  fun aToggleThatFailsToConnectWaitsBeforeTheNextToggleRuns() = runTest {
    // Toggle A gets no answer; toggle B, made meanwhile, gets through. A's change must wait before B runs, so B settles
    // it, rather than A's landing after B and being sent later.
    val gate = CompletableDeferred<Unit>()
    var calls = 0
    val gated = Harness(InMemoryTokenStore(storedTokens())) {
      if (calls++ == 0) {
        gate.await()
        throw IOException("unreachable")
      }
      json("""{"success":true}""")
    }
    gated.session.restore()
    val outbox = Outbox(InMemoryTextFile()) { "42" }
    val store = PostStateStore()
    val repository = PostsRepository(gated.posts, store, gated.session, backgroundScope, outbox = outbox)
    val a = async { repository.toggleFavourite(post) }
    runCurrent()
    val b = async { repository.toggleFavourite(post.copy(isFavorited = true)) }
    runCurrent()

    gate.complete(Unit)
    a.await()
    b.await()

    assertTrue(outbox.changes.value.isEmpty())
  }

  @Test
  fun aWaitingFavouriteOutlivesAListThatPredatesIt() = runTest {
    val rig = rig()
    rig.repository.toggleFavourite(post)
    val asked = rig.store.overrides.value

    rig.store.takeListed(listOf(post), asked)

    assertEquals(true, post.merge(rig.store.overrides.value[post.id]).isFavorited)
  }

  @Test
  fun aRefusalOnSendingRevertsAndIsReported() = runTest {
    val rig = rig()
    val refusals = mutableListOf<OutboxSender.Refusal>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.sender.refusals.toList(refusals) }
    rig.repository.toggleFavourite(post)
    rig.net = Net.Refuses

    retry(rig)

    val override = rig.store.overrides.value.getValue(post.id)
    assertEquals(false, override.isFavourited)
    assertEquals(post.favCount, override.favCount)
    assertFalse(override.inFlight)
    assertTrue(rig.outbox.changes.value.isEmpty())
    assertTrue(refusals.single().change is PendingChange.Favourite)
  }

  @Test
  fun aServerErrorOnSendingKeepsItWaiting() = runTest {
    val rig = rig()
    rig.repository.toggleFavourite(post)
    rig.net = Net.Fails

    retry(rig)

    assertEquals(1, rig.outbox.changes.value.size)
    assertEquals(true, post.merge(rig.store.overrides.value[post.id]).isFavorited)
  }

  @Test
  fun aRefusalOnTheFirstTryStillReverts() = runTest {
    val rig = rig()
    rig.net = Net.Fails

    assertTrue(rig.repository.toggleFavourite(post).isFailure)

    assertTrue(rig.outbox.changes.value.isEmpty())
    assertEquals(false, post.merge(rig.store.overrides.value[post.id]).isFavorited)
  }

  @Test
  fun aVoteWithNoConnectionWaitsAndTheLastOneIsSent() = runTest {
    val rig = rig()
    assertTrue(rig.repository.vote(post, 3).isSuccess)
    assertTrue(rig.repository.vote(post, 5).isSuccess)
    assertEquals(5, post.merge(rig.store.overrides.value[post.id]).userVote)
    rig.net = Net.Up

    retry(rig)

    val sent = rig.changes("/posts/${post.id}/vote").drop(2).single()
    assertEquals(HttpMethod.Put, sent.method)
    assertEquals("""{"score":5}""", (sent.body as io.ktor.http.content.TextContent).text)
    assertFalse(rig.store.overrides.value.getValue(post.id).voteInFlight)
  }

  @Test
  fun aFollowWithNoConnectionWaitsAndIsSent() = runTest {
    val rig = rig()
    assertTrue(rig.follows.toggle(tag).isSuccess)
    assertTrue(rig.follows.isFollowing(tag))
    rig.net = Net.Up

    retry(rig)

    assertEquals(HttpMethod.Post, rig.changes("/users/followings").drop(1).single().method)
    assertTrue(rig.outbox.changes.value.isEmpty())
    assertTrue(rig.follows.isFollowing(tag))
  }

  @Test
  fun aWaitingFollowOutlivesClearingTheFollows() = runTest {
    val rig = rig()
    rig.follows.toggle(tag)

    rig.follows.clear()

    assertTrue(rig.follows.isFollowing(tag))
  }

  @Test
  fun signingOutForgetsWhatWaits() = runTest {
    val rig = rig()
    rig.repository.toggleFavourite(post)
    rig.follows.toggle(tag)

    rig.h.session.signOut()
    runCurrent()

    assertTrue(rig.outbox.changes.value.isEmpty())
    assertFalse(rig.follows.isFollowing(tag))
    rig.net = Net.Up
    val tried = rig.h.requests.size
    retry(rig)
    assertEquals(tried, rig.h.requests.size)
  }
}
