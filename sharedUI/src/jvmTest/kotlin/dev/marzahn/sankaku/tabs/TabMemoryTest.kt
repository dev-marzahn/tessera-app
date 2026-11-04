package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage
import kotlin.test.Test
import kotlin.test.assertEquals

class TabMemoryTest {
  private val grid = TabContents(
    preview = emptyList(),
    images = listOf("a", "b", "c").map { PostImage(it, it, Variant.Preview, null) },
    posts = 3,
  )

  @Test
  fun countsTheTabsDecodedImagesOnceAndItsPosts() {
    val post = TabContents.post("a", "a")
    val cached = mapOf("a-Preview" to 1_000L, "a-Sample" to 50_000L, "b-Preview" to 2_000L, "z-Preview" to 9_999L)

    val bytes = estimateTabBytes(listOf(grid, post), cached)

    assertEquals(1_000L + 50_000L + 2_000L + 4 * POST_BYTES, bytes)
  }

  @Test
  fun aGridPreviewsFromWhereItWasScrolledTo() {
    val posts = (0 until 20).map { PostDto(id = "p$it", md5 = "m$it", rating = "s", status = "active") }

    assertEquals((10 until 16).map { "p$it" }, TabContents.grid(posts, firstShown = 10).preview.map { it.postId })
    assertEquals((14 until 20).map { "p$it" }, TabContents.grid(posts, firstShown = 19).preview.map { it.postId })
    assertEquals(60, TabContents.grid(posts, firstShown = 0).images.size)
  }

  @Test
  fun formatsLikeChrome() {
    assertEquals("640 KB", formatBytes(640L * 1024))
    assertEquals("12.4 MB", formatBytes((12.4 * 1024 * 1024).toLong()))
    assertEquals("1.2 GB", formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))
  }
}
