package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.api.dto.TagType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PostTitleTest {
  private fun tag(name: String, type: TagType, tagName: String? = name.lowercase().replace(' ', '_')) =
    TagDto(name = name, type = type.id, tagName = tagName)

  private val general = listOf("Monochrome", "1boy", "Short hair", "Smile").map { tag(it, TagType.General) }
  private val artist = tag("Some Artist", TagType.Artist)
  private val series = listOf("Gundam", "Gundam Seed", "Gundam Wing").map { tag(it, TagType.Copyright) }
  private val characters = listOf("Konan (Naruto)", "Cirno", "Hilda").map { tag(it, TagType.Character) }

  @Test
  fun charactersComeFirstAtMostTwo() {
    assertEquals("Konan (Naruto), Cirno", PostTitle.of(general + artist + series + characters))
  }

  @Test
  fun seriesWithoutCharacters() {
    assertEquals("Gundam, Gundam Seed", PostTitle.of(general + artist + series))
  }

  @Test
  fun artistWithoutCharactersOrSeries() {
    assertEquals("Some Artist", PostTitle.of(general + artist))
  }

  @Test
  fun onlyGeneralTagsGiveTheFirstThree() {
    assertEquals("Monochrome · 1boy · Short hair", PostTitle.of(general))
  }

  @Test
  fun theNewTagTypesAreGeneralInTheirOrder() {
    // As a list's post carries them: no tag of type General, the mockup's "Comic · 2boys · Speech bubble".
    val tags = listOf(
      tag("High resolution", TagType.Automatic),
      tag("Comic", TagType.Medium),
      tag("Translated", TagType.Meta),
      tag("2boys", TagType.Setting),
      tag("English", TagType.Language),
      tag("Speech bubble", TagType.Object),
      tag("Hood", TagType.Fashion),
    )
    assertEquals("Comic · 2boys · Speech bubble", PostTitle.of(tags))
  }

  private val original = tag("Original", TagType.Copyright)
  private val originalCharacter = tag("Original character", TagType.Copyright)

  @Test
  fun anOriginalWorkIsNamedByItsCharacter() {
    assertEquals("Cirno", PostTitle.of(general + artist + original + listOf(characters[1])))
  }

  @Test
  fun anOriginalWorkWithOnlyAnArtistIsNamedByTheArtist() {
    assertEquals("Some Artist", PostTitle.of(general + artist + original + originalCharacter))
  }

  @Test
  fun originalComesAfterTheOtherSeries() {
    val opala = tag("Legend of Queen Opala", TagType.Copyright)
    assertEquals("Legend of Queen Opala", PostTitle.of(listOf(original, opala, artist)))
  }

  @Test
  fun originalComesAfterTheGeneralTagsToo() {
    assertEquals("Monochrome · 1boy · Short hair", PostTitle.of(general + original))
  }

  @Test
  fun originalIsTheNameWhenNothingElseIsLeft() {
    assertEquals("Original", PostTitle.of(listOf(original)))
    assertEquals("Original, Original character", PostTitle.of(listOf(original, originalCharacter)))
    assertEquals("Original", PostTitle.of(listOf(original, tag("High resolution", TagType.Automatic))))
  }

  @Test
  fun originalIsFoundByItsSearchNameWhateverItsDisplayName() {
    val renamed = TagDto(name = "Original works", type = TagType.Copyright.id, tagName = "original")
    assertEquals("Some Artist", PostTitle.of(listOf(renamed, artist)))
  }

  @Test
  fun aSeriesThatOnlyContainsOriginalInItsNameIsNotSkipped() {
    assertEquals("Original Sin", PostTitle.of(listOf(tag("Original Sin", TagType.Copyright), artist)))
  }

  @Test
  fun noTagsIsUntitled() {
    assertNull(PostTitle.of(emptyList()))
  }

  @Test
  fun aStudioIsNotAGeneralTag() {
    assertEquals("Comic", PostTitle.of(listOf(tag("Some Studio", TagType.Studio), tag("Comic", TagType.Medium))))
  }

  @Test
  fun onlyFileAndTaggingTagsIsUntitled() {
    val tags = listOf(
      tag("High resolution", TagType.Automatic),
      tag("Translated", TagType.Meta),
      tag("English", TagType.Language),
    )
    assertNull(PostTitle.of(tags))
  }

  @Test
  fun rawNamesAreShownAsTheTagChipsShowThem() {
    val raw = tag("coconut_(girls_und_panzer)", TagType.Character, tagName = "coconut_(girls_und_panzer)")
    assertEquals("coconut (girls und panzer)", PostTitle.of(listOf(raw)))
    val onlyName = tag("speech_bubble", TagType.General, tagName = null)
    assertEquals("speech bubble", PostTitle.of(listOf(onlyName)))
    val display = tag("Coconut (Girls und Panzer)", TagType.Character, tagName = "coconut_(girls_und_panzer)")
    assertEquals("Coconut (Girls und Panzer)", PostTitle.of(listOf(display)))
  }

  @Test
  fun aDisplayNameKeepsItsUnderscores() {
    assertEquals("^_^", tag("^_^", TagType.General, tagName = "^_^_(emoticon)").displayName)
  }

  @Test
  fun neverTheId() {
    // A post with no tags at all has no title: the UI shows "Untitled post", not "Post <id>".
    assertNull(PostTitle.of(PostDto(id = "12345", md5 = "m", rating = "s", status = "active")))
  }
}
