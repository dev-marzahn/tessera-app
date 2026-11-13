package dev.marzahn.sankaku.data

import androidx.paging.testing.asSnapshot
import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding a grid's saved place again (#162): its cursor, then `date:`, then paging from the top. */
class GridPlaceTest {
  private val artist = listOf("artist_a")

  // 2025-10-29T12:00:00Z, the fixture posts' upload time.
  private val time = 1_761_739_200L

  private val dated = "date:2000-01-01..2025-10-29T13:00:00"

  private fun harness(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    Harness(InMemoryTokenStore(storedTokens()), handler = answer)

  private fun Harness.keysetRequests() = requestsTo("/v2/posts/keyset")

  private val HttpRequestData.tags get() = url.parameters["tags"]
  private val HttpRequestData.next get() = url.parameters["next"]

  @Test
  fun aSavedCursorWhosePageStillHoldsThePostStartsThere() = runTest {
    val h = harness { request ->
      if (request.next == "c5") json(Fixtures.page("c6", "p8", "p9")) else json(Fixtures.page("c2", "p1"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val start = repo.locate(artist, GridPlace("p9", "c5", 5, time))

    assertEquals(GridStart(artist, PageCursor("c5", 5)), start)
    assertTrue(start!!.midway)
    assertEquals(1, h.keysetRequests().size)
  }

  @Test
  fun aRejectedCursorFindsThePlaceByDate() = runTest {
    val h = harness { request ->
      when {
        request.next == "expired" -> json("""{"code":"invalid-parameters"}""", HttpStatusCode.BadRequest)
        request.tags == "artist_a $dated" && request.next == null -> json(Fixtures.page("d2", "p7", "p8"))
        request.tags == "artist_a $dated" && request.next == "d2" -> json(Fixtures.page("d3", "p9"))
        else -> json(Fixtures.page(null, "older"))
      }
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val start = repo.locate(artist, GridPlace("p9", "expired", 5, time))

    assertEquals(GridStart(artist + dated, PageCursor("d2", 2)), start)
    // The pager's first page is the one locate loaded.
    val posts = repo.posts(artist, start).asSnapshot()
    assertEquals(listOf("p9", "older"), posts.map { it.id })
    assertEquals(1, h.keysetRequests().count { it.next == "d2" })
    // It goes on in the dated search, which holds every older post.
    assertTrue(h.keysetRequests().filter { it.next == "d3" }.all { it.tags == "artist_a $dated" })
  }

  @Test
  fun newPostsAboveAFirstPagePlaceAreSkippedByDate() = runTest {
    // The post was on the first page; newer posts have pushed it down since.
    val h = harness { request ->
      if (request.tags == "artist_a $dated") json(Fixtures.page("d2", "p3")) else json(Fixtures.page("c2", "new1", "new2"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val start = repo.locate(artist, GridPlace("p3", time = time))

    assertEquals(GridStart(artist + dated, PageCursor()), start)
    assertTrue(start!!.midway)
  }

  @Test
  fun aMissingPostIsNotFound() = runTest {
    val h = harness { request ->
      if (request.next == null) json(Fixtures.page("c2", "p1")) else json(Fixtures.page(null, "p2"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    assertNull(repo.locate(artist, GridPlace("gone", "c5", 5, time)))
    // The cursor's page, then the dated search to its end.
    assertEquals(3, h.keysetRequests().size)
  }

  @Test
  fun aSearchNotOrderedByTimePagesFromTheTopUpToItsCap() = runTest {
    val popular = listOf("order:popularity", "artist_a")
    val h = harness { request ->
      if (request.next == "stale") return@harness json(Fixtures.page("n5", "q-stale"))
      val page = request.url.parameters["page"]!!.toInt()
      json(Fixtures.page("n${page + 1}", if (page == 4) "p9" else "q$page"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    val start = repo.locate(popular, GridPlace("p9", "stale", 4, time))

    // The stale cursor's page didn't hold it; the fourth page from the top does.
    assertEquals(GridStart(popular, PageCursor("n4", 4)), start)
    assertTrue(h.keysetRequests().none { it.tags!!.contains("date:") })

    assertNull(repo.locate(popular, GridPlace("never", time = time)))
    assertEquals(PostsRepository.PLACE_SCAN_PAGES, h.keysetRequests().map { it.url.parameters["page"] }.toSet().size)
  }

  @Test
  fun forYouIsNeitherDatedNorScanned() = runTest {
    val h = harness { json(Fixtures.page("n2", "p1")) }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)

    assertNull(repo.locate(GridPresets.forYou, GridPlace("p9", "c3", 3, time)))
    assertEquals(1, h.keysetRequests().size)
  }

  @Test
  fun aRefreshOfAGridStartedAtItsPlaceGoesToTheTop() = runTest {
    val h = harness { request ->
      if (request.next == "c5") json(Fixtures.page(null, "p9")) else json(Fixtures.page(null, "p1"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val start = GridStart(artist, PageCursor("c5", 5))

    assertEquals(listOf("p9"), repo.posts(artist, start).asSnapshot().map { it.id })
    assertEquals(listOf("p1"), repo.posts(artist, start).asSnapshot { refresh() }.map { it.id })
  }

  @Test
  fun eachLoadedPostIsReportedWithItsPagesCursor() = runTest {
    val h = harness { request ->
      if (request.next == "c2") json(Fixtures.page(null, "p3")) else json(Fixtures.page("c2", "p1", "p2"))
    }
    val repo = PostsRepository(h.posts, PostStateStore(), h.session, backgroundScope)
    val cursors = mutableMapOf<String, PageCursor>()

    repo.posts(artist, onLoaded = { cursor, posts -> posts.forEach { cursors[it.id] = cursor } })
      .asSnapshot { appendScrollWhile { true } }

    assertEquals(mapOf("p1" to PageCursor(), "p2" to PageCursor(), "p3" to PageCursor("c2", 2)), cursors)
  }

  @Test
  fun theDatedSearchEndsAfterTheUploadHour() {
    // 12:34:56 UTC: up to and including 12:xx, so the end is 13:00.
    assertEquals(artist + "date:2000-01-01..2025-10-29T13:00:00", datedSearch(artist, time + 34 * 60 + 56))
    // The last second of the day rolls over to the next.
    assertEquals("date:2000-01-01..2025-10-30T00:00:00", datedSearch(emptyList(), 1_761_782_399).last())
  }

  @Test
  fun onlyTimeOrderedSearchesUseDate() {
    assertTrue(timeOrdered(artist))
    assertTrue(timeOrdered(GridPresets.following))
    assertFalse(timeOrdered(GridPresets.forYou))
    assertFalse(timeOrdered(listOf("artist_a", "order:popularity")))
    assertFalse(timeOrdered(listOf("artist_a", "date:2024-01-01..2025-01-01")))
  }

  @Test
  fun aFirstPagePlaceAtTheTopOfItsOwnSearchIsNotMidway() {
    assertFalse(GridStart(artist, PageCursor()).midway)
  }
}
