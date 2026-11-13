package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.api.dto.TagDto
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagsRepositoryTest {
  private val h = Harness { request -> json(Fixtures.autocomplete(excluded = request.url.parameters["tag"]!!.startsWith("-"))) }
  private val repo = TagsRepository(h.tags)

  @Test
  fun nothingIsFetchedBelowTwoCharacters() = runTest {
    assertTrue(repo.suggest("b").isEmpty())
    assertTrue(repo.suggest("-b").isEmpty())
    assertTrue(repo.suggest("  ").isEmpty())

    assertEquals(0, h.requests.size)
  }

  @Test
  fun backspacingToAnEarlierPrefixDoesNotRefetch() = runTest {
    repo.suggest("bl")
    repo.suggest("blu")
    val again = repo.suggest("bl")

    assertEquals(2, h.requests.size)
    assertEquals(listOf("blue_hair", "blue_hat"), again.map { it.tagName })
  }

  @Test
  fun anExcludedPrefixKeepsTheDashOnTheSearchName() = runTest {
    val found = repo.suggest("-blu")

    assertEquals(listOf("-blue_hair", "-blue_hat"), found.map { it.tagName })
    assertEquals("-blu", h.requests.single().url.parameters["tag"])
    assertEquals(setOf("blue_hair", "blue_hat"), repo.known.value.keys)
  }

  @Test
  fun learntTagsSurviveARestart() = runTest {
    val file = InMemoryTextFile()
    TagsRepository(h.tags, file).suggest("blu")

    val restarted = TagsRepository(h.tags, file)

    assertEquals("Blue Hair", restarted.known.value["blue_hair"]?.displayName)
  }

  @Test
  fun forgettingEmptiesWhatWasLearntAndTheFile() = runTest {
    val file = InMemoryTextFile()
    val learning = TagsRepository(h.tags, file)
    learning.suggest("blu")

    learning.forget()

    assertTrue(learning.known.value.isEmpty())
    assertTrue(TagsRepository(h.tags, file).known.value.isEmpty())
    // The suggestions fetched went too: typing the same prefix asks again.
    val before = h.requests.size
    learning.suggest("blu")
    assertEquals(before + 1, h.requests.size)
  }

  @Test
  fun lookingUpAsksOncePerUnknownTagAndSkipsFilters() = runTest {
    repo.lookUp(listOf("blue_hair", "-blue_hair", "rating:s", "x"))
    repo.lookUp(listOf("blue_hair", "nothing_like_it"))
    repo.lookUp(listOf("nothing_like_it"))

    assertEquals(listOf("blue_hair", "nothing_like_it"), h.requests.map { it.url.parameters["tag"] })
    assertEquals(setOf("blue_hair"), repo.known.value.keys)
  }

  @Test
  fun aTagTheApiAnsweredWithoutIsUnknownUntilLearnt() = runTest {
    repo.lookUp(listOf("blue_hair", "-Nothing_Like_It", "rating:s", "x"))

    assertEquals(setOf("nothing_like_it"), repo.unknown.value)

    repo.learnFromPosts(
      listOf(TagDto(name = "Nothing Like It", tagName = "nothing_like_it", postCount = 1)),
    )

    assertEquals(emptySet(), repo.unknown.value)
  }

  @Test
  fun aFailedLookUpIsNotUnknown() = runTest {
    val failing = Harness { respondError(HttpStatusCode.InternalServerError) }
    val repo = TagsRepository(failing.tags)

    repo.lookUp(listOf("nothing_like_it"))

    assertEquals(emptySet(), repo.unknown.value)
  }
}
