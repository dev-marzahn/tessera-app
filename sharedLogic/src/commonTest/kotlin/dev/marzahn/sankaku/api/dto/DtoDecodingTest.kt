package dev.marzahn.sankaku.api.dto

import dev.marzahn.sankaku.Fixtures
import dev.marzahn.sankaku.api.SankakuJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DtoDecodingTest {
  @Test
  fun pageDecodesAndIgnoresUnknownFields() {
    val page = SankakuJson.decodeFromString<Page<PostDto>>(Fixtures.page(next = "c2", "p1", "p2"))

    assertEquals("c2", page.meta.next)
    assertEquals(listOf("p1", "p2"), page.data.map { it.id })
    val post = page.data.first()
    assertEquals("0123456789abcdef0123456789abcdef", post.md5)
    assertEquals("artist", post.author?.name)
    assertEquals("image/png", post.fileType)
    assertEquals(300, post.previewWidth)
    assertEquals(3000, post.fileWidth)
    assertEquals(7, post.favCount)
    assertEquals(123456L, post.fileSize)
    assertEquals(4, post.voteCount)
    assertEquals(17, post.totalScore)
    assertEquals("p0", post.parentId)
    assertEquals(listOf(TagDto("blue_hair", 0, id = "t1")), post.tags)
  }

  @Test
  fun tagNamesListEveryTagWhileTagsMayBeShort() {
    val post = SankakuJson.decodeFromString<PostDto>(Fixtures.fullPost(tagCount = 3))

    assertEquals(listOf("tag_1", "tag_2", "tag_3"), post.tagNames)
    assertEquals(emptyList<String>(), SankakuJson.decodeFromString<PostDto>(Fixtures.post()).tagNames)
  }

  @Test
  fun createdAtIsParsedFromSecondsAndNanoseconds() {
    val post = SankakuJson.decodeFromString<PostDto>(Fixtures.post())

    assertEquals(Instant.fromEpochSeconds(1761739200, 500_000_000), post.createdAt)
  }

  @Test
  fun tagTypesMapToCategoriesAndUnknownIsOther() {
    assertEquals(TagType.Fashion, TagDto("skirt", 10).category)
    assertEquals(TagType.Setting, TagDto("1girl", 20).category)
    assertEquals(TagType.Other, TagDto("new", 6).category)
    assertEquals(TagType.Other, TagDto("new", 99).category)
  }

  @Test
  fun tagSearchNameComesFromTagNameNotTheDisplayName() {
    val tag = SankakuJson.decodeFromString<TagDto>(
      """{"name":"Blue Hair","tagName":"blue_hair","type":0,"post_count":1527,"name_en":"Blue Hair"}""",
    )

    assertEquals("blue_hair", tag.searchName)
    assertEquals("Blue Hair", tag.name)
    assertEquals(1527, tag.postCount)
    assertEquals("blue_hair", TagDto("blue_hair").searchName)
  }

  @Test
  fun autocompleteDecodesABareArray() {
    val found = SankakuJson.decodeFromString<List<TagSuggestion>>(Fixtures.autocomplete())

    assertEquals(listOf("blue_hair", "blue_hat"), found.map { it.tagName })
    assertEquals("Blue Hair", found[0].nameEn)
    assertEquals(1234, found[0].posts)
    assertEquals(TagType.Fashion, found[1].category)
    assertNull(found[1].nameEn)
  }

  @Test
  fun lastPageHasNoCursor() {
    assertNull(SankakuJson.decodeFromString<Page<PostDto>>(Fixtures.page(next = null)).meta.next)
  }

  @Test
  fun postWithoutUrlsDecodes() {
    val stripped = """{"id":"p1","md5":"m","rating":"s","status":"active","preview_url":"https://v/x?e=1"}"""

    val post = SankakuJson.decodeFromString<PostDto>(stripped)

    assertNull(post.fileUrl)
    assertNull(post.sampleUrl)
  }

  @Test
  fun videoDurationIsFractionalSecondsOrAbsent() {
    val base = """"id":"p1","md5":"m","rating":"s","status":"active""""

    assertEquals(127.658667, SankakuJson.decodeFromString<PostDto>("""{$base,"video_duration":127.658667}""").videoDuration)
    assertNull(SankakuJson.decodeFromString<PostDto>("""{$base,"video_duration":null}""").videoDuration)
    assertNull(SankakuJson.decodeFromString<PostDto>("{$base}").videoDuration)
  }

  @Test
  fun userIdIsAcceptedAsNumberOrString() {
    val fromLogin = SankakuJson.decodeFromString<UserSummary>("""{"id":42,"name":"tester"}""")
    val fromApi = SankakuJson.decodeFromString<UserSummary>("""{"id":"42","name":"tester","avatar_url":"https://a"}""")

    assertEquals("42", fromLogin.id)
    assertEquals("42", fromApi.id)
    assertEquals("https://a", fromApi.avatarUrl)
  }
}
