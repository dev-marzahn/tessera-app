package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.json
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Calls every endpoint the `*Api.kt` files build and checks the request log keeps none of the ids, md5s, tag names
 * or queries it was given (#540). Lives in jvmTest so reflection can check no public call is left out.
 */
class EndpointLogTest {
  private val harness = Harness { json("{}", HttpStatusCode.NotFound) }
  private val posts = PostsApi(harness.mainClient)
  private val books = BooksApi(harness.mainClient)
  private val tags = TagsApi(harness.mainClient)
  private val blacklist = BlacklistApi(harness.mainClient)
  private val users = UsersApi(harness.mainClient)
  private val auth = harness.authApi

  private val calls: Map<String, suspend () -> Unit> = mapOf(
    "PostsApi.keyset" to { posts.keyset(listOf(TAG), next = SECRET) },
    "PostsApi.byId" to { posts.byId(POST_ID) },
    "PostsApi.full" to { posts.full(POST_ID) },
    "PostsApi.recommended" to { posts.recommended(POST_ID, listOf(TAG)) },
    "PostsApi.children" to { posts.children(NUMERIC_ID, listOf(TAG)) },
    "PostsApi.byIdOrNull" to { posts.byIdOrNull(NUMERIC_ID, listOf(TAG)) },
    "PostsApi.favourite" to { posts.favourite(POST_ID) },
    "PostsApi.unfavourite" to { posts.unfavourite(NUMERIC_ID) },
    "PostsApi.vote" to { posts.vote(POST_ID, 4) },
    "PostsApi.unvote" to { posts.unvote(NUMERIC_ID) },
    "BooksApi.list" to { books.list(listOf(TAG)) },
    "BooksApi.pages" to { books.pages(BOOK_ID) },
    "BooksApi.firstPages" to { books.firstPages(BOOK_ID) },
    "BooksApi.booksOf" to { books.booksOf(POST_ID) },
    "BooksApi.series" to { books.series(SERIES_ID) },
    "BooksApi.saveReading" to { books.saveReading(BOOK_ID, 2) },
    "BooksApi.clearReading" to { books.clearReading(BOOK_ID) },
    "BooksApi.favourite" to { books.favourite(BOOK_ID, on = true) },
    "TagsApi.autocomplete" to { tags.autocomplete(TAG) },
    "TagsApi.tagAndWiki" to { tags.tagAndWiki(TAG) },
    "TagsApi.followedTags" to { tags.followedTags(page = 2) },
    "TagsApi.follow" to { tags.follow(TAG_ID, TAG) },
    "TagsApi.unfollow" to { tags.unfollow(TAG_ID, TAG) },
    "BlacklistApi.usedRules" to { blacklist.usedRules() },
    "BlacklistApi.hideTag" to { blacklist.hideTag(TAG) },
    "BlacklistApi.remove" to { blacklist.remove(RULE_ID) },
    "UsersApi.me" to { users.me() },
    "AuthApi.passwordLogin" to { auth.passwordLogin(SECRET, SECRET) },
    "AuthApi.exchange" to { auth.exchange(SECRET) },
    "AuthApi.refresh" to { auth.refresh(SECRET) },
  )

  @Test
  fun everyPublicApiCallIsCovered() {
    val apis = listOf(PostsApi::class, BooksApi::class, TagsApi::class, BlacklistApi::class, UsersApi::class, AuthApi::class)
    val public = apis.flatMap { api ->
      api.java.declaredMethods
        .filter { !it.isSynthetic && Modifier.isPublic(it.modifiers) && it.parameterTypes.lastOrNull() == Continuation::class.java }
        .map { "${api.simpleName}.${it.name.substringBefore('$')}" }
    }.toSet()
    assertEquals(public, calls.keys)
  }

  @Test
  fun noLoggedPathKeepsAnIdMd5TagOrQuery() = runTest {
    for ((name, call) in calls) {
      val before = harness.log.entries.value.size
      try {
        call()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // A 404 fails most calls; only the logged request matters here.
      }
      val logged = harness.log.entries.value.drop(before)
      assertTrue(logged.isNotEmpty(), "$name logged no request")
      for (entry in logged) {
        val template = entry.pathTemplate
        assertFalse('?' in template, "$name: $template")
        for (secret in SECRETS) assertFalse(secret in template, "$name: $template keeps $secret")
        assertFalse(Regex("[0-9a-f]{32}").containsMatchIn(template), "$name: $template")
      }
    }
    // The paths themselves did carry the values, so the checks above saw something to strip.
    val sent = harness.requests.map { it.url.encodedPath }
    for (id in listOf(POST_ID, NUMERIC_ID, BOOK_ID, RULE_ID, TAG)) assertTrue(sent.any { id in it }, "no path carried $id")
  }

  private companion object {
    const val POST_ID = "aB3dE5gH7jK"
    const val NUMERIC_ID = "31415926"
    const val BOOK_ID = "bK2mN4pQ6rT"
    const val SERIES_ID = "sR9tU1vW3xY"
    const val TAG_ID = "tZ5aB7cD9eF"
    const val RULE_ID = "vkr00AbcDef"
    const val TAG = "blue_hair"
    const val SECRET = "s3cr3tV4lue"
    val SECRETS = listOf(POST_ID, NUMERIC_ID, BOOK_ID, SERIES_ID, TAG_ID, RULE_ID, TAG, SECRET)
  }
}
