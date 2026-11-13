package dev.marzahn.sankaku.data

import androidx.paging.testing.asSnapshot
import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.NOW
import dev.marzahn.sankaku.api.SankakuJson
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PostsRepositoryTest {
  private val post: PostDto = SankakuJson.decodeFromString(Fixtures.post(favourited = false))

  @Test
  fun failedFavouriteRevertsToThePreviousState() = runTest {
    val store = PostStateStore()
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("{}", HttpStatusCode.InternalServerError) }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)

    val result = repo.toggleFavourite(post)

    assertTrue(result.isFailure)
    val override = store.overrides.value.getValue(post.id)
    assertEquals(false, override.isFavourited)
    assertEquals(post.favCount, override.favCount)
    assertFalse(override.inFlight)
  }

  @Test
  fun aVoteIsAPutWithItsScoreAndTakesTheResponsesCount() = runTest {
    val store = PostStateStore()
    var sent: Pair<HttpMethod, String>? = null
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      sent = request.method to (request.body as io.ktor.http.content.TextContent).text
      json("""{"success":true,"post_id":"${post.id}","vote_count":${post.voteCount + 1},"score":4}""")
    }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)

    assertTrue(repo.vote(post, 4).isSuccess)

    assertEquals(HttpMethod.Put, sent?.first)
    assertEquals("""{"score":4}""", sent?.second)
    val voted = post.merge(store.overrides.value[post.id])
    assertEquals(4, voted.userVote)
    assertEquals(post.voteCount + 1, voted.voteCount)
    assertEquals(post.totalScore + 4, voted.totalScore)
  }

  @Test
  fun aFailedVoteRestoresTheVoteAndTotals() = runTest {
    val store = PostStateStore()
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("{}", HttpStatusCode.InternalServerError) }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)

    assertTrue(repo.vote(post.copy(userVote = 2), 5).isFailure)

    val after = post.merge(store.overrides.value[post.id])
    assertEquals(2, after.userVote)
    assertEquals(post.voteCount, after.voteCount)
    assertEquals(post.totalScore, after.totalScore)
  }

  @Test
  fun changingOrTakingBackAVoteMovesTheTotals() {
    val voted = VoteState(userVote = 2, voteCount = 3, totalScore = 10)
    assertEquals(VoteState(5, 3, 13), votedAs(voted, 5))
    assertEquals(VoteState(null, 2, 8), votedAs(voted, null))
    assertEquals(VoteState(4, 1, 4), votedAs(VoteState(null, 0, 0), 4))
  }

  @Test
  fun secondToggleShowsAtOnceAndSurvivesTheFirstFailing() = runTest {
    val store = PostStateStore()
    // Each favourite call waits on a gate the test completes: true answers 200, false 500.
    val pending = Channel<CompletableDeferred<Boolean>>(Channel.UNLIMITED)
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      val gate = CompletableDeferred<Boolean>().also { pending.send(it) }
      if (gate.await()) json(Fixtures.FAVOURITE) else json("{}", HttpStatusCode.InternalServerError)
    }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)

    val first = launch { repo.toggleFavourite(post) }
    runCurrent()
    val second = launch { repo.toggleFavourite(post) }
    runCurrent()

    // The second toggle shows before the first call returns.
    assertEquals(false, store.overrides.value.getValue(post.id).isFavourited)
    assertTrue(store.overrides.value.getValue(post.id).inFlight)

    pending.receive().complete(false)
    first.join()
    // The first call failed, but the second toggle owns the post: nothing was rolled back.
    assertEquals(false, store.overrides.value.getValue(post.id).isFavourited)
    assertTrue(store.overrides.value.getValue(post.id).inFlight)

    pending.receive().complete(true)
    second.join()
    assertEquals(false, store.overrides.value.getValue(post.id).isFavourited)
    assertFalse(store.overrides.value.getValue(post.id).inFlight)
    assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), h.requests.map { it.method })
  }

  @Test
  fun doubleToggleWhereBothSucceedEndsInTheSecondState() = runTest {
    val store = PostStateStore()
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.FAVOURITE) }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)

    val jobs = listOf(launch { repo.toggleFavourite(post) }, launch { repo.toggleFavourite(post) })
    jobs.forEach { it.join() }

    val override = store.overrides.value.getValue(post.id)
    assertEquals(false, override.isFavourited)
    assertEquals(post.favCount, override.favCount)
    assertFalse(override.inFlight)
  }

  @Test
  fun aListTakesOnlyTheStateThatSettledBeforeItWasAskedFor() {
    val store = PostStateStore()
    // p1: favourited and settled before the list was asked for. p2: in flight then. p3: favourited after the ask.
    // p4: a full post's vote before the ask; p6: a full post's vote and URLs after it.
    store.setFavourite("p1", true, 8, inFlight = false, generation = store.beginToggle("p1", true, 8))
    store.setUrls(post.copy(id = "p1", fileUrl = "older"))
    val p2Toggle = store.beginToggle("p2", value = true, favCount = 8)
    store.setServerVote(post.copy(id = "p4", userVote = 5))
    val whenAsked = store.overrides.value
    store.setFavourite("p2", true, 8, inFlight = false, generation = p2Toggle)
    store.setFavourite("p3", true, 8, inFlight = false, generation = store.beginToggle("p3", true, 8))
    val fresh = post.copy(id = "p6", userVote = 5, voteCount = 11, totalScore = 50, fileUrl = "fresh")
    store.setServerVote(fresh)
    store.setUrls(fresh)

    val listed = { id: String -> post.copy(id = id, isFavorited = false, favCount = 3, voteCount = 9, totalScore = 40) }
    store.takeListed(listOf("p1", "p2", "p3", "p4", "p5", "p6").map(listed), whenAsked)

    val all = store.overrides.value
    assertEquals(false to 3, all.getValue("p1").let { it.isFavourited to it.favCount }, "settled: the server's state")
    assertEquals(post.fileUrl, all.getValue("p1").urls?.file, "settled: the list's URLs")
    assertEquals(true to 8, all.getValue("p2").let { it.isFavourited to it.favCount }, "in flight when asked")
    assertEquals(true to 8, all.getValue("p3").let { it.isFavourited to it.favCount }, "toggled after the ask")
    assertEquals(VoteState(5, 9, 40), all.getValue("p4").vote, "a list has no own vote, only the totals")
    assertNull(all["p5"], "a post without an override gets none")
    assertEquals(VoteState(5, 11, 50), all.getValue("p6").vote, "a full post newer than the list keeps its totals")
    assertEquals("fresh", all.getValue("p6").urls?.file, "and its URLs")
    assertNull(all.getValue("p4").isFavourited, "a vote-only override gets no favourite, left to the loaded copy")
    assertNull(all.getValue("p6").favCount, "a URL-only override neither")
    assertNull(all.getValue("p3").urls, "a favourite-only override gets no URLs")
  }

  /**
   * #347: favourite a post, open one of its tags (a search whose page lists the post), come back to the post. Every
   * copy loaded before the favourite (the full post, the grid's earlier page) still shows it favourited.
   */
  @Test
  fun aFavouriteSurvivesASearchThatListsThePostAgain() = runTest {
    var serverFavourited = false
    val store = PostStateStore()
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      when (request.url.encodedPath) {
        "/posts/p1" -> json(Fixtures.fullPost("p1").replace("\"is_favorited\": false", "\"is_favorited\": $serverFavourited"))
        "/posts/p1/favorite" -> {
          serverFavourited = true
          json(Fixtures.FAVOURITE)
        }
        else -> json(Fixtures.page(null, "p1").replace("\"is_favorited\": false", "\"is_favorited\": $serverFavourited"))
      }
    }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)
    fun shown(copy: PostDto?) = checkNotNull(copy).merge(store.overrides.value["p1"]).isFavorited

    val origin = repo.page(listOf("a")).data.single()
    val full = repo.fullPost("p1")
    assertTrue(repo.toggleFavourite(full).isSuccess)
    assertTrue(shown(repo.heldPost("p1")))

    // A tap on one of its tags opens a search whose page lists the post, as the server has it now.
    repo.page(listOf("blue_hair"))

    assertTrue(shown(repo.heldPost("p1")), "the post's sheet, back through the queue")
    assertTrue(shown(origin), "the cell in the grid it was favourited from")
    assertTrue(shown(full), "the full post loaded before the favourite")
  }

  @Test
  fun changeOfUserClearsPostState() = runTest {
    val store = PostStateStore()
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("{}") }
    h.session.restore()
    PostsRepository(h.posts, store, h.session, backgroundScope)
    runCurrent()
    store.setUrls(post)

    h.session.signOut()
    runCurrent()

    assertTrue(store.overrides.value.isEmpty())
  }

  @Test
  fun concurrentRefetchesMakeOneRequest() = runTest {
    val store = PostStateStore()
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.page(next = null, "p1")) }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)

    val results = (1..10).map { async { repo.refetch("p1") } }.awaitAll()

    assertEquals(10, results.size)
    assertEquals(1, h.requests.size)
    assertEquals(post.fileUrl, store.overrides.value.getValue("p1").urls?.file)
  }

  @Test
  fun recommendedDropsThePostItselfAndHiddenPostsAndIsFetchedOncePerSearch() = runTest {
    val hidden = Fixtures.post("p3").replace("\"status\": \"active\",", "\"status\": \"active\", \"redirect_to_signup\": true,")
      .replace(Regex("\"preview_url\": \"[^\"]*\","), "")
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("[${Fixtures.post("p1")}, ${Fixtures.post("p2")}, $hidden]") }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val first = repo.recommended("p1")
    val again = repo.recommended("p1")
    repo.recommended("p1", listOf("rating:s"))

    assertEquals(listOf("p2"), first.map { it.id })
    assertEquals(first, again)
    assertEquals(2, h.requestsTo("/v2/posts").size)
  }

  /** #342: the Children strip is one `parent:<id>` keyset request per post and search, without hidden posts. */
  @Test
  fun childrenAreOneParentSearchPerPostAndSearch() = runTest {
    val hidden = Fixtures.post("c3").replace("\"status\": \"active\",", "\"status\": \"active\", \"redirect_to_signup\": true,")
      .replace(Regex("\"preview_url\": \"[^\"]*\","), "")
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      json("""{ "meta": { "next": null, "prev": null }, "data": [${Fixtures.post("c1")}, ${Fixtures.post("c2")}, $hidden] }""")
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val first = repo.children("p1")
    val again = repo.children("p1")
    repo.children("p1", listOf("rating:s"))

    assertEquals(listOf("c1", "c2"), first.posts.map { it.id })
    assertFalse(first.mayHaveMore, "3 of 20: that's all of them")
    assertEquals(first, again)
    val asked = h.requestsTo("/v2/posts/keyset")
    assertEquals(2, h.requests.size)
    assertEquals(listOf("parent:p1", "parent:p1 rating:s"), asked.map { it.url.parameters["tags"] })
    assertEquals("20", asked.first().url.parameters["limit"])
  }

  /** #342: a full first page may have more, also when some of it is hidden here, so the "All" tile still shows. */
  @Test
  fun aFullPageOfChildrenMayHaveMoreEvenWithSomeHidden() = runTest {
    val hidden = Fixtures.post("c0").replace("\"status\": \"active\",", "\"status\": \"active\", \"redirect_to_signup\": true,")
    val shown = (1..19).joinToString(",") { Fixtures.post("c$it") }
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{ "meta": { "next": null, "prev": null }, "data": [$shown, $hidden] }""") }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val children = repo.children("p1")

    assertEquals(19, children.posts.size)
    assertTrue(children.mayHaveMore)
  }

  /** #342: a held parent with nothing to show (no preview) is asked for, not left out. */
  @Test
  fun aHeldParentWithoutAPreviewIsAskedFor() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.page(null, "p0")) }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val parent: PostDto = SankakuJson.decodeFromString(Fixtures.post("p0"))
    repo.hold(parent.copy(previewUrl = null))

    assertEquals("p0", repo.parent("p0")?.id)
    assertEquals(listOf("id:p0"), h.requests.map { it.url.parameters["tags"] })
  }

  /** #342: a parent the app holds costs no request; one it doesn't is one `id:` request, asked once. */
  @Test
  fun aHeldParentIsShownWithoutARequestAndAnotherIsAskedOnce() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.page(null, "p0")) }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val parent: PostDto = SankakuJson.decodeFromString(Fixtures.post("p0"))

    assertEquals("p0", repo.parent("p0")?.id)
    assertEquals("p0", repo.parent("p0")?.id)
    assertEquals(listOf("id:p0"), h.requests.map { it.url.parameters["tags"] }, "asked once")

    val held = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    held.hold(parent.copy(id = "h0"))
    h.requests.clear()
    assertEquals("h0", held.parent("h0")?.id)
    assertEquals("h0", held.parent("h0", listOf("rating:s"))?.id, "rated s, so the lock lets it through")
    assertTrue(h.requests.isEmpty(), "a held parent costs no request")
  }

  /** #342: with the rating locked, a held parent of another rating is asked for with `rating:s`, which leaves it out. */
  @Test
  fun aHeldParentTheLockHidesIsAskedForWithTheLock() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json("""{ "meta": { "next": null, "prev": null }, "data": [] }""") }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val parent: PostDto = SankakuJson.decodeFromString(Fixtures.post("p0"))
    repo.hold(parent.copy(rating = "q"))

    assertNull(repo.parent("p0", listOf("rating:s")))
    assertNull(repo.parent("p0", listOf("rating:s")))
    assertEquals(listOf("id:p0 rating:s"), h.requests.map { it.url.parameters["tags"] }, "asked once, with the lock")
  }

  @Test
  fun fullPostHasEveryTagAndIsFetchedOnce() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.fullPost("p1", tagCount = 59)) }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val first = repo.fullPost("p1")
    val second = repo.fullPost("p1")

    assertEquals(59, first.tags.size)
    assertEquals(first, second)
    assertEquals(1, h.requestsTo("/posts/p1").size)
    assertEquals(1, h.requests.size)
  }

  /** #321: a recommendation held before its lone post opens is the held post, never over a copy already held. */
  @Test
  fun aHeldRecommendationIsTheHeldPostUntilTheAccountChanges() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.fullPost("p1")) }
    h.session.restore()
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    runCurrent()

    repo.hold(post)
    assertEquals(post, repo.heldPost(post.id))
    assertTrue(h.requests.isEmpty(), "holding fetches nothing")

    val full = repo.fullPost("p1")
    repo.hold(full.copy(fileUrl = "other"))
    assertEquals(full, repo.heldPost("p1"), "a post already held keeps its copy")

    h.session.signOut()
    runCurrent()
    assertNull(repo.heldPost(post.id), "another account's held posts are dropped")
  }

  /** #277: a lone post's first frame reads the held post without suspending; nothing is fetched for it. */
  @Test
  fun heldPostIsTheCachedPostWithoutWaiting() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.fullPost("p1")) }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    assertNull(repo.heldPost("p1"))
    val full = repo.fullPost("p1")
    assertEquals(full, repo.heldPost("p1"))
    assertEquals(1, h.requests.size)
  }

  @Test
  fun concurrentFullPostCallersShareOneRequestAndGetFreshUrls() = runTest {
    val store = PostStateStore()
    val fresh = "https://s.sankakucomplex.com/data/01/23/x.png?e=9&m=fresh"
    val gate = CompletableDeferred<Unit>()
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      gate.await()
      json(Fixtures.fullPost("p1", fileUrl = fresh))
    }
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)

    val callers = (1..10).map { async { repo.fullPost("p1") } }
    runCurrent()
    gate.complete(Unit)
    callers.awaitAll()

    assertEquals(1, h.requests.size)
    assertEquals(fresh, store.overrides.value.getValue("p1").urls?.file)
  }

  @Test
  fun fullPostCacheForgetsTheLeastRecentlyUsedPost() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      json(Fixtures.fullPost(request.url.encodedPath.substringAfterLast('/'), tagCount = 2))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val size = PostsRepository.FULL_POST_CACHE_SIZE
    // Fill the cache with p0..p(size-1), use p0 again, then one more post pushes out p1, the oldest unused.
    (0 until size).forEach { repo.fullPost("p$it") }
    repo.fullPost("p0")
    repo.fullPost("p$size")
    repo.fullPost("p0")
    repo.fullPost("p1")

    assertEquals(1, h.requestsTo("/posts/p0").size)
    assertEquals(2, h.requestsTo("/posts/p1").size)
  }

  /** Two keyset pages for one search: p1, p2, then p3, p4. */
  @Test
  fun forgettingASearchsPagesMakesTheNextLoadAskTheApiAndLeavesOtherSearches() = runTest {
    val h = twoPages()
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope, MovableClock())
    repo.page(listOf("a"))
    repo.page(listOf("b"))
    assertEquals(listOf("p1", "p2"), repo.cachedFirstPage(listOf("a"))?.map { it.id })

    repo.forgetPages(listOf("a"))

    assertEquals(null, repo.cachedFirstPage(listOf("a")))
    repo.page(listOf("a"))
    repo.page(listOf("b"))
    assertEquals(3, h.requests.size)
  }

  private fun twoPages() = Harness(InMemoryTokenStore(storedTokens())) { request ->
    if (request.url.parameters["next"] == "c2") json(Fixtures.page(null, "p3", "p4")) else json(Fixtures.page("c2", "p1", "p2"))
  }

  private class MovableClock(var now: Instant = NOW) : Clock {
    override fun now() = now
  }

  @Test
  fun aPageLoadedAgainComesFromTheCacheUntilItExpires() = runTest {
    val h = twoPages()
    val clock = MovableClock()
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope, clock)

    repo.page(listOf("a"))
    val again = repo.page(listOf("a"))
    assertEquals(listOf("p1", "p2"), again.data.map { it.id })
    assertEquals(1, h.requests.size)

    // Another search is its own page.
    repo.page(listOf("b"))
    assertEquals(2, h.requests.size)

    clock.now += PostsRepository.PAGE_CACHE_TTL
    repo.page(listOf("a"))
    assertEquals(3, h.requests.size)
  }

  @Test
  fun cachedIndexOfCountsOnlyTheCachedPagesFromTheFirst() = runTest {
    val h = twoPages()
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val tags = listOf("a")

    repo.page(tags)
    assertEquals(1, repo.cachedIndexOf(tags, "p2"))
    assertEquals(null, repo.cachedIndexOf(tags, "p4"))

    repo.page(tags, PageCursor("c2", 2))
    assertEquals(3, repo.cachedIndexOf(tags, "p4"))
    assertEquals(null, repo.cachedIndexOf(tags, "p9"))
    assertEquals(null, repo.cachedIndexOf(listOf("b"), "p1"))
  }

  /** #507: signed out, the grid leaves out posts hidden from signed-out users, says so, and counts without them. */
  @Test
  fun theGridLeavesOutPostsHiddenFromSignedOutUsers() = runTest {
    val hidden = """{ "id": "p2", "md5": "m2", "rating": "s", "status": "active", "redirect_to_signup": true }"""
    val body = Fixtures.page(null, "p1", "p3").replace("\"data\": [", "\"data\": [$hidden,")
    val h = Harness(InMemoryTokenStore()) { json(body) }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val tags = listOf("a")
    var lockedPages = 0

    val posts = repo.posts(tags, onLocked = { lockedPages++ }).asSnapshot()

    assertEquals(listOf("p1", "p3"), posts.map { it.id })
    assertEquals(1, lockedPages)
    assertEquals(1, repo.cachedIndexOf(tags, "p3"))
    assertEquals(null, repo.cachedIndexOf(tags, "p2"))
  }

  /**
   * Shaped like For you (2026-10-31): it pages by the `page` number and ignores `next`, and pages repeat a post now
   * and then. Without `page` it answered page 1 again, with the `next` it was asked with, which put every post in the
   * grid twice (#72).
   */
  private fun forYouFeed() = Harness(InMemoryTokenStore(storedTokens())) { request ->
    when (request.url.parameters["page"]) {
      "2" -> json(Fixtures.page("n3", "p3", "p2", "p4"))
      "3" -> json(Fixtures.page("n4", "p5"))
      "4" -> json(Fixtures.page(null))
      else -> json(Fixtures.page(request.url.parameters["next"] ?: "n2", "p1", "p2"))
    }
  }

  @Test
  fun forYouShowsEachPostOnceInTheFeedsOrder() = runTest {
    val h = forYouFeed()
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val posts = repo.posts(GridPresets.forYou).asSnapshot { appendScrollWhile { true } }

    assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), posts.map { it.id })
    assertEquals(listOf("1", "2", "3", "4"), h.requestsTo("/v2/posts/keyset").map { it.url.parameters["page"] })
  }

  @Test
  fun cachedIndexOfCountsARepeatedPostOnceAsTheGridDoes() = runTest {
    val h = forYouFeed()
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val tags = GridPresets.forYou

    repo.page(tags)
    repo.page(tags, PageCursor("n2", 2))

    assertEquals(2, repo.cachedIndexOf(tags, "p3"))
    assertEquals(3, repo.cachedIndexOf(tags, "p4"))
  }

  @Test
  fun aPostFromAPageIsCachedByIdAndTheFullPostWins() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      if (request.url.encodedPath == "/posts/p1") json(Fixtures.fullPost("p1", tagCount = 9)) else json(Fixtures.page(null, "p1"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    assertEquals(null, repo.cachedPost("p1"))
    repo.page(listOf("a"))
    assertEquals("p1", repo.cachedPost("p1")?.id)

    repo.fullPost("p1")
    assertEquals(9, repo.cachedPost("p1")?.tags?.size)
    // The batch lookup gives the same copies, and leaves out what isn't held.
    assertEquals(mapOf("p1" to 9), repo.cachedPosts(listOf("p1", "p9")).mapValues { it.value.tags.size })
  }

  @Test
  fun changeOfUserClearsCachedPagesAndPosts() = runTest {
    val h = twoPages()
    h.session.restore()
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    runCurrent()
    repo.page(listOf("a"))

    h.session.signOut()
    runCurrent()

    assertEquals(null, repo.cachedPost("p1"))
    assertEquals(null, repo.cachedIndexOf(listOf("a"), "p1"))
    repo.page(listOf("a"))
    assertEquals(2, h.requestsTo("/v2/posts/keyset").size)
  }

  @Test
  fun aResponseForThePreviousUserIsNotCachedAfterTheChange() = runTest {
    val gate = CompletableDeferred<Unit>()
    // The engine answers on its own dispatcher, so the test waits for each request to arrive rather than runCurrent.
    val arrived = Channel<String>(Channel.UNLIMITED)
    val h = Harness(InMemoryTokenStore(storedTokens())) { request ->
      val path = request.url.encodedPath
      arrived.send(path)
      gate.await()
      when (path) {
        "/v2/posts" -> json("[${Fixtures.post("p2")}]")
        "/posts/p1" -> json(Fixtures.fullPost("p1"))
        else -> json(Fixtures.page(next = null, "p1"))
      }
    }
    suspend fun arrivals(n: Int) = List(n) { arrived.receive() }.sorted()
    h.session.restore()
    val store = PostStateStore()
    val repo = PostsRepository(h.posts, store, h.session, backgroundScope)
    runCurrent()
    // Two hosts of one strip at once share the request.
    val recommended = listOf(async { repo.recommended("p1") }, async { repo.recommended("p1") })
    val full = async { repo.fullPost("p1") }
    val page = async { repo.page(listOf("a")) }
    assertEquals(listOf("/posts/p1", "/v2/posts", "/v2/posts/keyset"), arrivals(3))

    h.session.signOut()
    runCurrent()
    // Asked again after the change, while the previous account's requests are still out: new requests, not theirs.
    val recommendedAfter = async { repo.recommended("p1") }
    val fullAfter = async { repo.fullPost("p1") }
    assertEquals(listOf("/posts/p1", "/v2/posts"), arrivals(2))

    // The new account's own favourite (settled, not in flight), which the previous account's page, listing p1, must not
    // drop. (Not a vote: the new account's full post rightly sets that from the server.)
    store.setFavourite("p1", true, 8, inFlight = false, generation = store.beginToggle("p1", value = true, favCount = 8))
    gate.complete(Unit)
    recommended.awaitAll()
    full.await()
    page.await()
    recommendedAfter.await()
    fullAfter.await()

    // Only the new account's answers are kept: one more call for each is served from memory.
    repo.recommended("p1")
    repo.fullPost("p1")
    assertEquals(2, h.requestsTo("/v2/posts").size)
    assertEquals(2, h.requestsTo("/posts/p1").size)
    assertEquals(null, repo.cachedIndexOf(listOf("a"), "p1"))
    assertEquals(true, store.overrides.value["p1"]?.isFavourited)
  }

  /** #317: the queue's preload puts a grid's first page in the cache, and the grid then starts from it. */
  @Test
  fun aPreloadedFirstPageIsTheGridsWithNoRequestOfItsOwn() = runTest {
    val h = Harness(InMemoryTokenStore(storedTokens())) { json(Fixtures.page(null, "p1", "p2")) }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val tags = listOf("a")

    assertEquals(listOf("p1", "p2"), repo.gridStart(tags).map { it.id })
    assertEquals(listOf("p1", "p2"), repo.cachedFirstPage(tags)?.map { it.id })
    val seen = mutableListOf<List<String>>()
    val posts = repo.posts(tags, onPage = { page -> seen += page.map { it.id } }).asSnapshot()

    assertEquals(listOf("p1", "p2"), posts.map { it.id })
    assertEquals(1, h.requestsTo("/v2/posts/keyset").size)
    // The grid still sees the page once, for its previews, though the preload asked for it.
    assertEquals(listOf(listOf("p1", "p2")), seen)
    repo.posts(tags, onPage = { page -> seen += page.map { it.id } }).asSnapshot()
    assertEquals(1, seen.size)
  }

  /** A grid shown while its preload's request is still on the way waits for that request instead of making one. */
  @Test
  fun aGridJoinsThePreloadsRequest() = runTest {
    val gate = CompletableDeferred<Unit>()
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      gate.await()
      json(Fixtures.page(null, "p1"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val tags = listOf("a")

    val preload = launch { repo.gridStart(tags) }
    runCurrent()
    var seen = 0
    val grid = async { repo.page(tags, onPage = { seen++ }) }
    runCurrent()
    // Landed: the preload has moved on, but the grid still wants the page.
    preload.cancel()
    runCurrent()
    gate.complete(Unit)

    assertEquals(listOf("p1"), grid.await().data.map { it.id })
    assertEquals(1, h.requestsTo("/v2/posts/keyset").size)
    assertEquals(1, seen)
  }

  /** A preload no one else waits for is cancelled with its request when its item stops being a neighbour. */
  @Test
  fun aPreloadCancelledAloneCancelsItsRequest() = runTest {
    // The engine answers on its own threads: the test waits for what it does.
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        cancelled.complete(Unit)
      }
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val preload = launch { repo.gridStart(listOf("a")) }
    started.await()
    preload.cancel()

    cancelled.await()
    assertEquals(1, h.requests.size)
    assertNull(repo.cachedFirstPage(listOf("a")))
  }

  /** A refresh while the page is still on the way asks again, and the answer from before it isn't cached. */
  @Test
  fun aRefreshDoesNotJoinARequestFromBeforeIt() = runTest {
    val first = CompletableDeferred<Unit>()
    val arrived = CompletableDeferred<Unit>()
    var asked = 0
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      if (++asked == 1) {
        arrived.complete(Unit)
        first.await()
        json(Fixtures.page(null, "old"))
      } else {
        json(Fixtures.page(null, "new"))
      }
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val tags = listOf("a")

    val preload = async { repo.gridStart(tags) }
    // The engine answers on its own threads: the preload's request is there before the refresh.
    arrived.await()
    repo.forgetPages(tags)
    assertEquals(listOf("new"), repo.page(tags).data.map { it.id })
    first.complete(Unit)
    assertEquals(listOf("old"), preload.await().map { it.id })

    assertEquals(2, h.requestsTo("/v2/posts/keyset").size)
    assertEquals(listOf("new"), repo.cachedFirstPage(tags)?.map { it.id })
  }

  /** A request a refresh dropped still stops once its last caller leaves: no one else can join it. */
  @Test
  fun aDroppedRequestIsCancelledWhenItsLastCallerLeaves() = runTest {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val h = Harness(InMemoryTokenStore(storedTokens())) {
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        cancelled.complete(Unit)
      }
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val preload = launch { repo.gridStart(listOf("a")) }
    started.await()
    repo.forgetPages(listOf("a"))
    preload.cancel()

    cancelled.await()
    assertEquals(1, h.requests.size)
  }
}
